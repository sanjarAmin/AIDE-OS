package com.osamu.aide.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.osamu.aide.core.ui.theme.CodeTextStyle

/**
 * What the git panel can do, gathered so it can be passed through the layout.
 *
 * [EditorArea] and [BottomToolDock] only forward these; threading six lambdas
 * through two signatures that do not use any of them makes both harder to read
 * than the indirection costs.
 */
data class GitActions(
    val stage: (String) -> Unit,
    val unstage: (String) -> Unit,
    val setMessage: (String) -> Unit,
    val commit: () -> Unit,
    val push: () -> Unit,
    val openSettings: () -> Unit,
    val initialise: () -> Unit,
    val showDiff: (path: String, staged: Boolean) -> Unit,
    val dismissDiff: () -> Unit,
)

/**
 * Stage, commit and push, in the dock beside the build output.
 *
 * A list of changed paths rather than a diff, because there is no diff yet --
 * `vcs/git/FINDINGS.md` lists it as the next thing the panel needs. Saying so
 * with file names is honest; a mocked-up diff would not be.
 *
 * Staged and unstaged are shown as one list with a `+`/`-` per row rather than
 * as two sections. On a phone the dock is 200dp tall, and two scrolling
 * sections in that space means neither is usable.
 */
@Composable
fun GitPanel(
    state: GitUiState,
    actions: GitActions,
    modifier: Modifier = Modifier,
) {
    when (state.isRepository) {
        null -> Text("Looking for a repository…", style = MaterialTheme.typography.bodySmall)
        false -> NotARepository(modifier, state.isBusy, actions.initialise)
        true -> Column(modifier.fillMaxSize()) {
            Header(state, actions.push)

            val staged = state.status.staged.sorted()
            val unstaged = (state.status.unstaged + state.status.untracked).sorted()

            // **`weight(1f)`, filling.** With `fill = false` the list took only
            // the height it wanted, and the dock is a fixed 200dp that the
            // header and commit row nearly fill on their own -- so the list was
            // laid out at zero height and a repository full of changes showed
            // nothing at all. Found by opening the panel in the running app;
            // every test asserts through the view model, where the layout does
            // not exist.
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (staged.isEmpty() && unstaged.isEmpty()) {
                    Text(
                        text = "Nothing has changed since the last commit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(staged, key = { "staged/$it" }) { path ->
                            ChangedFile(
                                path = path,
                                isStaged = true,
                                enabled = !state.isBusy,
                                onToggle = { actions.unstage(path) },
                                onOpen = { actions.showDiff(path, true) },
                            )
                        }
                        items(unstaged, key = { "unstaged/$it" }) { path ->
                            ChangedFile(
                                path = path,
                                isStaged = false,
                                enabled = !state.isBusy,
                                onToggle = { actions.stage(path) },
                                onOpen = { actions.showDiff(path, false) },
                            )
                        }
                    }
                }
            }

            CommitRow(state, staged.isNotEmpty(), actions)
        }
    }

    state.diff?.let { diff -> DiffDialog(diff, actions.dismissDiff) }
}

/**
 * One file's diff.
 *
 * A dialog rather than a pane in the dock: the dock is 340dp at its tallest and
 * a diff is the one thing here that genuinely wants the screen. Monospace and
 * horizontally scrollable, because a wrapped diff line is unreadable -- the
 * leading `+`/`-` stops lining up and that column is the whole point.
 */
@Composable
private fun DiffDialog(diff: GitDiff, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(diff.path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
                Text(
                    // Says which of the two diffs this is. They differ, and a
                    // label that did not distinguish them would be wrong half
                    // the time.
                    text = if (diff.staged) "Staged, against the last commit" else "Not staged yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            if (diff.text.isBlank()) {
                Text(
                    "No text to show. The file may be binary, or only its mode changed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = diff.text,
                        style = CodeTextStyle,
                        softWrap = false,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .semantics { contentDescription = "Diff" },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Close diff" },
            ) { Text("Close") }
        },
    )
}

@Composable
private fun Header(state: GitUiState, onPush: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = state.branch ?: "detached HEAD",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { contentDescription = "Current branch" },
            )
            val subtitle = state.progress
                ?: state.errorMessage
                ?: state.notice
                ?: state.recent.firstOrNull()?.let { "${it.abbreviated}  ${it.summary}" }
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.errorMessage != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.semantics { contentDescription = "Git status line" },
                )
            }
        }
        TextButton(
            onClick = onPush,
            enabled = !state.isBusy,
            modifier = Modifier.semantics { contentDescription = "Push" },
        ) { Text("Push") }
    }
    if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun ChangedFile(
    path: String,
    isStaged: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onToggle,
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = if (isStaged) "Unstage $path" else "Stage $path"
            },
        ) {
            Icon(
                imageVector = if (isStaged) Icons.Default.Remove else Icons.Default.Add,
                contentDescription = null,
                tint = if (isStaged) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        // The path opens the diff; the icon stages. Two targets in one row,
        // because "look at it" and "commit it" are different decisions and
        // making one of them require a long press hides it.
        Text(
            text = path,
            style = CodeTextStyle,
            color = if (isStaged) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onOpen)
                .semantics { contentDescription = "Show changes in $path" },
        )
    }
}

@Composable
private fun CommitRow(state: GitUiState, hasStagedFiles: Boolean, actions: GitActions) {
    // Named rather than left to the disabled button, because "why is this grey"
    // is the whole question -- and the answer is in a different screen.
    if (!state.hasIdentity) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Set a name and email before committing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = actions.openSettings,
                modifier = Modifier.semantics { contentDescription = "Open git settings" },
            ) { Text("Settings") }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.message,
            onValueChange = actions.setMessage,
            label = { Text("Commit message") },
            singleLine = true,
            modifier = Modifier.weight(1f)
                .semantics { contentDescription = "Commit message" },
        )
        Button(
            onClick = actions.commit,
            enabled = !state.isBusy && state.hasIdentity &&
                hasStagedFiles && state.message.isNotBlank(),
            modifier = Modifier.semantics { contentDescription = "Commit" },
        ) { Text("Commit") }
    }
}

@Composable
private fun NotARepository(modifier: Modifier, isBusy: Boolean, onInitialise: () -> Unit) {
    Column(modifier.fillMaxWidth().padding(8.dp)) {
        Text(
            text = "Not a git repository",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "This project was created or imported rather than cloned. Start " +
                "tracking it and every change from here on can be committed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Button(
            onClick = onInitialise,
            enabled = !isBusy,
            modifier = Modifier.padding(top = 10.dp)
                .semantics { contentDescription = "Create a repository" },
        ) { Text("Create a repository") }
    }
}
