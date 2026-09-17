package com.osamu.aide.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.osamu.aide.ai.core.AiProviderType
import com.osamu.aide.ai.core.ApiKeyStore
import com.osamu.aide.ai.core.Endpoint
import com.osamu.aide.ai.core.GoogleAuthManager
import com.osamu.aide.ai.core.parseEndpoint
import org.koin.compose.koinInject

/**
 * Choosing an assistant and giving it a key.
 *
 * **The provider is a container, not a chip.** Everything a chip changes --
 * the key, the model, the address requests go to -- is drawn inside one surface
 * headed with that provider's name, and everything the chip does *not* change
 * sits outside it under a heading that says so. The previous version was one
 * flat column in which the strongest rule on the screen fell between the model
 * and the key, which are both per-provider, while "Share project context",
 * which is one app-wide preference, sat inside the same block as the selected
 * provider's credentials. Nothing said what tapping a chip changed, and the one
 * plausible reading -- that context sharing was Anthropic's, and Gemini had its
 * own -- was wrong.
 *
 * The card's title repeats the word already showing on the selected chip. That
 * repetition is the mechanism: it is what binds the rows to the selection
 * without a sentence explaining the binding, and it is why
 * "Each service remembers its own model" could be deleted rather than reworded.
 *
 * **Three properties, one row each, values always visible.** Key, model and
 * endpoint were a status row, a dropdown and a disclosure -- three shapes for
 * three things of the same kind, one of which hid its value until asked. They
 * are now the same row, and each shows what it is currently set to, so the
 * question this screen exists to answer -- *where does my key go?* -- is
 * answered by reading rather than by tapping Advanced. That change is what
 * surfaced [endpointDetail]'s last case: a Custom provider with no address set
 * sent its requests to OpenAI, which had been true and invisible. It is fixed
 * now, and the row says so.
 *
 * Three things kept from the previous version, each for a reason recorded then:
 *
 *  - **The tick on a provider chip** answers "which of these can I use", which
 *    otherwise took four taps to find out. It means a key is stored, not that
 *    the key works; only a successful request proves that.
 *  - **Remove names one provider.** It used to call [ApiKeyStore.clear], which
 *    wipes all four and deletes the Keystore alias, while the label said
 *    "a key".
 *  - **Two Save buttons, each naming what it writes.** With one button a typo
 *    in the endpoint blocked saving the key, and saving a key silently rewrote
 *    the endpoint.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApiKeySection(keys: ApiKeyStore, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var activeProvider by remember { mutableStateOf(keys.activeProvider()) }
    var activeModel by remember(activeProvider) { mutableStateOf(keys.activeModel(activeProvider)) }
    var shareContext by remember { mutableStateOf(keys.shareProjectContext()) }

    var isGoogleSignedIn by remember { mutableStateOf(keys.isGoogleSignedIn()) }
    var googleEmail by remember { mutableStateOf(keys.googleUserEmail()) }
    var googleScopes by remember { mutableStateOf(keys.googleGrantedScopes()) }

    // **Keyed on the provider, so switching chips re-reads rather than showing
    // the previous provider's state under the new provider's name.** That is
    // ai/core/FINDINGS.md section 17's bug, where one provider's key appeared
    // under another, and it was being prevented by re-reading each of these by
    // hand in the chip's `onClick` -- correct, and a trap: a seventh piece of
    // per-provider state added here and forgotten there brings the bug back,
    // silently, in a screen about credentials. The key does it structurally,
    // and a new value is right by construction.
    //
    // The store keeps one endpoint per provider and each client is built from
    // its own -- see ApiKeyStore.providerBaseUrl.
    var saved by remember(activeProvider) { mutableStateOf(keys.hasProviderKey(activeProvider)) }
    var draft by remember(activeProvider) { mutableStateOf("") }
    var revealed by remember(activeProvider) { mutableStateOf(false) }
    var storedEndpoint by remember(activeProvider) {
        mutableStateOf(keys.providerBaseUrl(activeProvider).orEmpty())
    }
    var endpointDraft by remember(activeProvider) { mutableStateOf(storedEndpoint) }

    // Open when there is no key, because on a fresh install typing one is the
    // only thing this screen is for. Once a key is stored the row collapses to
    // its state: a full-width field labelled "Replace key" was the largest
    // thing on the screen in the state where nothing needs doing.
    var editingKey by remember(activeProvider) {
        mutableStateOf(!keys.hasProviderKey(activeProvider))
    }
    // Never open by default any more. It used to be, whenever an endpoint was
    // stored, so that a custom address could not be forgotten and blamed on the
    // network -- the row now shows the address whether or not it is open, which
    // is the same guarantee without the field.
    var editingEndpoint by remember(activeProvider) { mutableStateOf(false) }

    var clipboardKey by remember(activeProvider) { mutableStateOf<String?>(null) }

    val checkClipboard: () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = clipboard?.primaryClip?.let { clip ->
            if (clip.itemCount > 0) clip.getItemAt(0)?.text?.toString() else null
        }?.trim()
        clipboardKey = if (clipText != null && looksLikeKeyFor(activeProvider, clipText)) {
            clipText
        } else {
            null
        }
    }

    LifecycleResumeEffect(activeProvider) {
        checkClipboard()
        onPauseOrDispose { }
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
            Text("AI assistant", style = MaterialTheme.typography.titleMedium)
        }

        Text(
            // No mention of Google Sign-In: it is disabled, and naming a way in
            // that does not exist is worse than naming none. See
            // GoogleAuthManager.SIGN_IN_ENABLED.
            text = "Bring a key from one of these services. Keys are encrypted by the " +
                "device's hardware keystore and sent only to the service you pick.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // == The picker ======================================================

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
                        // The store first: everything below reads it on the
                        // recomposition this line causes.
                        keys.setActiveProvider(provider)
                        activeProvider = provider
                    },
                    label = { Text(provider.displayName) },
                    // Ready, not merely keyed: a Custom key with no address
                    // is a provider the chat cannot use. ApiKeyStore.isReady.
                    leadingIcon = if (keys.isReady(provider)) {
                        {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "${provider.displayName} is ready",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        // == What the picker selects =========================================

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(
                    // The selected chip's own word, on purpose. See the class
                    // comment: this is what scopes the rows below it.
                    text = activeProvider.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )

                // The Google card is a real second way in, so it stays a card --
                // but only when it can do something. Rendering a signed-out card
                // with no button, which is what SIGN_IN_ENABLED = false leaves,
                // advertises a route that does not exist. It sits inside the
                // provider's surface because it is Gemini's, not the screen's.
                if (activeProvider == AiProviderType.GEMINI &&
                    (isGoogleSignedIn || GoogleAuthManager.SIGN_IN_ENABLED)
                ) {
                    GoogleAccount(
                        signedIn = isGoogleSignedIn,
                        email = googleEmail,
                        scopes = googleScopes,
                        onSignIn = {
                            val authManager = GoogleAuthManager(keys)
                            val request = authManager.createAuthorizationRequest()
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(request.authUrl)),
                            )
                        },
                        onSignOut = {
                            keys.signOutGoogle()
                            isGoogleSignedIn = false
                            googleEmail = null
                            googleScopes = null
                            saved = keys.hasProviderKey(activeProvider)
                        },
                    )
                }

                // The on-device provider's equivalent of the key field: it is
                // configured by downloading a model and starting a process, not
                // by pasting a secret, so the control replaces the key rather
                // than sitting beside it. Inside the provider's surface for the
                // reason Gemini's card is.
                if (activeProvider == AiProviderType.LOCAL) {
                    LocalServerControl(
                        server = koinInject(),
                        toolchain = koinInject(),
                        keys = keys,
                    )
                }

                // -- Key ---------------------------------------------------

                // **No key row for the on-device provider.** A field labelled
                // Key that can never be filled is a question with no right
                // answer, and the tick beside it would never light.
                if (activeProvider != AiProviderType.LOCAL) {
                    ProviderProperty(
                        label = "Key",
                        value = if (saved) "Saved on this device" else "Not set",
                        expanded = editingKey,
                        onToggle = { editingKey = !editingKey },
                    ) {
                        providerConsoleUrl(activeProvider)?.let { url ->
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = providerConsoleLabel(activeProvider),
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }

                        val detectedKey = clipboardKey
                        if (detectedKey != null && detectedKey != draft) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            text = "Found ${activeProvider.displayName} key in clipboard",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                        Text(
                                            text = detectedKey.take(8) + "..." + detectedKey.takeLast(4),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                        )
                                    }
                                    Button(
                                        onClick = { draft = detectedKey },
                                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                                    ) {
                                        Text("Use key")
                                    }
                                }
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
                            visualTransformation = if (revealed) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { revealed = !revealed }) {
                                    Icon(
                                        if (revealed) {
                                            Icons.Default.VisibilityOff
                                        } else {
                                            Icons.Default.Visibility
                                        },
                                        contentDescription = if (revealed) "Hide key" else "Show key",
                                    )
                                }
                            },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = {
                                    val trimmed = draft.trim()
                                    when (activeProvider) {
                                        // Only Anthropic writes the legacy slot.
                                        // Every provider used to write it, so saving
                                        // a Gemini key put that key in Anthropic's
                                        // store and lit Anthropic's tick.
                                        AiProviderType.ANTHROPIC -> keys.save(trimmed)
                                        AiProviderType.GEMINI -> keys.saveGeminiApiKey(trimmed)
                                        AiProviderType.OPENAI -> keys.saveOpenAiApiKey(trimmed)
                                        AiProviderType.CUSTOM -> keys.saveCustomApiKey(trimmed)
                                        // Nothing to save. The server is on this
                                        // phone's loopback and authenticates nobody;
                                        // the key field is not shown for it.
                                        AiProviderType.LOCAL -> Unit
                                    }
                                    saved = true
                                    draft = ""
                                    revealed = false
                                    editingKey = false
                                },
                                enabled = draft.isNotBlank(),
                            ) { Text("Save key") }

                            TextButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clipText = clipboard?.primaryClip?.let { clip ->
                                        if (clip.itemCount > 0) clip.getItemAt(0)?.text?.toString() else null
                                    }?.trim().orEmpty()
                                    if (clipText.isNotEmpty()) {
                                        draft = clipText
                                    }
                                },
                            ) { Text("Paste") }

                            if (saved) {
                                TextButton(
                                    onClick = {
                                        keys.clearProviderKey(activeProvider)
                                        saved = keys.hasProviderKey(activeProvider)
                                        draft = ""
                                        // Left open: there is now nothing stored,
                                        // and the field is what to do about it.
                                        editingKey = true
                                    },
                                ) { Text("Remove") }
                            }
                        }
                    }
                }

                PropertyDivider()

                // -- Model -------------------------------------------------

                var showModelDropdown by remember { mutableStateOf(false) }
                Box {
                    ProviderProperty(
                        label = "Model",
                        value = activeModel,
                        // The menu covers the row, so there is no expanded
                        // state to show underneath it.
                        expanded = false,
                        onToggle = { showModelDropdown = true },
                        rowSemantics = "Model",
                    )
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

                PropertyDivider()

                // -- Endpoint ----------------------------------------------

                ProviderProperty(
                    label = "Endpoint",
                    value = endpointValue(activeProvider, storedEndpoint),
                    detail = endpointDetail(activeProvider, storedEndpoint),
                    expanded = editingEndpoint,
                    // GeminiAiClient takes no base URL, so a field here would be
                    // one that accepts an address and ignores it. The row still
                    // appears, saying where Gemini goes: a row that is missing
                    // reads as a setting hidden somewhere else.
                    onToggle = if (activeProvider == AiProviderType.GEMINI) {
                        null
                    } else {
                        { editingEndpoint = !editingEndpoint }
                    },
                ) {
                    OutlinedTextField(
                        value = endpointDraft,
                        onValueChange = { endpointDraft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "API endpoint" },
                        label = { Text("API endpoint") },
                        placeholder = { Text("https://my-proxy.local") },
                        singleLine = true,
                        isError = endpoint is Endpoint.Rejected,
                        supportingText = {
                            Text(
                                text = when (endpoint) {
                                    is Endpoint.Rejected -> endpoint.reason
                                    is Endpoint.Custom -> "Your key will be sent to this address."
                                    Endpoint.Default ->
                                        "Leave blank for the default service endpoint."
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

        // == What the picker does not select =================================

        HorizontalDivider(Modifier.padding(top = 6.dp))

        Text(
            // The fix for this screen's worst reading. One preference, one
            // heading, outside the card -- so it cannot be read as Anthropic's.
            text = "Applies to every assistant",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Weighted, or the Switch is measured in what a two-line detail
            // leaves and draws at zero width. CLAUDE.md, seven instances.
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Share project context", style = MaterialTheme.typography.bodyLarge)
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
                modifier = Modifier.semantics { contentDescription = "Share project context" },
            )
        }
    }
}

/**
 * One property of the selected provider: what it is, what it is set to, and a
 * way to change it.
 *
 * **Values live under their label, never to the right of it.** The right-hand
 * slot holds a fixed-size chevron and nothing else, because a variable-width
 * value opposite a variable-width label is the row this codebase has shipped
 * broken seven times -- a `Row` squeezes rather than overflows, and the half
 * that loses is whichever one is not weighted. Here the label column is
 * weighted and the only other child cannot be compressed, so there is no width
 * for the layout to get wrong. [ProviderPropertyTest] asserts it by comparing
 * this chevron against the same chevron in a row whose label is one word.
 */
@Composable
private fun ProviderProperty(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    rowSemantics: String? = null,
    editor: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
                .then(
                    if (rowSemantics != null) {
                        Modifier.semantics { contentDescription = rowSemantics }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Two lines, not one: an endpoint is the value most likely
                    // to be long, and a URL ellipsised in the middle is worse
                    // than a URL on two lines.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onToggle != null) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { editor() }
        }
    }
}

/** Inset, so it separates the rows without cutting the card in half. */
@Composable
private fun PropertyDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun GoogleAccount(
    signedIn: Boolean,
    email: String?,
    scopes: String?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (signedIn) {
                        "Signed in as ${email ?: "a Google Account"}"
                    } else {
                        "Google Account"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                // **The scopes Google granted, not the ones asked for.** A
                // dropped scope is invisible until the first request fails with
                // ACCESS_TOKEN_SCOPE_INSUFFICIENT, which names the method and
                // not the scope -- and a toast was truncated on the device this
                // was diagnosed on, which emits no logcat either. It lives here
                // because here it can be read.
                if (signedIn) {
                    Text(
                        text = "Granted: " + (
                            scopes
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

        if (signedIn) {
            Text(
                "Gemini does not accept a Google sign-in for chat requests. " +
                    "Add a key below instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onSignOut) { Text("Sign out") }
        } else {
            Button(onClick = onSignIn) { Text("Sign in with Google") }
        }
    }
}

private fun keyPlaceholder(provider: AiProviderType): String = when (provider) {
    AiProviderType.GEMINI -> "AIza..."
    AiProviderType.ANTHROPIC -> "sk-ant-..."
    AiProviderType.OPENAI -> "sk-..."
    AiProviderType.CUSTOM -> "Whatever your service issues"
    AiProviderType.LOCAL -> "No key needed"
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
    AiProviderType.CUSTOM -> "Any OpenAI-compatible service."
    AiProviderType.LOCAL -> "None. The model runs on this phone."
}

/** What the endpoint row reads when it is closed, which is most of the time. */
private fun endpointValue(provider: AiProviderType, stored: String): String = when {
    provider == AiProviderType.GEMINI -> "Google's own, and fixed"
    stored.isNotEmpty() -> stored
    else -> "Default"
}

/**
 * The sentence under the endpoint, where there is one worth reading.
 *
 * **The last case was a real defect this row made visible.** `Assistant`
 * built Custom out of `OpenAiClient`, which fell back to `DEFAULT_BASE_URL`
 * when given no address -- so picking Custom and stopping there sent the key to
 * OpenAI. This row said so, and then a phone did exactly that: a 404 from
 * OpenAI for `llama3.3:70b`. Custom now sends nothing without an address
 * (`ApiKeyStore.isReady`), and the row says that instead.
 */
private fun endpointDetail(provider: AiProviderType, stored: String): String? = when {
    provider == AiProviderType.GEMINI ->
        "Pick Custom to send requests to a service of your own."

    stored.isNotEmpty() -> "Your key is sent here."

    provider == AiProviderType.CUSTOM ->
        "Set one to use Custom. Nothing is sent until you do."

    else -> "Requests go to ${provider.displayName}'s own API."
}

internal fun providerConsoleUrl(provider: AiProviderType): String? = when (provider) {
    AiProviderType.GEMINI -> "https://aistudio.google.com/app/apikey"
    AiProviderType.ANTHROPIC -> "https://console.anthropic.com/settings/keys"
    AiProviderType.OPENAI -> "https://platform.openai.com/api-keys"
    AiProviderType.CUSTOM -> null
    AiProviderType.LOCAL -> null
}

internal fun providerConsoleLabel(provider: AiProviderType): String = when (provider) {
    AiProviderType.GEMINI -> "Get key from Google AI Studio"
    AiProviderType.ANTHROPIC -> "Get key from Anthropic Console"
    AiProviderType.OPENAI -> "Get key from OpenAI Platform"
    AiProviderType.CUSTOM -> "Get key from provider"
    AiProviderType.LOCAL -> "No key needed"
}

internal fun looksLikeKeyFor(provider: AiProviderType, text: CharSequence): Boolean {
    val trimmed = text.toString().trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
    return when (provider) {
        AiProviderType.GEMINI ->
            (trimmed.startsWith("AIzaSy") || trimmed.startsWith("AIza")) && trimmed.length in 35..45
        AiProviderType.ANTHROPIC ->
            trimmed.startsWith("sk-ant-") && trimmed.length >= 40
        AiProviderType.OPENAI ->
            trimmed.startsWith("sk-") && !trimmed.startsWith("sk-ant-") && trimmed.length >= 40
        AiProviderType.CUSTOM ->
            false
        // There is no key shape to recognise, and nowhere to paste one.
        AiProviderType.LOCAL ->
            false
    }
}
