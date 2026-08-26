package com.osamu.aide.ui.workspace

import com.osamu.aide.engine.api.Diagnostic
import java.io.File

/**
 * The message "fix this" sends on the user's behalf.
 *
 * Written here rather than in `:ai:core` because it is the one place that knows
 * both a [Diagnostic] and where the project root is, and `:ai:core` has no
 * business depending on the build engine's types.
 *
 * **The path is made relative on purpose.** The assistant's tools reject an
 * absolute path outright -- `ProjectFiles.resolve` refuses them so the model
 * learns to send relative ones -- so pasting `/storage/.../src/Main.java` into
 * the message would spend the first tool call on a refusal, and possibly the
 * second on the model guessing at the right form. Handing it the path its own
 * tools accept skips both.
 *
 * The source line is not included. The assistant has `read_file` and will use
 * it, and a line quoted here is a line that can already be stale by the time it
 * is read -- the user may have edited the file since the build ran.
 */
fun fixRequest(diagnostic: Diagnostic, projectRoot: File): String {
    val where = diagnostic.file?.relativeToProject(projectRoot)

    return buildString {
        append("The build reported this ")
        append(diagnostic.severity.name.lowercase())
        append(":\n\n")

        if (where != null) {
            append(where)
            if (diagnostic.line != Diagnostic.UNKNOWN) {
                append(":").append(diagnostic.line)
                if (diagnostic.column != Diagnostic.UNKNOWN) append(":").append(diagnostic.column)
            }
            append("\n")
        }
        append(diagnostic.message.trim())

        // Asking for the cause rather than only the edit, because a build error
        // is usually a symptom -- a missing import for a type that was renamed,
        // say -- and an edit that silences it without saying why leaves the user
        // unable to tell a fix from a workaround.
        append("\n\nRead the file, tell me what is causing it, and fix it.")
    }
}

/**
 * The path as the assistant's tools expect it, or the plain name if the file is
 * somehow outside the project -- which is better than an absolute path the
 * tools will refuse.
 */
internal fun File.relativeToProject(root: File): String {
    val relative = runCatching { relativeTo(root).path }.getOrNull()
    return if (relative.isNullOrBlank() || relative.startsWith("..")) name else relative
}
