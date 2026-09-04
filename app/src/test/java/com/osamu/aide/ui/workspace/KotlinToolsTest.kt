package com.osamu.aide.ui.workspace

import com.osamu.aide.ai.core.ProjectFiles
import com.osamu.aide.ai.core.ProjectToolset
import com.osamu.aide.ai.core.ToolRisk
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import com.osamu.aide.lsp.api.CompletionItem
import com.osamu.aide.lsp.api.LanguageService
import com.osamu.aide.lsp.api.SourceLocation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Kotlin tools as the model sees them.
 *
 * The service itself is faked here on purpose: what a real one answers is
 * `:lsp:kotlin`'s eighteen instrumented tests, on a device, and repeating that
 * against a stub would assert the stub. What is worth pinning here is the
 * layer between — that a refusal is distinguishable from an empty answer, that
 * neither tool is gated behind a confirmation, and that a path is checked
 * before a service is asked for.
 */
class KotlinToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var served: LanguageService? = null

    private fun project() = Project(
        name = "Sample",
        rootDir = temp.root,
        applicationId = "com.example.sample",
        language = SourceLanguage.KOTLIN,
        engine = BuildEngine.FAST,
        lastOpenedAt = 0L,
    )

    private fun tools(open: Boolean = true) = kotlinTools(
        serviceFor = { served },
        project = { if (open) project() else null },
    )

    private fun toolset(open: Boolean = true) =
        ProjectToolset(ProjectFiles(temp.root), tools(open))

    private fun kotlinFile(text: String = "fun main() {}"): File =
        temp.newFile("Main.kt").apply { writeText(text) }

    /** A service that answers whatever the test hands it. */
    private class Fake(
        private val diagnostics: List<Diagnostic> = emptyList(),
        private val signature: String? = null,
        private val location: SourceLocation? = null,
    ) : LanguageService {
        override fun handles(file: File) = file.extension == "kt"
        override suspend fun diagnostics(file: File, text: String) = diagnostics
        override suspend fun complete(file: File, text: String, offset: Int) =
            emptyList<CompletionItem>()
        override suspend fun definition(file: File, text: String, offset: Int) = location
        override suspend fun signatureAt(file: File, text: String, offset: Int) = signature
        override fun close() = Unit
    }

    @Test
    fun both_tools_are_offered_and_neither_needs_approval() {
        assertEquals(
            listOf(
                "list_files", "read_file", "grep", "edit_file",
                "check_kotlin", "explain_kotlin_symbol",
            ),
            toolset().all().map { it.name },
        )
        assertTrue(
            "resolving and reporting changes nothing, so nothing should prompt",
            toolset().all().none { it.name.contains("kotlin") && it.risk == ToolRisk.MUTATING },
        )
    }

    /**
     * **The failure that would actively mislead the model.**
     *
     * Kotlin intelligence is two downloads most devices do not have. If
     * `check_kotlin` answered "no problems" when there was no analyser at all,
     * the model would be told its broken code is fine — and would believe it,
     * because that is exactly what a clean file looks like. A refusal it can
     * read is the only safe answer, and it has to name the reason.
     */
    @Test
    fun check_refuses_rather_than_reporting_a_clean_file_when_there_is_no_service() = runTest {
        served = null
        kotlinFile()

        val outcome = toolset().execute("check_kotlin", mapOf("path" to "Main.kt"))

        val refusal = outcome as? ProjectFiles.Outcome.Refused
            ?: throw AssertionError("a missing service must refuse, not answer: $outcome")
        assertTrue(
            "the refusal should say what is missing: ${refusal.reason}",
            "Kotlin" in refusal.reason && "run_build" in refusal.reason,
        )
    }

    @Test
    fun check_reports_errors_before_warnings() = runTest {
        served = Fake(
            diagnostics = listOf(
                Diagnostic(DiagnosticSeverity.WARNING, "unused", File("Main.kt"), 9, 1),
                Diagnostic(DiagnosticSeverity.ERROR, "type mismatch", File("Main.kt"), 2, 5),
            ),
        )
        kotlinFile()

        val outcome = toolset().execute("check_kotlin", mapOf("path" to "Main.kt"))

        val content = (outcome as ProjectFiles.Outcome.Ok).content
        assertTrue("counts should lead: $content", content.startsWith("1 error(s), 1 warning(s)"))
        assertTrue(
            "the error should come before the warning: $content",
            content.indexOf("type mismatch") < content.indexOf("unused"),
        )
    }

    @Test
    fun check_says_so_when_a_file_is_clean() = runTest {
        served = Fake()
        kotlinFile()

        val outcome = toolset().execute("check_kotlin", mapOf("path" to "Main.kt"))

        assertEquals("No problems in Main.kt.", (outcome as ProjectFiles.Outcome.Ok).content)
    }

    /**
     * A non-Kotlin path is refused before any service is asked for.
     *
     * The order matters: asking for a service first would build one — several
     * seconds and a large heap — to answer a question about a file it could
     * never handle.
     */
    @Test
    fun a_file_that_is_not_kotlin_is_refused() = runTest {
        served = Fake()
        temp.newFile("notes.txt").writeText("hello")

        val outcome = toolset().execute("check_kotlin", mapOf("path" to "notes.txt"))

        assertTrue(outcome is ProjectFiles.Outcome.Refused)
    }

    @Test
    fun a_path_outside_the_project_is_refused() = runTest {
        served = Fake()

        val outcome = toolset().execute("check_kotlin", mapOf("path" to "../escape.kt"))

        assertTrue(outcome is ProjectFiles.Outcome.Refused)
    }

    @Test
    fun explain_reports_the_signature_and_where_it_is_declared() = runTest {
        served = Fake(
            signature = "greet(name: String): String",
            location = SourceLocation(File("src/Main.kt"), 12, 5, 10),
        )
        kotlinFile("fun greet(name: String) = name\nfun caller() = greet(\"x\")\n")

        val outcome = toolset().execute(
            "explain_kotlin_symbol",
            mapOf("path" to "Main.kt", "line" to "2", "column" to "16"),
        )

        val content = (outcome as ProjectFiles.Outcome.Ok).content
        assertTrue("the signature is missing: $content", "greet(name: String)" in content)
        assertTrue("the declaration site is missing: $content", "src/Main.kt:12:5" in content)
    }

    /**
     * Resolving nothing is an ordinary answer, and says why.
     *
     * A caret on a keyword, or on a symbol declared in a library, has nothing
     * to report — and the model needs to be told that rather than left to read
     * an empty string as an error.
     */
    @Test
    fun explain_says_so_when_nothing_resolves() = runTest {
        served = Fake()
        kotlinFile("fun main() {}")

        val outcome = toolset().execute(
            "explain_kotlin_symbol",
            mapOf("path" to "Main.kt", "line" to "1", "column" to "1"),
        )

        val content = (outcome as ProjectFiles.Outcome.Ok).content
        assertTrue("should explain the two reasons: $content", "library" in content)
    }

    @Test
    fun explain_refuses_a_position_outside_the_file() = runTest {
        served = Fake(signature = "never asked")
        kotlinFile("fun main() {}")

        val outcome = toolset().execute(
            "explain_kotlin_symbol",
            mapOf("path" to "Main.kt", "line" to "99", "column" to "1"),
        )

        assertTrue(outcome is ProjectFiles.Outcome.Refused)
    }

    @Test
    fun nothing_is_checked_when_no_project_is_open() = runTest {
        served = Fake()

        val outcome = toolset(open = false).execute("check_kotlin", mapOf("path" to "Main.kt"))

        assertTrue(outcome is ProjectFiles.Outcome.Refused)
    }
}
