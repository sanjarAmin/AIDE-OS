package com.osamu.aide.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.toolchain.manager.InstalledToolchain
import com.osamu.aide.toolchain.manager.ToolchainManager
import kotlinx.coroutines.withContext

/**
 * What has been downloaded, and how to get the space back.
 *
 * The app can install about a gigabyte across nine components -- clang alone is
 * 551 MB installed -- and until now there was no screen that admitted it. On a
 * phone "what is taking up all this room and can I have it back" is the
 * question, so the list is **ordered by size** rather than by name, and the
 * size is the only number shown.
 *
 * It lists what is on disk rather than what the app expects, so a component
 * left behind by a re-pin appears too. Those cannot be described -- nothing
 * claims them -- but they can be deleted, which is the part that matters.
 */
@Composable
fun ToolchainSection(
    toolchain: ToolchainManager,
    dispatchers: DispatcherProvider,
    modifier: Modifier = Modifier,
) {
    var installed by remember { mutableStateOf<List<InstalledToolchain>?>(null) }
    var confirming by remember { mutableStateOf<InstalledToolchain?>(null) }
    var reload by remember { mutableStateOf(0) }

    // Walking directories to add up file sizes is disk work, and clang is
    // 550 MB of it: off the main thread, and repeated after a removal so the
    // screen shows what is there rather than what was.
    LaunchedEffect(reload) {
        installed = withContext(dispatchers.io) { toolchain.storage.installed() }
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text("Toolchains", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Compilers and runtimes this app downloaded. Removing one frees the " +
                "space; it is fetched again the next time something needs it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val entries = installed
        when {
            entries == null -> Text(
                text = "Measuring…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )

            entries.isEmpty() -> Text(
                text = "Nothing downloaded yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )

            else -> {
                Text(
                    text = "${megabytes(entries.sumOf { it.bytes })} in total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                entries.forEach { entry ->
                    ToolchainRow(entry) { confirming = entry }
                }
            }
        }
    }

    confirming?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Remove ${entry.displayName}?") },
            text = {
                Text(
                    // Named rather than implied. A component removed while a
                    // project depends on it is not broken -- the next build
                    // offers the download again -- and saying so is the
                    // difference between freeing space and fearing to.
                    "This frees ${megabytes(entry.bytes)}. Anything that needs it will " +
                        "offer to download it again.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        toolchain.storage.removeInstalled(entry)
                        confirming = null
                        reload++
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun ToolchainRow(entry: InstalledToolchain, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.padding(end = 12.dp)) {
            Text(entry.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = buildString {
                    append(megabytes(entry.bytes))
                    // Worth saying: an abandoned download is space with nothing
                    // usable in it, which is the easiest thing on the list to
                    // decide about.
                    if (entry.isPartialDownload) append(" · unfinished download")
                    if (entry.component == null) append(" · no longer used")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onRemove,
            modifier = Modifier.semantics {
                contentDescription = "Remove ${entry.displayName}"
            },
        ) { Text("Remove") }
    }
}

/**
 * Megabytes, because that is the unit the download prompts use.
 *
 * Anything under a megabyte reads as "less than 1 MB" rather than as a rounded
 * 0 MB, which on a screen about reclaiming space would look like a bug.
 */
private fun megabytes(bytes: Long): String {
    val mb = bytes / (1024 * 1024)
    return if (mb == 0L) "less than 1 MB" else "$mb MB"
}
