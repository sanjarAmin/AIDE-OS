package com.osamu.aide.ui.projects

import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ProjectGroupingTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        LocalDateTime.of(year, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun project(name: String, opened: Long, language: SourceLanguage = SourceLanguage.JAVA) = Project(
        name = name,
        rootDir = File("/tmp/$name"),
        applicationId = "com.example.$name",
        language = language,
        engine = BuildEngine.FAST,
        lastOpenedAt = opened,
    )

    private val now = at(2026, 10, 26, hour = 9)

    @Test
    fun last_night_is_not_today_even_though_it_was_under_a_day_ago() {
        // Ten hours ago, but yesterday. The heading names a day, so it must
        // say so, even though the card beside it reads "Opened 10h ago".
        assertEquals(Recency.PAST_WEEK, recencyOf(at(2026, 10, 25, hour = 23), now, zone))
        assertEquals(Recency.TODAY, recencyOf(at(2026, 10, 26, hour = 0), now, zone))
    }

    @Test
    fun buckets_follow_whole_days() {
        assertEquals(Recency.PAST_WEEK, recencyOf(at(2026, 10, 20), now, zone))
        assertEquals(Recency.PAST_MONTH, recencyOf(at(2026, 10, 19), now, zone))
        assertEquals(Recency.PAST_MONTH, recencyOf(at(2026, 9, 27), now, zone))
        assertEquals(Recency.OLDER, recencyOf(at(2026, 9, 26), now, zone))
    }

    @Test
    fun never_opened_and_the_future_have_their_own_answers() {
        assertEquals(Recency.NEVER, recencyOf(0L, now, zone))
        // A clock that went backwards: opened "tomorrow" means just now.
        assertEquals(Recency.TODAY, recencyOf(now + 86_400_000L, now, zone))
    }

    @Test
    fun groups_come_out_in_heading_order_whatever_order_the_list_is_in() {
        val projects = listOf(
            project("old", at(2025, 1, 1)),
            project("fresh", at(2026, 10, 26, hour = 8)),
            project("never", 0L),
            project("week", at(2026, 10, 22)),
            project("also-fresh", at(2026, 10, 26, hour = 7)),
        )

        val groups = groupByRecency(projects, now, zone)

        assertEquals(
            listOf(Recency.TODAY, Recency.PAST_WEEK, Recency.OLDER, Recency.NEVER),
            groups.map { it.first },
        )
        // Within a heading, the incoming order is kept.
        assertEquals(listOf("fresh", "also-fresh"), groups.first().second.map { it.name })
    }

    @Test
    fun language_counts_list_only_what_is_present_most_used_first() {
        val projects = listOf(
            project("a", 1L, SourceLanguage.CPP),
            project("b", 1L, SourceLanguage.JAVA),
            project("c", 1L, SourceLanguage.JAVA),
            project("d", 1L, SourceLanguage.KOTLIN),
        )

        assertEquals(
            // Kotlin and C++ tie at one; the catalog's order (Kotlin before
            // C++) breaks it, so the row does not reshuffle as counts change.
            listOf(SourceLanguage.JAVA to 2, SourceLanguage.KOTLIN to 1, SourceLanguage.CPP to 1),
            languageCounts(projects),
        )
    }

    @Test
    fun the_resumed_project_is_the_most_recent_and_only_while_browsing() {
        val projects = listOf(
            project("never", 0L),
            project("older", 100L),
            project("newest", 300L),
            project("middle", 200L),
        )

        assertEquals("newest", resumeCandidate(projects, query = "", language = null)?.name)
        assertNull(resumeCandidate(projects, query = "mid", language = null))
        assertNull(resumeCandidate(projects, query = "", language = SourceLanguage.JAVA))
        assertNull(resumeCandidate(listOf(project("never", 0L)), query = "", language = null))
    }

    @Test
    fun facts_leave_out_the_engine_for_a_project_that_only_runs() {
        assertEquals("Java · Fast build · 1 KB", projectFacts(SourceLanguage.JAVA, BuildEngine.FAST, "1 KB"))
        assertEquals("Python · 3 KB", projectFacts(SourceLanguage.PYTHON, BuildEngine.FAST, "3 KB"))
    }

    @Test
    fun every_language_has_a_monogram_short_enough_for_its_tile() {
        SourceLanguage.entries.forEach { language ->
            val letters = language.monogram
            assert(letters.length in 1..3) { "$language has monogram '$letters'" }
        }
        assertEquals(SourceLanguage.entries.size, SourceLanguage.entries.map { it.monogram }.distinct().size)
    }

    @Test
    fun sizes_and_counts_read_naturally() {
        assertEquals("512 B", sizeLabel(512))
        assertEquals("2 KB", sizeLabel(2048))
        assertEquals("1.5 MB", sizeLabel(1536L * 1024))
        assertEquals("No projects yet", projectCountLabel(0))
        assertEquals("1 project on this device", projectCountLabel(1))
        assertEquals("3 projects on this device", projectCountLabel(3))
    }
}
