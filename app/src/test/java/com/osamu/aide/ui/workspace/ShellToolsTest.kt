package com.osamu.aide.ui.workspace

import com.osamu.aide.ai.core.ProjectFiles
import com.osamu.aide.ai.core.ProjectToolset
import com.osamu.aide.ai.core.ToolRisk
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShellToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun project() = Project(
        name = "Sample",
        rootDir = temp.root,
        applicationId = "com.example.sample",
        language = SourceLanguage.JAVA,
        engine = BuildEngine.FAST,
        lastOpenedAt = 0L,
    )

    private fun tools(
        open: Boolean = true,
        commandRunner: (suspend (File, String) -> ProjectFiles.Outcome)? = null,
    ) = shellTools(
        project = { if (open) project() else null },
        commandRunner = commandRunner,
    )

    private fun toolset(
        open: Boolean = true,
        commandRunner: (suspend (File, String) -> ProjectFiles.Outcome)? = null,
    ) = ProjectToolset(ProjectFiles(temp.root), tools(open, commandRunner))

    @Test
    fun run_shell_is_offered_and_is_mutating() {
        val tool = toolset().all().firstOrNull { it.name == "run_shell" }
        assertTrue("run_shell must be offered in toolset", tool != null)
        assertEquals(
            "run_shell can execute arbitrary code, so it MUST be ToolRisk.MUTATING",
            ToolRisk.MUTATING,
            tool?.risk,
        )
        assertTrue("command is a required parameter", tool?.required?.contains("command") == true)
    }

    @Test
    fun run_shell_refuses_without_user_approval() = runTest {
        val outcome = toolset().execute(
            name = "run_shell",
            input = mapOf("command" to "echo 42"),
            approved = false,
        )
        assertTrue("Unapproved mutating tool must be refused", outcome is ProjectFiles.Outcome.Refused)
        assertTrue(
            "Reason must explain approval was needed",
            "not confirmed" in (outcome as ProjectFiles.Outcome.Refused).reason,
        )
    }

    @Test
    fun run_shell_refuses_when_no_project_is_open() = runTest {
        val outcome = toolset(open = false).execute(
            name = "run_shell",
            input = mapOf("command" to "echo 42"),
            approved = true,
        )
        assertTrue(outcome is ProjectFiles.Outcome.Refused)
        assertTrue("No project is open" in (outcome as ProjectFiles.Outcome.Refused).reason)
    }

    @Test
    fun run_shell_refuses_when_command_is_missing() = runTest {
        val outcome = toolset().execute(
            name = "run_shell",
            input = emptyMap(),
            approved = true,
        )
        assertTrue(outcome is ProjectFiles.Outcome.Refused)
        assertTrue("needs a command" in (outcome as ProjectFiles.Outcome.Refused).reason)
    }

    @Test
    fun run_shell_runs_when_approved() = runTest {
        var ranCommand = ""
        val outcome = toolset(
            commandRunner = { _, cmd ->
                ranCommand = cmd
                ProjectFiles.Outcome.Ok("command output")
            },
        ).execute(
            name = "run_shell",
            input = mapOf("command" to "ls -la"),
            approved = true,
        )

        assertEquals("ls -la", ranCommand)
        assertTrue(outcome is ProjectFiles.Outcome.Ok)
        assertEquals("command output", (outcome as ProjectFiles.Outcome.Ok).content)
    }
}
