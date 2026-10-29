package com.osamu.aide.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osamu.aide.editor.EditorColorTheme
import com.osamu.aide.editor.EditorPreferences
import com.osamu.aide.editor.EditorSettings
import kotlin.math.roundToInt

/**
 * How code is drawn, shown before it is asked about.
 *
 * **A preview every control on the page changes.** The old page previewed one
 * line at the chosen size and nothing else, so a tab width was three numbers,
 * "Wrap long lines" was a paragraph arguing about indentation, and the colour
 * choice was three words. Now each one is visible the moment it is touched:
 * the indentation widens, the gutter appears and disappears, the long line
 * wraps or runs off the edge, and the background flips. A setting that can be
 * seen does not need its consequences explained in prose.
 *
 * Size is still first among the controls, because it is the one that stops
 * people: the editor shipped fixed at 14 sp, a decision made for one pair of
 * eyes.
 */
@Composable
fun EditorSection(preferences: EditorPreferences, modifier: Modifier = Modifier) {
    val settings by preferences.settings.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = PanelSpacing,
    ) {
        EditorPreview(settings)

        SettingsPanel {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The label takes what the value leaves, never the reverse.
                    Text("Text size", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        // Beside the control rather than in a heading joined by
                        // an em dash: "Text size — 14 sp" puts the number
                        // furthest from the thing that changes it.
                        text = "${settings.fontSizeSp.roundToInt()} sp",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = settings.fontSizeSp,
                    // **Snapped here, not by `steps`.** A step per whole sp is
                    // right for the value and wrong for the drawing: Material
                    // puts a tick on every stop, and seventeen sizes across a
                    // 360 dp screen rendered as two rows of dots with a bar
                    // between them -- a control that read as a broken progress
                    // meter. Rounding in the callback keeps the size whole.
                    onValueChange = { size ->
                        val whole = size.roundToInt().toFloat()
                        if (whole != settings.fontSizeSp) {
                            preferences.update { it.copy(fontSizeSp = whole) }
                        }
                    },
                    valueRange = EditorSettings.FONT_SIZE_RANGE,
                    // **The inactive track, said explicitly.** Material 3
                    // defaults it to `secondaryContainer`, which is green in
                    // this palette, so the control drew a blue filled half
                    // against a green empty half and read as two things.
                    colors = SliderDefaults.colors(
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.semantics { contentDescription = "Text size" },
                )
            }
            PanelDivider()
            ChoiceRow(
                title = "Colours",
                options = EditorColorTheme.entries,
                selected = settings.theme,
                label = { it.displayName },
                onSelect = { theme -> preferences.update { it.copy(theme = theme) } },
            )
            PanelDivider()
            ChoiceRow(
                title = "Tab width",
                options = EditorSettings.TAB_WIDTHS,
                selected = settings.tabWidth,
                label = { "$it spaces" },
                onSelect = { width -> preferences.update { it.copy(tabWidth = width) } },
            )
        }

        SettingsPanel {
            SettingSwitch(
                title = "Line numbers",
                detail = "The gutter that diagnostics point into.",
                checked = settings.showLineNumbers,
            ) { on -> preferences.update { it.copy(showLineNumbers = on) } }
            PanelDivider()
            SettingSwitch(
                title = "Wrap long lines",
                // The default is an opinion and the screen says so rather
                // than presenting it as neutral. Shorter than it was: the
                // preview now shows what wrapping does to indentation.
                detail = "Off by default, so indentation keeps showing a program's shape.",
                checked = settings.wordWrap,
            ) { on -> preferences.update { it.copy(wordWrap = on) } }
        }

        // At the foot, and a full label. A font size someone can no longer
        // read is the failure this recovers from, and by then the button has
        // to be legible at whatever size the rest of the app uses.
        TextButton(
            onClick = preferences::reset,
            modifier = Modifier.align(Alignment.End),
        ) {
            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Reset to defaults", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * A few lines of code, drawn the way the editor will draw them.
 *
 * **The colours are the editor's, read out of sora-editor 0.23.6's bytecode**
 * -- `SchemeDarcula` for dark and the base `EditorColorScheme` for light, which
 * is what `CodeEditorView` installs. Taken from the app's own palette instead,
 * the preview showed a blue-and-purple dark theme and the editor then opened
 * in Darcula's orange and green, which is a preview of nothing. If the
 * editor's scheme changes, these have to follow it.
 */
@Composable
private fun EditorPreview(settings: EditorSettings) {
    // The same test CodeEditorView makes, so "Match app" previews the scheme
    // the editor will actually pick rather than a guess at it.
    val appIsDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val palette = if (settings.theme.isDark(appIsDark)) DARCULA else SORA_LIGHT
    val indent = " ".repeat(settings.tabWidth)

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = palette.background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // A title bar, so the block reads as a window onto the editor
            // rather than as a code sample pasted into the settings.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.gutter)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(Color(0xFFFB7185), Color(0xFFFBBF24), Color(0xFF34D399)).forEach { dot ->
                    Spacer(Modifier.size(8.dp).background(dot.copy(alpha = 0.8f), CircleShape))
                }
                Text(
                    text = "Greeter.java",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.lineNumber,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Column(Modifier.padding(vertical = 12.dp)) {
                SAMPLE.forEachIndexed { index, line ->
                    PreviewLine(
                        number = index + 1,
                        text = buildAnnotatedString {
                            append(indent.repeat(line.depth))
                            line.tokens.forEach { (text, kind) ->
                                withStyle(palette.styleOf(kind)) { append(text) }
                            }
                        },
                        settings = settings,
                        palette = palette,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewLine(
    number: Int,
    text: androidx.compose.ui.text.AnnotatedString,
    settings: EditorSettings,
    palette: PreviewPalette,
) {
    val size = settings.fontSizeSp.sp
    Row(Modifier.fillMaxWidth()) {
        if (settings.showLineNumbers) {
            Text(
                text = "$number",
                fontFamily = FontFamily.Monospace,
                fontSize = size,
                color = palette.lineNumber,
                modifier = Modifier.padding(start = 14.dp, end = 12.dp),
            )
        } else {
            Spacer(Modifier.padding(start = 14.dp))
        }
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = size,
            lineHeight = size * 1.35f,
            color = palette.text,
            // Unwrapped, a long line is cut at the edge -- which is exactly
            // what the editor does, and the point of showing it.
            softWrap = settings.wordWrap,
            maxLines = if (settings.wordWrap) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.weight(1f).padding(end = 14.dp),
        )
    }
}

private enum class TokenKind { KEYWORD, TYPE, FUNCTION, STRING, PLAIN }

private class SampleLine(val depth: Int, val tokens: List<Pair<String, TokenKind>>)

/**
 * Three levels of nesting, so tab width is visible, and one line long enough
 * to run past a phone's width at the default size, so wrapping is.
 */
private val SAMPLE = listOf(
    SampleLine(0, listOf("class " to TokenKind.KEYWORD, "Greeter" to TokenKind.TYPE, " {" to TokenKind.PLAIN)),
    SampleLine(
        1,
        listOf(
            "String " to TokenKind.TYPE, "greet" to TokenKind.FUNCTION, "(" to TokenKind.PLAIN,
            "String " to TokenKind.TYPE, "name) {" to TokenKind.PLAIN,
        ),
    ),
    SampleLine(2, listOf("if " to TokenKind.KEYWORD, "(name.isEmpty()) {" to TokenKind.PLAIN)),
    SampleLine(3, listOf("return " to TokenKind.KEYWORD, "\"Hello, whoever you are\"" to TokenKind.STRING, ";" to TokenKind.PLAIN)),
    SampleLine(2, listOf("}" to TokenKind.PLAIN)),
    SampleLine(2, listOf("return " to TokenKind.KEYWORD, "\"Hello, \" + name;" to TokenKind.STRING)),
    SampleLine(1, listOf("}" to TokenKind.PLAIN)),
    SampleLine(0, listOf("}" to TokenKind.PLAIN)),
)

private class PreviewPalette(
    val background: Color,
    val gutter: Color,
    val lineNumber: Color,
    val text: Color,
    val keyword: Color,
    val type: Color,
    val function: Color,
    val string: Color,
) {
    /** Keywords and types bold, as `EditorTheme` styles them. */
    fun styleOf(kind: TokenKind): SpanStyle = when (kind) {
        TokenKind.KEYWORD -> SpanStyle(color = keyword, fontWeight = FontWeight.Bold)
        TokenKind.TYPE -> SpanStyle(color = type, fontWeight = FontWeight.Bold)
        TokenKind.FUNCTION -> SpanStyle(color = function)
        TokenKind.STRING -> SpanStyle(color = string)
        TokenKind.PLAIN -> SpanStyle(color = text)
    }
}

/** `SchemeDarcula`: WHOLE_BACKGROUND, LINE_NUMBER_BACKGROUND, LINE_NUMBER, TEXT_NORMAL, KEYWORD, IDENTIFIER_NAME, FUNCTION_NAME, LITERAL. */
private val DARCULA = PreviewPalette(
    background = Color(0xFF2B2B2B),
    gutter = Color(0xFF313335),
    lineNumber = Color(0xFF606366),
    text = Color(0xFFFFFFFF),
    keyword = Color(0xFFCC7832),
    type = Color(0xFFFFFFFF),
    function = Color(0xFFFFFFFF),
    string = Color(0xFF6A8759),
)

/** The base `EditorColorScheme`, in the same order. */
private val SORA_LIGHT = PreviewPalette(
    background = Color(0xFFFFFFFF),
    gutter = Color(0xFFEEEEEE),
    lineNumber = Color(0xFF505050),
    text = Color(0xFF333333),
    keyword = Color(0xFF2196F3),
    type = Color(0xFF03A9F4),
    function = Color(0xFFE040FB),
    string = Color(0xFF008080),
)
