package com.osamu.aide.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.osamu.aide.ai.core.AiProviderType
import com.osamu.aide.ai.core.ApprovalScope
import com.osamu.aide.ai.core.ChatEntry
import com.osamu.aide.ai.core.ChatUiState
import kotlinx.coroutines.delay

/**
 * The assistant panel.
 *
 * **Laid out for the two things that actually happen here**: reading an answer
 * that is still being written, and checking what it did to the project. So the
 * transcript gets every pixel that is not a control, the controls collapse
 * rather than compete, and nothing decorative takes horizontal space -- this
 * runs at 360 dp on an ordinary phone, which is the width CLAUDE.md says to
 * drive before believing a row is fine.
 *
 * **Waiting is designed for, not hidden.** A local 1.5B takes tens of seconds
 * for a first answer (`tools/localai/FINDINGS.md` §9), so the panel always says
 * what it is doing and, once it has been a while, how long it has been doing it.
 */
/**
 * Everything the panel can ask of the app, in one holder.
 *
 * **The same idiom the workspace already uses** for git, the terminal, the
 * debugger and logcat. The panel grew from five callbacks to fifteen with
 * history and message actions, and threading fifteen lambdas through the
 * dock's helper composable -- which already takes thirty parameters -- is how a
 * screen becomes unmaintainable.
 *
 * Hold it in a `remember` at the call site: a fresh instance per frame makes
 * every parameter of [ChatPanel] change and recomposes the whole transcript.
 */
class ChatActions(
    val send: (String) -> Unit,
    val approve: (Boolean, ApprovalScope) -> Unit,
    val dismissError: () -> Unit,
    val addKey: () -> Unit,
    val signInGoogle: () -> Unit = addKey,
    val switchProvider: (AiProviderType) -> Unit = {},
    val switchModel: (String) -> Unit = {},
    val toggleShareContext: (Boolean) -> Unit = {},
    val cancelSend: () -> Unit = {},
    val newChat: () -> Unit = {},
    val regenerate: () -> Unit = {},
    val editAndResend: (Long, String) -> Unit = { _, _ -> },
    val openConversation: (String) -> Unit = {},
    val deleteConversation: (String) -> Unit = {},
    val renameConversation: (String, String) -> Unit = { _, _ -> },
    val insertCode: ((String) -> Unit)? = null,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatPanel(
    state: ChatUiState,
    actions: ChatActions,
    activeFileName: String? = null,
    modifier: Modifier = Modifier,
) {
    val onSend = actions.send
    val onApproval = actions.approve
    val onDismissError = actions.dismissError
    val onAddKey = actions.addKey
    val onSignInGoogle = actions.signInGoogle
    val onSwitchProvider = actions.switchProvider
    val onSwitchModel = actions.switchModel
    val onToggleShareContext = actions.toggleShareContext
    val onCancelSend = actions.cancelSend
    val onNewChat = actions.newChat
    val onRegenerate = actions.regenerate
    val onEditAndResend = actions.editAndResend
    val onOpenConversation = actions.openConversation
    val onDeleteConversation = actions.deleteConversation
    val onRenameConversation = actions.renameConversation
    val onInsertCode = actions.insertCode

    var showHistory by remember { mutableStateOf(false) }
    // Survives the panel being closed and reopened, which on a phone happens
    // every time the editor needs the screen. Losing a half-typed question to
    // a glance at the code is the kind of thing that stops people using a
    // panel at all.
    var draft by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<ChatEntry.FromUser?>(null) }

    // **An approval takes the keyboard away.** The prompt is blocking and needs
    // no typing, and with the IME up the panel's lower half -- which is exactly
    // where Allow, Allow in this chat and Don't are -- is simply covered. Found
    // by driving: a prompt appeared while the keyboard was open and the gate
    // that authorises writing to someone's files could not be answered at all.
    //
    // Hiding the IME rather than padding around it, because padding still has
    // to fit a diff, three buttons and a composer into what is left, and the
    // honest answer is that the question on screen is not one you type at.
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(state.pendingApproval != null) {
        if (state.pendingApproval != null) {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    // **Lifted above the keyboard.** Found by driving: with the IME open the
    // panel's bottom is simply covered, and the bottom is where the approval
    // prompt's buttons are -- so the gate that authorises writing to someone's
    // files could not be answered while they were typing. The composer alone
    // stayed visible because it is shorter than the prompt, which is why this
    // went unnoticed until a prompt appeared with the keyboard up.
    Column(modifier.fillMaxWidth().imePadding()) {
        AgentHeader(
            state = state,
            onSwitchProvider = onSwitchProvider,
            onSwitchModel = onSwitchModel,
            onToggleShareContext = onToggleShareContext,
            onNewChat = onNewChat,
            onOpenHistory = { showHistory = true },
        )
        HorizontalDivider()

        Box(Modifier.weight(1f)) {
            if (state.entries.isEmpty()) {
                EmptyTranscript(
                    state = state,
                    onSend = onSend,
                    onSignInGoogle = onSignInGoogle,
                    onAddKey = onAddKey,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Transcript(
                    state = state,
                    onInsertCode = onInsertCode,
                    onRegenerate = onRegenerate,
                    onEdit = { entry ->
                        editing = entry
                        draft = withoutMarker(entry.text)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        state.error?.let { message ->
            ErrorBar(
                message = message,
                onRetry = onRegenerate.takeIf { state.canRegenerate && !state.sending },
                onDismiss = onDismissError,
            )
        }

        // **Only when the empty state is not already saying it.** Found by
        // driving: an unconfigured provider put "Set up on-device" in the
        // middle of the panel and the same sentence again in a bar above the
        // composer. Mid-conversation the bar is the only place it can go, which
        // is the case this keeps it for.
        if (state.needsKey && state.entries.isNotEmpty()) {
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

        HorizontalDivider()
        Composer(
            enabled = !state.sending && state.pendingApproval == null,
            sending = state.sending,
            activeStatus = state.activeStatus,
            activeFileName = activeFileName,
            draft = draft,
            onDraftChange = { draft = it },
            editing = editing,
            onCancelEdit = {
                editing = null
                draft = ""
            },
            onSubmit = { text ->
                val target = editing
                if (target != null) {
                    onEditAndResend(target.id, text)
                    editing = null
                } else {
                    onSend(text)
                }
                draft = ""
            },
            onCancelSend = onCancelSend,
        )
    }

    if (showHistory) {
        HistorySheet(
            conversations = state.conversations,
            activeId = state.activeConversationId,
            onOpen = {
                onOpenConversation(it)
                showHistory = false
            },
            onDelete = onDeleteConversation,
            onRename = onRenameConversation,
            onNewChat = {
                onNewChat()
                showHistory = false
            },
            onDismiss = { showHistory = false },
        )
    }
}

/**
 * Who is answering, and the two things done most often to a conversation.
 *
 * **One identity control, not two.** The provider and the model were separate
 * controls competing for the same row, and at 360 dp the model's name -- which
 * can be `qwen2.5-coder-1.5b` -- squeezed everything else. They are one button
 * now: the provider reads as the name, the model as its subtitle, and the menu
 * that opens offers both.
 *
 * Everything past New chat and History lives in the overflow, because a row of
 * five icons beside a long model name is the squeeze CLAUDE.md documents.
 */
@Composable
private fun AgentHeader(
    state: ChatUiState,
    onSwitchProvider: (AiProviderType) -> Unit,
    onSwitchModel: (String) -> Unit,
    onToggleShareContext: (Boolean) -> Unit,
    onNewChat: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    var showIdentity by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    onClick = { showIdentity = true },
                    modifier = Modifier.testTag(IDENTITY_TAG),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                // **The provider, not the conversation's
                                // title.** Showing the title here truncated it
                                // to "What does this project do, an..." while
                                // the same words sat in full immediately below,
                                // and it cost the one line that says which
                                // assistant is answering -- the thing this
                                // control exists to change. Titles belong to
                                // the history sheet, where they distinguish
                                // conversations that are not on screen.
                                text = state.activeProvider.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = state.activeModel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        }
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Change assistant or model",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                DropdownMenu(expanded = showIdentity, onDismissRequest = { showIdentity = false }) {
                    Text(
                        text = "Assistant",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    AiProviderType.entries.forEach { provider ->
                        val configured = provider in state.providersWithKeys
                        DropdownMenuItem(
                            text = { Text(provider.displayName) },
                            // Stated before the choice, not discovered on the
                            // next message when the switch is three taps back.
                            trailingIcon = if (configured) {
                                null
                            } else {
                                {
                                    Text(
                                        text = "Needs setup",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            leadingIcon = { TickSlot(provider == state.activeProvider) },
                            onClick = {
                                onSwitchProvider(provider)
                                showIdentity = false
                            },
                        )
                    }
                    HorizontalDivider()
                    Text(
                        text = "Model",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    state.activeProvider.availableModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model, style = MaterialTheme.typography.bodySmall) },
                            leadingIcon = { TickSlot(model == state.activeModel) },
                            onClick = {
                                onSwitchModel(model)
                                showIdentity = false
                            },
                        )
                    }
                }
            }

            IconButton(onClick = onOpenHistory, modifier = Modifier.size(36.dp).testTag(HISTORY_TAG)) {
                Icon(
                    Icons.Default.History,
                    contentDescription = "Past conversations",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            IconButton(onClick = onNewChat, modifier = Modifier.size(36.dp).testTag(NEW_CHAT_TAG)) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "New chat",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            Box {
                IconButton(onClick = { showOverflow = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
                DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (state.shareProjectContext) {
                                    "Stop sending project files"
                                } else {
                                    "Send project files"
                                },
                            )
                        },
                        leadingIcon = { TickSlot(state.shareProjectContext) },
                        onClick = {
                            onToggleShareContext(!state.shareProjectContext)
                            showOverflow = false
                        },
                    )
                    if (state.standingApprovals.isNotEmpty()) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Allowed here: " + state.standingApprovals.joinToString(", ") {
                                        it.replace('_', ' ')
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            enabled = false,
                            onClick = {},
                        )
                    }
                }
            }
        }
    }
}

/** A fixed-width tick slot, so names keep one left edge whether ticked or not. */
@Composable
private fun TickSlot(ticked: Boolean) {
    if (ticked) {
        Icon(Icons.Default.Check, contentDescription = "in use", modifier = Modifier.size(18.dp))
    } else {
        Box(Modifier.size(18.dp))
    }
}

/**
 * The panel before anything has been asked.
 *
 * **An invitation, not a logo.** Four openings, phrased as the things people
 * actually ask an assistant that can see their project, and laid out in a
 * `FlowRow` because four chips do not fit across 360 dp -- a `Row` would squeeze
 * the last one to 19 dp and wrap its label inside it, which is the exact defect
 * `CreateProjectDialogTest` was written for.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyTranscript(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onSignInGoogle: () -> Unit,
    onAddKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        // **Centred vertically, left-aligned horizontally.** Driving it showed
        // the cost of the obvious layout: three lines at the top of the panel
        // and a screen of black underneath, which is what reads as unfinished.
        // The text still starts on the same left edge every other block uses --
        // centring the words as well would make it a splash screen.
        modifier = modifier.fillMaxHeight().padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "Ask about this project",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "I can read and search your files, and change them once you say yes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.needsKey) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                if (state.activeProvider == AiProviderType.GEMINI &&
                    com.osamu.aide.ai.core.GoogleAuthManager.SIGN_IN_ENABLED
                ) {
                    OutlinedButton(onClick = onSignInGoogle) { Text("Sign in with Google") }
                }
                Button(onClick = onAddKey) {
                    Text(
                        when (state.activeProvider) {
                            AiProviderType.CUSTOM -> "Set the address"
                            AiProviderType.LOCAL -> "Set up on-device"
                            else -> "Add a key"
                        },
                    )
                }
            }
            // What setting up actually involves, since this is now the only
            // place that says it.
            Text(
                text = when (state.activeProvider) {
                    AiProviderType.CUSTOM -> "Custom needs the address of an OpenAI-compatible server."
                    AiProviderType.LOCAL ->
                        "Download the engine and a model, then start the server. Nothing leaves the phone."
                    else -> "${state.activeProvider.displayName} needs an API key. It stays on this device."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (opening in OPENINGS) {
                    AssistChip(
                        onClick = { onSend(opening.prompt) },
                        label = { Text(opening.label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
    }
}

/**
 * What to ask an assistant that can see your project.
 *
 * Written as a person would say them, not as feature names. "Explain
 * architecture" is a label on a button; "What does this project do?" is a
 * question, and it is also literally what gets sent.
 */
private class Opening(val label: String, val prompt: String)

private val OPENINGS = listOf(
    Opening("What does this do?", "What does this project do, and how is it laid out?"),
    Opening("Find the bug", "Look through this project for bugs or mistakes and tell me what you find."),
    Opening("Explain a file", "Which file should I read first to understand this project, and what is in it?"),
    Opening("Write a test", "Write a unit test for the most important logic in this project."),
)

/**
 * The input, and everything that belongs to the act of sending.
 *
 * **The send button is the row's control and the field is its label**, which is
 * why the field carries the weight: without it the button is measured in what
 * the field leaves, and a long draft squeezes it to nothing. Nine instances of
 * that defect are on record in this codebase.
 */
@Composable
private fun Composer(
    enabled: Boolean,
    sending: Boolean,
    activeStatus: String?,
    activeFileName: String?,
    draft: String,
    onDraftChange: (String) -> Unit,
    editing: ChatEntry.FromUser?,
    onCancelEdit: () -> Unit,
    onSubmit: (String) -> Unit,
    onCancelSend: () -> Unit,
) {
    var attachActiveFile by remember(activeFileName) { mutableStateOf(activeFileName != null) }

    fun submit() {
        if (!enabled || draft.isBlank()) return
        val prefix = if (attachActiveFile && activeFileName != null && editing == null) {
            "[@$activeFileName]\n"
        } else {
            ""
        }
        onSubmit(prefix + draft)
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (sending) WorkingIndicator(activeStatus)

        if (editing != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Editing your message",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancelEdit) { Text("Cancel") }
            }
        } else if (activeFileName != null && attachActiveFile) {
            AssistChip(
                onClick = { attachActiveFile = false },
                label = {
                    Text(
                        text = activeFileName,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(14.dp))
                },
                trailingIcon = {
                    Icon(Icons.Default.Close, contentDescription = "Do not send this file", modifier = Modifier.size(14.dp))
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                ),
                modifier = Modifier.testTag(ATTACHMENT_TAG),
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f).testTag(COMPOSER_TAG),
                // Not the same words as the empty state's heading. Both said
                // "Ask about this project", which reads as a stutter on the
                // one screen that shows both at once.
                placeholder = { Text(if (editing != null) "Ask it differently" else "Ask a question") },
                // Grows to six lines and then scrolls: enough to see a
                // multi-part question, not enough to swallow the transcript.
                maxLines = 6,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Default),
                shape = MaterialTheme.shapes.large,
            )

            if (sending) {
                IconButton(
                    onClick = onCancelSend,
                    modifier = Modifier.padding(bottom = 4.dp).testTag(STOP_TAG),
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp),
                    )
                }
            } else {
                val canSend = enabled && draft.isNotBlank()
                IconButton(
                    onClick = ::submit,
                    enabled = canSend,
                    modifier = Modifier.padding(bottom = 4.dp).testTag(SEND_TAG),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * What the assistant is doing, and -- once it has been a while -- for how long.
 *
 * **The elapsed count is the point.** On this hardware a first answer from a
 * local model can take a minute (`tools/localai/FINDINGS.md` §9), and a
 * spinner with no number is indistinguishable from a hang. It appears only
 * after a few seconds, so a fast provider never shows a stopwatch.
 */
@Composable
private fun WorkingIndicator(activeStatus: String?) {
    var seconds by remember(activeStatus) { mutableStateOf(0) }
    LaunchedEffect(activeStatus) {
        seconds = 0
        while (true) {
            delay(1_000)
            seconds++
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(13.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = activeStatus ?: "Thinking",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag(STATUS_TAG),
        )
        AnimatedVisibility(seconds >= SHOW_ELAPSED_AFTER) {
            Text(
                text = "${seconds}s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A failure, with the one action that usually fixes it.
 *
 * Dismiss alone left the user to retype their question; the message they sent
 * is still in the transcript, so sending it again is one tap.
 */
@Composable
private fun ErrorBar(message: String, onRetry: (() -> Unit)?, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag(ERROR_TAG),
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retry")
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun KeyPrompt(
    activeProvider: AiProviderType,
    onAddKey: () -> Unit,
    onSignInGoogle: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when (activeProvider) {
                    AiProviderType.CUSTOM -> "Custom needs the address of an OpenAI-compatible server."
                    // Not a key, and saying "needs an API key" would send the
                    // user looking for one that does not exist. What it needs
                    // is a download and a running server.
                    AiProviderType.LOCAL ->
                        "On-device needs the engine and a model downloaded, then the server started."
                    else -> "${activeProvider.displayName} needs an API key. It stays on this device."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (activeProvider == AiProviderType.GEMINI &&
                com.osamu.aide.ai.core.GoogleAuthManager.SIGN_IN_ENABLED
            ) {
                TextButton(onClick = onSignInGoogle) { Text("Sign in") }
            }
            TextButton(onClick = onAddKey) {
                Text(if (activeProvider == AiProviderType.CUSTOM) "Set address" else "Set up")
            }
        }
    }
}

/** After this long, a spinner alone stops being honest. */
private const val SHOW_ELAPSED_AFTER = 3

const val IDENTITY_TAG = "chat-identity"
const val HISTORY_TAG = "chat-history"
const val NEW_CHAT_TAG = "chat-new"
const val COMPOSER_TAG = "chat-composer"
const val SEND_TAG = "chat-send"
const val STOP_TAG = "chat-stop"
const val STATUS_TAG = "chat-status"
const val ERROR_TAG = "chat-error"
const val ATTACHMENT_TAG = "chat-attachment"
