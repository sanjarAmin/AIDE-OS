package com.osamu.aide.editor

import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem

/**
 * A completion item that carries semantic metadata for the [SemanticCompletionAdapter].
 *
 * This extends [SimpleCompletionItem] so it can be passed through sora-editor's
 * publisher and adapter system while keeping the extra data we need for our
 * "feature-full" UI.
 */
class SemanticCompletionItem(
    label: CharSequence,
    desc: CharSequence?,
    prefixLength: Int,
    commitText: String,
    val signature: String? = null,
    val returnType: String? = null,
    val score: Int = 0,
) : SimpleCompletionItem(label, desc, prefixLength, commitText)
