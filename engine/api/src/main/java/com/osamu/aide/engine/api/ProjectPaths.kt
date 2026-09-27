package com.osamu.aide.engine.api

import java.io.File

/**
 * Cuts tool output back to project-relative paths, as [Diagnostic] requires.
 *
 * Both sides are canonicalised first, because Android reaches app storage by
 * two different absolute paths: `/data/user/0/<pkg>` is a symlink to
 * `/data/data/<pkg>`. A project handed to a tool as one is reported back as the
 * other the moment that tool canonicalises, and a plain prefix match then
 * silently leaves every diagnostic pointing at an absolute cache path.
 *
 * **Here rather than in each engine** because [Diagnostic] is where the rule is
 * written — "`file` should be relative to the project root" — and every
 * producer of one needs it. It lived privately in `:engine:fast` and again in
 * `:lsp:java`, identically, and `:lsp:node` shipped without it: a `.js` syntax
 * error filled three lines of a phone with
 * `/storage/emulated/0/Android/data/...` before anyone opened the app. Two
 * copies is a coincidence; the third would have been a convention.
 */
object ProjectPaths {

    fun relativise(file: File, projectRoot: File): File {
        val path = canonical(file)
        val prefix = canonical(projectRoot).trimEnd('/') + "/"
        // Anything outside the project -- android.jar, generated R.java -- is
        // returned exactly as the tool reported it. Rewriting those would
        // present a file the user cannot open as one they wrote.
        return if (path.startsWith(prefix)) File(path.removePrefix(prefix)) else file
    }

    private fun canonical(file: File): String =
        runCatching { file.canonicalFile }.getOrDefault(file).invariantSeparatorsPath
}
