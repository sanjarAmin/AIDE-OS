package com.osamu.aide.ui.projects

import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProjectsFilterTest {

    private val sampleProjects = listOf(
        Project(
            name = "Alpha Kotlin",
            rootDir = File("/tmp/alpha"),
            applicationId = "com.example.alpha",
            language = SourceLanguage.KOTLIN,
            engine = BuildEngine.FAST,
            lastOpenedAt = 1000L,
        ),
        Project(
            name = "Beta Java",
            rootDir = File("/tmp/beta"),
            applicationId = "com.example.beta",
            language = SourceLanguage.JAVA,
            engine = BuildEngine.FAST,
            lastOpenedAt = 2000L,
        ),
        Project(
            name = "Gamma Native",
            rootDir = File("/tmp/gamma"),
            applicationId = "org.sample.gamma",
            language = SourceLanguage.CPP,
            engine = BuildEngine.GRADLE,
            lastOpenedAt = 3000L,
        ),
        Project(
            name = "Delta Python",
            rootDir = File("/tmp/delta"),
            applicationId = "com.sample.delta",
            language = SourceLanguage.PYTHON,
            engine = BuildEngine.FAST,
            lastOpenedAt = 4000L,
        ),
    )

    @Test
    fun blank_query_and_null_language_returns_all_projects() {
        val result = applyFilter(sampleProjects, "", null)
        assertEquals(4, result.size)
    }

    @Test
    fun search_query_filters_by_name_case_insensitively() {
        val result = applyFilter(sampleProjects, "alpha", null)
        assertEquals(1, result.size)
        assertEquals("Alpha Kotlin", result[0].name)
    }

    @Test
    fun search_query_filters_by_application_id() {
        val result = applyFilter(sampleProjects, "org.sample", null)
        assertEquals(1, result.size)
        assertEquals("Gamma Native", result[0].name)
    }

    @Test
    fun language_filter_isolates_matching_language() {
        val result = applyFilter(sampleProjects, "", SourceLanguage.PYTHON)
        assertEquals(1, result.size)
        assertEquals("Delta Python", result[0].name)
    }

    @Test
    fun combined_search_and_language_filter_matches_both() {
        val resultMatch = applyFilter(sampleProjects, "sample", SourceLanguage.PYTHON)
        assertEquals(1, resultMatch.size)
        assertEquals("Delta Python", resultMatch[0].name)

        val resultNoMatch = applyFilter(sampleProjects, "sample", SourceLanguage.JAVA)
        assertTrue(resultNoMatch.isEmpty())
    }

    @Test
    fun non_matching_query_returns_empty_list() {
        val result = applyFilter(sampleProjects, "nonexistent", null)
        assertTrue(result.isEmpty())
    }
}
