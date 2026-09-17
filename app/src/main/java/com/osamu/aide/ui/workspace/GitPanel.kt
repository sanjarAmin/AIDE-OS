package com.osamu.aide.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osamu.aide.core.ui.theme.CodeTextStyle
import com.osamu.aide.ui.util.FileIcons
import java.io.File

/**
 * What the git panel can do, gathered so it can be passed through the layout.
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
    val stageAll: () -> Unit = {},
    val unstageAll: () -> Unit = {},
    val discard: (String) -> Unit = {},
    val checkoutBranch: (name: String, createNew: Boolean) -> Unit = { _, _ -> },
)

/**
 * Stage, commit and push, in the dock beside the build output.
 */
@Composable
fun GitPanel(
    state: GitUiState,
    actions: GitActions,
    modifier: Modifier = Modifier,
) {
    var confirmingDiscard by remember { mutableStateOf<String?>(null) }
    var showBranchDialog by remember { mutableStateOf(false) }

    when (state.isRepository) {
        null -> Text("Looking for a repository…", style = MaterialTheme.typography.bodySmall)
        false -> NotARepository(modifier, state.isBusy, actions.initialise)
        true -> Column(modifier.fillMaxSize()) {
            Header(
                state = state,
                onPush = actions.push,
                onOpenBranches = { showBranchDialog = true },
            )

            val staged = state.status.staged.sorted()
            val unstaged = (state.status.unstaged + state.status.untracked).sorted()

            // Quick Stage/Unstage all row when files are present
            if (staged.isNotEmpty() || unstaged.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (unstaged.isNotEmpty()) {
                            TextButton(
                                onClick = actions.stageAll,
                                enabled = !state.isBusy,
                                modifier = Modifier.semantics { contentDescription = "Stage All" },
                            ) {
                                Text("Stage All (${unstaged.size})", fontSize = 12.sp)
                            }
                        }
                        if (staged.isNotEmpty()) {
                            TextButton(
                                onClick = actions.unstageAll,
                                enabled = !state.isBusy,
                                modifier = Modifier.semantics { contentDescription = "Unstage All" },
                            ) {
                                Text("Unstage All (${staged.size})", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

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
                                onDiscard = { confirmingDiscard = path },
                            )
                        }
                    }
                }
            }

            CommitRow(state, staged.isNotEmpty(), actions)
        }
    }

    state.diff?.let { diff -> DiffDialog(diff, actions.dismissDiff) }

    confirmingDiscard?.let { path ->
        AlertDialog(
            onDismissRequest = { confirmingDiscard = null },
            title = { Text("Discard changes?") },
            text = { Text("Are you sure you want to discard changes in '$path'? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        actions.discard(path)
                        confirmingDiscard = null
                    },
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDiscard = null }) { Text("Cancel") }
            },
        )
    }

    if (showBranchDialog) {
        BranchDialog(
            currentBranch = state.branch,
            branches = state.branches,
            onSelect = { branch ->
                actions.checkoutBranch(branch, false)
                showBranchDialog = false
            },
            onCreate = { branch ->
                actions.checkoutBranch(branch, true)
                showBranchDialog = false
            },
            onDismiss = { showBranchDialog = false },
        )
    }
}

/**
 * One file's diff with syntax highlighting and line numbers.
 */
@Composable
private fun DiffDialog(diff: GitDiff, onDismiss: () -> Unit) {
    val file = remember(diff.path) { File(diff.path) }
    val iconInfo = FileIcons.infoFor(file, isDirectory = false)
    val lines = remember(diff.text) { diff.text.lines() }

    val additionBg = Color(0x2234D399)
    val additionText = Color(0xFF34D399)
    val deletionBg = Color(0x22FB7185)
    val deletionText = Color(0xFFFB7185)
    val hunkBg = Color(0x22A78BFA)
    val hunkText = Color(0xFFA78BFA)
    val defaultText = MaterialTheme.colorScheme.onSurface
    val variantText = MaterialTheme.colorScheme.onSurfaceVariant

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = iconInfo.icon,
                    contentDescription = null,
                    tint = iconInfo.tint,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = diff.path.substringAfterLast('/'),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (diff.staged) "Staged, against the last commit" else "Not staged yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            if (diff.text.isBlank()) {
                Text(
                    "No text to show. The file may be binary, or only its mode changed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .semantics { contentDescription = "Diff" },
                    ) {
                        lines.forEachIndexed { index, line ->
                            val (bgColor, textColor) = when {
                                line.startsWith("+++") || line.startsWith("---") ->
                                    Color.Transparent to variantText
                                line.startsWith("+") -> additionBg to additionText
                                line.startsWith("-") -> deletionBg to deletionText
                                line.startsWith("@@") -> hunkBg to hunkText
                                else -> Color.Transparent to defaultText
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bgColor)
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${index + 1}".padStart(3),
                                    style = CodeTextStyle.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Text(
                                    text = line,
                                    style = CodeTextStyle.copy(fontSize = 12.sp),
                                    color = textColor,
                                    softWrap = false,
                                )
                            }
                        }
                    }
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
private fun Header(
    state: GitUiState,
    onPush: () -> Unit,
    onOpenBranches: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Surface(
                onClick = onOpenBranches,
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.semantics { contentDescription = "Current branch" },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = state.branch ?: "detached HEAD",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
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
    onDiscard: (() -> Unit)? = null,
) {
    val file = remember(path) { File(path) }
    val iconInfo = FileIcons.infoFor(file, isDirectory = false)

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
        Icon(
            imageVector = iconInfo.icon,
            contentDescription = null,
            tint = iconInfo.tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = path,
            style = CodeTextStyle,
            color = if (isStaged) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = enabled, onClick = onOpen)
                .semantics { contentDescription = "Show changes in $path" },
        )
        if (onDiscard != null) {
            IconButton(
                onClick = onDiscard,
                enabled = enabled,
                modifier = Modifier.size(36.dp).semantics { contentDescription = "Discard changes in $path" },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun CommitRow(state: GitUiState, hasStagedFiles: Boolean, actions: GitActions) {
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
private fun BranchDialog(
    currentBranch: String?,
    branches: List<String>,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newBranchName by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCreating) "Create Branch" else "Branches") },
        text = {
            if (isCreating) {
                OutlinedTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it },
                    label = { Text("Branch name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    branches.forEach { branch ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(branch) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = branch,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (branch == currentBranch) {
                                    primaryColor
                                } else {
                                    onSurfaceColor
                                },
                            )
                            if (branch == currentBranch) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                ) {
                                    Text(
                                        text = "CURRENT",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isCreating) {
                Button(
                    onClick = { if (newBranchName.isNotBlank()) onCreate(newBranchName.trim()) },
                    enabled = newBranchName.isNotBlank(),
                ) { Text("Create & Switch") }
            } else {
                TextButton(onClick = { isCreating = true }) { Text("New Branch") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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
