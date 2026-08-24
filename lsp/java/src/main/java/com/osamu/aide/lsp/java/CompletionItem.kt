package com.osamu.aide.lsp.java

/** What kind of thing a proposal is, for the icon beside it. */
enum class CompletionKind { METHOD, FIELD, VARIABLE, CLASS, PACKAGE, KEYWORD }

/**
 * One completion proposal.
 *
 * [label] is what the list shows; [insert] is what replacing the typed prefix
 * puts in the buffer. They differ for anything that carries a signature -- the
 * user should read `getSystemService(String)` and get `getSystemService`.
 */
data class CompletionItem(
    val label: String,
    val kind: CompletionKind,
    val insert: String = label,
    val detail: String? = null,
)
