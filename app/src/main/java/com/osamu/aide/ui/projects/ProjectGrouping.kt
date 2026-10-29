package com.osamu.aide.ui.projects

import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The headings the project list is divided under.
 *
 * Rolling windows, and named as rolling windows: "This week" for something
 * opened six days ago reads as wrong to anyone whose week started yesterday,
 * and "Past week" is true whichever day it is.
 */
internal enum class Recency(val heading: String) {
    TODAY("Today"),
    PAST_WEEK("Past week"),
    PAST_MONTH("Past month"),
    OLDER("Older"),

    /** A clone or an import that has not been opened since it arrived. */
    NEVER("Never opened"),
}

/**
 * Which heading a project opened at [millis] belongs under.
 *
 * **"Today" is a calendar day, not 24 hours.** Something opened at 23:00 and
 * looked at again at 09:00 was not opened today, and a heading that says so
 * is the one place this list would be plainly wrong. [lastOpenedLabel] counts
 * elapsed time, which is right for "opened 10h ago"; a heading names a day.
 */
internal fun recencyOf(
    millis: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): Recency {
    if (millis <= 0L) return Recency.NEVER
    // A clock that has gone backwards -- a restored backup, a timezone
    // change -- puts the project in the future; it was opened just now.
    if (millis >= now) return Recency.TODAY
    val opened = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(opened, today)
    return when {
        days <= 0L -> Recency.TODAY
        days < 7 -> Recency.PAST_WEEK
        days < 30 -> Recency.PAST_MONTH
        else -> Recency.OLDER
    }
}

/**
 * [projects] under their headings, headings in [Recency] order.
 *
 * Sorted by heading explicitly rather than trusting the repository's order to
 * produce them in sequence: a list that is ever sorted any other way would
 * otherwise print "Today" twice, which reads as a rendering fault. Within a
 * heading the incoming order is kept, which is most recent first.
 */
internal fun groupByRecency(
    projects: List<Project>,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<Pair<Recency, List<Project>>> =
    projects.groupBy { recencyOf(it.lastOpenedAt, now, zone) }
        .toSortedMap()
        .map { (recency, members) -> recency to members }

/**
 * The languages [projects] are written in, most-used first, with how many.
 *
 * **Only the ones present.** The filter used to offer all seven languages to
 * someone with three Java projects, which is six chips that each lead to an
 * empty list -- and at 360 dp the row ran off the edge, so it also looked
 * broken. Ties keep the catalog's order, so the row does not reshuffle when a
 * count changes.
 */
internal fun languageCounts(projects: List<Project>): List<Pair<SourceLanguage, Int>> =
    projects.groupingBy { it.language }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<SourceLanguage, Int>> { it.value }.thenBy { it.key.ordinal })
        .map { it.key to it.value }

/**
 * The project to offer as "continue where you left off", if any.
 *
 * Only while browsing: with a search or a filter applied the user is looking
 * for something, and a large card above the results showing a project they
 * did not search for is in the way. A project never opened has nothing to
 * continue, so it is not offered either.
 */
internal fun resumeCandidate(
    projects: List<Project>,
    query: String,
    language: SourceLanguage?,
): Project? {
    if (query.isNotBlank() || language != null) return null
    return projects.filter { it.lastOpenedAt > 0L }.maxByOrNull { it.lastOpenedAt }
}

/**
 * How many project cards fit across a window [widthDp] wide.
 *
 * **One on every phone, and that is the point of the numbers.** A card needs
 * about 300 dp before its facts line ("C++ · Gradle build · 1.2 MB") starts
 * losing its size to an ellipsis, so the widest phone (~430 dp) stays at one
 * column, an unfolded foldable (~670 dp) gets two, and a landscape tablet
 * three or more. There is no cap: a desktop-width window simply shows more
 * columns of the same card, rather than stretching three until the name and
 * its menu are a hand-span apart -- the defect a single column had on a
 * tablet, which this exists to fix.
 *
 * The window's width, not the screen's, so split-screen gets a phone layout.
 */
internal fun projectColumns(widthDp: Float): Int {
    val usable = widthDp - 2 * GRID_EDGE_DP + GRID_GAP_DP
    return (usable / (MIN_CARD_WIDTH_DP + GRID_GAP_DP)).toInt().coerceAtLeast(1)
}

/** The narrowest a project card is allowed to get; see [projectColumns]. */
internal const val MIN_CARD_WIDTH_DP = 300f

/** The space between two cards in a row. */
internal const val GRID_GAP_DP = 12f

/** The margin at each side of the list, which every other block on the screen shares. */
internal const val GRID_EDGE_DP = 16f

/** A project's facts on one line: what it is written in, how it builds, its size. */
internal fun projectFacts(language: SourceLanguage, engine: BuildEngine, size: String): String =
    buildList {
        add(language.displayName)
        // A run-only project has no build engine to name; see RUN_ONLY.
        if (language !in RUN_ONLY) add("${engine.displayName} build")
        add(size)
    }.joinToString(" · ")

/** "1 project", "3 projects": the toolbar's count, which is the only real number it has. */
internal fun projectCountLabel(count: Int): String = when (count) {
    0 -> "No projects yet"
    1 -> "1 project on this device"
    else -> "$count projects on this device"
}

/**
 * Bytes, at the precision someone deciding what to delete needs.
 *
 * Moved here from the card unchanged, so it can be tested.
 */
internal fun sizeLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
}

/**
 * The letters on a project's tile.
 *
 * Two at most where the language's own short name allows, three for C++ because
 * "C+" is not anything. These replace a generic file icon, which drew the same
 * page-with-a-fold for every Java project and said nothing a glance could use.
 */
internal val SourceLanguage.monogram: String
    get() = when (this) {
        SourceLanguage.JAVA -> "J"
        SourceLanguage.KOTLIN -> "Kt"
        SourceLanguage.C -> "C"
        SourceLanguage.CPP -> "C++"
        SourceLanguage.JAVASCRIPT -> "JS"
        SourceLanguage.CSHARP -> "C#"
        SourceLanguage.PYTHON -> "Py"
    }

/**
 * The extension [com.osamu.aide.ui.util.FileIcons] colours a language's files by.
 *
 * A project's tile takes its colour from there so a Java project and a `.java`
 * file in its tree are the same orange, rather than the list inventing a second
 * palette that drifts from the editor's.
 */
internal val SourceLanguage.extension: String
    get() = when (this) {
        SourceLanguage.KOTLIN -> "kt"
        SourceLanguage.JAVASCRIPT -> "js"
        SourceLanguage.CSHARP -> "cs"
        SourceLanguage.PYTHON -> "py"
        SourceLanguage.C -> "c"
        SourceLanguage.CPP -> "cpp"
        SourceLanguage.JAVA -> "java"
    }
