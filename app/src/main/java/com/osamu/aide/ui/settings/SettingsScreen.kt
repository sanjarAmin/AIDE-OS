package com.osamu.aide.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.osamu.aide.ai.core.ApiKeyStore
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.editor.EditorPreferences
import com.osamu.aide.engine.fast.ReleaseKeystoreStore
import com.osamu.aide.toolchain.manager.ToolchainManager
import com.osamu.aide.vcs.git.GitCredentialStore
import com.osamu.aide.vcs.git.GitIdentityStore
import org.koin.compose.koinInject

/**
 * The categories the IDE's settings are organized into.
 */
enum class SettingsCategory(
    val title: String,
    val shortTitle: String,
    val subtitle: String,
    val icon: ImageVector,
    val routeKey: String,
) {
    EDITOR(
        title = "Editor & Appearance",
        shortTitle = "Editor",
        subtitle = "Font size, color theme, tab width, and line wrapping",
        icon = Icons.Default.Edit,
        routeKey = "editor",
    ),
    AI(
        title = "AI Assistant",
        shortTitle = "AI Assistant",
        subtitle = "Gemini, Anthropic & OpenAI keys, models, and endpoints",
        icon = Icons.Default.AutoAwesome,
        routeKey = "ai",
    ),
    GIT(
        title = "Git & Version Control",
        shortTitle = "Git",
        subtitle = "Committer name, email, and personal access tokens",
        icon = Icons.Default.AccountTree,
        routeKey = "git",
    ),
    TOOLCHAINS(
        title = "Toolchains & Storage",
        shortTitle = "Toolchains",
        subtitle = "Downloaded compilers, runtimes, and disk usage",
        icon = Icons.Default.Build,
        routeKey = "toolchains",
    ),
    SIGNING(
        title = "Release Signing",
        shortTitle = "Signing",
        subtitle = "Release keystore, certificates, and APK signing",
        icon = Icons.Default.Key,
        routeKey = "signing",
    ),
    ABOUT(
        title = "About & Licenses",
        shortTitle = "About",
        subtitle = "AIDE-OS version, GPLv3 license, and open-source notices",
        icon = Icons.Default.Info,
        routeKey = "about",
    );

    companion object {
        fun fromRouteKey(key: String?): SettingsCategory? {
            if (key.isNullOrBlank()) return null
            return entries.firstOrNull {
                it.routeKey.equals(key, ignoreCase = true) || it.name.equals(key, ignoreCase = true)
            }
        }
    }
}

/**
 * Categorized and adaptive Settings screen.
 *
 * - On phones: A quick category filter chip row at the top with cleanly grouped cards,
 *   supporting single-category focus and deep linking.
 * - On tablets/foldables: A responsive two-pane Master-Detail layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    initialCategory: SettingsCategory? = null,
) {
    val keys = koinInject<ApiKeyStore>()
    val identities = koinInject<GitIdentityStore>()
    val credentials = koinInject<GitCredentialStore>()
    val toolchain = koinInject<ToolchainManager>()
    val dispatchers = koinInject<DispatcherProvider>()
    val editorPreferences = koinInject<EditorPreferences>()
    val releaseKeystore = koinInject<ReleaseKeystoreStore>()

    var selectedCategory by remember(initialCategory) { mutableStateOf(initialCategory) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = if (!isWide && selectedCategory != null) {
                                selectedCategory!!.title
                            } else {
                                "Settings"
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (!isWide && selectedCategory != null) {
                                    selectedCategory = null
                                } else {
                                    onNavigateBack()
                                }
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            if (isWide) {
                // == Two-pane Master-Detail on tablets & foldables ====================
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .width(280.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        item {
                            Text(
                                text = "Categories",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                        items(SettingsCategory.entries, key = { it.name }) { category ->
                            val isSelected = (selectedCategory ?: SettingsCategory.EDITOR) == category
                            Surface(
                                selected = isSelected,
                                onClick = { selectedCategory = category },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        imageVector = category.icon,
                                        contentDescription = null,
                                        tint = if (isSelected) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = category.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                        Text(
                                            text = category.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    VerticalDivider()

                    AnimatedContent(
                        targetState = selectedCategory ?: SettingsCategory.EDITOR,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(150))
                        },
                        label = "settingsDetailPane",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) { targetCat ->
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            item {
                                when (targetCat) {
                                    SettingsCategory.EDITOR -> EditorSection(editorPreferences)
                                    SettingsCategory.AI -> ApiKeySection(keys)
                                    SettingsCategory.GIT -> GitSection(identities, credentials)
                                    SettingsCategory.TOOLCHAINS -> {
                                        ToolchainSection(toolchain, dispatchers)
                                        ToolchainRoadmapCard()
                                    }
                                    SettingsCategory.SIGNING -> SigningSection(releaseKeystore)
                                    SettingsCategory.ABOUT -> AboutSection()
                                }
                            }
                        }
                    }
                }
            } else {
                // == Single-pane with category filter tabs on phones ==================
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    CategoryTabs(
                        selected = selectedCategory,
                        onSelect = { selectedCategory = it },
                    )

                    AnimatedContent(
                        targetState = selectedCategory,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(150))
                        },
                        label = "settingsPhoneContent",
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) { targetCat ->
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 24.dp),
                        ) {
                            when (targetCat) {
                                null -> {
                                    // "All" mode: display all categories cleanly in logical order
                                    item {
                                        SettingsCard { EditorSection(editorPreferences) }
                                    }
                                    item {
                                        SettingsCard { ApiKeySection(keys) }
                                    }
                                    item {
                                        SettingsCard { GitSection(identities, credentials) }
                                    }
                                    item {
                                        SettingsCard {
                                            ToolchainSection(toolchain, dispatchers)
                                            ToolchainRoadmapCard()
                                        }
                                    }
                                    item {
                                        SettingsCard { SigningSection(releaseKeystore) }
                                    }
                                    item {
                                        SettingsCard { AboutSection() }
                                    }
                                }
                                SettingsCategory.EDITOR -> {
                                    item { EditorSection(editorPreferences) }
                                }
                                SettingsCategory.AI -> {
                                    item { ApiKeySection(keys) }
                                }
                                SettingsCategory.GIT -> {
                                    item { GitSection(identities, credentials) }
                                }
                                SettingsCategory.TOOLCHAINS -> {
                                    item {
                                        ToolchainSection(toolchain, dispatchers)
                                        ToolchainRoadmapCard()
                                    }
                                }
                                SettingsCategory.SIGNING -> {
                                    item { SigningSection(releaseKeystore) }
                                }
                                SettingsCategory.ABOUT -> {
                                    item { AboutSection() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTabs(
    selected: SettingsCategory?,
    onSelect: (SettingsCategory?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("All") },
            )
        }
        items(SettingsCategory.entries, key = { it.name }) { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(category) },
                leadingIcon = {
                    Icon(
                        imageVector = category.icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                label = { Text(category.shortTitle) },
            )
        }
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        content()
    }
}

@Composable
private fun ToolchainRoadmapCard(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(18.dp)
                    .padding(top = 2.dp),
            )
            Column {
                Text(
                    text = "Upcoming: Project JDK Level",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Configurable Java target levels per project are planned for an upcoming toolchain release.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
