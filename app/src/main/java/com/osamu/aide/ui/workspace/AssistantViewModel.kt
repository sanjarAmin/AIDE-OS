package com.osamu.aide.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
class AssistantViewModel(
    private val assistant: Assistant,
    private val builder: ProjectBuilder,
    private val projects: ProjectRepository,
) : ViewModel() {

    /** Shared by both build tools, so one can read what the other produced. */
    private val lastBuild = LastBuild()

    private val _completing = MutableStateFlow(false)

    /** True while a completion is in flight, so the button can say so. */
    val completing: StateFlow<Boolean> = _completing

    private val noticeChannel = Channel<String>(Channel.BUFFERED)

    /**
     * Things the user needs told that are not part of the conversation.
     *
     * A completion that finds nothing, or fails, has to say so somewhere. The
     * chat panel is the wrong place -- the user is in the editor and may never
     * open it -- and silence is worse: a button that sometimes does nothing and
     * never explains reads as broken.
     */
    val notices: Flow<String> get() = noticeChannel.receiveAsFlow()

    /**
     * The open project, looked up when a tool asks rather than held.
     *
     * `run_build` needs a [Project] -- name, application id, dependencies --
     * and the screen only hands over a directory. Reading it at call time also
     * means a dependency the user added mid-conversation is in the build.
     */
    private var openProject: Project? = null

    private val controller = MutableStateFlow<ChatController?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ChatUiState> = controller
        .flatMapLatest { it?.state ?: flowOf(ChatUiState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    /** Idempotent, because the screen calls it from a LaunchedEffect. */
    fun open(projectDir: File) {
        if (controller.value?.projectDir == projectDir) return

        controller.value = ChatController(
            assistant = assistant,
            projectDir = projectDir,
            scope = viewModelScope,
            extraTools = buildTools(
                // The summary, not the log -- see BuildTools.summarise for what
                // is dropped and why.
                runBuild = { target -> builder.build(target).toList().summarise(target.rootDir) },
                project = { openProject },
                lastBuild = lastBuild,
            ),
        )

        viewModelScope.launch {
            openProject = (projects.openProject(projectDir) as? AppResult.Success)?.value
        }
    }

    fun send(text: String) = controller.value?.send(text) ?: Unit

    fun resolveApproval(approved: Boolean) =
        controller.value?.resolveApproval(approved) ?: Unit

    fun dismissError() = controller.value?.dismissError() ?: Unit

    /**
     * Completes at [cursor] and hands the text back to be inserted.
     *
     * Guarded against overlap rather than queued: two completions in flight
     * would insert twice at a cursor that has since moved, and the second one
     * would be answering a question about text that no longer exists.
     */
    fun complete(path: String, text: String, cursor: Int, onInsert: (String) -> Unit) {
        if (_completing.value) return
        _completing.value = true

        viewModelScope.launch {
            val completer = assistant.completer()
            if (completer == null) {
                _completing.value = false
                noticeChannel.trySend("Add your Anthropic API key in Settings to use completion.")
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
