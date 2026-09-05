package com.osamu.aide.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
 * Choosing an assistant and giving it a key.
 *
 * **Ordered as the job is done, not as the data is stored.** Three groups, each
 * answering one question: which assistant, what key, and how it behaves. The
 * previous version was one flat column of controls under a single Save button,
 * so nothing said which control that button applied to -- and it applied to two
 * of them.
 *
 * Three things here are deliberately factual rather than decorative:
 *
 *  - **The tick on a provider chip** answers "which of these can I use", which
 *    otherwise took four taps to find out. It means a key is stored, not that
 *    the key works; only a successful request proves that.
 *  - **Remove names its provider**, because it now removes only that provider's
 *    key. It used to call [ApiKeyStore.clear], which wipes all four and deletes
 *    the Keystore alias, while the label said "a key".
 *  - **Two Save buttons, each naming what it writes.** With one button a typo in
 *    the endpoint blocked saving the key, and saving a key silently rewrote the
 *    endpoint.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApiKeySection(keys: ApiKeyStore, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var activeProvider by remember { mutableStateOf(keys.activeProvider()) }
    var activeModel by remember { mutableStateOf(keys.activeModel(activeProvider)) }
    var shareContext by remember { mutableStateOf(keys.shareProjectContext()) }

    var isGoogleSignedIn by remember { mutableStateOf(keys.isGoogleSignedIn()) }
    var googleEmail by remember { mutableStateOf(keys.googleUserEmail()) }
    var googleScopes by remember { mutableStateOf(keys.googleGrantedScopes()) }

    // Keyed by provider so switching chips re-reads rather than showing the
    // previous provider's state under the new provider's name.
    var saved by remember { mutableStateOf(keys.hasProviderKey(activeProvider)) }
    var draft by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }

    // Per provider, because the store keeps one endpoint per provider and each
    // client is built from its own -- see ApiKeyStore.providerBaseUrl.
    var storedEndpoint by remember { mutableStateOf(keys.providerBaseUrl(activeProvider).orEmpty()) }
    var endpointDraft by remember { mutableStateOf(storedEndpoint) }

    // Open when there is already something to see, or when the provider is the
    // one whose entire purpose is a custom address. Otherwise the endpoint is a
    // field almost nobody wants, sitting between the key and the switch.
    var showAdvanced by remember {
        mutableStateOf(storedEndpoint.isNotEmpty() || activeProvider == AiProviderType.CUSTOM)
    }

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
            // No mention of Google Sign-In: it is disabled, and naming a way in
            // that does not exist is worse than naming none. See
            // GoogleAuthManager.SIGN_IN_ENABLED.
            text = "Bring a key from one of these services. Keys are encrypted by the " +
                "device's hardware keystore, and are sent only to the service you pick.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // == 1. Which assistant ==============================================

        // FlowRow, not Row: four chips do not fit the width of a phone, and a
        // Row shrinks the last one until its label wraps one character per
        // line. Wrapping by chip is the only thing that degrades sensibly.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AiProviderType.entries.forEach { provider ->
                FilterChip(
                    selected = activeProvider == provider,
                    onClick = {
                        activeProvider = provider
                        keys.setActiveProvider(provider)
                        activeModel = keys.activeModel(provider)
                        saved = keys.hasProviderKey(provider)
                        draft = ""
                        revealed = false
                        storedEndpoint = keys.providerBaseUrl(provider).orEmpty()
                        endpointDraft = storedEndpoint
                        showAdvanced = showAdvanced ||
                            storedEndpoint.isNotEmpty() ||
                            provider == AiProviderType.CUSTOM
                    },
                    label = { Text(provider.displayName) },
                    leadingIcon = if (keys.hasProviderKey(provider)) {
                        {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "${provider.displayName} has a key",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        // The Google card is a real second way in, so it stays a card -- but
        // only when it can do something. Rendering a signed-out card with no
        // button, which is what SIGN_IN_ENABLED = false leaves, advertises a
        // route that does not exist.
        if (activeProvider == AiProviderType.GEMINI &&
            (isGoogleSignedIn || GoogleAuthManager.SIGN_IN_ENABLED)
        ) {
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
                                    "Signed in as ${googleEmail ?: "a Google Account"}"
                                } else {
                                    "Google Account"
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
                        Text(
                            "Gemini does not accept a Google sign-in for chat requests. " +
                                "Add an API key below instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                keys.signOutGoogle()
                                isGoogleSignedIn = false
                                googleEmail = null
                                googleScopes = null
                                saved = keys.hasProviderKey(activeProvider)
                            },
                        ) {
                            Text("Sign out")
                        }
                    } else {
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
                }
            }
        }

        var showModelDropdown by remember { mutableStateOf(false) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Model", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Each service remembers its own model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                OutlinedButton(
                    onClick = { showModelDropdown = true },
                    modifier = Modifier.semantics { contentDescription = "Model" },
                ) {
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

        HorizontalDivider()

        // == 2. Its key ======================================================

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
                    text = "${activeProvider.displayName} key saved",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        keys.clearProviderKey(activeProvider)
                        saved = keys.hasProviderKey(activeProvider)
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
            placeholder = { Text(keyPlaceholder(activeProvider)) },
            supportingText = { Text(whereToGetAKey(activeProvider)) },
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

        Button(
            onClick = {
                val trimmed = draft.trim()
                when (activeProvider) {
                    // Only Anthropic writes the legacy slot. Every provider used
                    // to write it, so saving a Gemini key put that key in
                    // Anthropic's store and lit Anthropic's tick.
                    AiProviderType.ANTHROPIC -> keys.save(trimmed)
                    AiProviderType.GEMINI -> keys.saveGeminiApiKey(trimmed)
                    AiProviderType.OPENAI -> keys.saveOpenAiApiKey(trimmed)
                    AiProviderType.CUSTOM -> keys.saveCustomApiKey(trimmed)
                }
                saved = true
                draft = ""
                revealed = false
            },
            enabled = draft.isNotBlank(),
        ) { Text("Save key") }

        HorizontalDivider()

        // == 3. How it behaves ===============================================

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Share project context", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Send your file tree and compiler errors along with each question.",
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

        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "Hide advanced" else "Advanced")
            Icon(
                if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
        }

        AnimatedVisibility(visible = showAdvanced) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (activeProvider == AiProviderType.GEMINI) {
                    // GeminiAiClient takes no base URL, so a field here would be
                    // one that accepts an address and ignores it.
                    Text(
                        "Gemini always goes to Google's own endpoint. " +
                            "Pick Custom to use a service of your own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
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
                    Button(
                        onClick = {
                            keys.saveProviderBaseUrl(activeProvider, endpoint)
                            storedEndpoint = normalised.orEmpty()
                            endpointDraft = storedEndpoint
                        },
                        enabled = normalised != null && normalised != storedEndpoint,
                    ) { Text("Save endpoint") }
                }
            }
        }
    }
}

private fun keyPlaceholder(provider: AiProviderType): String = when (provider) {
    AiProviderType.GEMINI -> "AIza..."
    AiProviderType.ANTHROPIC -> "sk-ant-..."
    AiProviderType.OPENAI -> "sk-..."
    AiProviderType.CUSTOM -> "Whatever your service issues"
}

/**
 * Where the key comes from.
 *
 * The console that issues a key is not guessable from the provider's name, and
 * an IDE on a phone is a bad place to go hunting for it. Naming the page is the
 * difference between a two-minute detour and abandoning the screen.
 */
private fun whereToGetAKey(provider: AiProviderType): String = when (provider) {
    AiProviderType.GEMINI -> "Create one at aistudio.google.com/apikey"
    AiProviderType.ANTHROPIC -> "Create one at console.anthropic.com under API keys"
    AiProviderType.OPENAI -> "Create one at platform.openai.com/api-keys"
    AiProviderType.CUSTOM -> "Any OpenAI-compatible service. Set its address under Advanced."
}
