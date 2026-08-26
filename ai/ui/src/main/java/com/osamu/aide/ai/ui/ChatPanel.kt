package com.osamu.aide.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.osamu.aide.ai.core.ApprovalRequest
import com.osamu.aide.ai.core.ChatEntry
import com.osamu.aide.ai.core.ChatUiState
import com.osamu.aide.core.ui.theme.CodeTextStyle

/**
 * The assistant panel.
 *
 * Stateless apart from the draft in the input field: everything else comes from
 * [ChatUiState], which `ChatController` owns. That split is what keeps the
 * approval prompt honest — the panel cannot decide to skip it, because the
 * prompt is a field in the state and the loop is parked until [onApproval] is
 * called.
 */
@Composable
fun ChatPanel(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onApproval: (Boolean) -> Unit,
    onDismissError: () -> Unit,
    onAddKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Transcript(state, Modifier.weight(1f))

        state.error?.let { ErrorBar(it, onDismissError) }
        if (state.needsKey) KeyPrompt(onAddKey)

        state.pendingApproval?.let { request ->
            HorizontalDivider()
            ApprovalPrompt(request, onApproval)
        }

        HorizontalDivider()
        Composer(
            enabled = !state.sending && state.pendingApproval == null,
            sending = state.sending,
            onSend = onSend,
        )
    }
}

@Composable
private fun Transcript(state: ChatUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // Follow the tail as the conversation grows. Keyed on the entry count
    // rather than on the state object, so re-rendering for an unrelated change
    // -- an approval prompt appearing, say -- does not yank the user back down
    // while they are reading something further up.
    LaunchedEffect(state.entries.size) {
        if (state.entries.isNotEmpty()) listState.animateScrollToItem(state.entries.lastIndex)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.entries) { entry ->
            when (entry) {
                is ChatEntry.FromUser -> Bubble(entry.text, fromUser = true)
                is ChatEntry.FromAssistant -> Bubble(entry.text, fromUser = false)
                is ChatEntry.Tool -> ToolChip(entry)
            }
        }
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (fromUser) colors.primaryContainer else colors.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (fromUser) colors.onPrimaryContainer else colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * One tool call, shown rather than hidden.
 *
 * The assistant reads and writes the user's files; a transcript that shows only
 * prose is one the user has to take on trust. Declined and failed calls are
 * distinguished because they mean opposite things — "you said no" versus "I
 * never saw that file" — and a failed read the user reads as a successful one
 * is how they come to believe an answer that was based on nothing.
 */
@Composable
private fun ToolChip(entry: ChatEntry.Tool) {
    val colors = MaterialTheme.colorScheme
    val (icon, tint) = when {
        entry.declined -> Icons.Default.Block to colors.outline
        entry.failed -> Icons.Default.ErrorOutline to colors.error
        else -> entry.name.icon() to colors.primary
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(
            text = entry.summary(),
            style = MaterialTheme.typography.labelMedium,
            color = if (entry.failed) colors.error else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun ChatEntry.Tool.summary(): String {
    val verb = when {
        declined -> "declined"
        failed -> "could not run"
        else -> "ran"
    }
    return if (detail.isBlank()) "$verb $name" else "$verb $name — $detail"
}

private fun String.icon(): ImageVector = when (this) {
    "list_files" -> Icons.Default.FolderOpen
    "read_file" -> Icons.Default.Description
    "grep" -> Icons.Default.Search
    else -> Icons.Default.Edit
}

/**
 * The confirmation gate, on screen.
 *
 * Inline rather than a dialog: the user needs the transcript above it to decide
 * — a dialog that covers "I'll rewrite Main.kt to do X" and then asks whether
 * to rewrite Main.kt is asking them to remember rather than to read.
 */
@Composable
private fun ApprovalPrompt(request: ApprovalRequest, onApproval: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme

    Surface(color = colors.secondaryContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Write ${request.path.ifBlank { "a file" }}?",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSecondaryContainer,
            )
            Text(
                text = "This replaces the file's contents. Nothing is written until you allow it.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSecondaryContainer,
            )

            if (request.preview.isNotBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .background(colors.surface, RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Text(request.preview, style = CodeTextStyle, color = colors.onSurface)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onApproval(true) }) { Text("Allow") }
                TextButton(onClick = { onApproval(false) }) { Text("Don't") }
            }
        }
    }
}

@Composable
private fun ErrorBar(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/** Where every new user starts, so it is a prompt and not an error. */
@Composable
private fun KeyPrompt(onAddKey: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "The assistant needs your Anthropic API key. It stays on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAddKey) { Text("Add key") }
        }
    }
}

@Composable
private fun Composer(enabled: Boolean, sending: Boolean, onSend: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }

    fun submit() {
        if (!enabled || draft.isBlank()) return
        onSend(draft)
        draft = ""
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask about this project") },
            // Not disabled while sending: the user can keep typing the next
            // question, and a field that greys out mid-thought loses whatever
            // they had half-written.
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )

        if (sending) {
            CircularProgressIndicator(
                Modifier
                    .size(24.dp)
                    .padding(bottom = 12.dp),
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(onClick = ::submit, enabled = enabled && draft.isNotBlank()) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (enabled && draft.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Unspecified
                    },
                )
            }
        }
    }
}
