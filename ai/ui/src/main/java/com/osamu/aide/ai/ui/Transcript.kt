package com.osamu.aide.ai.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.osamu.aide.ai.core.ChatEntry
import com.osamu.aide.ai.core.ChatUiState
import kotlinx.coroutines.launch

/**
 * A turn, as the transcript draws it.
 *
 * **Grouped, because the rail is the structure.** The controller keeps a flat
 * list of entries; everything the assistant did in answer to one question
 * belongs together, sharing one vertical rule, so that prose-tool-prose reads
 * as a single piece of work rather than three unrelated cards. Grouping is a
 * pure function over the list and is tested as one.
 */
internal sealed interface Turn {
    val key: Long

    data class Question(val entry: ChatEntry.FromUser) : Turn {
        override val key: Long get() = entry.id
    }

    data class Answer(val entries: List<ChatEntry>) : Turn {
        override val key: Long get() = entries.first().id
        val streaming: Boolean
            get() = entries.any { it is ChatEntry.FromAssistant && it.streaming }
        val failed: Boolean
            get() = entries.any { it is ChatEntry.Tool && it.failed }
        /** Everything the assistant said, for one Copy that copies the answer. */
        val prose: String
            get() = entries.filterIsInstance<ChatEntry.FromAssistant>()
                .joinToString("\n\n") { it.text }
                .trim()
    }
}

/** Consecutive assistant and tool entries become one answer. */
internal fun groupTurns(entries: List<ChatEntry>): List<Turn> {
    val turns = mutableListOf<Turn>()
    val pending = mutableListOf<ChatEntry>()

    fun flush() {
        if (pending.isNotEmpty()) {
            turns += Turn.Answer(pending.toList())
            pending.clear()
        }
    }

    for (entry in entries) {
        if (entry is ChatEntry.FromUser) {
            flush()
            turns += Turn.Question(entry)
        } else {
            pending += entry
        }
    }
    flush()
    return turns
}

/**
 * The conversation.
 *
 * **Auto-scroll that yields to the reader.** Following the stream is right
 * until the person scrolls up to re-read something, at which point yanking
 * them back to the bottom every 50 ms is intolerable. So the list follows only
 * while it is already near the bottom, and otherwise offers a button. This is
 * the single most-felt interaction in a chat panel and the easiest to get
 * wrong.
 */
@Composable
internal fun Transcript(
    state: ChatUiState,
    onInsertCode: ((String) -> Unit)?,
    onRegenerate: () -> Unit,
    onEdit: (ChatEntry.FromUser) -> Unit,
    modifier: Modifier = Modifier,
) {
    val turns = remember(state.entries) { groupTurns(state.entries) }
    val listState = rememberLazyListState()

    // "Near the bottom" rather than "at the bottom": a streaming answer grows
    // under the reader's thumb, and an exact test would stop following the
    // moment a token made the last item taller than the viewport.
    val following by remember {
        androidx.compose.runtime.derivedStateOf {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            last == null || last.index >= layout.totalItemsCount - 2
        }
    }

    val lastKey = turns.lastOrNull()?.key
    val lastText = (state.entries.lastOrNull() as? ChatEntry.FromAssistant)?.text?.length ?: 0
    LaunchedEffect(lastKey, lastText, following) {
        if (following && turns.isNotEmpty()) {
            // Not animated. An animation restarted by every token never
            // arrives, and the list crawls behind the text.
            listState.scrollToItem(turns.lastIndex)
        }
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().testTag(TRANSCRIPT_TAG),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(
                count = turns.size,
                // Keyed, so a streaming answer does not rebuild the turns above
                // it and lose their expanded tool cards.
                key = { index -> turns[index].key },
            ) { index ->
                when (val turn = turns[index]) {
                    is Turn.Question -> {
                        Question(turn.entry, onEdit)
                        // **The answer's place is held from the moment the
                        // question is sent.** Driving it showed the cost of not
                        // doing so: the question sat alone above a screen of
                        // black for six seconds with only a spinner at the
                        // bottom edge to say anything was happening.
                        if (state.sending && index == turns.lastIndex) PendingAnswer()
                    }
                    is Turn.Answer -> Answer(
                        turn = turn,
                        onInsertCode = onInsertCode,
                        onRegenerate = onRegenerate.takeIf {
                            // Only the last answer can be regenerated: redoing
                            // an earlier one would orphan everything below it.
                            index == turns.lastIndex && state.canRegenerate && !state.sending
                        },
                    )
                }
            }
        }

        if (!following) {
            ScrollToLatest(
                onClick = { },
                listState = listState,
                target = turns.lastIndex,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            )
        }
    }
}

/**
 * The user's own message.
 *
 * **Deliberately the quieter half.** A person knows what they just asked; the
 * answer is what they came for. So the question is a compact tinted block that
 * stops short of the full width, which also gives the alignment its meaning --
 * the conversation has two sides and only one of them needs room.
 */
@Composable
private fun Question(entry: ChatEntry.FromUser, onEdit: (ChatEntry.FromUser) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            // **Neutral, not `secondaryContainer`.** That token is green in
            // this theme, so the user's own question rendered as a success
            // banner -- louder than the answer it is supposed to be quieter
            // than, and competing with the blue everything else uses. Found by
            // driving; no assertion would have noticed.
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            // Square on the top-right only: a small asymmetry that points the
            // block at its author, doing the job a tail or an avatar would
            // without spending 32 dp of width on one.
            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 4.dp, bottomStart = 14.dp, bottomEnd = 14.dp),
            modifier = Modifier.fillMaxWidth(0.86f).testTag(QUESTION_TAG),
            onClick = { onEdit(entry) },
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                val attachment = remember(entry.text) { attachedFile(entry.text) }
                if (attachment != null) {
                    // The composer's `[@file]` marker, shown as what it means
                    // rather than as the markup it is sent as.
                    Text(
                        text = attachment,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Text(
                    text = remember(entry.text) { withoutMarker(entry.text) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * One answer: the rail, everything on it, and the actions at its foot.
 */
@Composable
private fun Answer(
    turn: Turn.Answer,
    onInsertCode: ((String) -> Unit)?,
    onRegenerate: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current

    // The one piece of non-triggered motion in the panel. It marks the rail of
    // the turn being written, which is the only thing on screen that is
    // changing on its own, and it stops the moment the turn ends.
    val pulse = if (turn.streaming) {
        val transition = rememberInfiniteTransition(label = "rail")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "railAlpha",
        ).value
    } else {
        1f
    }

    val railColor = when {
        turn.streaming -> colors.primary.copy(alpha = pulse)
        turn.failed -> colors.error.copy(alpha = 0.7f)
        // `outlineVariant` is #1E2838 against a #0B0E14 background in this
        // theme: on the phone the rail was invisible, which left the answer
        // looking unstructured and the tool rows looking unattached. `outline`
        // is still quiet and actually renders.
        else -> colors.outline
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Drawn rather than laid out as a sibling: a Box with
            // `fillMaxHeight` inside a lazy item has no bounded height to fill,
            // and `IntrinsicSize.Min` would measure the whole answer twice on
            // every token.
            .drawBehind {
                val stroke = RAIL_WIDTH.toPx()
                drawLine(
                    color = railColor,
                    start = Offset(stroke / 2, 0f),
                    end = Offset(stroke / 2, size.height),
                    strokeWidth = stroke,
                )
            }
            .padding(start = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (entry in turn.entries) {
            when (entry) {
                is ChatEntry.FromAssistant -> SelectionContainer {
                    // Selectable, because an answer's value is often one line of
                    // it. The long-press that would otherwise open a menu is
                    // spent on selection instead, which is why the actions below
                    // are buttons rather than a hidden gesture.
                    Column {
                        MarkdownText(
                            markdown = entry.text,
                            onInsertCode = onInsertCode,
                            textColor = colors.onSurface,
                        )
                        if (entry.streaming) StreamingCaret()
                    }
                }

                is ChatEntry.Tool -> ToolPeg(entry)

                is ChatEntry.FromUser -> Unit // grouped elsewhere
            }
        }

        if (!turn.streaming && turn.prose.isNotBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(turn.prose)) },
                    modifier = Modifier.testTag(COPY_ANSWER_TAG),
                ) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy", style = MaterialTheme.typography.labelMedium)
                }
                if (onRegenerate != null) {
                    TextButton(
                        onClick = onRegenerate,
                        modifier = Modifier.testTag(REGENERATE_TAG),
                    ) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Try again", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/**
 * The rail, with a caret and nothing on it yet.
 *
 * Shown between sending a question and the first token arriving, which on this
 * hardware is most of a minute for a local model.
 */
@Composable
private fun PendingAnswer() {
    val colors = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "pendingRail")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pendingAlpha",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .drawBehind {
                val stroke = RAIL_WIDTH.toPx()
                drawLine(
                    color = colors.primary.copy(alpha = alpha),
                    start = Offset(stroke / 2, 0f),
                    end = Offset(stroke / 2, size.height),
                    strokeWidth = stroke,
                )
            }
            .padding(start = 14.dp)
            .testTag(PENDING_TAG),
    ) {
        StreamingCaret()
    }
}

/**
 * A block cursor at the end of the text being written.
 *
 * Sits on its own line rather than inline: putting it inside the
 * `AnnotatedString` means re-laying out the whole paragraph twice a second, and
 * a caret that lands mid-word after a line break looks like a rendering fault.
 */
@Composable
private fun StreamingCaret() {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "caretAlpha",
    )
    Box(
        Modifier
            .padding(top = 2.dp)
            .size(width = 7.dp, height = 15.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), RoundedCornerShape(1.dp))
            .testTag(CARET_TAG),
    )
}

@Composable
private fun ScrollToLatest(
    onClick: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    target: Int,
    modifier: Modifier = Modifier,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(50),
        shadowElevation = 3.dp,
        onClick = {
            onClick()
            scope.launch { if (target >= 0) listState.animateScrollToItem(target) }
        },
        modifier = modifier.testTag(SCROLL_LATEST_TAG),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.ArrowDownward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Latest", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** `[@Main.kt]` -> `Main.kt`, or null. */
internal fun attachedFile(text: String): String? =
    Regex("^\\[@([^]]+)]").find(text)?.groupValues?.get(1)

internal fun withoutMarker(text: String): String =
    text.replace(Regex("^\\[@[^]]*]\\s*"), "")

private val RAIL_WIDTH = 2.dp

const val TRANSCRIPT_TAG = "transcript"
const val QUESTION_TAG = "chat-question"
const val CARET_TAG = "chat-caret"
const val COPY_ANSWER_TAG = "chat-copy-answer"
const val REGENERATE_TAG = "chat-regenerate"
const val SCROLL_LATEST_TAG = "chat-scroll-latest"
const val PENDING_TAG = "chat-pending"
