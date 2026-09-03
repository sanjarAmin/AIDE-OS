package com.osamu.aide.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.osamu.aide.ai.core.AiProviderType
import com.osamu.aide.ai.core.ApprovalRequest
import com.osamu.aide.ai.core.ChatEntry
import com.osamu.aide.ai.core.ChatUiState
import com.osamu.aide.core.ui.theme.CodeTextStyle

/**
 * The assistant panel, styled like Gemini in Android Studio.
 *
 * Supports Google Gemini by default with Google Sign-In and API Key options,
 * plus multi-model switching (OpenAI, Anthropic, Custom), quick action chips,
 * and Android Studio-style diff previews.
 */
@Composable
fun ChatPanel(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onApproval: (Boolean) -> Unit,
    onDismissError: () -> Unit,
    onAddKey: () -> Unit,
    onSignInGoogle: () -> Unit = onAddKey,
    onSwitchProvider: (AiProviderType) -> Unit = {},
    onSwitchModel: (String) -> Unit = {},
    onToggleShareContext: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        AgentHeader(
            state = state,
            onSwitchProvider = onSwitchProvider,
            onSwitchModel = onSwitchModel,
            onToggleShareContext = onToggleShareContext,
        )
        HorizontalDivider()

        Transcript(
            state = state,
            onSend = onSend,
            onSignInGoogle = onSignInGoogle,
            onAddKey = onAddKey,
            modifier = Modifier.weight(1f),
        )

        state.error?.let { ErrorBar(it, onDismissError) }
        if (state.needsKey) {
            KeyPrompt(
                activeProvider = state.activeProvider,
                onAddKey = onAddKey,
                onSignInGoogle = onSignInGoogle,
            )
        }

        state.pendingApproval?.let { request ->
            HorizontalDivider()
            ApprovalPrompt(request, onApproval)
        }

        if (state.entries.isNotEmpty()) {
            QuickActionsBar(onSend = onSend)
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
private fun AgentHeader(
    state: ChatUiState,
    onSwitchProvider: (AiProviderType) -> Unit,
    onSwitchModel: (String) -> Unit,
    onToggleShareContext: (Boolean) -> Unit,
) {
    var showProviderMenu by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Box {
                    TextButton(onClick = { showProviderMenu = true }) {
                        Text(
                            text = state.activeProvider.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    DropdownMenu(
                        expanded = showProviderMenu,
                        onDismissRequest = { showProviderMenu = false },
                    ) {
                        AiProviderType.entries.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.displayName) },
                                onClick = {
                                    onSwitchProvider(provider)
                                    showProviderMenu = false
                                },
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    AssistChip(
                        onClick = { showModelMenu = true },
                        label = {
                            Text(
                                text = state.activeModel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ),
                    )
                    DropdownMenu(
                        expanded = showModelMenu,
                        onDismissRequest = { showModelMenu = false },
                    ) {
                        state.activeProvider.availableModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    onSwitchModel(model)
                                    showModelMenu = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Transcript(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onSignInGoogle: () -> Unit,
    onAddKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.entries.isEmpty()) {
        EmptyTranscript(
            state = state,
            onSend = onSend,
            onSignInGoogle = onSignInGoogle,
            onAddKey = onAddKey,
            modifier = modifier,
        )
        return
    }

    val listState = rememberLazyListState()

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
private fun EmptyTranscript(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onSignInGoogle: () -> Unit,
    onAddKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )

            Text(
                text = "Ask about your code",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = "The assistant can read and search the files in this project, " +
                    "and edit them once you confirm the change.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (state.needsKey && state.activeProvider == AiProviderType.GEMINI) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Button(onClick = onSignInGoogle) {
                        Text("Sign in with Google")
                    }
                    OutlinedButton(onClick = onAddKey) {
                        Text("Add API Key")
                    }
                }
            }

            Column(
                modifier = Modifier.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Suggested Prompts",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AssistChip(
                        onClick = { onSend("Explain the architecture and files in this project.") },
                        label = { Text("Explain architecture") },
                    )
                    AssistChip(
                        onClick = { onSend("Find any build errors or missing dependencies in this project.") },
                        label = { Text("Check build errors") },
                    )
                    AssistChip(
                        onClick = { onSend("Help me create a new Compose screen for this app.") },
                        label = { Text("Create Compose UI") },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionsBar(onSend: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AssistChip(
            onClick = { onSend("Explain what this code does.") },
            label = { Text("Explain code") },
        )
        AssistChip(
            onClick = { onSend("Check this project for potential bugs or improvements.") },
            label = { Text("Find bugs") },
        )
        AssistChip(
            onClick = { onSend("Generate unit tests for this project.") },
            label = { Text("Generate test") },
        )
        AssistChip(
            onClick = { onSend("Run the build and fix any compiler errors found.") },
            label = { Text("Fix error") },
        )
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
 * Android Studio-style diff and approval prompt.
 */
@Composable
private fun ApprovalPrompt(request: ApprovalRequest, onApproval: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme

    Surface(
        color = colors.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    tint = colors.onSecondaryContainer,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Write ${request.path.ifBlank { "a file" }}?",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSecondaryContainer,
                )
            }
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

@Composable
private fun KeyPrompt(
    activeProvider: AiProviderType = AiProviderType.GEMINI,
    onAddKey: () -> Unit,
    onSignInGoogle: () -> Unit = onAddKey,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (activeProvider == AiProviderType.GEMINI) {
                    "Gemini needs sign-in or an API key. Data stays on this device."
                } else {
                    "The assistant needs your ${activeProvider.displayName} API key. It stays on this device."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (activeProvider == AiProviderType.GEMINI) {
                TextButton(onClick = onSignInGoogle) { Text("Sign in") }
            }
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
            val canSend = enabled && draft.isNotBlank()
            IconButton(onClick = ::submit, enabled = canSend) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (canSend) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                )
            }
        }
    }
}
