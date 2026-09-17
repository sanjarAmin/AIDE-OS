package com.osamu.aide.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osamu.aide.ai.core.ApprovalRequest
import com.osamu.aide.ai.core.ApprovalScope
import com.osamu.aide.core.ui.theme.CodeTextStyle

/**
 * The gate in front of anything that changes the project.
 *
 * **It has to survive being seen forty times.** A prompt that can only be
 * answered once per call trains people to tap the primary button without
 * reading, which is worse than no gate at all -- so the middle option grants
 * the tool for the rest of this conversation, and the person who is editing all
 * afternoon makes one decision instead of forty. A refusal is always just a
 * refusal: a standing *no* would leave the assistant unable to act with nothing
 * on screen to explain why.
 *
 * **What is about to happen is shown, not described.** For a file, the diff; for
 * a command, the command as a shell would see it. A prompt that says "the
 * assistant wants to edit a file" and nothing else cannot be answered
 * responsibly, and so it gets answered carelessly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApprovalPrompt(
    request: ApprovalRequest,
    onApproval: (Boolean, ApprovalScope) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isCommand = request.toolName == "run_shell"

    Surface(
        // **Neutral, not `secondaryContainer`.** That token is green in this
        // theme, so the gate in front of rewriting someone's files rendered as
        // a green banner -- which reads as "approved" at a glance, the opposite
        // of what it is asking. A lifted neutral separates it from the
        // transcript without colouring the decision.
        color = colors.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().testTag(APPROVAL_TAG),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (isCommand) Icons.Default.Terminal else Icons.Default.EditNote,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp),
                )
                // Names the thing, in the user's terms. "Run a command in your
                // project?" answers what and where; "run_shell requires
                // approval" answers neither.
                Text(
                    text = if (isCommand) {
                        "Run a command in your project?"
                    } else {
                        "Change ${request.path.ifBlank { "a file" }}?"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }

            if (request.preview.isNotBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.surface, RoundedCornerShape(8.dp))
                        .heightIn(max = if (isCommand) 120.dp else 240.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    if (isCommand) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "$",
                                style = CodeTextStyle,
                                color = colors.primary,
                            )
                            Text(request.preview, style = CodeTextStyle, color = colors.onSurface)
                        }
                    } else {
                        DiffViewer(request.preview)
                    }
                }
            }

            // FlowRow, because three labelled buttons do not fit across 360 dp
            // and a Row would squeeze the last one until its label wrapped
            // inside it. The decline is a text button rather than a third
            // filled one: three equal-weight buttons make the safe choice no
            // easier to find than the risky one.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = { onApproval(true, ApprovalScope.ONCE) },
                    modifier = Modifier.testTag(ALLOW_ONCE_TAG),
                ) {
                    Text("Allow")
                }
                OutlinedButton(
                    onClick = { onApproval(true, ApprovalScope.CONVERSATION) },
                    modifier = Modifier.testTag(ALLOW_ALWAYS_TAG),
                ) {
                    Text("Allow in this chat")
                }
                TextButton(
                    onClick = { onApproval(false, ApprovalScope.ONCE) },
                    modifier = Modifier.testTag(DECLINE_TAG),
                ) {
                    Text("Don't")
                }
            }
        }
    }
}

/**
 * A unified diff, coloured.
 *
 * Line numbers are the file's, counted over context and additions only, because
 * a deleted line has no number in the result and numbering it implies the file
 * still has it.
 */
@Composable
private fun DiffViewer(preview: String) {
    val lines = remember(preview) { preview.lines() }
    val colors = MaterialTheme.colorScheme
    // Fixed rather than theme colours: red and green mean added and removed
    // everywhere a developer has ever seen a diff, and `error`/`primary` here
    // would make an addition the same sky blue as the send button.
    val addText = Color(0xFF4ADE80)
    val addBackground = Color(0x1A22C55E)
    val removeText = Color(0xFFFB7185)
    val removeBackground = Color(0x1AF43F5E)

    Column {
        var number = 0
        for (line in lines) {
            val added = line.startsWith("+")
            val removed = line.startsWith("-")
            if (!removed) number++
            Row(
                modifier = Modifier
                    .background(
                        when {
                            added -> addBackground
                            removed -> removeBackground
                            else -> Color.Transparent
                        },
                    )
                    .padding(vertical = 1.dp, horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (removed) "   " else number.toString().padStart(3),
                    style = CodeTextStyle.copy(fontSize = 11.sp),
                    color = colors.onSurfaceVariant.copy(alpha = 0.45f),
                )
                Text(
                    text = line,
                    style = CodeTextStyle.copy(fontSize = 11.5.sp),
                    color = when {
                        added -> addText
                        removed -> removeText
                        else -> colors.onSurface
                    },
                )
            }
        }
    }
}

const val APPROVAL_TAG = "chat-approval"
const val ALLOW_ONCE_TAG = "chat-allow-once"
const val ALLOW_ALWAYS_TAG = "chat-allow-always"
const val DECLINE_TAG = "chat-decline"
