package com.osamu.aide.engine.gradle

import com.osamu.aide.engine.api.BuildStage
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import java.io.File

/**
 * Reads Gradle's console output into the events the editor already understands.
 *
 * Gradle is not the fast pipeline and does not have its stages; it has tasks,
 * hundreds of them, named per variant. Rather than invent a second vocabulary
 * for the UI, the handful of tasks that correspond to work the user recognises
 * are mapped onto [BuildStage], and everything else becomes a note.
 *
 * **The mapping is deliberately partial.** A task that is not listed is not a
 * stage; reporting every task as one would turn a progress bar into a flicker
 * of two hundred entries, and reporting an unknown task under the wrong stage
 * would be worse than saying nothing.
 */
internal object GradleOutput {

    private val TASK = Regex("""^> Task :(?<name>[\w:\-]+)\s*(?<state>UP-TO-DATE|NO-SOURCE|FAILED)?\s*$""")

    /**
     * `file:line:column: severity: message`, which is what javac, kotlinc and
     * aapt2 all print — Gradle passes their output through unchanged.
     */
    private val DIAGNOSTIC = Regex(
        """^(?<file>[^:\s][^:]*):(?<line>\d+):(?:(?<column>\d+):)?\s*(?<severity>error|warning|note):\s*(?<message>.*)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Which Gradle task means which stage.
     *
     * Suffixes rather than exact names, because every task carries its variant:
     * `compileDebugJavaWithJavac`, `compileReleaseJavaWithJavac`. Matching the
     * ending keeps one entry per kind of work instead of one per variant.
     */
    private val STAGES = listOf(
        "JavaWithJavac" to BuildStage.COMPILE_JAVA,
        "Kotlin" to BuildStage.COMPILE_KOTLIN,
        "Resources" to BuildStage.LINK_RESOURCES,
        "Dex" to BuildStage.DEX,
        "dexBuilder" to BuildStage.DEX,
        "package" to BuildStage.PACKAGE,
    )

    /** The stage a task line announces, or null if it is not one we report. */
    fun stageOf(line: String): BuildStage? {
        val name = TASK.matchEntire(line.trim())?.groups?.get("name")?.value ?: return null
        // Skipped work is not work: reporting UP-TO-DATE as a stage that ran
        // would make an incremental build look like a full one.
        if (TASK.matchEntire(line.trim())?.groups?.get("state")?.value != null) return null
        return STAGES.firstOrNull { (fragment, _) -> name.contains(fragment, ignoreCase = true) }?.second
    }

    /** A compiler message, or null. [projectRoot] makes the path tappable. */
    fun diagnosticOf(line: String, projectRoot: File): Diagnostic? {
        val match = DIAGNOSTIC.matchEntire(line.trim()) ?: return null
        val file = File(match.groups["file"]!!.value)
        return Diagnostic(
            severity = when (match.groups["severity"]!!.value.lowercase()) {
                "error" -> DiagnosticSeverity.ERROR
                "warning" -> DiagnosticSeverity.WARNING
                else -> DiagnosticSeverity.INFO
            },
            message = match.groups["message"]!!.value,
            file = relativise(file, projectRoot),
            line = match.groups["line"]!!.value.toIntOrNull() ?: Diagnostic.UNKNOWN,
            column = match.groups["column"]?.value?.toIntOrNull() ?: Diagnostic.UNKNOWN,
        )
    }

    /**
     * Gradle's own one-line explanation of a failure.
     *
     * The line after `* What went wrong:`, which is the sentence a person would
     * read out. Everything else in that block is a stack trace or a suggestion
     * to run with `--stacktrace`.
     */
    fun failureMessage(output: String): String? {
        val lines = output.lines()
        val marker = lines.indexOfFirst { it.trim() == "* What went wrong:" }
        if (marker == -1) return null

        val block = lines.drop(marker + 1).takeWhile { !it.trim().startsWith("* Try:") }
        val headline = block.firstOrNull { it.isNotBlank() }?.trim() ?: return null

        // Gradle's first line is often a category -- "Gradle could not start
        // your build." -- and the sentence that says *why* is the indented one
        // under it. Reporting only the first leaves the user with a message
        // that names no cause; reporting the whole block gives them a stack
        // trace. The first detail line is the useful middle.
        val detail = block.dropWhile { it.isBlank() }
            .drop(1)
            .firstOrNull { it.trim().startsWith("> ") }
            ?.trim()
            ?.removePrefix("> ")

        return if (detail.isNullOrBlank()) headline.removePrefix("> ") else "$headline $detail"
    }

    private fun relativise(file: File, projectRoot: File): File {
        val root = runCatching { projectRoot.canonicalPath }.getOrDefault(projectRoot.path)
        val path = runCatching { file.canonicalPath }.getOrDefault(file.path)
        val prefix = root.trimEnd('/') + "/"
        return if (path.startsWith(prefix)) File(path.removePrefix(prefix)) else file
    }
}
