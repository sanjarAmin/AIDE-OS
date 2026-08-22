package com.osamu.aide.engine.fast

import java.io.File

/**
 * Cuts tool output back to project-relative paths.
 *
 * Both sides are canonicalised first, because Android reaches app storage by two
 * different absolute paths: `/data/user/0/<pkg>` is a symlink to
 * `/data/data/<pkg>`. A project handed to a tool as one is reported back as the
 * other the moment that tool canonicalises, and a plain prefix match then
 * silently leaves every diagnostic pointing at an absolute cache path.
 */
internal object ProjectPaths {

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
