package com.osamu.aide.editor

import com.itsaky.androidide.treesitter.TreeSitter

/**
 * Loads tree-sitter's native core, once, before anything touches a grammar.
 *
 * Nothing does this implicitly: the bindings' static initialisers declare the
 * native methods but do not load the library, so the first call into a grammar
 * fails with `UnsatisfiedLinkError` rather than anything that names the cause.
 *
 * A failure here is not fatal. It means this device has no build of the library
 * for its ABI, and the correct response is an editor that highlights nothing
 * rather than an editor that crashes -- reading and writing code does not
 * depend on colour.
 */
internal object TreeSitterRuntime {

    val isAvailable: Boolean by lazy {
        runCatching { TreeSitter.loadLibrary() }.isSuccess
    }
}
