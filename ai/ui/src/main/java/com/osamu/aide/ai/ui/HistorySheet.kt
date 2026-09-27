package com.osamu.aide.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.osamu.aide.ai.core.ConversationSummary
import java.util.concurrent.TimeUnit

/**
 * Past conversations for this project.
 *
 * **A sheet rather than a drawer.** The panel already lives inside a workspace
 * that owns the left edge for the file tree, and a second drawer competing for
 * the same gesture is the confusion the workspace just removed. A sheet also
 * puts the list under the thumb, where a one-handed tap lands.
 *
 * **Search appears only when there is enough to search.** A filter field above
 * three conversations is furniture; above thirty it is the only way to find
 * anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistorySheet(
    conversations: List<ConversationSummary>,
    activeId: String?,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onNewChat: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<ConversationSummary?>(null) }
    var confirmingDelete by remember { mutableStateOf<ConversationSummary?>(null) }

    val shown = remember(conversations, query) {
        if (query.isBlank()) {
            conversations
        } else {
            conversations.filter { it.title.contains(query, ignoreCase = true) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Conversations",
                    style = MaterialTheme.typography.titleMedium,
                    // The label half of the row: the button keeps its size.
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onNewChat, modifier = Modifier.testTag(HISTORY_NEW_TAG)) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Text("New", modifier = Modifier.padding(start = 4.dp))
                }
            }

            if (conversations.size >= SEARCH_THRESHOLD) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(HISTORY_SEARCH_TAG),
                )
            }

            if (shown.isEmpty()) {
                Text(
                    text = if (conversations.isEmpty()) {
                        "Nothing here yet. Ask something and it will be saved."
                    } else {
                        "No conversation matches \"$query\"."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(shown, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            active = conversation.id == activeId,
                            onOpen = { onOpen(conversation.id) },
                            onRename = { renaming = conversation },
                            onDelete = { confirmingDelete = conversation },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { target ->
        RenameDialog(
            initial = target.title,
            onConfirm = { title ->
                onRename(target.id, title)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    confirmingDelete?.let { target ->
        // Confirmed, because there is no undo. The conversation is the record
        // of what the assistant changed in the project, which is exactly the
        // thing someone goes looking for after a bad edit.
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("Delete this conversation?") },
            text = { Text("\"${target.title}\" will be gone for good.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target.id)
                        confirmingDelete = null
                    },
                    modifier = Modifier.testTag(HISTORY_CONFIRM_DELETE_TAG),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    active: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = if (active) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = MaterialTheme.shapes.medium,
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().testTag(HISTORY_ROW_TAG),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (active) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "open now",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp).padding(end = 4.dp),
                        )
                    }
                    Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "${conversation.messageCount} messages · ${ago(conversation.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRename, modifier = Modifier.size(34.dp).testTag(HISTORY_RENAME_TAG)) {
                Icon(
                    Icons.Default.DriveFileRenameOutline,
                    contentDescription = "Rename",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp).testTag(HISTORY_DELETE_TAG)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename conversation") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(HISTORY_RENAME_FIELD_TAG),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag(HISTORY_RENAME_SAVE_TAG),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A relative time, coarse on purpose.
 *
 * Nobody needs to know a conversation was last touched 43 minutes ago; they
 * need to know whether it was today. Coarse buckets also mean the string does
 * not change while the sheet is open.
 */
private fun ago(millis: Long): String {
    if (millis <= 0) return "just now"
    val elapsed = System.currentTimeMillis() - millis
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> "${days / 7}w ago"
    }
}

/** Below this, a search field is furniture. */
private const val SEARCH_THRESHOLD = 8

const val HISTORY_ROW_TAG = "history-row"
const val HISTORY_NEW_TAG = "history-new"
const val HISTORY_SEARCH_TAG = "history-search"
const val HISTORY_RENAME_TAG = "history-rename"
const val HISTORY_RENAME_FIELD_TAG = "history-rename-field"
const val HISTORY_RENAME_SAVE_TAG = "history-rename-save"
const val HISTORY_DELETE_TAG = "history-delete"
const val HISTORY_CONFIRM_DELETE_TAG = "history-confirm-delete"
