package com.osamu.aide.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.osamu.aide.ai.core.ApiKeyStore
import com.osamu.aide.vcs.git.GitCredentialStore
import com.osamu.aide.vcs.git.GitIdentityStore
import org.koin.compose.koinInject

private data class SettingsSection(val title: String, val summary: String)

/**
 * Settings.
 *
 * Only the assistant's key is a real control; the rest is still an honest list
 * of what is coming, because a toggle that silently does nothing is worse than
 * an entry that says it does not exist yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val sections = listOf(
        SettingsSection("Editor", "Font size, tab width, line numbers, colour scheme."),
        SettingsSection("Build", "Fast or Gradle engine, JDK level, signing keys."),
        SettingsSection("Toolchains", "Download and manage aapt2, Kotlin and the NDK."),
        SettingsSection("About", "AIDE-OS, an on-device IDE for phones and tablets."),
    )

    val keys = koinInject<ApiKeyStore>()
    val identities = koinInject<GitIdentityStore>()
    val credentials = koinInject<GitCredentialStore>()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { ApiKeySection(keys) }
            item { GitSection(identities, credentials) }

            items(sections) { section ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = section.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
