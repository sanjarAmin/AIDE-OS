package com.osamu.aide.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.osamu.aide.ai.core.Assistant
import com.osamu.aide.ai.core.ChatController
import com.osamu.aide.ai.core.ChatUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.io.File

/**
 * Keeps one conversation alive for as long as the workspace is open.
 *
 * Separate from [WorkspaceViewModel] rather than folded into it because the
 * two have nothing to say to each other: the assistant reads the project from
 * disk through its own sandbox, not from the editor's buffers. Sharing a view
 * model would only mean a rotation in the editor and a rotation in the chat
 * were the same event, which they are not.
 *
 * The scope is [viewModelScope], so a turn in flight survives a rotation and is
 * cancelled when the workspace is actually left -- including a turn parked on
 * an approval the user never answered.
 */
class AssistantViewModel(private val assistant: Assistant) : ViewModel() {

    private val controller = MutableStateFlow<ChatController?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ChatUiState> = controller
        .flatMapLatest { it?.state ?: flowOf(ChatUiState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    /** Idempotent, because the screen calls it from a LaunchedEffect. */
    fun open(projectDir: File) {
        if (controller.value?.projectDir == projectDir) return
        controller.value = ChatController(assistant, projectDir, viewModelScope)
    }

    fun send(text: String) = controller.value?.send(text) ?: Unit

    fun resolveApproval(approved: Boolean) =
        controller.value?.resolveApproval(approved) ?: Unit

    fun dismissError() = controller.value?.dismissError() ?: Unit
}
