package com.osamu.aide.editor

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.osamu.aide.engine.api.Diagnostic
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File

/**
 * The editor widget, hosted in Compose.
 *
 * sora-editor is a View and draws its own text, which is the point: a Compose
 * text field re-lays out the whole document on every keystroke, and on a
 * thousand-line file that is visible. This is a thin host around it and should
 * stay thin -- behaviour belongs in the widget or in the state above it, not in
 * the interop layer.
 *
 * One widget serves every tab. Switching tabs swaps the buffer rather than
 * building a second editor: each editor carries a parse thread and native
 * tree-sitter memory, and a phone with eight files open cannot afford eight of
 * them.
 */
@Composable
fun CodeEditorView(
    document: SourceDocument,
    /** Every open document, so buffers for closed tabs can be dropped. */
    openDocuments: List<SourceDocument>,
    languages: EditorLanguages,
    onTextChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    controller: CodeEditorController? = null,
    /** Build diagnostics for the whole project; the gutter shows this file's. */
    diagnostics: List<Diagnostic> = emptyList(),
    /** Diagnostic paths are relative to this. Null disables the gutter. */
    projectRoot: File? = null,
    editable: Boolean = true,
) {
    // Identity, not contents. Recomposition must not push text back into the
    // widget: setText resets the cursor, the scroll position and the undo
    // stack, so doing it on every keystroke makes the editor unusable in a way
    // that reads as an input bug.
    val documentKey = document.file.absolutePath

    // The subscription is made once, in the factory, and outlives every
    // recomposition -- so it has to read the *current* callback rather than the
    // one that happened to be in scope when the view was created.
    val currentListener = rememberUpdatedState(onTextChanged)
    val buffers = remember { EditorBuffers() }
    val currentController = rememberUpdatedState(controller)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            CodeEditor(context).apply {
                typefaceText = Typeface.MONOSPACE
                typefaceLineNumber = Typeface.MONOSPACE
                setTextSize(DEFAULT_TEXT_SIZE_SP)
                // Code has meaningful indentation and long lines; wrapping them
                // hides the structure the indentation is there to show.
                setWordwrap(false)
                setLineNumberEnabled(true)
                setTabWidth(4)

                subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
                    currentListener.value(event.editor.text.toString())
                }
                currentController.value?.attach(this)
            }
        },
        update = { editor ->
            editor.setEditable(editable)
            currentController.value?.attach(editor)
            buffers.retainOnly(openDocuments)

            if (editor.getTag(R.id.aide_editor_document) != documentKey) {
                editor.setTag(R.id.aide_editor_document, documentKey)
                editor.setEditorLanguage(languages.languageFor(document.file))
                // reuseContentObject: this tab's undo history and cursor come
                // back with it. Passing the raw String instead would build a
                // fresh Content and throw both away.
                editor.setText(buffers.bufferFor(document), true, null)
            }

            // Rebuilt whenever the set changes -- including on a tab switch,
            // since the regions are indexes into whichever buffer is showing.
            editor.diagnostics = if (projectRoot == null) {
                null
            } else {
                EditorDiagnostics.containerFor(
                    diagnostics = diagnostics,
                    file = document.file,
                    projectRoot = projectRoot,
                    content = editor.text,
                )
            }
        },
        onRelease = { editor ->
            currentController.value?.detach()
            buffers.clear()
            // Holds a parse thread and native tree-sitter memory. Leaving it to
            // the garbage collector leaks both.
            editor.release()
        },
    )
}

private const val DEFAULT_TEXT_SIZE_SP = 14f
