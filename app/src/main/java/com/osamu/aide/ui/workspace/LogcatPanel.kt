package com.osamu.aide.ui.workspace

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osamu.aide.core.ui.theme.AideAmber
import com.osamu.aide.core.ui.theme.AideBlue
import com.osamu.aide.core.ui.theme.AideGreen
import com.osamu.aide.core.ui.theme.AideRed
import com.osamu.aide.core.ui.theme.CodeTextStyle

/** What the panel can do, so the screen owns the view model and this does not. */
data class LogcatActions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val clear: () -> Unit,
    val setFilter: (String) -> Unit,
    val setLevel: (LogcatLevel) -> Unit = {},
)

/**
 * The device log, filtered to the app being built.
 */
@Composable
fun LogcatPanel(
    state: LogcatUiState,
    actions: LogcatActions,
    /** Prefilled into the filter: the id of the app the user is building. */
    applicationId: String?,
    modifier: Modifier = Modifier,
) {
    val filter = rememberTextFieldState(initialText = applicationId.orEmpty())
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(filter) {
        snapshotFlow { filter.text.toString() }.collect(actions.setFilter)
    }

    LaunchedEffect(Unit) { if (!state.isReading) actions.start() }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                state = filter,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Log filter" },
                label = { Text("Filter") },
                lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
                textStyle = CodeTextStyle,
            )
            IconButton(
                onClick = {
                    if (state.lines.isNotEmpty()) {
                        clipboardManager.setText(AnnotatedString(state.lines.joinToString("\n")))
                        Toast.makeText(context, "Copied ${state.lines.size} lines to clipboard", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = state.lines.isNotEmpty(),
                modifier = Modifier.semantics { contentDescription = "Copy logs" },
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            TextButton(onClick = actions.clear) { Text("Clear") }
            if (state.isReading) {
                TextButton(onClick = actions.stop) { Text("Pause") }
            } else {
                TextButton(onClick = actions.start) { Text("Resume") }
            }
        }

        // Quick log level filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogcatLevel.entries.forEach { level ->
                FilterChip(
                    selected = state.minLevel == level,
                    onClick = { actions.setLevel(level) },
                    label = {
                        Text(
                            text = level.label,
                            fontSize = 11.sp,
                            fontWeight = if (state.minLevel == level) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    modifier = Modifier.semantics { contentDescription = "Log level ${level.label}" },
                )
            }
        }

        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        if (state.isOwnProcessOnly) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Only AIDE-OS's own lines are showing. Reading the log of " +
                        "the app you built needs Android's permission, which it asks " +
                        "for once per read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = actions.start,
                    modifier = Modifier.semantics { contentDescription = "Ask for log access again" },
                ) { Text("Ask again") }
            }
        }

        if (state.lines.isEmpty()) {
            Text(
                text = if (state.isReading) "Waiting for output…" else "No log lines.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
            return@Column
        }

        val listState = rememberLazyListState()
        LaunchedEffect(state.lines.size) {
            if (state.lines.isNotEmpty()) listState.scrollToItem(state.lines.size - 1)
        }

        val defaultTextColor = MaterialTheme.colorScheme.onSurface
        val variantTextColor = MaterialTheme.colorScheme.onSurfaceVariant

        // Unified horizontal scrolling container keeps all log lines perfectly aligned
        val horizontalScrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .horizontalScroll(horizontalScrollState),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxHeight(),
            ) {
                items(state.lines) { line ->
                    val level = levelOf(line)
                    val (textColor, bgColor) = when (level) {
                        LogcatLevel.ERROR -> AideRed to Color(0x1EFB7185)
                        LogcatLevel.WARN -> AideAmber to Color(0x14FBBF24)
                        LogcatLevel.INFO -> AideGreen to Color.Transparent
                        LogcatLevel.DEBUG -> AideBlue to Color.Transparent
                        LogcatLevel.VERBOSE -> variantTextColor to Color.Transparent
                        LogcatLevel.ALL -> defaultTextColor to Color.Transparent
                    }

                    Text(
                        text = line,
                        style = CodeTextStyle.copy(fontSize = 11.5.sp),
                        color = textColor,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .background(bgColor)
                            .padding(horizontal = 8.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}
