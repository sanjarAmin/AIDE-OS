package com.osamu.aide.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.osamu.aide.engine.fast.ReleaseKeyInfo
import com.osamu.aide.engine.fast.ReleaseKeystoreStore
import java.text.DateFormat

/**
 * The key a release APK is signed with, which is the user's to keep.
 *
 * **Backing it up is the loudest thing here, on purpose.** Android identifies
 * an app by its signing certificate: lose this file and a published app can
 * never be updated again, only republished under a new name. The generate path
 * exists because the project's premise is that a phone is enough -- and it is
 * only defensible if getting the key back off the phone is one tap, which is
 * what Export is.
 */
@Composable
fun SigningSection(store: ReleaseKeystoreStore, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var info by remember { mutableStateOf(store.describe()) }
    var configured by remember { mutableStateOf(store.isConfigured()) }
    var message by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    var confirmingRemoval by remember { mutableStateOf(false) }

    fun refresh() {
        configured = store.isConfigured()
        info = store.describe()
    }

    val importFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) prompt = Prompt.Import(uri.toString())
    }

    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-pkcs12"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { store.exportTo(it) }
            "Keystore exported. Keep it somewhere that is not this phone."
        }.getOrElse { "Could not export the keystore: ${it.message}" }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Key, contentDescription = null)
            Text("Release signing", style = MaterialTheme.typography.titleMedium)
        }

        Text(
            text = "Debug builds are signed with a key this device made and cannot " +
                "export. A release build needs one of yours, which has to outlive " +
                "the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (configured) {
            KeySummary(info)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { exportFile.launch("aide-os-release.p12") },
                    modifier = Modifier.semantics { contentDescription = "Export the keystore" },
                ) { Text("Export") }
                TextButton(onClick = { confirmingRemoval = true }) { Text("Remove") }
            }
            if (info == null) {
                Text(
                    text = "The passphrase is not remembered, so a release build will " +
                        "ask for it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { prompt = Prompt.Generate },
                    modifier = Modifier.semantics { contentDescription = "Generate a release key" },
                ) { Text("Generate") }
                TextButton(
                    // Anything: a .jks or .p12 has no reliable MIME type, and
                    // a filter that guesses wrong hides the file the user came
                    // to pick.
                    onClick = { importFile.launch(arrayOf("*/*")) },
                ) { Text("Import") }
            }
        }

        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    prompt?.let { pending ->
        PassphraseDialog(
            prompt = pending,
            onDismiss = { prompt = null },
            onConfirm = { passphrase, name, remember ->
                message = runCatching {
                    when (pending) {
                        Prompt.Generate -> {
                            store.generate(passphrase, name, remember)
                            "Release key created. **Export it now** -- if this phone is " +
                                "lost, so is the ability to update anything signed with it."
                        }

                        is Prompt.Import -> {
                            context.contentResolver
                                .openInputStream(android.net.Uri.parse(pending.uri))
                                ?.use { store.import(it, passphrase, remember) }
                            "Keystore imported."
                        }
                    }
                }.getOrElse { it.message ?: "That did not work." }
                prompt = null
                refresh()
            },
        )
    }

    if (confirmingRemoval) {
        AlertDialog(
            onDismissRequest = { confirmingRemoval = false },
            title = { Text("Remove the release key?") },
            text = {
                Text(
                    "This deletes the keystore from the device. Anything already " +
                        "published with it can only be updated by a build signed with " +
                        "the same key -- so do not do this unless you have a copy.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.remove()
                        confirmingRemoval = false
                        message = "Release key removed."
                        refresh()
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemoval = false }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun KeySummary(info: ReleaseKeyInfo?) {
    if (info == null) {
        Text(
            text = "A release key is set up.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    Column {
        Text(info.commonName, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "Alias ${info.alias} · expires " +
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(info.expiresOn),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the dialog is being opened for. */
private sealed interface Prompt {
    data object Generate : Prompt
    data class Import(val uri: String) : Prompt
}

@Composable
private fun PassphraseDialog(
    prompt: Prompt,
    onDismiss: () -> Unit,
    onConfirm: (passphrase: CharArray, commonName: String, remember: Boolean) -> Unit,
) {
    // TextFieldState rather than a value/onValueChange pair: a constant
    // TextFieldValue never tells the IME anything changed, which is the bug the
    // terminal shipped with.
    val passphrase = rememberTextFieldState()
    val name = rememberTextFieldState(initialText = "AIDE-OS")
    var remember by remember { mutableStateOf(true) }
    val generating = prompt is Prompt.Generate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (generating) "New release key" else "Import a keystore") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (generating) {
                    OutlinedTextField(
                        state = name,
                        label = { Text("Name on the certificate") },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        modifier = Modifier.fillMaxWidth()
                            .semantics { contentDescription = "Certificate name" },
                    )
                }
                OutlinedTextField(
                    state = passphrase,
                    label = { Text("Passphrase") },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    // One field, because PKCS#12 has one password: keytool
                    // refuses a separate key passphrase out loud, and offering
                    // two here would promise a distinction the format cannot
                    // keep. See ReleaseSigningKey.load.
                    supportingText = {
                        Text(
                            if (generating) {
                                "Used for the file and the key. There is no way to " +
                                    "recover it."
                            } else {
                                "The password this keystore was made with."
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                        .semantics { contentDescription = "Keystore passphrase" },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text(
                        text = "Remember it, so a release build does not ask",
                        style = MaterialTheme.typography.bodySmall,
                        // Weighted: the label is the variable half and the
                        // checkbox must keep its size. CLAUDE.md, seven times.
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = passphrase.text.isNotBlank(),
                onClick = {
                    onConfirm(
                        passphrase.text.toString().toCharArray(),
                        name.text.toString().ifBlank { "AIDE-OS" },
                        remember,
                    )
                },
                modifier = Modifier.semantics { contentDescription = "Confirm the passphrase" },
            ) { Text(if (generating) "Create" else "Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
