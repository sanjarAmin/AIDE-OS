package com.osamu.aide.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.osamu.aide.ai.LocalModelServer
import com.osamu.aide.ai.core.AiProviderType
import com.osamu.aide.ai.core.ApiKeyStore
import com.osamu.aide.toolchain.manager.ToolchainComponent
import com.osamu.aide.toolchain.manager.ToolchainManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Starting and stopping the on-device model server.
 *
 * **The only provider whose readiness is an action rather than a secret.** The
 * other four are configured by pasting a key; this one is configured by having
 * a gigabyte of model on disk and a process running. So this is the control
 * that replaces the key field for [AiProviderType.LOCAL], and it reports the
 * three states that matter: what is missing, what is loading, and what is
 * listening.
 *
 * It does **not** download anything. The engine and the models are
 * `ToolchainComponent`s with their own screen, which already handles pinning,
 * verification and progress; duplicating a 4.7 GB download behind a second
 * button would be duplicating the part most likely to go wrong. This says what
 * is absent and sends the user there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalServerControl(
    server: LocalModelServer,
    toolchain: ToolchainManager,
    keys: ApiKeyStore,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    // The address is the source of truth for "is a server up", not a flag
    // here: `Assistant` reads the same value, and a local boolean could say
    // running while the provider reported itself unusable.
    var address by remember { mutableStateOf(keys.localBaseUrl()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // Which size to start. Defaults to whatever the provider is set to, so the
    // model picker above this and this control cannot disagree.
    var selected by remember {
        mutableStateOf(modelFor(keys.activeModel(AiProviderType.LOCAL)))
    }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = when {
                address != null -> "Running on $address"
                busy -> "Starting…"
                else -> "Not running"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(LOCAL_SERVER_STATUS_TAG),
        )

        Spacer(Modifier.height(8.dp))

        // **FlowRow, not Row.** Four model chips do not fit the width of a
        // phone and a Row squeezes rather than wraps -- the defect CLAUDE.md
        // records nine times, and the new-project dialog's C# chip is the one
        // that shipped unreadable.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolchainComponent.LOCAL_MODELS.forEach { model ->
                val installed = toolchain.installedFile(model) != null
                FilterChip(
                    selected = selected?.id == model.id,
                    // A model that is not downloaded cannot be started, and a
                    // chip that selects it would arm a button that then
                    // explains why it cannot work. Disabled says it sooner.
                    enabled = installed && address == null && !busy,
                    onClick = {
                        selected = model
                        keys.setActiveModel(AiProviderType.LOCAL, modelIdFor(model))
                    },
                    label = { Text(shortLabel(model)) },
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = "Sizes without a download are greyed out. Get them from Local models.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (address == null) {
                Button(
                    enabled = selected != null && !busy,
                    modifier = Modifier.testTag(LOCAL_SERVER_START_TAG),
                    onClick = {
                        val model = selected ?: return@Button
                        // Checked before starting, so the reason names the
                        // missing piece rather than arriving as a failure to
                        // bind a port.
                        server.unavailableReason(model)?.let {
                            notice = it
                            return@Button
                        }
                        busy = true
                        notice = null
                        scope.launch {
                            // Off the main thread: start() waits for /health,
                            // which is seconds for a small model and minutes
                            // for the 7B.
                            val started = withContext(Dispatchers.IO) { server.start(model) }
                            busy = false
                            address = started
                            if (started == null) {
                                notice = "The server did not start. " + server.recentLog()
                            }
                        }
                    },
                ) { Text("Start server") }
            } else {
                OutlinedButton(
                    modifier = Modifier.testTag(LOCAL_SERVER_STOP_TAG),
                    onClick = {
                        server.stop()
                        address = null
                        notice = null
                    },
                ) { Text("Stop server") }
            }

            if (busy) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }

        notice?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            // Said plainly, because it is the thing a user will otherwise
            // discover by waiting. `tools/localai/FINDINGS.md` §8.
            text = "A 7B model needs about 4.4 GB of RAM and took five minutes to " +
                "read one project-sized question on a flagship phone. The 1.5B is " +
                "the smallest that picks tools correctly.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The model id this provider stores for [component], and back again.
 *
 * `AiProviderType.LOCAL.availableModels` holds short names and
 * `ToolchainComponent.LOCAL_MODELS` holds component ids; this is the one place
 * the two are matched, by the size in both. Matching on the size rather than a
 * table means adding a fifth model needs no edit here -- and a size present in
 * one list and absent from the other simply does not select, rather than
 * selecting the wrong file.
 */
private fun modelIdFor(component: ToolchainComponent): String =
    AiProviderType.LOCAL.availableModels.firstOrNull { name ->
        sizeOf(name) != null && sizeOf(name) == sizeOf(component.id)
    } ?: AiProviderType.LOCAL.defaultModel

private fun modelFor(storedModelId: String?): ToolchainComponent? {
    val size = sizeOf(storedModelId.orEmpty()) ?: return null
    return ToolchainComponent.LOCAL_MODELS.firstOrNull { sizeOf(it.id) == size }
}

/** `0.5b`, `1.5b`, `3b`, `7b` — whichever of those appears in [text]. */
private fun sizeOf(text: String): String? =
    Regex("""(\d+(?:\.\d+)?b)""").find(text.lowercase())?.groupValues?.get(1)

/** "1.5B", from a display name that also carries the licence and the family. */
private fun shortLabel(component: ToolchainComponent): String =
    sizeOf(component.id)?.uppercase() ?: component.displayName

internal const val LOCAL_SERVER_STATUS_TAG = "local-server-status"
internal const val LOCAL_SERVER_START_TAG = "local-server-start"
internal const val LOCAL_SERVER_STOP_TAG = "local-server-stop"
