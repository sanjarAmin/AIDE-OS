package com.osamu.aide.ui.workspace

import android.view.KeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.osamu.aide.core.ui.theme.CodeTextStyle

/** What the terminal panel can do, gathered so the dock can forward it. */
data class TerminalActions(
    val type: (String) -> Unit,
    val typeChar: (Char, Boolean) -> Unit,
    val sendKey: (Int) -> Unit,
    val interrupt: () -> Unit,
    val restart: () -> Unit,
)

/**
 * A terminal, rendered from the vendored emulator's screen.
 *
 * **Characters go through as they are typed**, not a line at a time: the
 * emulator can interpret what comes back, so an interactive program receives
 * input while it is running. The field is kept empty and every change is
 * forwarded, which is what makes it behave like a keyboard rather than a form.
 *
 * A row of keys a soft keyboard does not have -- Esc, Tab, Ctrl, the arrows --
 * sits above it, because without them a phone cannot drive anything that reads
 * more than plain text.
 *
 * Attributes are not rendered. The emulator tracks colour, bold and inverse per
 * cell; this draws the characters in one style. `terminal/FINDINGS.md` records
 * that as the next piece of work rather than a limitation of the emulator.
 */
@Composable
fun TerminalPanel(
    state: TerminalUiState,
    actions: TerminalActions,
    modifier: Modifier = Modifier,
) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val focus = remember { FocusRequester() }
    var control by remember { mutableStateOf(false) }

    // Follow the output the way a terminal does. Keyed on length so an
    // unchanged screen does not re-scroll on every recomposition.
    LaunchedEffect(state.screen.text.length) { vertical.animateScrollTo(vertical.maxValue) }

    Column(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(vertical)
                .horizontalScroll(horizontal),
        ) {
            Text(
                text = state.screen.text.ifEmpty { "Starting a shell…" },
                style = CodeTextStyle,
                softWrap = false,
                modifier = Modifier.semantics { contentDescription = "Terminal output" },
            )
        }

        when (val shell = state.state) {
            is com.osamu.aide.terminal.TerminalState.Exited ->
                ExitedRow(shell.status, actions.restart)
            is com.osamu.aide.terminal.TerminalState.Failed -> Text(
                text = shell.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }

        KeyRow(
            control = control,
            onToggleControl = { control = !control },
            enabled = state.isRunning,
            onKey = { code ->
                actions.sendKey(code)
                control = false
            },
            onInterrupt = actions.interrupt,
        )

        // The field is always empty: what it receives is forwarded immediately
        // and then discarded. It exists to raise the soft keyboard and to give
        // the platform somewhere to deliver characters, not to hold text.
        BasicTextField(
            value = TextFieldValue(""),
            onValueChange = { typed ->
                if (typed.text.isEmpty()) return@BasicTextField
                if (control && typed.text.length == 1) {
                    actions.typeChar(typed.text[0], true)
                    control = false
                } else {
                    actions.type(typed.text)
                }
            },
            enabled = state.isRunning,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                autoCorrect = false,
                imeAction = ImeAction.None,
            ),
            decorationBox = { field ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("> ", style = CodeTextStyle, color = MaterialTheme.colorScheme.primary)
                    Box(Modifier.weight(1f)) {
                        field()
                        Text(
                            text = "type here",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .focusRequester(focus)
                .semantics { contentDescription = "Terminal input" },
        )
    }
}

/**
 * The keys a soft keyboard does not have.
 *
 * Esc, Tab, the arrows and a sticky Ctrl. Every mobile terminal grows this row
 * for the same reason: without it there is no way to leave `vi`, complete a
 * path, or interrupt anything.
 */
@Composable
private fun KeyRow(
    control: Boolean,
    onToggleControl: () -> Unit,
    enabled: Boolean,
    onKey: (Int) -> Unit,
    onInterrupt: () -> Unit,
) {
    // Scrollable: the row is wider than a phone. Clipping it silently loses
    // whichever key is last, which was ^C -- the one nobody can do without.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TextButton(onClick = onToggleControl, enabled = enabled) {
            Text(
                text = "CTRL",
                style = MaterialTheme.typography.labelSmall,
                color = if (control) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        TextButton(
            onClick = { onKey(KeyEvent.KEYCODE_ESCAPE) },
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "Escape key" },
        ) { Text("ESC", style = MaterialTheme.typography.labelSmall) }
        TextButton(
            onClick = { onKey(KeyEvent.KEYCODE_TAB) },
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "Tab key" },
        ) { Text("TAB", style = MaterialTheme.typography.labelSmall) }

        Arrow(Icons.Default.KeyboardArrowLeft, "Left", KeyEvent.KEYCODE_DPAD_LEFT, enabled, onKey)
        Arrow(Icons.Default.KeyboardArrowDown, "Down", KeyEvent.KEYCODE_DPAD_DOWN, enabled, onKey)
        Arrow(Icons.Default.KeyboardArrowUp, "Up", KeyEvent.KEYCODE_DPAD_UP, enabled, onKey)
        Arrow(Icons.Default.KeyboardArrowRight, "Right", KeyEvent.KEYCODE_DPAD_RIGHT, enabled, onKey)

        TextButton(
            onClick = onInterrupt,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "Interrupt" },
        ) { Text("^C", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun Arrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    keyCode: Int,
    enabled: Boolean,
    onKey: (Int) -> Unit,
) {
    IconButton(
        onClick = { onKey(keyCode) },
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "$name key" },
    ) { Icon(icon, contentDescription = null) }
}

@Composable
private fun ExitedRow(status: Int, onRestart: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            // 128 + signal is how a shell reports being killed, and it means
            // something different from exiting with a status.
            text = if (status > 128) {
                "The shell was killed by signal ${status - 128}."
            } else {
                "The shell exited with status $status."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onRestart,
            modifier = Modifier.semantics { contentDescription = "Restart the shell" },
        ) { Text("Restart") }
    }
}
