package com.osamu.aide.editor

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.viewinterop.AndroidView
import com.osamu.aide.engine.api.Diagnostic
import io.github.rosemoe.sora.event.ClickEvent
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorMotionEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.component.EditorDiagnosticTooltipWindow
import io.github.rosemoe.sora.widget.getComponent
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
    /** Where the caret went, as a character index. Drives signature hints. */
    onCursorMoved: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    controller: CodeEditorController? = null,
    /** Build diagnostics for the whole project; the gutter shows this file's. */
    diagnostics: List<Diagnostic> = emptyList(),
    /** Diagnostic paths are relative to this. Null disables the gutter. */
    projectRoot: File? = null,
    editable: Boolean = true,
    /**
     * Applied on every recomposition, not only at construction, so a change
     * made in Settings reaches the widget already on screen.
     */
    settings: EditorSettings = EditorSettings(),
    /** 1-based lines holding a breakpoint in this document. */
    breakpointLines: Set<Int> = emptySet(),
    /** The 1-based line a debugger is stopped on in this document, if any. */
    executionLine: Int? = null,
    /**
     * A tap on a line number, with the 1-based line. Null leaves the gutter
     * doing what sora does with it, which is to move the caret.
     */
    onLineNumberTap: ((Int) -> Unit)? = null,
    /**
     * 1-based lines a write from outside just changed, painted and then faded.
     *
     * The identity of the set is the trigger: pass a new set to start a new
     * fade, and `emptySet()` for an ordinary edit by the person typing -- their
     * own keystrokes need no announcing.
     */
    changedLines: Set<Int> = emptySet(),
    /**
     * Bumped by the caller when [document] holds genuinely new text for the
     * same file -- a reload from disk. Typing does not change it.
     */
    reloadToken: Int = 0,
) {
    // Identity, not contents. Recomposition must not push text back into the
    // widget: setText resets the cursor, the scroll position and the undo
    // stack, so doing it on every keystroke makes the editor unusable in a way
    // that reads as an input bug.
    // **Identity plus a reload count.** The path alone answered "is this a
    // different tab", which is the right question for a tab switch and the
    // wrong one for a file rewritten under the editor: the assistant could
    // change the open file and the widget would keep showing the old text --
    // and then save over the edit on the next keystroke.
    val documentKey = document.file.absolutePath + "#" + reloadToken

    // The subscription is made once, in the factory, and outlives every
    // recomposition -- so it has to read the *current* callback rather than the
    // one that happened to be in scope when the view was created.
    val currentListener = rememberUpdatedState(onTextChanged)
    val currentCursorListener = rememberUpdatedState(onCursorMoved)
    val currentGutterTap = rememberUpdatedState(onLineNumberTap)
    val buffers = remember { EditorBuffers() }
    val currentController = rememberUpdatedState(controller)

    // The diagnostics the widget was last given, so a change can be told from a
    // recomposition. See the tooltip note in the update block.
    val shownDiagnostics = remember { mutableStateOf<List<Diagnostic>>(emptyList()) }

    // **Read from the app's own colours, not from the OS.** Both used to ask
    // `isSystemInDarkTheme()` separately, which agreed only because the app's
    // `darkTheme` happens to default to the same call -- it is a parameter, so
    // anything that sets it would have left the editor following the OS and
    // drawing a white slab inside a dark app, which is the exact bug this enum
    // was added to fix, returning by a different door.
    //
    // Luminance rather than a flag, because Material's colour scheme does not
    // carry one: the background is near-black in the dark scheme and near-white
    // in the light one, so the midpoint separates them with a wide margin.
    //
    // Resolved here because it is a composable read and the update block below
    // is not one. It also means a theme change recomposes and repaints the
    // editor, which is the behaviour that was missing entirely.
    val wantsDark = settings.theme.isDark(MaterialTheme.colorScheme.background.luminance() < 0.5f)

    // **One orchestrated fade, started by a new set arriving.** Held as an
    // `Animatable` rather than a `tween` on a state read, because the highlight
    // has to survive recomposition -- the buffer it is painting was just
    // replaced, so recomposition is exactly what is happening around it.
    val changedFade = remember { Animatable(0f) }
    LaunchedEffect(changedLines) {
        if (changedLines.isEmpty()) {
            changedFade.snapTo(0f)
            return@LaunchedEffect
        }
        // Held at full strength first: a fade that begins immediately is half
        // over before the eye finds the line it is meant to draw attention to.
        changedFade.snapTo(1f)
        delay(CHANGED_HOLD_MS)
        changedFade.animateTo(0f, tween(CHANGED_FADE_MS))
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            DebugGutterEditor(context).apply {
                typefaceText = Typeface.MONOSPACE
                typefaceLineNumber = Typeface.MONOSPACE

                getComponent<EditorAutoCompletion>().setAdapter(SemanticCompletionAdapter())

                subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
                    currentListener.value(event.editor.text.toString())
                }
                // Reported separately from text changes because the caret moves
                // without the text doing so -- a tap into the middle of an
                // existing call should raise its signature, and no edit has
                // happened to notice.
                subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
                    currentCursorListener.value(event.editor.cursor.left)
                }
                // The line-number column is where every IDE puts breakpoints,
                // so a tap there toggles one instead of moving the caret --
                // intercepted, or sora also selects the line, and a user
                // setting a breakpoint mid-edit loses their place.
                subscribeEvent(ClickEvent::class.java) { event, _ ->
                    val onTap = currentGutterTap.value ?: return@subscribeEvent
                    if (event.motionRegion == EditorMotionEvent.REGION_LINE_NUMBER) {
                        onTap(event.line + 1)
                        event.intercept()
                    }
                }
                currentController.value?.attach(this)
            }
        },
        update = { editor ->
            editor.setEditable(editable)
            (editor as? DebugGutterEditor)?.let {
                it.breakpointLines = breakpointLines
                it.executionLine = executionLine
                it.changedLines = changedLines
                // Read here so every animation frame reaches the widget: the
                // update block re-runs on each recomposition, and an
                // `Animatable`'s value is a state read, so this is what
                // subscribes it.
                it.changedFade = changedFade.value
            }
            // Set here rather than in the factory: these are the four things a
            // user can change while a file is open, and a widget built before
            // the change would otherwise keep the old value until the tab was
            // closed and reopened.
            editor.setTextSize(settings.fontSizeSp)
            editor.setTabWidth(settings.tabWidth)
            editor.setLineNumberEnabled(settings.showLineNumbers)
            // **Pinned**, which sora does not do by default. Word wrap is off
            // unless the user turns it on, so scrolling sideways is the normal
            // way to read a long line -- and unpinned, the gutter scrolls away
            // with the text, so the further right you go the less able you are
            // to tell which line a diagnostic was about.
            editor.setPinLineNumber(true)
            editor.setWordwrap(settings.wordWrap)
            // Compared rather than assigned every frame: a new scheme object
            // makes the editor rebuild its styles, which on a 5,000-line file
            // is visible.
            if (wantsDark != (editor.colorScheme is SchemeDarcula)) {
                editor.colorScheme = if (wantsDark) SchemeDarcula() else EditorColorScheme()
            }
            currentController.value?.attach(editor)
            buffers.retainOnly(openDocuments)

            if (editor.getTag(R.id.aide_editor_document) != documentKey) {
                editor.setTag(R.id.aide_editor_document, documentKey)
                editor.setEditorLanguage(languages.languageFor(document.file))
                // reuseContentObject: this tab's undo history and cursor come
                // back with it. Passing the raw String instead would build a
                // fresh Content and throw both away.
                editor.setText(buffers.bufferFor(document, reloadToken), true, null)
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

            // **The tooltip does not know the container was replaced.** sora
            // opens `EditorDiagnosticTooltipWindow` when the caret lands on a
            // diagnostic and updates it from selection changes; nothing tells it
            // when the diagnostics themselves change underneath. Fix the error
            // and its message stays on screen, anchored where the old error was
            // -- seen reading "';' expected" over two lines that no longer
            // contained one, with Problems already down to a single unrelated
            // entry.
            //
            // Only when the set actually changes: dismissing on every
            // recomposition would shut a tooltip the user is still reading.
            if (diagnostics != shownDiagnostics.value) {
                shownDiagnostics.value = diagnostics
                editor.getComponent<EditorDiagnosticTooltipWindow>().dismiss()
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

/**
 * Long enough to find the line before it starts leaving.
 *
 * A fade that begins the instant the file changes is half gone before the eye
 * has arrived, which reads as a flicker rather than as a mark.
 */
private const val CHANGED_HOLD_MS = 900L
private const val CHANGED_FADE_MS = 1_400
