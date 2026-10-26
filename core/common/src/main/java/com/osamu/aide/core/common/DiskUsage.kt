package com.osamu.aide.core.common

import java.io.File
import java.nio.file.Files

/** Bytes on disk, measured the way `du` would. */
object DiskUsage {

    /**
     * Bytes under [file], **not following symlinks**.
     *
     * `File.walkTopDown()` follows them. Toolchains are full of them -- Node's
     * `bin/npm` points into `lib/node_modules` -- and walking through counted
     * Node at 184 MB where `du` said 119, a 55 % overstatement. A cloned project
     * can hold them too, and a symlink back to an ancestor turned the walk into
     * a loop that pinned a thread rather than an exaggeration.
     *
     * A symlink contributes nothing: what it points at is counted where it
     * lives, or belongs to something else entirely.
     */
    fun sizeOf(file: File): Long = when {
        Files.isSymbolicLink(file.toPath()) -> 0
        file.isFile -> file.length()
        else -> file.listFiles().orEmpty().sumOf { sizeOf(it) }
    }
}
