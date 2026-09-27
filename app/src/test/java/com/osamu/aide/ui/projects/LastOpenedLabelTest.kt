package com.osamu.aide.ui.projects

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The one line every project card carries.
 *
 * It replaced a slot that showed "Runs on Mono" for some languages and an
 * application id for others, so the cases worth pinning are the boundaries
 * people actually read across -- minutes to hours, yesterday, weeks -- plus the
 * two that come from bad data rather than from time passing.
 */
class LastOpenedLabelTest {

    private val now = 1_800_000_000_000L

    private fun ago(amount: Long, unit: TimeUnit) =
        lastOpenedLabel(now - unit.toMillis(amount), now)

    @Test
    fun `a project just opened says so`() {
        assertEquals("Opened just now", ago(30, TimeUnit.SECONDS))
    }

    @Test
    fun `minutes and hours read as minutes and hours`() {
        assertEquals("Opened 5m ago", ago(5, TimeUnit.MINUTES))
        assertEquals("Opened 59m ago", ago(59, TimeUnit.MINUTES))
        assertEquals("Opened 1h ago", ago(60, TimeUnit.MINUTES))
        assertEquals("Opened 23h ago", ago(23, TimeUnit.HOURS))
    }

    @Test
    fun `one day is yesterday, not 1d ago`() {
        assertEquals("Opened yesterday", ago(24, TimeUnit.HOURS))
        assertEquals("Opened 2d ago", ago(48, TimeUnit.HOURS))
    }

    @Test
    fun `a week or more counts in weeks`() {
        assertEquals("Opened 1w ago", ago(7, TimeUnit.DAYS))
        assertEquals("Opened 4w ago", ago(30, TimeUnit.DAYS))
    }

    @Test
    fun `beyond a year it stops counting`() {
        assertEquals("Opened over a year ago", ago(400, TimeUnit.DAYS))
    }

    @Test
    fun `a project that has never been opened says that`() {
        // An imported project, or one whose aide.json predates the field.
        assertEquals("Not opened yet", lastOpenedLabel(0L, now))
    }

    @Test
    fun `a timestamp in the future does not count backwards`() {
        // A restored backup or a timezone change should not produce
        // "Opened -3d ago".
        assertEquals("Opened just now", lastOpenedLabel(now + 60_000L, now))
    }
}
