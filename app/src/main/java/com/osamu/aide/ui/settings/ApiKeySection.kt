package com.osamu.aide.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.osamu.aide.ai.core.AiProviderType
import com.osamu.aide.ai.core.ApiKeyStore
import com.osamu.aide.ai.core.Endpoint
import com.osamu.aide.ai.core.GoogleAuthManager
import com.osamu.aide.ai.core.parseEndpoint

/**
 * Settings for the AI Assistant, with Gemini as the default provider.
 *
 * Supports Google Sign-In, Gemini API keys, multi-model selection (OpenAI, Anthropic, Custom),
 * and context sharing preferences.
 */
@Composable
fun ApiKeySection(keys: ApiKeyStore, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var activeProvider by remember { mutableStateOf(keys.activeProvider()) }
    var activeModel by remember { mutableStateOf(keys.activeModel(activeProvider)) }
    var shareContext by remember { mutableStateOf(keys.shareProjectContext()) }

    var isGoogleSignedIn by remember { mutableStateOf(keys.isGoogleSignedIn()) }
    var googleEmail by remember { mutableStateOf(keys.googleUserEmail()) }
    var googleScopes by remember { mutableStateOf(keys.googleGrantedScopes()) }

    var saved by remember { mutableStateOf(keys.hasKey()) }
    var draft by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }

    var storedEndpoint by remember { mutableStateOf(keys.baseUrl().orEmpty()) }
    var endpointDraft by remember { mutableStateOf(storedEndpoint) }

    val endpoint = parseEndpoint(endpointDraft)
    val normalised = when (endpoint) {
        is Endpoint.Custom -> endpoint.baseUrl
        Endpoint.Default -> ""
        is Endpoint.Rejected -> null
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("AI Assistant", style = MaterialTheme.typography.titleMedium)
        }

        Text(
            text = "Gemini is the built-in default assistant. Sign in with Google or bring your own API key. " +
                "Credentials are encrypted in the device's hardware Keystore.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // -- Provider Selector Chips ----------------------------------------
        Text("Provider", style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AiProviderType.entries.forEach { provider ->
                FilterChip(
                    selected = activeProvider == provider,
                    onClick = {
                        activeProvider = provider
                        keys.setActiveProvider(provider)
                        activeModel = keys.activeModel(provider)
                        saved = keys.hasKey()
                    },
                    label = { Text(provider.displayName) },
                )
            }
        }

        // -- Google Sign In for Gemini --------------------------------------
        if (activeProvider == AiProviderType.GEMINI) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column {
                            Text(
                                text = if (isGoogleSignedIn) {
                                    "Signed in: ${googleEmail ?: "Google Account"}"
                                } else {
                                    "Google Account (Android Studio style)"
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            // **The scopes Google granted, not the ones asked
                            // for.** A dropped scope is invisible until the
                            // first request fails with
                            // ACCESS_TOKEN_SCOPE_INSUFFICIENT, which names the
                            // method and not the scope -- and a toast was
                            // truncated on the device this was diagnosed on,
                            // which emits no logcat either. It lives here
                            // because here it can be read.
                            if (isGoogleSignedIn) {
                                Text(
                                    text = "Granted: " + (
                                        googleScopes
                                            ?.split(' ')
                                            ?.joinToString(", ") { it.substringAfterLast('/') }
                                            ?: "not reported"
                                        ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (isGoogleSignedIn) {
                        OutlinedButton(
                            onClick = {
                                keys.signOutGoogle()
                                isGoogleSignedIn = false
                                googleEmail = null
                                googleScopes = null
                                saved = keys.hasKey()
                            },
                        ) {
                            Text("Sign out")
                        }
                    } else if (GoogleAuthManager.SIGN_IN_ENABLED) {
                        Button(
                            onClick = {
                                val authManager = GoogleAuthManager(keys)
                                val request = authManager.createAuthorizationRequest()
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(request.authUrl))
                                context.startActivity(intent)
                            },
                        ) {
                            Text("Sign in with Google")
                        }
                    }
                    // No else: signing in succeeds and then every Gemini
                    // request fails, because generateContent accepts no OAuth
                    // scope. Offering a button that works and then does not is
                    // worse than offering none. Sign *out* stays available
                    // above, so anyone already signed in can clear it.
                }
            }
        }

        // -- Model Selector -------------------------------------------------
        var showModelDropdown by remember { mutableStateOf(false) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Model", style = MaterialTheme.typography.bodyMedium)
            Box {
                OutlinedButton(onClick = { showModelDropdown = true }) {
                    Text(activeModel)
                }
                DropdownMenu(
                    expanded = showModelDropdown,
                    onDismissRequest = { showModelDropdown = false },
                ) {
                    activeProvider.availableModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                activeModel = model
                                keys.setActiveModel(activeProvider, model)
                                showModelDropdown = false
                            },
                        )
                    }
                }
            }
        }

        // -- Status Indicator -----------------------------------------------
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
                        isGoogleSignedIn = false
                        googleEmail = null
                    },
                ) { Text("Remove") }
            }
        }

        // -- API Key Input --------------------------------------------------
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "API key" },
            label = { Text(if (saved) "Replace key" else "API key") },
            placeholder = {
                Text(
                    when (activeProvider) {
                        AiProviderType.GEMINI -> "AIza..."
                        AiProviderType.ANTHROPIC -> "sk-ant-..."
                        AiProviderType.OPENAI -> "sk-..."
                        AiProviderType.CUSTOM -> "API key (optional)"
                    },
                )
            },
            singleLine = true,
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (revealed) "Hide key" else "Show key",
                    )
                }
            },
        )

        // -- Endpoint Input -------------------------------------------------
        OutlinedTextField(
            value = endpointDraft,
            onValueChange = { endpointDraft = it },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "API endpoint" },
            label = { Text("API endpoint") },
            placeholder = {
                Text(
                    when (activeProvider) {
                        AiProviderType.GEMINI -> "Google AI (default)"
                        AiProviderType.ANTHROPIC -> "Anthropic (default)"
                        AiProviderType.OPENAI -> "OpenAI (default)"
                        AiProviderType.CUSTOM -> "https://my-proxy.local"
                    },
                )
            },
            singleLine = true,
            isError = endpoint is Endpoint.Rejected,
            supportingText = {
                Text(
                    text = when (endpoint) {
                        is Endpoint.Rejected -> endpoint.reason
                        is Endpoint.Custom -> "Your key will be sent to this address."
                        Endpoint.Default -> "Leave blank for the default service endpoint."
                    },
                )
            },
        )

        // -- Code Context Sharing Switch ------------------------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Share project context", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Include project file structure and compiler diagnostics with queries.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = shareContext,
                onCheckedChange = {
                    shareContext = it
                    keys.setShareProjectContext(it)
                },
            )
        }

        // -- Save Button ----------------------------------------------------
        Button(
            onClick = {
                if (draft.isNotBlank()) {
                    val trimmed = draft.trim()
                    keys.save(trimmed)
                    when (activeProvider) {
                        AiProviderType.GEMINI -> keys.saveGeminiApiKey(trimmed)
                        AiProviderType.OPENAI -> keys.saveOpenAiApiKey(trimmed)
                        AiProviderType.ANTHROPIC -> keys.save(trimmed)
                        AiProviderType.CUSTOM -> keys.saveCustomApiKey(trimmed)
                    }
                    saved = true
                    draft = ""
                    revealed = false
                }
                keys.saveBaseUrl(endpoint)
                storedEndpoint = normalised.orEmpty()
                endpointDraft = storedEndpoint
            },
            enabled = normalised != null && (draft.isNotBlank() || normalised != storedEndpoint),
        ) { Text("Save") }
    }
}
