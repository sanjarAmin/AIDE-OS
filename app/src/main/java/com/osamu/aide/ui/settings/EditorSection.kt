package com.osamu.aide.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osamu.aide.editor.EditorPreferences
import com.osamu.aide.editor.EditorSettings
import kotlin.math.roundToInt

/**
 * How code is drawn: size first, because that is the one that stops people.
 *
 * The editor shipped fixed at 14 sp, which is a decision made for one pair of
 * eyes. The sample line above the slider is the point of the control -- a
 * number in sp means nothing to anyone, and the only useful preview of a code
 * font is code, in the font, at the size.
 */
@Composable
fun EditorSection(preferences: EditorPreferences, modifier: Modifier = Modifier) {
    val settings by preferences.settings.collectAsStateWithLifecycle()

    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Editor", style = MaterialTheme.typography.titleMedium)
            // Not "Reset" alone: a font size someone can no longer read is the
            // failure this recovers from, and by then the label has to be
            // legible in whatever size the rest of the app uses.
            TextButton(onClick = preferences::reset) { Text("Reset to defaults") }
        }

        Text(
            text = "Text size — ${settings.fontSizeSp.roundToInt()} sp",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            // Real code, and a line with indentation in it, so the tab width
            // below is visible in the same preview.
            text = "    return greeting + name;",
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = settings.fontSizeSp.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Slider(
            value = settings.fontSizeSp,
            onValueChange = { size -> preferences.update { it.copy(fontSizeSp = size) } },
            valueRange = EditorSettings.FONT_SIZE_RANGE,
            // One stop per whole sp: a code font at 14.37 sp is not a size
            // anyone chose, and the label would have to lie about it.
            steps = (EditorSettings.FONT_SIZE_RANGE.endInclusive -
                EditorSettings.FONT_SIZE_RANGE.start).toInt() - 1,
            modifier = Modifier.semantics { contentDescription = "Text size" },
        )

        Text(
            text = "Tab width",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorSettings.TAB_WIDTHS.forEach { width ->
                FilterChip(
                    selected = settings.tabWidth == width,
                    onClick = { preferences.update { it.copy(tabWidth = width) } },
                    label = { Text("$width") },
                )
            }
        }

        SettingSwitch(
            title = "Line numbers",
            detail = "The gutter that diagnostics point into.",
            checked = settings.showLineNumbers,
        ) { on -> preferences.update { it.copy(showLineNumbers = on) } }

        SettingSwitch(
            title = "Wrap long lines",
            // The default is an opinion and the screen says so rather than
            // presenting it as neutral.
            detail = "Off by default: wrapping hides the indentation that shows " +
                "a program's structure. On a narrow screen that can be worth it.",
            checked = settings.wordWrap,
        ) { on -> preferences.update { it.copy(wordWrap = on) } }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.padding(end = 12.dp)) {
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
