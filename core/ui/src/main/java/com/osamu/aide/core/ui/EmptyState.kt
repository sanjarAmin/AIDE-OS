package com.osamu.aide.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * A panel with nothing in it yet.
 *
 * **Every panel was inventing its own, and most invented one grey sentence.**
 * "No problems found in project." and "Build output appears here." sat alone in
 * 500 dp of blank space, saying only that something is absent -- while the Git
 * tab, two tabs along, named the situation, explained why, and offered the
 * action that resolves it. This is that shape, extracted, so the good one stops
 * being the exception.
 *
 * The three parts each do one job:
 *
 *  - [title] names the state in the user's terms, not the system's.
 *  - [explanation] says what will put something here. An empty screen is an
 *    invitation to act, and a person who does not know what fills a panel
 *    cannot act.
 *  - [action] is the thing to do next, when there is one. Many panels fill
 *    themselves as a side effect of work elsewhere and have none; those are
 *    honest without a button, and a button that only restates the explanation
 *    is worse than none.
 *
 * Left-aligned, not centred: every other block in these panels starts on the
 * same left edge, and a centred island in a tool dock reads as a splash screen.
 */
@Composable
fun EmptyState(
    title: String,
    explanation: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                // Quiet: the icon is a marker for the eye scanning a dock full
                // of tabs, not the subject of the panel.
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.invoke()
    }
}
