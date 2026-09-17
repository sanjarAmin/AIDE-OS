package com.osamu.aide.ai.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osamu.aide.ai.core.AiProviderType
import com.osamu.aide.ai.core.ApprovalRequest
import com.osamu.aide.ai.core.ChatEntry
import com.osamu.aide.ai.core.ChatUiState
import com.osamu.aide.ai.core.GoogleAuthManager
import com.osamu.aide.core.ui.theme.CodeTextStyle

/**
 * The assistant panel.
 *
 * Four providers, each with its own models and its own key. The header is where
 * both are chosen; see [AgentHeader] for why it says as much as it does.
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
    onCancelSend: () -> Unit = {},
    onNewChat: () -> Unit = {},
    onInsertCode: ((String) -> Unit)? = null,
    activeFileName: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        AgentHeader(
            state = state,
            onSwitchProvider = onSwitchProvider,
            onSwitchModel = onSwitchModel,
            onToggleShareContext = onToggleShareContext,
            onNewChat = onNewChat,
        )
        HorizontalDivider()

        Transcript(
            state = state,
            onSend = onSend,
            onSignInGoogle = onSignInGoogle,
            onAddKey = onAddKey,
            onInsertCode = onInsertCode,
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
            activeStatus = state.activeStatus,
            activeFileName = activeFileName,
            onSend = onSend,
            onCancelSend = onCancelSend,
        )
    }
}

/**
 * Which assistant is answering, and which of its models.
 *
 * **Both controls now look like controls.** They were a bare `TextButton` and a
 * bare chip -- text with no affordance, in a header full of other text -- so the
 * only way to find out the assistant could be changed at all was to tap the
 * name and see what happened. Each carries a caret, and each menu ticks the
 * entry that is currently in use.
 *
 * **The provider menu says which providers can actually answer.** Switching to
 * one with no key used to look like it worked; the failure arrived on the next
 * message, by which point the switch was three taps back and looked unrelated.
 * `Needs a key` is stated in the menu, before the choice, and the key prompt
 * appears the moment the switch is made rather than after a lost message.
 */
@Composable
private fun AgentHeader(
    state: ChatUiState,
    onSwitchProvider: (AiProviderType) -> Unit,
    onSwitchModel: (String) -> Unit,
    onToggleShareContext: (Boolean) -> Unit,
    onNewChat: () -> Unit,
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
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Change assistant",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showProviderMenu,
                        onDismissRequest = { showProviderMenu = false },
                    ) {
                        AiProviderType.entries.forEach { provider ->
                            val configured = provider in state.providersWithKeys
                            DropdownMenuItem(
                                text = { Text(provider.displayName) },
                                trailingIcon = if (configured) {
                                    null
                                } else {
                                    {
                                        Text(
                                            text = "Needs a key",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = {
                                    // A fixed-width slot either way, so the
                                    // names stay on one left edge instead of
                                    // stepping in and out as keys are added.
                                    if (provider == state.activeProvider) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "in use",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    } else {
                                        Box(Modifier.size(18.dp))
                                    }
                                },
                                onClick = {
                                    onSwitchProvider(provider)
                                    showProviderMenu = false
                                },
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = onNewChat,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "New chat",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }

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
                        trailingIcon = {
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "Change model",
                                modifier = Modifier.size(18.dp),
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
                                leadingIcon = {
                                    if (model == state.activeModel) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "in use",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    } else {
                                        Box(Modifier.size(18.dp))
                                    }
                                },
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
    onInsertCode: ((String) -> Unit)? = null,
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.entries) { entry ->
            when (entry) {
                is ChatEntry.FromUser -> Bubble(entry.text, fromUser = true)
                is ChatEntry.FromAssistant -> Bubble(entry.text, fromUser = false, onInsertCode = onInsertCode)
                is ChatEntry.Tool -> ToolCard(entry)
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

            if (state.needsKey) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    if (state.activeProvider == AiProviderType.GEMINI &&
                        GoogleAuthManager.SIGN_IN_ENABLED
                    ) {
                        OutlinedButton(onClick = onSignInGoogle) {
                            Text("Sign in with Google")
                        }
                    }
                    Button(onClick = onAddKey) {
                        Text(
                            if (state.activeProvider == AiProviderType.CUSTOM) {
                                "Set Custom's address"
                            } else {
                                "Add ${state.activeProvider.displayName} key"
                            },
                        )
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
private fun Bubble(
    text: String,
    fromUser: Boolean,
    onInsertCode: ((String) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        if (fromUser) {
            Surface(
                color = colors.primaryContainer,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        } else {
            Surface(
                color = colors.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    MarkdownText(
                        markdown = text,
                        onInsertCode = onInsertCode,
                        textColor = colors.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCard(entry: ChatEntry.Tool) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }

    val (icon, tint) = when {
        entry.declined -> Icons.Default.Block to colors.outline
        entry.failed -> Icons.Default.ErrorOutline to colors.error
        else -> entry.name.icon() to colors.primary
    }

    Surface(
        color = colors.surfaceContainerHigh.copy(alpha = 0.65f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        onClick = { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    Text(
                        text = entry.summary(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (entry.failed) colors.error else colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (entry.input.isNotEmpty()) {
                        Text(
                            text = "ARGUMENTS",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        entry.input.forEach { (k, v) ->
                            Text(
                                text = "$k: $v",
                                style = CodeTextStyle.copy(fontSize = 11.5.sp),
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }

                    val toolResult = entry.result
                    if (!toolResult.isNullOrBlank()) {
                        Text(
                            text = "RESULT",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 140.dp)
                                .background(colors.surface, RoundedCornerShape(6.dp))
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(8.dp),
                        ) {
                            Text(
                                text = toolResult,
                                style = CodeTextStyle.copy(fontSize = 11.5.sp),
                                color = if (entry.failed) colors.error else colors.onSurface,
                            )
                        }
                    }
                }
            }
        }
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
    "git_status", "git_diff", "git_log" -> Icons.Default.AccountTree
    "run_shell" -> Icons.Default.Terminal
    "search_definitions" -> Icons.Default.Code
    "run_build", "read_build_errors" -> Icons.Default.Build
    "check_kotlin", "explain_kotlin_symbol" -> Icons.Default.Code
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
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (request.toolName == "run_shell") Icons.Default.Terminal else Icons.Default.Edit,
                    contentDescription = null,
                    tint = colors.onSecondaryContainer,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (request.toolName == "run_shell") {
                        "Run shell command?"
                    } else {
                        "Write ${request.path.ifBlank { "a file" }}?"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSecondaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = if (request.toolName == "run_shell") {
                    "This executes in the project environment. Shell commands can alter files."
                } else {
                    "This replaces or updates file contents. Review the proposed changes below:"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSecondaryContainer,
            )

            if (request.preview.isNotBlank()) {
                if (request.toolName == "run_shell") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface, RoundedCornerShape(6.dp))
                            .padding(10.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "$",
                                style = CodeTextStyle.copy(fontWeight = FontWeight.Bold),
                                color = colors.primary,
                            )
                            Text(request.preview, style = CodeTextStyle, color = colors.onSurface)
                        }
                    }
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .background(colors.surface, RoundedCornerShape(6.dp))
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(6.dp),
                    ) {
                        DiffViewer(request.preview)
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Button(onClick = { onApproval(true) }) { Text("Allow") }
                OutlinedButton(onClick = { onApproval(false) }) { Text("Decline") }
            }
        }
    }
}

@Composable
private fun DiffViewer(preview: String) {
    val lines = remember(preview) { preview.lines() }
    val additionColor = Color(0xFF2E7D32)
    val additionBg = Color(0x224CAF50)
    val deletionColor = Color(0xFFC62828)
    val deletionBg = Color(0x22F44336)

    Column {
        lines.forEachIndexed { index, line ->
            val (bgColor, textColor) = when {
                line.startsWith("+") -> additionBg to additionColor
                line.startsWith("-") -> deletionBg to deletionColor
                else -> Color.Transparent to MaterialTheme.colorScheme.onSurface
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bgColor)
                    .padding(vertical = 1.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${index + 1}".padStart(3),
                    style = CodeTextStyle.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Text(
                    text = line,
                    style = CodeTextStyle.copy(fontSize = 11.5.sp),
                    color = textColor,
                )
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
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (activeProvider == AiProviderType.CUSTOM) {
                    "Custom needs the address of an OpenAI-compatible server."
                } else {
                    "${activeProvider.displayName} needs an API key. It stays on this device."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val consoleUrl = when (activeProvider) {
                AiProviderType.GEMINI -> "https://aistudio.google.com/app/apikey"
                AiProviderType.ANTHROPIC -> "https://console.anthropic.com/settings/keys"
                AiProviderType.OPENAI -> "https://platform.openai.com/api-keys"
                AiProviderType.CUSTOM -> null
            }
            if (consoleUrl != null) {
                TextButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(consoleUrl))
                        context.startActivity(intent)
                    },
                ) {
                    Text(if (activeProvider == AiProviderType.GEMINI) "Get free key" else "Get key")
                }
            }
            if (activeProvider == AiProviderType.GEMINI && GoogleAuthManager.SIGN_IN_ENABLED) {
                TextButton(onClick = onSignInGoogle) { Text("Sign in") }
            }
            TextButton(onClick = onAddKey) {
                Text(if (activeProvider == AiProviderType.CUSTOM) "Set address" else "Add key")
            }
        }
    }
}

@Composable
private fun Composer(
    enabled: Boolean,
    sending: Boolean,
    activeStatus: String?,
    activeFileName: String?,
    onSend: (String) -> Unit,
    onCancelSend: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var attachActiveFile by remember { mutableStateOf(activeFileName != null) }

    fun submit() {
        if (!enabled || draft.isBlank()) return
        val finalPrompt = if (attachActiveFile && activeFileName != null) {
            "[@${activeFileName}]\n$draft"
        } else {
            draft
        }
        onSend(finalPrompt)
        draft = ""
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // In-flight status indicator
        if (sending) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = activeStatus ?: "Thinking...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Active File context chip
        if (activeFileName != null && attachActiveFile) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AssistChip(
                    onClick = { attachActiveFile = false },
                    label = {
                        Text(
                            text = "Context: $activeFileName",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove context",
                            modifier = Modifier.size(14.dp),
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    ),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about this project...") },
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )

            if (sending) {
                IconButton(
                    onClick = onCancelSend,
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            RoundedCornerShape(12.dp),
                        ),
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop generation",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                val canSend = enabled && draft.isNotBlank()
                IconButton(
                    onClick = ::submit,
                    enabled = canSend,
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .background(
                            if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(12.dp),
                        ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
