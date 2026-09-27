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

class GitToolsTest {

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

    private fun tools(open: Boolean = true) = gitTools(
        project = { if (open) project() else null },
        git = null,
        workspaceRoot = temp.root,
    )

    private fun toolset(open: Boolean = true) =
        ProjectToolset(ProjectFiles(temp.root), tools(open))

    @Test
    fun all_three_git_tools_are_offered_and_all_are_read_only() {
        val names = toolset().all().map { it.name }
        assertTrue("git_status must be present", "git_status" in names)
        assertTrue("git_diff must be present", "git_diff" in names)
        assertTrue("git_log must be present", "git_log" in names)

        val gitToolList = toolset().all().filter { it.name.startsWith("git_") }
        assertEquals(3, gitToolList.size)
        assertTrue(
            "Git inspection tools must be READ_ONLY so they do not prompt for confirmation",
            gitToolList.all { it.risk == ToolRisk.READ_ONLY },
        )
    }

    @Test
    fun git_status_refuses_when_no_project_is_open() = runTest {
        val outcome = toolset(open = false).execute("git_status", emptyMap())
        assertTrue(outcome is ProjectFiles.Outcome.Refused)
        assertTrue("No project is open" in (outcome as ProjectFiles.Outcome.Refused).reason)
    }

    @Test
    fun git_status_refuses_when_git_service_is_null() = runTest {
        val outcome = toolset(open = true).execute("git_status", emptyMap())
        assertTrue(outcome is ProjectFiles.Outcome.Refused)
        assertTrue("Git workspace service is not available" in (outcome as ProjectFiles.Outcome.Refused).reason)
    }

    @Test
    fun git_diff_refuses_when_no_project_is_open() = runTest {
        val outcome = toolset(open = false).execute("git_diff", emptyMap())
        assertTrue(outcome is ProjectFiles.Outcome.Refused)
        assertTrue("No project is open" in (outcome as ProjectFiles.Outcome.Refused).reason)
    }

    @Test
    fun git_log_refuses_when_no_project_is_open() = runTest {
        val outcome = toolset(open = false).execute("git_log", emptyMap())
        assertTrue(outcome is ProjectFiles.Outcome.Refused)
        assertTrue("No project is open" in (outcome as ProjectFiles.Outcome.Refused).reason)
    }
}
