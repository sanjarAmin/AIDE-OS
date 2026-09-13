package com.osamu.aide.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.osamu.aide.core.ui.theme.CodeTextStyle
import com.osamu.aide.debugger.DebugState
import com.osamu.aide.debugger.VariableView
import java.io.File

/** What the Debug panel can ask for. */
data class DebugActions(
    /** Null where this project cannot be debugged; the panel then says why. */
    val start: (() -> Unit)?,
    val resume: () -> Unit,
    val stepOver: () -> Unit,
    val stepInto: () -> Unit,
    val stepOut: () -> Unit,
    val stop: () -> Unit,
    val selectFrame: (Int) -> Unit,
    val toggleExpanded: (Long) -> Unit,
    val openBreakpoint: (FileBreakpoint) -> Unit,
    val removeBreakpoint: (FileBreakpoint) -> Unit,
)

/**
 * The Debug tab: a session's state, and what can be done to it.
 *
 * **Stopped is the only state with much to show, and it shows it in the order
 * it is read**: where it stopped, then the stack that got there, then the
 * variables of whichever frame is selected. Everything else is a sentence and
 * the list of breakpoints, because before a session starts the breakpoints
 * *are* the debugger -- they are set in the editor's gutter and there is no
 * other place that lists them.
 *
 * The step controls sit on a row of their own and scroll. Five buttons beside
 * a status line is exactly the row this codebase has squeezed seven times
 * (`CLAUDE.md`), and here the squeezed half would be Resume.
 */
@Composable
fun DebugPanel(
    state: DebugUiState,
    actions: DebugActions,
    projectRoot: File?,
    /** Why Debug is unavailable for this project, when it is. */
    unavailableReason: String?,
    modifier: Modifier = Modifier,
) {
    val session = state.session
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (session is DebugState.Attaching || (session is DebugState.Running && session.stepping)) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
            }
            Text(
                text = headline(session),
                style = MaterialTheme.typography.titleSmall,
                color = if (session is DebugState.Stopped) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // Weighted: the headline is the variable half of this row.
                modifier = Modifier.weight(1f).semantics { contentDescription = "Debug status" },
            )
            // **Here, not at the end of the step buttons.** It was the fifth
            // button of that row, and on a phone the row scrolled it off the
            // right edge -- the one control that ends a session, reachable
            // only by dragging sideways. Driving it at 1080 px wide found that.
            if (state.isActive) {
                TextButton(
                    onClick = actions.stop,
                    modifier = Modifier.semantics { contentDescription = "Stop debugging" },
                ) { Text("Stop", color = MaterialTheme.colorScheme.error) }
            }
        }

        when {
            state.isActive -> SessionControls(session, actions)
            unavailableReason != null -> Text(
                text = unavailableReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            actions.start != null -> FilledTonalButton(
                onClick = actions.start,
                modifier = Modifier.semantics { contentDescription = "Start debugging" },
            ) { Text("Debug") }
        }

        LazyColumn(Modifier.fillMaxWidth()) {
            if (session is DebugState.Stopped) {
                stopped(session, actions)
            } else {
                breakpoints(state, actions, projectRoot)
            }
        }
    }
}

private fun headline(session: DebugState): String = when (session) {
    DebugState.Idle -> "Not debugging"
    is DebugState.Attaching -> session.message
    is DebugState.Running -> session.message
    is DebugState.Stopped -> {
        val where = session.frame?.let { frame ->
            frame.source?.let { "${it.key.substringAfterLast('/')}:${it.line}" } ?: frame.title
        } ?: "an unknown place"
        val others = if (session.waiting > 0) " · ${session.waiting} more waiting" else ""
        "Paused at $where on ${session.threadName}$others"
    }
    is DebugState.Ended -> session.message
}

@Composable
private fun SessionControls(session: DebugState, actions: DebugActions) {
    val stopped = session is DebugState.Stopped
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(onClick = actions.resume, enabled = stopped) { Text("Resume") }
        OutlinedButton(onClick = actions.stepOver, enabled = stopped) { Text("Step over") }
        OutlinedButton(onClick = actions.stepInto, enabled = stopped) { Text("Step into") }
        OutlinedButton(onClick = actions.stepOut, enabled = stopped) { Text("Step out") }
    }
}

private fun LazyListScope.sectionTitle(title: String) {
    item(key = "title-$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
    }
}

private fun LazyListScope.breakpoints(state: DebugUiState, actions: DebugActions, projectRoot: File?) {
    sectionTitle("Breakpoints")
    if (state.breakpoints.isEmpty()) {
        item(key = "no-breakpoints") {
            Text(
                text = "Tap a line number in the editor to set one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val sorted = state.breakpoints.sortedWith(compareBy({ it.file.path }, { it.line }))
    items(sorted, key = { "${it.file.path}:${it.line}" }) { breakpoint ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.error, RoundedCornerShape(5.dp)),
            )
            // The file and line first, the directory under it: a full path
            // ellipsised on a phone cut off exactly the part that told two
            // breakpoints apart -- the line number.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { actions.openBreakpoint(breakpoint) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "${breakpoint.file.name}:${breakpoint.line}",
                    style = CodeTextStyle,
                    maxLines = 1,
                )
                Text(
                    text = breakpoint.file.parentFile?.displayName(projectRoot).orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
            IconButton(
                onClick = { actions.removeBreakpoint(breakpoint) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove the breakpoint at ${breakpoint.file.name}:${breakpoint.line}",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private fun LazyListScope.stopped(session: DebugState.Stopped, actions: DebugActions) {
    // **Variables first.** They are what a breakpoint is for; the stack is how
    // execution got there, and on a phone it is fifty frames of framework that
    // pushed the variables below the bottom of the dock. Selecting a frame
    // still changes which variables show.
    sectionTitle("Variables")
    session.variablesNote?.let { note ->
        item(key = "variables-note") {
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    variables(session.variables, session.expanded, depth = 0, path = "v", actions)

    sectionTitle("Frames")
    items(session.frames.withIndex().toList(), key = { "frame-${it.index}" }) { (index, frame) ->
        val selected = index == session.selectedFrame
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(6.dp),
                )
                .clickable { actions.selectFrame(index) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = frame.title,
                style = CodeTextStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = frame.source?.let { "${it.key.substringAfterLast('/')}:${it.line}" } ?: "no source",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * Variables, and the fields of any the user has opened, as one indented list.
 *
 * Flattened into the lazy list rather than nested columns so that opening a
 * large object does not compose every field of it at once. Keys carry the path
 * down from the frame, because the same object can be reachable twice -- `this`
 * and a field pointing back to it -- and a key made of the object id alone
 * would collide.
 */
private fun LazyListScope.variables(
    list: List<VariableView>,
    expanded: Map<Long, List<VariableView>>,
    depth: Int,
    path: String,
    actions: DebugActions,
) {
    list.forEachIndexed { index, variable ->
        val key = "$path/$index"
        val open = variable.objectId != null && variable.objectId in expanded
        item(key = key) { VariableRow(variable, depth, open, actions) }
        // Bounded, so a cycle the user keeps opening cannot recurse forever.
        if (open && depth < MAX_DEPTH) {
            variables(expanded.getValue(variable.objectId!!), expanded, depth + 1, key, actions)
        }
    }
}

private const val MAX_DEPTH = 8

@Composable
private fun VariableRow(variable: VariableView, depth: Int, open: Boolean, actions: DebugActions) {
    val expandable = variable.objectId != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = expandable) { variable.objectId?.let(actions.toggleExpanded) }
            .padding(start = (depth * 14).dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (expandable) {
                Icon(
                    if (open) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = variable.name,
            style = CodeTextStyle,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            text = " = ",
            style = CodeTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = variable.value,
            style = CodeTextStyle,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Weighted: a long string value is the part that gives.
            modifier = Modifier.weight(1f).semantics { contentDescription = "${variable.name} = ${variable.value}" },
        )
        Text(
            text = variable.type,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp, end = 4.dp),
        )
    }
}

private fun File.displayName(root: File?): String =
    root?.let { runCatching { relativeTo(it).path }.getOrNull() } ?: name
