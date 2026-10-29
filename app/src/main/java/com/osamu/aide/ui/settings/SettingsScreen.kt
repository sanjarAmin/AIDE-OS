package com.osamu.aide.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osamu.aide.ai.core.ApiKeyStore
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.editor.EditorPreferences
import com.osamu.aide.engine.fast.ReleaseKeystoreStore
import com.osamu.aide.toolchain.manager.ToolchainManager
import com.osamu.aide.vcs.git.GitCredentialStore
import com.osamu.aide.vcs.git.GitIdentityStore
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * The categories the IDE's settings are organized into.
 *
 * [subtitle] is what a category's page says under its icon, so it describes
 * what the page is for rather than listing its controls -- the hub row above
 * it already says what each is set to.
 */
enum class SettingsCategory(
    val title: String,
    val shortTitle: String,
    val subtitle: String,
    val icon: ImageVector,
    val routeKey: String,
) {
    EDITOR(
        title = "Editor & appearance",
        shortTitle = "Editor",
        subtitle = "How code looks while you write it. Every change shows in the preview first.",
        icon = Icons.Default.Edit,
        routeKey = "editor",
    ),
    AI(
        title = "AI assistant",
        shortTitle = "AI assistant",
        subtitle = "Which service answers in the chat panel, and the key or model it uses.",
        icon = Icons.Default.AutoAwesome,
        routeKey = "ai",
    ),
    GIT(
        title = "Git & version control",
        shortTitle = "Git",
        subtitle = "The name on your commits, and the tokens that let a push sign in.",
        icon = Icons.Default.AccountTree,
        routeKey = "git",
    ),
    TOOLCHAINS(
        title = "Toolchains & storage",
        shortTitle = "Toolchains",
        subtitle = "Compilers and runtimes this app downloaded, largest first.",
        icon = Icons.Default.Build,
        routeKey = "toolchains",
    ),
    SIGNING(
        title = "Release signing",
        shortTitle = "Signing",
        subtitle = "The key a release APK is signed with. It has to outlive this phone.",
        icon = Icons.Default.Key,
        routeKey = "signing",
    ),
    ABOUT(
        title = "About & licences",
        shortTitle = "About",
        subtitle = "The version that is running, its licence, and what it is built from.",
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
 * How the hub groups the categories, by what the user is doing when they need one.
 *
 * About is not in a group: it is not a setting, and it sits apart at the foot.
 */
private val HUB_GROUPS = listOf(
    "Writing code" to listOf(SettingsCategory.EDITOR, SettingsCategory.AI, SettingsCategory.GIT),
    "Building & shipping" to listOf(SettingsCategory.TOOLCHAINS, SettingsCategory.SIGNING),
)

/**
 * Settings, as a hub of categories that each say what they are set to.
 *
 * **The hub replaces "All".** The phone layout used to open on every section
 * at once -- editor, four AI providers, git, nine toolchains, signing and the
 * licence, one under another -- behind a row of filter chips that scrolled off
 * the edge. Finding the git token meant scrolling past the editor's slider.
 * Now the first screen is six rows, and each row's second line is the answer
 * to the question that usually brings someone here: *which provider is the
 * chat using, who am I committing as, how much space are the downloads
 * taking*. Every line is read from the store it describes, so none of them can
 * be a decoration that is always green.
 *
 * - On phones: the hub, and a page per category behind it.
 * - On tablets and foldables: the hub as a list beside the selected page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    initialCategory: SettingsCategory? = null,
    /** Opens the local model benchmark, a preview tool under the AI settings. */
    onOpenBenchmark: () -> Unit = {},
) {
    val keys = koinInject<ApiKeyStore>()
    val identities = koinInject<GitIdentityStore>()
    val credentials = koinInject<GitCredentialStore>()
    val toolchain = koinInject<ToolchainManager>()
    val dispatchers = koinInject<DispatcherProvider>()
    val editorPreferences = koinInject<EditorPreferences>()
    val releaseKeystore = koinInject<ReleaseKeystoreStore>()

    var selectedCategory by remember(initialCategory) { mutableStateOf(initialCategory) }
    // **Where Back goes depends on how the page was reached.** Opened from the
    // hub, Back returns to the hub. Opened by a link from the workspace --
    // "add a key", "set your git identity" -- Back returns to the workspace,
    // because that is where the user was and what they were in the middle of;
    // stopping at a hub they never saw would be a screen in the way.
    var openedFromHub by remember(initialCategory) { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp
        val showingPage = !isWide && selectedCategory != null

        // The system Back gesture, which this screen used to ignore: from a
        // category it left Settings altogether while the arrow went to "All".
        BackHandler(enabled = showingPage && openedFromHub) { selectedCategory = null }

        val summaries = rememberSummaries(
            // Re-read whenever the hub comes back into view, since the page
            // just left may have changed what it describes.
            refreshKey = if (isWide) selectedCategory else showingPage,
            hubVisible = isWide || !showingPage,
            keys = keys,
            identities = identities,
            credentials = credentials,
            toolchain = toolchain,
            releaseKeystore = releaseKeystore,
            editorPreferences = editorPreferences,
            dispatchers = dispatchers,
        )

        val page: @Composable (SettingsCategory, Boolean) -> Unit = { category, showTitle ->
            CategoryPage(category, showTitle) {
                when (category) {
                    SettingsCategory.EDITOR -> EditorSection(editorPreferences)
                    SettingsCategory.AI -> Column(verticalArrangement = PanelSpacing) {
                        ApiKeySection(keys)
                        SettingsPanel(Modifier.padding(horizontal = 16.dp)) {
                            LocalModelBenchmarkEntry(onOpenBenchmark)
                        }
                    }
                    SettingsCategory.GIT -> SettingsPanel(Modifier.padding(horizontal = 16.dp)) {
                        GitSection(identities, credentials)
                    }
                    SettingsCategory.TOOLCHAINS -> Column(verticalArrangement = PanelSpacing) {
                        SettingsPanel(Modifier.padding(horizontal = 16.dp)) {
                            ToolchainSection(toolchain, dispatchers)
                        }
                        ToolchainRoadmapNote()
                    }
                    SettingsCategory.SIGNING -> SettingsPanel(Modifier.padding(horizontal = 16.dp)) {
                        SigningSection(releaseKeystore)
                    }
                    SettingsCategory.ABOUT -> AboutSection()
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = if (showingPage) selectedCategory!!.title else "Settings",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (showingPage && openedFromHub) selectedCategory = null else onNavigateBack()
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
        ) { padding ->
            if (isWide) {
                val shown = selectedCategory ?: SettingsCategory.EDITOR
                Row(Modifier.fillMaxSize().padding(padding)) {
                    SettingsHub(
                        summaries = summaries,
                        selected = shown,
                        onSelect = { selectedCategory = it },
                        modifier = Modifier.width(360.dp).fillMaxHeight(),
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AnimatedContent(
                        targetState = shown,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(150))
                        },
                        label = "settingsDetailPane",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) { category -> page(category, true) }
                }
            } else {
                AnimatedContent(
                    targetState = selectedCategory,
                    // Forward slides in from the right and Back from the left,
                    // so the motion says which way the user went.
                    transitionSpec = {
                        val forward = targetState != null
                        (slideInHorizontally(tween(260)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(260)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(150)))
                    },
                    label = "settingsPhoneContent",
                    modifier = Modifier.fillMaxSize().padding(padding),
                ) { category ->
                    if (category == null) {
                        SettingsHub(
                            summaries = summaries,
                            selected = null,
                            onSelect = {
                                selectedCategory = it
                                openedFromHub = true
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        page(category, false)
                    }
                }
            }
        }
    }
}

/**
 * Every category's current state, read off the main thread.
 *
 * The cheap reads -- a few SharedPreferences values -- and the expensive one --
 * walking every downloaded toolchain to add up its size -- are separate
 * states, so the hub fills in as each arrives rather than waiting for clang's
 * half a gigabyte of small files before saying anything at all.
 *
 * Skipped while the hub is out of view: the toolchain page measures the same
 * directories itself, and walking them twice to fill rows nobody can see is
 * the waste [ToolchainSection]'s own comment warns about.
 */
@Composable
private fun rememberSummaries(
    refreshKey: Any?,
    hubVisible: Boolean,
    keys: ApiKeyStore,
    identities: GitIdentityStore,
    credentials: GitCredentialStore,
    toolchain: ToolchainManager,
    releaseKeystore: ReleaseKeystoreStore,
    editorPreferences: EditorPreferences,
    dispatchers: DispatcherProvider,
): Map<SettingsCategory, Summary?> {
    val context = LocalContext.current
    val editor by editorPreferences.settings.collectAsStateWithLifecycle()
    val version = remember(context) { installedVersion(context) }

    val stores by produceState<Triple<Summary, Summary, Summary>?>(null, refreshKey) {
        if (!hubVisible) return@produceState
        value = withContext(dispatchers.io) {
            val provider = keys.activeProvider()
            Triple(
                aiSummary(provider, keys.isReady(provider)),
                gitSummary(identities.read()?.name, credentials.hosts().size),
                signingSummary(releaseKeystore.isConfigured()),
            )
        }
    }
    val toolchains by produceState(toolchainSummary(null, 0), refreshKey) {
        if (!hubVisible) return@produceState
        value = withContext(dispatchers.io) {
            val installed = toolchain.storage.installed()
            toolchainSummary(installed.sumOf { it.bytes }, installed.size)
        }
    }

    return mapOf(
        SettingsCategory.EDITOR to editorSummary(editor),
        SettingsCategory.AI to stores?.first,
        SettingsCategory.GIT to stores?.second,
        SettingsCategory.TOOLCHAINS to toolchains,
        SettingsCategory.SIGNING to stores?.third,
        SettingsCategory.ABOUT to aboutSummary(version),
    )
}

/** The categories in their groups, each row saying what it is set to now. */
@Composable
private fun SettingsHub(
    summaries: Map<SettingsCategory, Summary?>,
    selected: SettingsCategory?,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)) {
        HUB_GROUPS.forEach { (heading, categories) ->
            item(key = heading) {
                HubGroup(heading) {
                    categories.forEachIndexed { index, category ->
                        // Inset to start under the text, as a list's rules do,
                        // so the badges read as one column.
                        if (index > 0) PanelDivider(inset = 70.dp)
                        CategoryRow(category, summaries[category], selected?.let { it == category }) {
                            onSelect(category)
                        }
                    }
                }
            }
        }
        item(key = "about") {
            HubGroup(heading = null) {
                val about = SettingsCategory.ABOUT
                CategoryRow(about, summaries[about], selected?.let { it == about }) { onSelect(about) }
            }
        }
    }
}

@Composable
private fun HubGroup(heading: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (heading != null) {
            Text(
                text = heading,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        SettingsPanel { content() }
    }
}

/**
 * One category on the hub: its badge, its name, and what it is set to.
 *
 * The summary is weighted and may wrap to a second line rather than squeezing
 * the chevron -- the shape CLAUDE.md has recorded nine times, and a git name
 * is as long as somebody's name.
 */
@Composable
private fun CategoryRow(
    category: SettingsCategory,
    summary: Summary?,
    /**
     * Null on a phone, where the hub is a menu rather than a selection: a row
     * there announced "not selected" to TalkBack, which describes nothing.
     */
    selected: Boolean?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Only drawn in the two-pane layout, where one row is the page
            // beside it. Tinted with the accent rather than drawn in
            // `secondaryContainer`, which is green here and made the selected
            // category look like the one that passed.
            .background(
                if (selected == true) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent,
            )
            .then(
                if (selected == null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier.selectable(selected = selected, role = Role.Tab, onClick = onClick)
                },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(category, size = 40.dp)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                text = category.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                // A space while the store is read, not "Loading…": the row
                // keeps its height, so nothing below it jumps when it fills.
                text = summary?.text ?: " ",
                style = MaterialTheme.typography.bodySmall,
                color = (summary?.tone ?: Tone.NEUTRAL).color(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A category's page: what it is for, then its settings.
 *
 * On a phone the toolbar already carries the title, so the header shows only
 * the badge and the description; in the two-pane layout the toolbar says
 * "Settings" and the header names the page itself.
 */
@Composable
private fun CategoryPage(
    category: SettingsCategory,
    showTitle: Boolean,
    content: @Composable () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryBadge(category, size = 52.dp)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    if (showTitle) {
                        Text(category.title, style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        text = category.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "content") { content() }
    }
}

/**
 * What the toolchain page cannot do yet, said where someone would look for it.
 *
 * Kept from the previous screen, quieter: a note under the list rather than a
 * card competing with it.
 */
@Composable
private fun ToolchainRoadmapNote() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp).padding(top = 2.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Upcoming: a Java level per project",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = "Configurable Java target levels per project are planned for an " +
                        "upcoming toolchain release.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The way into the local model benchmark.
 *
 * Under the AI settings because a local model would be one more assistant
 * provider, and marked a preview because it measures rather than provides one.
 * `tools/localai/FINDINGS.md`.
 */
@Composable
private fun LocalModelBenchmarkEntry(onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Local model benchmark (preview)", style = MaterialTheme.typography.bodyLarge)
            Text(
                // "Not used by the chat yet" was true when this was written and
                // is not any more -- the models it downloads are what the
                // on-device assistant runs. A screen that describes a shipped
                // feature as unfinished is worse than saying nothing.
                "Download a coding model and measure how it runs on this phone. " +
                    "The chat uses the same downloads.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onOpen) { Text("Open") }
    }
}
