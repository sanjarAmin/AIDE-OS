package com.osamu.aide.editor

import java.io.File

/** What kind of thing a proposal is, for the icon beside it. */
enum class EditorCompletionKind { METHOD, FIELD, VARIABLE, CLASS, PACKAGE, KEYWORD }

/** One proposal, in the terms the editor needs and no others. */
data class EditorCompletion(
    val label: String,
    val kind: EditorCompletionKind,
    val insert: String = label,
    val detail: String? = null,
)

/**
 * Where the editor gets its proposals.
 *
 * An interface, and in this module rather than the one that implements it, so
 * that `:editor` never learns what a compiler is. Java intelligence lives in
 * `:lsp:java`, Kotlin's will live in `:lsp:kotlin`, and clangd will arrive over
 * a pipe; the editor should be able to show a list from any of them without
 * gaining a dependency on all three.
 *
 * [completionsAt] is called on a **background thread** by sora's completion
 * machinery, and may block -- it is already off the main thread and the caller
 * expects it to take a while. It may also be abandoned mid-flight when the user
 * types another character, so it must not have side effects worth keeping.
 */
fun interface CompletionSource {
    fun completionsAt(file: File, text: String, offset: Int): List<EditorCompletion>
}
