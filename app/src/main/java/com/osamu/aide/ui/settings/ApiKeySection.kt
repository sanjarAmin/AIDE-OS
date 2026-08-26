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

/**
 * Where the user's Anthropic key is entered.
 *
 * The stored key is never read back into the field. [ApiKeyStore] can decrypt
 * it, so this is not about capability -- it is that a screen which renders a
 * secret has to be right about screenshots, accessibility services and the
 * recents thumbnail forever after, and showing "a key is saved" costs nothing
 * and asks none of those questions. Replacing it means typing it again, which
 * is the same thing the user does when they rotate it anyway.
 */
@Composable
fun ApiKeySection(keys: ApiKeyStore, modifier: Modifier = Modifier) {
    var saved by remember { mutableStateOf(keys.hasKey()) }
    var draft by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("AI assistant", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Your own Anthropic API key. It is encrypted with a key held in " +
                "this device's hardware keystore and never leaves the device.",
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

        Button(
            onClick = {
                keys.save(draft.trim())
                saved = true
                draft = ""
                revealed = false
            },
            // Trimmed, because a key pasted from a browser arrives with a
            // trailing newline often enough that "invalid x-api-key" on a key
            // the user can see is correct is worth one call to trim().
            enabled = draft.isNotBlank(),
        ) { Text("Save key") }
    }
}
