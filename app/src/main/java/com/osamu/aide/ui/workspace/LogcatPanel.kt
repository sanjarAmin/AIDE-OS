package com.osamu.aide.ui.workspace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.osamu.aide.core.ui.theme.CodeTextStyle

/** What the panel can do, so the screen owns the view model and this does not. */
data class LogcatActions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val clear: () -> Unit,
    val setFilter: (String) -> Unit,
)

/**
 * The device log, filtered to the app being built.
 *
 * Reading it needs `READ_LOGS`, which Android grants through **its own**
 * one-time dialog the first time this reads -- see `tools/logcat/FINDINGS.md`
 * for why that is the whole mechanism and what it costs. Declining is an
 * ordinary outcome, so the refusal has a sentence and a button rather than an
 * error.
 */
@Composable
fun LogcatPanel(
    state: LogcatUiState,
    actions: LogcatActions,
    /** Prefilled into the filter: the id of the app the user is building. */
    applicationId: String?,
    modifier: Modifier = Modifier,
) {
    // A TextFieldState, not a value/onValueChange pair: a constant
    // TextFieldValue never tells the IME anything changed, and the terminal's
    // input bug was exactly that.
    val filter = rememberTextFieldState(initialText = applicationId.orEmpty())

    LaunchedEffect(filter) {
        snapshotFlow { filter.text.toString() }.collect(actions.setFilter)
    }

    // Read once, when the tab is first composed. Not in the view model's init:
    // that would ask for consent for a panel nobody had opened.
    LaunchedEffect(Unit) { if (!state.isReading) actions.start() }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                state = filter,
                // Weighted, so the buttons beside it keep their size. That is
                // the defect CLAUDE.md records seven times over.
                modifier = Modifier.weight(1f).semantics { contentDescription = "Log filter" },
                label = { Text("Filter") },
                lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
                textStyle = CodeTextStyle,
            )
            TextButton(onClick = actions.clear) { Text("Clear") }
            if (state.isReading) {
                TextButton(onClick = actions.stop) { Text("Pause") }
            } else {
                TextButton(onClick = actions.start) { Text("Resume") }
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
                    // Says what is missing and what it costs the user to fix,
                    // and does not blame them for declining.
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
        // Follows the tail, which is what a log pane is for. `size - 1` rather
        // than a flag: a list that is already at the bottom scrolls to the
        // bottom again for free.
        LaunchedEffect(state.lines.size) {
            if (state.lines.isNotEmpty()) listState.scrollToItem(state.lines.size - 1)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            items(state.lines) { line ->
                Text(
                    text = line,
                    style = CodeTextStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                )
            }
        }
    }
}
