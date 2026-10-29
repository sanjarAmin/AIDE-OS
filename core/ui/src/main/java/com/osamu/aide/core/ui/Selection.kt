package com.osamu.aide.core.ui

import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Selected-state colours for a chip, in the accent rather than in green.
 *
 * **Material 3 draws a selected `FilterChip` in `secondaryContainer`, and this
 * palette makes that green.** So every selection in the app read as a success
 * state: the projects filter's "All" looked approved, Settings' "Match app"
 * looked like a passed check, and a tab width of 4 looked like the *right*
 * answer rather than the chosen one. Selection is not success; it belongs to
 * the primary accent, which is what every other "this one" in the app uses.
 * CLAUDE.md, "The palette gives Material's role names real colours".
 */
@Composable
fun selectionChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/**
 * The same correction for a segmented button, which defaults to the same green.
 *
 * The inactive container is transparent so a segment row sits on whatever
 * surface hosts it, and the border is `outline` rather than `outlineVariant`:
 * the segments are separate targets, and the quieter role does not survive a
 * phone screen at that width.
 */
@Composable
fun selectionSegmentColors(): SegmentedButtonColors = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    activeBorderColor = MaterialTheme.colorScheme.primary,
    inactiveContainerColor = Color.Transparent,
    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    inactiveBorderColor = MaterialTheme.colorScheme.outline,
)
