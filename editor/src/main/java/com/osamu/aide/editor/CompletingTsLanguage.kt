package com.osamu.aide.editor

import android.os.Bundle
import io.github.rosemoe.sora.editor.ts.TsLanguage
import io.github.rosemoe.sora.editor.ts.TsLanguageSpec
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import java.io.File

/**
 * Tree-sitter highlighting, plus proposals from a language service.
 *
 * The two halves are independent on purpose. Highlighting is tree-sitter's and
 * must keep working when no service is available -- an uninstalled platform, a
 * file type nothing analyses -- so [source] is read at completion time and a
 * null one simply produces no list rather than breaking the editor.
 *
 * [file] is captured because sora's completion callback is handed the text and
 * the cursor but not the document it came from, and a language service needs to
 * know which file it is being asked about to resolve anything in the project
 * around it.
 */
internal class CompletingTsLanguage(
    spec: TsLanguageSpec,
    private val file: File,
    private val source: () -> CompletionSource?,
    theme: io.github.rosemoe.sora.editor.ts.TsThemeBuilder.() -> Unit,
) : TsLanguage(spec, false, theme) {

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle,
    ) {
        val completions = source() ?: return
        val text = content.reference.toString()
        val offset = position.index.coerceIn(0, text.length)

        // sora cancels a stale request by **interrupting this thread**, and it
        // does that constantly -- every keystroke supersedes the one before.
        // A source that blocks (any of them will: this is a compiler) sees that
        // as an InterruptedException, and letting it escape gets the request
        // logged as "Completion failed" rather than recognised as cancelled.
        val proposals = try {
            completions.completionsAt(file, text, offset)
        } catch (interrupted: InterruptedException) {
            // Re-assert the flag the throw cleared, so anything further up the
            // stack still knows, then say what actually happened.
            Thread.currentThread().interrupt()
            throw CompletionCancelledException()
        }

        // The request may also have been superseded without an interrupt, in
        // which case this list is about a buffer that no longer exists.
        publisher.checkCancelled()

        val prefix = prefixLength(text, offset)
        proposals.forEach { proposal ->
            publisher.addItem(
                SimpleCompletionItem(proposal.label, proposal.detail, prefix, proposal.insert)
                    .kind(proposal.kind.toSora()),
            )
        }
        publisher.updateList()
    }

    /**
     * How much of what the user typed the proposal replaces.
     *
     * sora deletes this many characters before the cursor and inserts the
     * commit text. Getting it wrong is visible and annoying: too small leaves
     * `getSysgetSystemService`, too large eats the dot.
     */
    private fun prefixLength(text: String, offset: Int): Int {
        var start = offset
        while (start > 0 && Character.isJavaIdentifierPart(text[start - 1])) start--
        return offset - start
    }

    private fun EditorCompletionKind.toSora(): CompletionItemKind = when (this) {
        EditorCompletionKind.METHOD -> CompletionItemKind.Method
        EditorCompletionKind.FIELD -> CompletionItemKind.Field
        EditorCompletionKind.VARIABLE -> CompletionItemKind.Variable
        EditorCompletionKind.CLASS -> CompletionItemKind.Class
        EditorCompletionKind.PACKAGE -> CompletionItemKind.Module
        EditorCompletionKind.KEYWORD -> CompletionItemKind.Keyword
    }
}
