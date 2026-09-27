package com.osamu.aide.ui.workspace

import com.osamu.aide.ai.core.AideTool
import com.osamu.aide.ai.core.ProjectFiles
import com.osamu.aide.ai.core.ToolRisk
import com.osamu.aide.core.fs.Project
import com.osamu.aide.engine.api.DiagnosticSeverity
import com.osamu.aide.lsp.api.LanguageService
import java.io.File

/**
 * Kotlin's resolved answers, offered to the assistant.
 *
 * **Why these exist when `run_build` already does.** The loop the assistant
 * lives in is edit, check, fix. Checking through `run_build` costs seconds to a
 * minute and runs on the user's phone; the same question asked of a resident
 * analysis session costs about a fifth of a second, because the session is
 * already up for the editor. For the commonest mistake -- code that does not
 * type-check -- that is the difference between a loop the model will actually
 * run and one it will skip and guess instead.
 *
 * They live in `:app` for the reason `buildTools` does: they need a language
 * service, and a module whose job is talking to an API has no business
 * depending on a toolchain. `ProjectToolset` takes them as `extra`.
 *
 * Both are READ_ONLY. They resolve and report; nothing here touches the user's
 * files.
 *
 * **Refusing is not the same as finding nothing, and this is the whole risk of
 * the feature.** Kotlin intelligence is two downloads that most devices do not
 * have. A `check_kotlin` that answered "no problems" when there was no analyser
 * would tell the model its broken code is fine -- so the absence of a service
 * is `Refused` with a reason, never an empty list of diagnostics.
 */
fun kotlinTools(
    serviceFor: (File) -> LanguageService?,
    project: () -> Project?,
): List<AideTool> = listOf(
    AideTool(
        name = "check_kotlin",
        description =
            "Type-check one Kotlin file and report its errors and warnings, " +
                "without building. This is the fast way to check a Kotlin edit " +
                "you just made: it answers in well under a second, where " +
                "run_build takes seconds to a minute. It sees only this file " +
                "and what it references, so run_build is still what tells you " +
                "the project as a whole compiles and packages.",
        risk = ToolRisk.READ_ONLY,
        parameters = mapOf(
            "path" to AideTool.Parameter(
                "string",
                "Kotlin file to check, relative to the project root.",
            ),
        ),
        required = listOf("path"),
        handler = { input ->
            withKotlinFile(input["path"], project(), serviceFor) { service, file, text ->
                val found = service.diagnostics(file, text)
                if (found.isEmpty()) {
                    ProjectFiles.Outcome.Ok("No problems in ${input["path"]}.")
                } else {
                    // Errors first: a file with one error and forty warnings is
                    // a file with one problem, and the model should not have to
                    // read to the bottom to find it.
                    val ordered = found.sortedBy { it.severity != DiagnosticSeverity.ERROR }
                    ProjectFiles.Outcome.Ok(
                        buildString {
                            append(ordered.count { it.severity == DiagnosticSeverity.ERROR })
                            append(" error(s), ")
                            append(ordered.size - ordered.count { it.severity == DiagnosticSeverity.ERROR })
                            append(" warning(s):\n")
                            ordered.forEach { append(it.describe()).append('\n') }
                        }.trim(),
                    )
                }
            }
        },
    ),
    AideTool(
        name = "explain_kotlin_symbol",
        description =
            "Resolve the Kotlin symbol at a position and report its signature " +
                "and where it is declared. Use this instead of grep when you " +
                "need to know what a name actually refers to -- grep matches " +
                "text, this resolves through imports and overloads, so it can " +
                "tell which of several same-named things is meant. It cannot " +
                "reach declarations that live in a library rather than in this " +
                "project's source.",
        risk = ToolRisk.READ_ONLY,
        parameters = mapOf(
            "path" to AideTool.Parameter(
                "string",
                "Kotlin file containing the symbol, relative to the project root.",
            ),
            "line" to AideTool.Parameter("string", "1-based line number."),
            "column" to AideTool.Parameter("string", "1-based column number."),
        ),
        required = listOf("path", "line", "column"),
        handler = { input ->
            withKotlinFile(input["path"], project(), serviceFor) { service, file, text ->
                val offset = offsetOf(text, input["line"], input["column"])
                    ?: return@withKotlinFile ProjectFiles.Outcome.Refused(
                        "line and column must be 1-based numbers inside the file.",
                    )

                val signature = service.signatureAt(file, text, offset)
                val declaredAt = service.definition(file, text, offset)
                if (signature == null && declaredAt == null) {
                    ProjectFiles.Outcome.Ok(
                        "Nothing resolved at ${input["path"]}:${input["line"]}:" +
                            "${input["column"]}. It may be a keyword, or declared in a " +
                            "library rather than in this project.",
                    )
                } else {
                    ProjectFiles.Outcome.Ok(
                        buildString {
                            signature?.let { append(it).append('\n') }
                            declaredAt?.let {
                                append("declared at ${it.file.path}:${it.line}:${it.column}")
                            }
                        }.trim(),
                    )
                }
            }
        },
    ),
)

/**
 * Resolves the path, finds a Kotlin service for it, and reads the file.
 *
 * Reads from **disk**, not from an editor buffer: the assistant edits through
 * `edit_file`, so what is on disk is the state its own change produced. Asking
 * about an unsaved buffer it cannot see would answer a question nobody asked.
 */
private inline fun withKotlinFile(
    path: String?,
    project: Project?,
    serviceFor: (File) -> LanguageService?,
    body: (LanguageService, File, String) -> ProjectFiles.Outcome,
): ProjectFiles.Outcome {
    if (path == null) return ProjectFiles.Outcome.Refused("This tool needs a path.")
    if (project == null) {
        return ProjectFiles.Outcome.Refused("No project is open, so there is nothing to check.")
    }

    val file = File(project.rootDir, path)
    if (!file.isFile) return ProjectFiles.Outcome.Refused("$path is not a file in this project.")
    if (file.extension != "kt" && file.extension != "kts") {
        return ProjectFiles.Outcome.Refused("$path is not a Kotlin file.")
    }

    val service = serviceFor(file)
        ?: return ProjectFiles.Outcome.Refused(
            "Kotlin intelligence is not available on this device -- it needs the " +
                "Kotlin compiler and Analysis API components, which are downloaded " +
                "on demand. Use run_build to check this file instead.",
        )
    return body(service, file, file.readText())
}

/** A 1-based line and column as a character offset, or null if out of range. */
private fun offsetOf(text: String, line: String?, column: String?): Int? {
    val target = line?.toIntOrNull() ?: return null
    val across = column?.toIntOrNull() ?: return null
    if (target < 1 || across < 1) return null

    var offset = 0
    var current = 1
    while (current < target) {
        val next = text.indexOf('\n', offset)
        if (next < 0) return null
        offset = next + 1
        current++
    }
    val end = text.indexOf('\n', offset).takeIf { it >= 0 } ?: text.length
    return (offset + across - 1).takeIf { it <= end }
}
