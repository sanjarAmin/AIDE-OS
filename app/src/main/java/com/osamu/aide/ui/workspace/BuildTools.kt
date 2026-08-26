package com.osamu.aide.ui.workspace

import com.osamu.aide.ai.core.AideTool
import com.osamu.aide.ai.core.ProjectFiles
import com.osamu.aide.ai.core.ToolRisk
import com.osamu.aide.core.fs.Project
import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import java.io.File

/**
 * The last build's outcome, remembered so it can be read back.
 *
 * A field rather than a re-run, because `read_build_errors` exists precisely so
 * the assistant can look at what the user is already looking at without
 * spending a minute of the user's battery reproducing it.
 */
class LastBuild {
    @Volatile
    var summary: String? = null
        private set

    fun record(text: String) {
        summary = text
    }
}

/**
 * The plan's last two tools, wired to the real build engine.
 *
 * They live in `:app` rather than `:ai:core` because they need the build
 * engine, and a module whose job is talking to an API has no business depending
 * on a toolchain. `ProjectToolset` takes them as `extra`.
 *
 * [runBuild] is a function rather than a `ProjectBuilder` so that this file
 * knows only "build it and give me a summary". That keeps the tools testable
 * without a toolchain, a platform and a device -- none of which have anything
 * to do with what is being asserted.
 *
 * **`run_build` is READ_ONLY, and that is a judgement rather than an
 * oversight.** It writes only to the build cache, never to the user's sources,
 * so the plan's "confirm every mutating tool" does not apply. Putting a prompt
 * in front of it anyway would break the loop this whole feature exists for --
 * fix, rebuild, see whether the fix worked -- into three taps, and train the
 * user to approve without reading, which is how the one prompt that matters
 * (`edit_file`) gets waved through.
 */
fun buildTools(
    runBuild: suspend (Project) -> String,
    project: () -> Project?,
    lastBuild: LastBuild,
): List<AideTool> = listOf(
    AideTool(
        name = "run_build",
        description =
            "Compile and package the project, and report whether it succeeded " +
                "along with any errors. Use this to check a change you made, " +
                "rather than assuming it compiles. A build takes seconds to a " +
                "minute and runs on the user's phone, so do not call it twice " +
                "for the same state of the code.",
        risk = ToolRisk.READ_ONLY,
        parameters = emptyMap(),
        required = emptyList(),
        handler = {
            val target = project()
            if (target == null) {
                ProjectFiles.Outcome.Refused("No project is open, so there is nothing to build.")
            } else {
                val summary = runBuild(target)
                lastBuild.record(summary)
                ProjectFiles.Outcome.Ok(summary)
            }
        },
    ),
    AideTool(
        name = "read_build_errors",
        description =
            "Report the result of the most recent build without running a new " +
                "one. Prefer this when the user has just built and is asking " +
                "about the errors they can see.",
        risk = ToolRisk.READ_ONLY,
        parameters = emptyMap(),
        required = emptyList(),
        handler = {
            lastBuild.summary?.let(ProjectFiles.Outcome::Ok)
                ?: ProjectFiles.Outcome.Refused(
                    "Nothing has been built yet in this session. Use run_build first.",
                )
        },
    ),
)

/**
 * A build, as a handful of lines the model can act on.
 *
 * Deliberately not the whole log. A build emits a stage line per phase and a
 * note per dependency resolve, and none of it helps the model decide anything;
 * what it needs is whether the build passed and, if not, the errors with their
 * locations. Sending the rest spends context that the next tool call needs.
 *
 * Paths are made relative for the reason [fixRequest] explains: the assistant's
 * file tools refuse an absolute path, so an error reported as
 * `/data/.../Main.java` would cost a round trip before it could be read.
 */
internal fun List<BuildEvent>.summarise(projectRoot: File): String {
    val result = filterIsInstance<BuildEvent.Finished>().lastOrNull()?.result

    // Both sources, deduplicated. The engine streams diagnostics as it finds
    // them *and* repeats them on the result, and which of the two is populated
    // depends on the stage that failed -- taking only one silently loses the
    // errors from the other.
    val diagnostics = (
        filterIsInstance<BuildEvent.DiagnosticReported>().map { it.diagnostic } +
            result?.diagnostics.orEmpty()
        ).distinct()

    return buildString {
        when (result) {
            is BuildResult.Success -> append("The build succeeded.")
            is BuildResult.Failure -> {
                append("The build failed")
                result.stage?.let { append(" during ").append(it.name.lowercase()) }
                append(": ").append(result.message)
            }
            // No Finished event at all means the flow was cancelled -- the user
            // stopped the build, or the screen went away. Saying so beats
            // reporting a success that never happened.
            null -> append("The build did not finish.")
        }

        // Errors only, when there are any. A build that fails on one error and
        // forty warnings should not spend the model's context on the warnings.
        val errors = diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }
        val reported = if (errors.isNotEmpty()) errors else diagnostics
        if (reported.isEmpty()) return@buildString

        append("\n\n")
        reported.take(MAX_DIAGNOSTICS).forEach { append(it.forModel(projectRoot)).append('\n') }
        if (reported.size > MAX_DIAGNOSTICS) {
            append("...and ").append(reported.size - MAX_DIAGNOSTICS).append(" more.")
        }
    }.trim()
}

private fun Diagnostic.forModel(projectRoot: File): String = buildString {
    val where = file?.let { candidate ->
        val relative = runCatching { candidate.relativeTo(projectRoot).path }.getOrNull()
        if (relative.isNullOrBlank() || relative.startsWith("..")) candidate.name else relative
    }

    where?.let { append(it) }
    if (line != Diagnostic.UNKNOWN) append(':').append(line)
    if (where != null || line != Diagnostic.UNKNOWN) append(": ")
    append(severity.name.lowercase()).append(": ").append(message)
}

/**
 * Enough to work with, few enough to leave room for the fix.
 *
 * A single missing import can produce dozens of cascading errors, and the
 * twentieth adds nothing the first three did not.
 */
private const val MAX_DIAGNOSTICS = 20
