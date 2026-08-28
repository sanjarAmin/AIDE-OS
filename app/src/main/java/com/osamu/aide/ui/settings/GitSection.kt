package com.osamu.aide.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.osamu.aide.vcs.git.GitCredentialStore
import com.osamu.aide.vcs.git.GitIdentity
import com.osamu.aide.vcs.git.GitIdentityStore

/**
 * Where the committing identity and host access tokens are entered.
 *
 * Both exist because a device has neither. `FS.detect()` reports no user home
 * and no system config, so there is no `~/.gitconfig` for JGit to read and no
 * credential helper to ask -- `tools/git/FINDINGS.md` finding 1. Without this
 * screen a commit cannot be attributed and a push cannot authenticate.
 *
 * The asymmetry between the two halves is deliberate and mirrors
 * [ApiKeySection]. **The identity is shown back; the token never is.** A name
 * and an email are printed into every commit and pushed to a public host, so
 * hiding them would imply a secrecy they do not have -- and an identity the
 * user cannot see is one they cannot notice is wrong, having already signed
 * commits with it. A token is the opposite on every count.
 *
 * Identity is validated before it is offered for saving rather than after,
 * because the failure it prevents is not recoverable by editing a setting:
 * commits are already signed by then, and fixing authorship means rewriting
 * history.
 */
@Composable
fun GitSection(
    identities: GitIdentityStore,
    credentials: GitCredentialStore,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text("Git", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "A phone has no global git config, so the name on your commits and " +
                "any access token live here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        IdentityFields(identities)
        TokenFields(credentials)
    }
}

@Composable
private fun IdentityFields(identities: GitIdentityStore) {
    var stored by remember { mutableStateOf(identities.read()) }
    var name by remember { mutableStateOf(stored?.name.orEmpty()) }
    var email by remember { mutableStateOf(stored?.email.orEmpty()) }

    val draft = GitIdentity(name, email)
    // Only complained about once there is something to complain about: an
    // empty form on first open is not a mistake the user has made yet.
    val untouched = name.isBlank() && email.isBlank()
    // Per field, so the one that is wrong is the one that turns red. A single
    // combined check reddened the name because the email was empty.
    val nameProblem = if (untouched) null else draft.nameProblem()
    val emailProblem = if (untouched) null else draft.emailProblem()
    val problem = nameProblem ?: emailProblem
    val changed = draft.trimmed() != stored

    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Name") },
        singleLine = true,
        isError = nameProblem != null,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Git name" },
    )
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("Email") },
        singleLine = true,
        isError = emailProblem != null,
        supportingText = {
            Text(
                text = problem
                    ?: "Written into every commit. Hosts match commits to accounts by the email.",
            )
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            .semantics { contentDescription = "Git email" },
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stored != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "  Commits are signed as ${stored?.name}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            Text(
                text = "No identity set, so committing is blocked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            enabled = problem == null && changed && name.isNotBlank() && email.isNotBlank(),
            onClick = {
                if (identities.save(draft) == null) {
                    stored = identities.read()
                    // Rewritten with what was actually stored, so the trimming
                    // is visible rather than silent -- the same reason the
                    // endpoint field is written back in ApiKeySection.
                    name = stored?.name.orEmpty()
                    email = stored?.email.orEmpty()
                }
            },
            modifier = Modifier.semantics { contentDescription = "Save git identity" },
        ) { Text("Save") }
    }
}

@Composable
private fun TokenFields(credentials: GitCredentialStore) {
    val hosts = remember { credentials.hosts().toMutableStateList() }
    var host by remember { mutableStateOf("github.com") }
    var token by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }

    Text(
        text = "Access tokens",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
    Text(
        text = "One per host, not per repository. Encrypted with a key held in the " +
            "Android Keystore.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    hosts.forEach { saved ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(saved, style = MaterialTheme.typography.bodyMedium)
            IconButton(
                onClick = {
                    credentials.forget(saved)
                    hosts.remove(saved)
                },
                modifier = Modifier.semantics { contentDescription = "Remove token for $saved" },
            ) { Icon(Icons.Filled.Close, contentDescription = null) }
        }
    }

    OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { Text("Host") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            .semantics { contentDescription = "Git host" },
    )
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text("Access token") },
        singleLine = true,
        visualTransformation =
            if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    imageVector =
                        if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (revealed) "Hide token" else "Show token",
                )
            }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            .semantics { contentDescription = "Git access token" },
    )

    Button(
        enabled = host.isNotBlank() && token.isNotBlank(),
        onClick = {
            val normalised = host.trim().lowercase()
            credentials.save(normalised, token)
            if (normalised !in hosts) hosts.add(normalised)
            hosts.sort()
            // Cleared rather than left on screen. The field is the only place
            // the plaintext exists after this, and a token sitting in a
            // recomposed text field outlives the screen in the recents preview.
            token = ""
            revealed = false
        },
        modifier = Modifier.padding(top = 8.dp)
            .semantics { contentDescription = "Save git token" },
    ) { Text("Save token") }
}
