package com.osamu.aide.ui.benchmark

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osamu.aide.core.ui.theme.CodeTextStyle
import com.osamu.aide.toolchain.manager.InstallProgress
import org.koin.androidx.compose.koinViewModel

/**
 * Measures a local coding model on this phone, and hands back the numbers.
 *
 * A preview tool, reached from the AI settings. It exists because spike R16
 * could answer every question about a local model except the one that decides
 * whether to build it -- how fast it is on a phone -- and the answer has to come
 * from the user's own hardware.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(onNavigateBack: () -> Unit, viewModel: BenchmarkViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // **The screen stays on while anything is happening.** A phone that sleeps
    // mid-download or mid-run suspends both, and a benchmark taken while the
    // system throttles a dozing process measures the doze.
    val view = LocalView.current
    DisposableEffect(state.isBusy) {
        view.keepScreenOn = state.isBusy
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local model benchmark") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val listState = rememberLazyListState()
        LaunchedEffect(state.running, state.report) {
            if (state.running != null || state.report != null) listState.animateScrollToItem(0)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "Runs a coding model on this phone and measures it: how long it takes to " +
                        "start, how fast it writes, how long it reads a question the size the " +
                        "assistant sends, and whether it can use a tool. Nothing leaves the phone.\n\n" +
                        "Download the engine and one model, then Run. Use Wi-Fi, and keep this screen " +
                        "open while it downloads or runs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // **Progress and results first.** They sat below the model list, so
            // a tap on Run changed nothing visible until the user thought to
            // scroll -- found driving it. The list also scrolls up to them.
            state.running?.let { running ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Measuring ${running.displayName}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                state.step.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.semantics { contentDescription = "Benchmark step" },
                            )
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(
                                "This can take several minutes on the larger models.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            state.report?.let { report ->
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Results",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Button(
                                    onClick = { copy(context, report.asText()) },
                                    modifier = Modifier.semantics { contentDescription = "Copy results" },
                                ) { Text("Copy results") }
                            }
                            Text(report.device, style = MaterialTheme.typography.bodySmall)
                            Text("Model: ${report.model}", style = MaterialTheme.typography.bodySmall)
                            report.lines.forEach { (label, value) ->
                                Text(
                                    text = "$label: $value",
                                    style = CodeTextStyle,
                                    fontWeight = if (label == "Failed") FontWeight.Bold else FontWeight.Normal,
                                    color = if (label == "Failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.semantics { contentDescription = "$label: $value" },
                                )
                            }
                            Text(
                                "Copy and paste these into the chat with Claude.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            state.notice?.let { notice ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::dismissNotice) { Text("Dismiss") }
                    }
                }
            }

            item {
                val engine = state.engine
                if (engine == null) {
                    Text(
                        "The engine is not built for this phone's processor.",
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    ComponentCard(
                        title = "Engine: ${engine.component.displayName}",
                        detail = "${engine.component.archiveBytes / MB} MB download",
                        row = engine,
                        runEnabled = false,
                        onDownload = { viewModel.download(engine.component) },
                        onCancel = { viewModel.cancel(engine.component) },
                        onDelete = { viewModel.delete(engine.component) },
                        onRun = null,
                    )
                }
            }

            item {
                Text(
                    "Models",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            items(state.models, key = { it.component.id }) { row ->
                val neededGb = (row.component.archiveBytes + RUNTIME_OVERHEAD_BYTES) / GB
                val totalGb = state.totalRamBytes / GB
                ComponentCard(
                    title = row.component.displayName,
                    detail = "${row.component.archiveBytes / MB} MB · needs about ${"%.1f".format(neededGb)} GB " +
                        "of this phone's ${"%.1f".format(totalGb)} GB RAM",
                    warning = if (neededGb > totalGb * USABLE_RAM_FRACTION) {
                        "Probably too large for this phone: Android may stop it for memory."
                    } else {
                        null
                    },
                    row = row,
                    runEnabled = state.engine?.installed == true && state.running == null,
                    onDownload = { viewModel.download(row.component) },
                    onCancel = { viewModel.cancel(row.component) },
                    onDelete = { viewModel.delete(row.component) },
                    onRun = { viewModel.run(row.component) },
                )
            }

        }
    }
}

@Composable
private fun ComponentCard(
    title: String,
    detail: String,
    row: ModelRow,
    runEnabled: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onRun: (() -> Unit)?,
    warning: String? = null,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            warning?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            when (val progress = row.progress) {
                is InstallProgress.Downloading -> {
                    val fraction = progress.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${progress.bytes / MB} of ${progress.totalBytes / MB} MB",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                InstallProgress.Verifying, InstallProgress.Extracting -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        if (progress == InstallProgress.Verifying) "Verifying…" else "Unpacking…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (row.installed) {
                        if (onRun != null) {
                            Button(
                                onClick = onRun,
                                enabled = runEnabled,
                                modifier = Modifier.semantics { contentDescription = "Run $title" },
                            ) { Text("Run") }
                        } else {
                            Text(
                                "Installed",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        }
                        OutlinedButton(onClick = onDelete) { Text("Delete") }
                    } else {
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.semantics { contentDescription = "Download $title" },
                        ) { Text("Download") }
                    }
                }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("AIDE-OS benchmark", text))
}

private const val MB = 1_048_576L
private const val GB = 1_073_741_824.0

/** The server, its context window and the phone's own apps, beyond the file. */
private const val RUNTIME_OVERHEAD_BYTES = 700_000_000L

/**
 * Android keeps a good part of RAM for itself and other apps.
 *
 * 0.6 warned that the 1.5B was too large for the emulator's 2.4 GB, and spike
 * R16 had run it there. Loose on purpose: a warning that is wrong about the
 * model a user most wants to try teaches them to ignore it.
 */
private const val USABLE_RAM_FRACTION = 0.75
