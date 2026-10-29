package com.osamu.aide.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.osamu.aide.core.ui.BrandMark
import com.osamu.aide.core.ui.selectionSegmentColors
import com.osamu.aide.core.ui.theme.AidePurple
import com.osamu.aide.core.ui.theme.AidePurpleDeep

/**
 * A rounded surface that holds related settings, one per row.
 *
 * The page is a stack of these rather than one long column, so the eye can
 * find "the switches" or "the size" without reading every label on the way.
 * The border is there because `surfaceContainerLow` is one step from the
 * page background, and on a dim phone one step is not always an edge.
 */
@Composable
fun SettingsPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

/** The rule between two rows of a [SettingsPanel], inset to start under their text. */
@Composable
fun PanelDivider(inset: Dp = 16.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = inset),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * A setting that is on or off, explained.
 *
 * **`weight`, or the switch goes off the edge of the screen.** Without it the
 * text measures at whatever width it wants and the Switch is given what is
 * left, which for a two-line detail is less than a Switch -- "Wrap long lines"
 * had its track running past the right edge and half its touch target outside
 * the window, while "Line numbers", whose detail is shorter, sat correctly.
 * `EditorSectionTest` holds the two switches to the same size.
 *
 * **The whole row toggles, not only the switch.** Found driving the redesign:
 * a tap on "Wrap long lines" -- the words, which is where a thumb goes --
 * did nothing, because only the 52 dp track to their right was a target. The
 * row's `clickable` does not swallow the switch's own semantics: the Switch is
 * a merge boundary of its own, so the test still finds it by its description.
 */
@Composable
fun SettingSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Switch,
                onClickLabel = if (checked) "Turn off" else "Turn on",
            ) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

/**
 * One of a few named values, as a segmented row under its label.
 *
 * **Segments, not chips.** Chips in a `Row` were sized by their labels, so
 * "Match app" was twice the width of "Dark" and a tab width of 2 sat in a
 * box a third the size of its neighbours -- a choice that looked like three
 * unrelated buttons. Segments share the width equally and read as one control
 * with one answer, which is what a single choice is.
 *
 * The check icon Material draws in the selected segment is turned off: in a
 * third of a phone's width it pushed "Match app" into an ellipsis, and the
 * filled segment already says which one is chosen.
 */
@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    colors = selectionSegmentColors(),
                    icon = {},
                ) {
                    Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * The colour a category is drawn in, everywhere it appears.
 *
 * Identity only, never status -- which is why none of these is the palette's
 * green, and why the summaries under them are coloured separately and only
 * when something is wrong. Git's is git's own orange, as the file tree gives
 * `.gitignore`; About's is no colour at all, because it is not a setting.
 */
@Composable
internal fun SettingsCategory.accent(): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (this) {
        SettingsCategory.EDITOR -> MaterialTheme.colorScheme.primary
        SettingsCategory.AI -> if (dark) AidePurple else AidePurpleDeep
        SettingsCategory.GIT -> Color(0xFFF05032)
        SettingsCategory.TOOLCHAINS -> if (dark) Color(0xFF2DD4BF) else Color(0xFF0D9488)
        SettingsCategory.SIGNING -> MaterialTheme.colorScheme.tertiary
        SettingsCategory.ABOUT -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** A category's icon on a tile of its own colour; About wears the app's mark instead. */
@Composable
internal fun CategoryBadge(category: SettingsCategory, size: Dp = 40.dp) {
    if (category == SettingsCategory.ABOUT) {
        BrandMark(size = size)
        return
    }
    val accent = category.accent()
    Box(
        modifier = Modifier
            .size(size)
            .background(accent.copy(alpha = 0.16f), RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = category.icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

/** The colour a summary is written in: ordinary unless it is reporting something wrong. */
@Composable
internal fun Tone.color(): Color = when (this) {
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.ATTENTION -> MaterialTheme.colorScheme.tertiary
    Tone.PROBLEM -> MaterialTheme.colorScheme.error
}

/** The space between panels on a category page. */
internal val PanelSpacing = Arrangement.spacedBy(12.dp)
