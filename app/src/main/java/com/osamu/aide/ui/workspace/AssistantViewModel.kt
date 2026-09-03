package com.osamu.aide.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.osamu.aide.ai.core.AiProviderType
import com.osamu.aide.ai.core.ApiKeyStore
import com.osamu.aide.ai.core.Assistant
import com.osamu.aide.ai.core.CompletionContext
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.ProjectRepository
import com.osamu.aide.ai.core.ChatController
import com.osamu.aide.ai.core.ChatUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import java.io.File

/**
 * Keeps one conversation alive for as long as the workspace is open.
 */
class AssistantViewModel(
    private val assistant: Assistant,
    private val builder: ProjectBuilder,
    private val projects: ProjectRepository,
    private val keys: ApiKeyStore? = null,
) : ViewModel() {

    private val lastBuild = LastBuild()
    private val _completing = MutableStateFlow(false)

    val completing: StateFlow<Boolean> = _completing

    private val noticeChannel = Channel<String>(Channel.BUFFERED)
    val notices: Flow<String> get() = noticeChannel.receiveAsFlow()

    private var openProject: Project? = null
    private val controller = MutableStateFlow<ChatController?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ChatUiState> = controller
        .flatMapLatest { it?.state ?: flowOf(ChatUiState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    fun open(projectDir: File) {
        if (controller.value?.projectDir == projectDir) return

        controller.value = ChatController(
            assistant = assistant,
            projectDir = projectDir,
            scope = viewModelScope,
            extraTools = buildTools(
                runBuild = { target -> builder.build(target).toList().summarise(target.rootDir) },
                project = { openProject },
                lastBuild = lastBuild,
            ),
            keys = keys,
        )

        viewModelScope.launch {
            openProject = (projects.openProject(projectDir) as? AppResult.Success)?.value
        }
    }

    fun send(text: String) = controller.value?.send(text) ?: Unit

    fun resolveApproval(approved: Boolean) =
        controller.value?.resolveApproval(approved) ?: Unit

    fun dismissError() = controller.value?.dismissError() ?: Unit

    fun switchProvider(provider: AiProviderType) =
        controller.value?.switchProvider(provider) ?: Unit

    fun switchModel(model: String) =
        controller.value?.switchModel(model) ?: Unit

    fun toggleShareContext(share: Boolean) =
        controller.value?.toggleShareContext(share) ?: Unit

    fun complete(path: String, text: String, cursor: Int, onInsert: (String) -> Unit) {
        if (_completing.value) return
        _completing.value = true

        viewModelScope.launch {
            val completer = assistant.completer()
            if (completer == null) {
                _completing.value = false
                noticeChannel.trySend("Add your AI credentials in Settings to use completion.")
                return@launch
            }

            val outcome = runCatching {
                completer.complete(
                    CompletionContext(
                        path = path,
                        before = text.take(cursor),
                        after = text.drop(cursor),
                    ),
                )
            }
            _completing.value = false

            outcome.fold(
                onSuccess = { suggestion ->
                    if (suggestion == null) {
                        noticeChannel.trySend("Nothing to suggest here.")
                    } else {
                        onInsert(suggestion)
                    }
                },
                onFailure = { failure ->
                    noticeChannel.trySend(
                        "Completion failed: ${failure.message ?: failure::class.java.simpleName}",
                    )
                },
            )
        }
    }
}
