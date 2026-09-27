package com.osamu.aide.editor.treesitter

import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.TSLanguageCache

/**
 * The JavaScript grammar, built here rather than downloaded.
 *
 * Every other grammar the editor uses comes from
 * `com.itsaky.androidide.treesitter` as an AAR. **That publisher ships no
 * JavaScript one** — java, kotlin, xml, json, c, cpp, python, aidl, log and
 * properties is the whole list — so a `.js` buffer rendered as plain text from
 * the day M10 could run one. `tools/treesitter/build-grammars.sh` compiles
 * upstream's generated parser for the two ABIs this app ships, and this class
 * is the four lines of glue their AARs contain.
 *
 * The shape is copied from theirs on purpose: the native library exports one
 * function returning a `TSLanguage *`, [TSLanguage.create] wraps it, and
 * [TSLanguageCache] keeps one per name. **The cache is not an optimisation** —
 * a `TSLanguage` freed while a `TsLanguageSpec` still refers to it takes the
 * editor down, and going through the cache is how two files open at once share
 * one.
 */
object JavaScriptGrammar {

    init {
        System.loadLibrary("tree-sitter-javascript")
    }

    private external fun pointer(): Long

    fun language(): TSLanguage =
        TSLanguageCache.get(NAME) ?: TSLanguage.create(NAME, pointer())
            .also { TSLanguageCache.cache(NAME, it) }

    private const val NAME = "javascript"
}
