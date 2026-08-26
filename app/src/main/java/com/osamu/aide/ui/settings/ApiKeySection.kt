package com.osamu.aide.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.osamu.aide.ai.core.ApiKeyStore
import com.osamu.aide.ai.core.Endpoint
import com.osamu.aide.ai.core.parseEndpoint

/**
 * Where the user's API key and the endpoint it is sent to are entered.
 *
 * The stored key is never read back into the field. [ApiKeyStore] can decrypt
 * it, so this is not about capability -- it is that a screen which renders a
 * secret has to be right about screenshots, accessibility services and the
 * recents thumbnail forever after, and showing "a key is saved" costs nothing
 * and asks none of those questions. Replacing it means typing it again, which
 * is the same thing the user does when they rotate it anyway.
 *
 * The endpoint **is** shown back, and that asymmetry is the point: it is not a
 * secret, and a base URL the user cannot see is a base URL they cannot notice
 * is wrong -- while being exactly the setting that decides who receives their
 * key. It is also how the normalisation in `parseEndpoint` stays honest: the
 * field is rewritten with what was actually stored, so a stripped `/v1` is
 * visible rather than silent.
 */
@Composable
fun ApiKeySection(keys: ApiKeyStore, modifier: Modifier = Modifier) {
    var saved by remember { mutableStateOf(keys.hasKey()) }
    var draft by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }

    var stored by remember { mutableStateOf(keys.baseUrl().orEmpty()) }
    var endpointDraft by remember { mutableStateOf(stored) }

    val endpoint = parseEndpoint(endpointDraft)
    // The normalised form the field would save, or null when it cannot save.
    // Compared against what is stored rather than against the raw text, so
    // retyping the same URL with a trailing slash is correctly "no change".
    val normalised = when (endpoint) {
        is Endpoint.Custom -> endpoint.baseUrl
        Endpoint.Default -> ""
        is Endpoint.Rejected -> null
    }

    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("AI assistant", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Your own API key. It is encrypted with a key held in this " +
                "device's hardware keystore and never leaves the device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (saved) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "A key is saved.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        keys.clear()
                        saved = false
                        draft = ""
                    },
                ) { Text("Remove") }
            }
        }

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "API key" },
            label = { Text(if (saved) "Replace key" else "API key") },
            placeholder = { Text("sk-ant-...") },
            singleLine = true,
            visualTransformation = if (revealed) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (revealed) "Hide key" else "Show key",
                    )
                }
            },
        )

        OutlinedTextField(
            value = endpointDraft,
            onValueChange = { endpointDraft = it },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "API endpoint" },
            label = { Text("API endpoint") },
            placeholder = { Text("Anthropic (default)") },
            singleLine = true,
            isError = endpoint is Endpoint.Rejected,
            supportingText = {
                Text(
                    text = when (endpoint) {
                        // The warning is the whole reason this is shown rather
                        // than hidden behind an "advanced" toggle: a custom
                        // endpoint means the key above is sent somewhere the
                        // user chose, and they should be told so plainly.
                        is Endpoint.Rejected -> endpoint.reason
                        is Endpoint.Custom -> "Your key will be sent to this address."
                        Endpoint.Default ->
                            "Leave blank for Anthropic. Any service that speaks the " +
                                "same Messages API works here."
                    },
                )
            },
        )

        Button(
            onClick = {
                // A blank key field means "leave the key alone", because the
                // saved one is never rendered back -- so it is blank whenever
                // the user came here to change only the endpoint.
                if (draft.isNotBlank()) {
                    // Trimmed, because a key pasted from a browser arrives with
                    // a trailing newline often enough that "invalid x-api-key"
                    // on a key the user can see is correct is worth one call.
                    keys.save(draft.trim())
                    saved = true
                    draft = ""
                    revealed = false
                }
                keys.saveBaseUrl(endpoint)
                stored = normalised.orEmpty()
                // Rewritten with what was stored, not what was typed: this is
                // where the user finds out a trailing slash or `/v1` was taken
                // off, instead of wondering later why it looks different.
                endpointDraft = stored
            },
            enabled = normalised != null && (draft.isNotBlank() || normalised != stored),
        ) { Text("Save") }
    }
}
