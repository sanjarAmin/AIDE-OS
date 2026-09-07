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
import androidx.compose.material3.HorizontalDivider
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
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.editor.EditorPreferences
import com.osamu.aide.toolchain.manager.ToolchainManager
import com.osamu.aide.vcs.git.GitCredentialStore
import com.osamu.aide.vcs.git.GitIdentityStore
import org.koin.compose.koinInject

private data class SettingsSection(val title: String, val summary: String)

/**
 * Settings.
 *
 * Five of these sections work and two do not exist yet. They used to be drawn
 * identically -- a title in `titleMedium` over a grey summary, whether or not
 * anything was behind it -- so the only way to learn that "Build" was a
 * description of the future was to tap it and have nothing happen.
 *
 * The unbuilt ones now sit below a heading that says so, and carry the muted
 * colour of disabled content. Listing them at all is still right: a person
 * looking for the font size should find out it is coming, not conclude the
 * setting is hidden somewhere they have not looked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val sections = listOf(
        SettingsSection("Build", "Fast or Gradle engine, JDK level, signing keys."),
        SettingsSection("Themes", "A colour scheme for the editor, light and dark."),
    )

    val keys = koinInject<ApiKeyStore>()
    val identities = koinInject<GitIdentityStore>()
    val credentials = koinInject<GitCredentialStore>()
    val toolchain = koinInject<ToolchainManager>()
    val dispatchers = koinInject<DispatcherProvider>()
    val editorPreferences = koinInject<EditorPreferences>()

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
            item { HorizontalDivider() }
            item { GitSection(identities, credentials) }
            item { HorizontalDivider() }
            item { ToolchainSection(toolchain, dispatchers) }
            item { HorizontalDivider() }
            item { EditorSection(editorPreferences) }
            item { HorizontalDivider() }
            item { AboutSection() }

            item {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp)) {
                    HorizontalDivider()
                    Text(
                        text = "Not built yet",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                    Text(
                        text = "Listed so you know where these will live. Nothing here " +
                            "responds yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(sections) { section ->
                // Drawn in the colour Material uses for content that cannot be
                // acted on, so the difference from a live section is visible
                // before it is read.
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                    Text(
                        text = section.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }
            }
        }
    }
}
