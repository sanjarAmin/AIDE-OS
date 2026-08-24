package com.osamu.aide.lsp.java

import java.io.File

/**
 * Where something is declared.
 *
 * [file] is project-relative, for the same reason
 * [com.osamu.aide.engine.api.Diagnostic]'s is: an absolute `/data/user/0/...`
 * path is not something the editor can match against an open tab.
 *
 * The span covers the declared **name**, not the whole declaration. Jumping to
 * a method should put the cursor on its identifier; selecting its entire body
 * would be technically correct and useless.
 *
 * Lines and columns are 1-based, matching `Diagnostic` and the editor's
 * `jumpTo`.
 */
data class SourceLocation(
    val file: File,
    val line: Int,
    val column: Int,
    val endColumn: Int,
)
