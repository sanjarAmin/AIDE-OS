package com.osamu.aide.ui.projects

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.ProjectTemplate
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.core.fs.TemplateFile
import com.osamu.aide.core.fs.preview
import com.osamu.aide.core.ui.BrandMark
import com.osamu.aide.core.ui.fadingEdges
import com.osamu.aide.core.ui.selectionChipColors
import com.osamu.aide.ui.util.FileIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The first screen: what you were doing, what else there is, and how to start.
 *
 * Laid out in the order those questions get asked. **Actions first**, as
 * labelled tiles: they were three bare icons in the toolbar -- a cloud, a
 * folder with an arrow, a gear -- and the two that make projects could only be
 * told apart by long-pressing them. **Then the project last opened**, large,
 * because the list is sorted by it and "the one I was in" is the commonest
 * reason to be here. **Then everything else, under headings by when**, so a
 * list of thirty reads as a few short ones.
 *
 * There is no floating action button any more. "New project" is the first tile
 * and the screen's one filled control; a FAB beside it would be the same action
 * twice, and on a short list it sat over nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    onOpenProject: (File) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ProjectsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Reloaded on every return, not only when the view model is made: the
    // list is sorted by when each project was last opened, and coming back
    // from the one just worked in -- or from changing its engine -- has to
    // show that without a restart.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showCloneDialog by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf<Project?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { tree -> tree?.let(viewModel::importProject) }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    // **Loaded once means loaded.** Every return to this screen reloads, and
    // the reload sets isLoading -- which used to swap the whole list for a
    // spinner for a frame on every Back from a project. Only a screen with
    // nothing to show yet waits visibly.
    val firstLoad = state.isLoading && state.projects.isEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark()
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(
                                "AIDE-OS",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            // **A count, because it is real.** This bar once
                            // carried "v1.0 · Ready" beside a green dot, every
                            // part of which was a constant; a status that
                            // cannot change teaches people to ignore the ones
                            // that can. How many projects there are changes.
                            if (!firstLoad) {
                                Text(
                                    text = projectCountLabel(state.projects.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (firstLoad) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        } else {
            ProjectList(
                state = state,
                padding = padding,
                viewModel = viewModel,
                onOpenProject = onOpenProject,
                onCreate = { showCreateDialog = true },
                onClone = { showCloneDialog = true },
                onImport = { importLauncher.launch(null) },
                onDelete = { confirmingDelete = it },
            )
        }
    }

    state.cloneStatus?.let { status ->
        CloneProgressDialog(status = status, onCancel = viewModel::cancelClone)
    }

    if (showCloneDialog) {
        CloneRepositoryDialog(
            onDismiss = { showCloneDialog = false },
            onClone = { url ->
                viewModel.cloneRepository(url)
                showCloneDialog = false
            },
        )
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, template ->
                viewModel.createProject(name, template)
                showCreateDialog = false
            },
        )
    }

    confirmingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("Delete ${project.name}?") },
            text = { Text("This permanently removes the project and every file in it from this device.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteProject(project)
                        confirmingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Everything below the toolbar once the projects have loaded.
 *
 * Its own function so the loading branch is an ordinary `if`/`else` rather
 * than an early return out of the Scaffold's content lambda.
 */
@Composable
private fun ProjectList(
    state: ProjectsUiState,
    padding: PaddingValues,
    viewModel: ProjectsViewModel,
    onOpenProject: (File) -> Unit,
    onCreate: () -> Unit,
    onClone: () -> Unit,
    onImport: () -> Unit,
    onDelete: (Project) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 32.dp,
        ),
    ) {
        item(key = "actions") {
            QuickActions(
                onCreate = onCreate,
                onClone = onClone,
                onImport = onImport,
                cloning = state.cloneStatus != null,
                importing = state.isImporting,
            )
        }

        if (state.projects.isEmpty()) {
            item(key = "empty") { EmptyWorkspace() }
        } else {
            projectItems(state, viewModel, onOpenProject, onDelete)
        }
    }
}

/** The search, the resumed project and the grouped list, as lazy items. */
private fun androidx.compose.foundation.lazy.LazyListScope.projectItems(
    state: ProjectsUiState,
    viewModel: ProjectsViewModel,
    onOpenProject: (File) -> Unit,
    onDelete: (Project) -> Unit,
) {
    val resume = resumeCandidate(state.projects, state.searchQuery, state.selectedLanguage)
    // The resumed project is not listed again below its own card: the same
    // name twice on one screen reads as a duplicate project.
    val groups = groupByRecency(state.filteredProjects.filter { it.rootDir != resume?.rootDir })

    item(key = "search") {
        SearchAndFilter(
            query = state.searchQuery,
            onQueryChange = viewModel::setSearchQuery,
            languages = languageCounts(state.projects),
            totalCount = state.projects.size,
            selected = state.selectedLanguage,
            onSelect = viewModel::setLanguageFilter,
        )
    }

    if (resume != null) {
        item(key = "resume") {
            ResumeCard(
                project = resume,
                size = rememberProjectSize(resume, viewModel::sizeOf),
                onOpen = { onOpenProject(resume.rootDir) },
                onDelete = { onDelete(resume) },
                modifier = Modifier.animateItem(),
            )
        }
    }

    if (state.filteredProjects.isEmpty()) {
        item(key = "no-matching-projects") {
            NoMatches(
                query = state.searchQuery,
                language = state.selectedLanguage,
                onClear = {
                    viewModel.setSearchQuery("")
                    viewModel.setLanguageFilter(null)
                },
            )
        }
    }

    groups.forEach { (recency, members) ->
        item(key = "heading-${recency.name}") {
            GroupHeading(recency.heading, members.size, Modifier.animateItem())
        }
        items(members, key = { it.rootDir.absolutePath }) { project ->
            ProjectCard(
                project = project,
                size = rememberProjectSize(project, viewModel::sizeOf),
                onOpen = { onOpenProject(project.rootDir) },
                onDelete = { onDelete(project) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/**
 * The three ways a project arrives, as equal tiles.
 *
 * **Equal widths by `weight`, equal heights by `IntrinsicSize.Min`.** A label
 * that wraps on a narrow phone makes its tile taller, and three tiles of three
 * heights read as a layout fault -- the shape CLAUDE.md records nine times. The
 * intrinsic height makes the row as tall as its tallest tile and the others
 * fill it.
 *
 * Only "New project" is filled. It is the action the screen exists for, and it
 * replaces the FAB that used to carry that weight.
 */
@Composable
private fun QuickActions(
    onCreate: () -> Unit,
    onClone: () -> Unit,
    onImport: () -> Unit,
    cloning: Boolean,
    importing: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val tile = Modifier.weight(1f).fillMaxHeight()
        ActionTile(
            label = "New project",
            icon = Icons.Default.Add,
            description = "New project",
            prominent = true,
            onClick = onCreate,
            modifier = tile,
        )
        ActionTile(
            label = "Clone",
            icon = Icons.Default.CloudDownload,
            description = "Clone a repository",
            enabled = !cloning,
            onClick = onClone,
            modifier = tile,
        )
        ActionTile(
            // Said on the tile itself: an import copies thousands of files and
            // takes long enough that a tile that merely went grey would look
            // broken rather than busy.
            label = if (importing) "Importing…" else "Import",
            icon = Icons.Default.DriveFolderUpload,
            description = "Import a project folder",
            enabled = !importing,
            busy = importing,
            onClick = onImport,
            modifier = tile,
        )
    }
}

@Composable
private fun ActionTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        color = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                color = LocalContentColor.current.copy(alpha = if (enabled) 1f else 0.5f),
            )
        }
    }
}

/**
 * The search field, and the language filter when there is anything to filter.
 *
 * The filter row is hidden when every project is in one language: a row of
 * one chip beside "All" is two names for the same list.
 */
@Composable
private fun SearchAndFilter(
    query: String,
    onQueryChange: (String) -> Unit,
    languages: List<Pair<SourceLanguage, Int>>,
    totalCount: Int,
    selected: SourceLanguage?,
    onSelect: (SourceLanguage?) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search projects", style = MaterialTheme.typography.bodyLarge) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            // A filled pill with no underline: the underline is what makes a
            // TextField look like a form field, and this is a search bar.
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .semantics { contentDescription = "Search projects" },
        )

        // The selected language is offered even when no project uses it any
        // more -- deleting the last one leaves the filter applied, and without
        // its chip there is no way to see why the list is empty, or to undo it.
        val shown = if (selected != null && languages.none { it.first == selected }) {
            languages + (selected to 0)
        } else {
            languages
        }
        if (shown.size >= 2 || selected != null) {
            LanguageFilter(shown, totalCount, selected, onSelect)
        }
    }
}

@Composable
private fun LanguageFilter(
    shown: List<Pair<SourceLanguage, Int>>,
    totalCount: Int,
    selected: SourceLanguage?,
    onSelect: (SourceLanguage?) -> Unit,
) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            // Before the scroll, not after: a draw modifier placed after
            // it renders inside the scrolling content, so the gradient
            // lands at the ends of the row rather than at the edges of the
            // window onto it -- off screen, where it cannot be seen.
            .fadingEdges(scroll)
            .horizontalScroll(scroll)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LanguageChip(
            label = "All",
            count = totalCount,
            selected = selected == null,
            onClick = { onSelect(null) },
            description = "Filter all languages",
        )
        shown.forEach { (language, count) ->
            LanguageChip(
                label = language.displayName,
                count = count,
                selected = selected == language,
                onClick = { onSelect(if (selected == language) null else language) },
                description = "Filter ${language.displayName}",
            )
        }
    }
}

@Composable
private fun LanguageChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    description: String,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            // In a Row of its own: the chip lays its label slot out as one
            // box, and two bare Texts in it are drawn on top of each other.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label)
                Text(
                    text = "$count",
                    // Quieter than the name: the count answers "how many",
                    // which is the second question, not the first.
                    color = LocalContentColor.current.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        },
        shape = RoundedCornerShape(12.dp),
        colors = selectionChipColors(),
        modifier = Modifier.semantics { contentDescription = description },
    )
}

/**
 * The project last opened, as the screen's largest thing.
 *
 * Tinted by its language, as its tile is, so the card and the project's row
 * further down -- when a search brings it back -- are recognisably the same.
 */
@Composable
private fun ResumeCard(
    project: Project,
    size: String,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = languageAccent(project.language)
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            Modifier
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent)))
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Continue where you left off",
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    modifier = Modifier.weight(1f),
                )
                ProjectMenu(project, onDelete)
            }
            Row(
                modifier = Modifier.padding(top = 6.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LanguageMonogram(project.language, size = 52.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = projectFacts(project.language, project.engine, size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 18.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Weighted: Open is the control, and a long label must give
                // way to it rather than the reverse.
                Text(
                    text = lastOpenedLabel(project.lastOpenedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onOpen,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.semantics { contentDescription = "Open ${project.name}" },
                ) {
                    Text("Open")
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 6.dp).size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    size: String,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LanguageMonogram(project.language, size = 44.dp)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = projectFacts(project.language, project.engine, size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    // **The same fact on every card.** This slot once held
                    // "Runs on Mono" for one language and an application id
                    // for another, so two cards side by side answered
                    // different questions. When you last opened it is the fact
                    // a list of projects exists to serve.
                    text = lastOpenedLabel(project.lastOpenedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                )
            }
            ProjectMenu(project, onDelete)
        }
    }
}

/** A project's tile: its language's letters, in its language's colour. */
@Composable
private fun LanguageMonogram(language: SourceLanguage, size: Dp) {
    val accent = languageAccent(language)
    val shape = RoundedCornerShape(size * 0.3f)
    val letters = language.monogram
    Box(
        modifier = Modifier
            .size(size)
            .background(accent.copy(alpha = 0.14f), shape)
            .border(1.dp, accent.copy(alpha = 0.35f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letters,
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            // Smaller for three letters, so "C++" fits the tile "J" does.
            fontSize = (size.value * if (letters.length > 2) 0.3f else 0.38f).sp,
            maxLines = 1,
        )
    }
}

/** The colour the file tree gives this language's files. */
@Composable
private fun languageAccent(language: SourceLanguage): Color =
    FileIcons.infoFor(File("project.${language.extension}"), isDirectory = false).tint

@Composable
private fun ProjectMenu(project: Project, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "Project options for ${project.name}" },
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Delete project", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun GroupHeading(heading: String, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sentence case and the ordinary text colour: an uppercase, tracked,
        // accent-coloured eyebrow is the shape a generated screen takes.
        Text(heading, style = MaterialTheme.typography.titleSmall)
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                .padding(horizontal = 7.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun NoMatches(query: String, language: SourceLanguage?, onClear: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when {
                query.isNotBlank() -> "Nothing matches \"$query\""
                language != null -> "No ${language.displayName} projects"
                else -> "Nothing matches this filter"
            },
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        // The way out, beside the dead end. Clearing a filter scrolled out of
        // view is otherwise two steps that the empty list does not mention.
        TextButton(onClick = onClear, modifier = Modifier.padding(top = 4.dp)) {
            Text("Show all projects")
        }
    }
}

/**
 * A workspace with nothing in it, which is every first launch.
 *
 * The tiles above already hold the three ways in, so this does not repeat
 * them as buttons; it says what the tiles lead to. The languages are drawn
 * from the catalog, so the row cannot claim one the templates do not have.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyWorkspace() {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SourceLanguage.entries.forEach { LanguageMonogram(it, size = 40.dp) }
            }
            Text(
                text = "Your workspace is empty",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 22.dp),
            )
            Text(
                text = "Start a project from a template in any of these languages, clone " +
                    "one from Git, or import a folder you already have. Projects stay on " +
                    "this device, where a desktop can reach them over USB.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * A project's size, measured off the main thread and once per reload.
 *
 * "…" until measured rather than "0 B": a size that starts at zero and then
 * jumps reads as the project having grown.
 */
@Composable
private fun rememberProjectSize(project: Project, sizeOf: suspend (Project) -> Long): String {
    val size by produceState(initialValue = "…", project.rootDir) {
        value = sizeLabel(sizeOf(project))
    }
    return size
}

/**
 * When a project was last opened, coarsely.
 *
 * Coarse on purpose: nobody needs "43 minutes ago", they need to know whether
 * it was today. Coarse buckets also mean the string does not change while the
 * list is on screen.
 */
internal fun lastOpenedLabel(millis: Long, now: Long = System.currentTimeMillis()): String {
    if (millis <= 0L) return "Not opened yet"
    val elapsed = now - millis
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)
    return when {
        // A clock that has gone backwards -- a restored backup, a timezone
        // change -- should not produce "opened in -3 days".
        elapsed < 0L -> "Opened just now"
        minutes < 1 -> "Opened just now"
        minutes < 60 -> "Opened ${minutes}m ago"
        hours < 24 -> "Opened ${hours}h ago"
        days == 1L -> "Opened yesterday"
        days < 7 -> "Opened ${days}d ago"
        days < 365 -> "Opened ${days / 7}w ago"
        else -> "Opened over a year ago"
    }
}

@Composable
internal fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, ProjectTemplate) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(ProjectTemplate.ALL.first()) }

    // **What the selected template will actually write.** Produced by running
    // the template into a scratch directory, so it cannot describe something
    // Create does not do -- see `ProjectTemplate.preview`.
    //
    // Keyed on the template *and the name*, because the name decides the
    // package directory a template writes into, and a preview showing a path
    // the project will not have is the one way this feature can mislead.
    //
    // On the IO dispatcher: it is small, but it is disk, and `produceState`
    // would otherwise run it on the frame that handled the tap. Failure is an
    // empty list rather than a crash -- a dialog that cannot create a scratch
    // file should still let the user create a project.
    val context = LocalContext.current
    val preview by produceState(initialValue = emptyList<TemplateFile>(), template, name) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                template.preview(name, File(context.cacheDir, "template-preview"))
            }.getOrDefault(emptyList())
        }
    }

    // **How much room the keyboard has left.** The activity is edge to edge,
    // which makes the manifest's `adjustResize` a no-op: nothing resizes the
    // window any more, so a dialog that does not read the IME inset itself is
    // simply covered by it. Driving this at 360 dp, the Create button sat under
    // the keyboard and a tap on it landed on the letter above -- typing into
    // the name field instead of creating anything, with no hint why.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val keyboardIsUp = imeBottom > 0

    AlertDialog(
        // Padded by the IME, and told not to fit system windows itself, which
        // is the pair that lets the surface move rather than be overlapped.
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))

                // How many there are. Counted from the catalog so it cannot
                // drift from it.
                //
                // **The instruction that used to follow is gone.** It read
                // "15 templates — scroll for the rest", which is a sentence
                // apologising for a missing affordance: the first screenful
                // ended tidily under the last Kotlin template, so it looked
                // like a complete list of four. The fade at the foot of the
                // list now says that, in the place where the question arises
                // and without words.
                Text(
                    text = "${ProjectTemplate.ALL.size} templates",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val templateScroll = rememberScrollState()
                Box {
                // **A scrolling Column, not a LazyColumn.** An AlertDialog
                // measures its body with unbounded height, and a lazy list
                // given no height at all composes one item and reports itself
                // as tall as that -- which looks like a picker that lost every
                // template but the first. `heightIn` is what gives it a bound;
                // the list is eleven items, so nothing is saved by laziness.
                Column(
                    modifier = Modifier
                        // **Shorter while the keyboard is up.** Moving the
                        // dialog is not enough on a short screen: fifteen
                        // templates plus a name field plus the actions do not
                        // fit in what is left, and the half that gets clipped
                        // is the half with the buttons. The list is the part
                        // that can afford to shrink -- it already scrolls.
                        .heightIn(max = if (keyboardIsUp) 150.dp else 320.dp)
                        .verticalScroll(templateScroll),
                ) {
                    // **`groupBy`, not "a heading when the language changes".**
                    // The latter is the same thing only while the catalog is
                    // grouped, and when it was not it printed JAVA, KOTLIN,
                    // JAVA, KOTLIN -- four headings for two languages, which
                    // reads as a rendering fault. Every test passed: they count
                    // rows and check each objective is reachable, and a heading
                    // is neither. `groupBy` keeps first-encounter order, so the
                    // languages still appear in the catalog's order and a
                    // template added in the wrong place cannot split a group.
                    ProjectTemplate.ALL.groupBy { it.language }.forEach { (language, options) ->
                        Text(
                            // Sentence case: an all-caps eyebrow above every
                            // group is the shape a generated screen takes, and
                            // "Java" is a name rather than a shout.
                            text = language.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                        )
                        options.forEach { option ->
                            TemplateRow(
                                template = option,
                                selected = template == option,
                                onSelect = { template = option },
                                // Only the selected row has a preview, so only
                                // one is computed: previewing all fifteen on
                                // every keystroke would be fifteen times the
                                // disk work to show fourteen things nobody is
                                // looking at.
                                files = if (template == option) preview else emptyList(),
                            )
                        }
                    }
                }

                    // **The cue that replaced a sentence.** Shown only while
                    // there is more below, so a list that ends does not wear a
                    // permanent shadow suggesting otherwise. The colour is the
                    // dialog's own container, which is what makes it read as
                    // the list passing under an edge rather than as a grey bar.
                    if (templateScroll.canScrollForward) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(28.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Transparent,
                                            MaterialTheme.colorScheme.surfaceContainerHigh,
                                        ),
                                    ),
                                ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), template) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * One template, and what it is for.
 *
 * **`Modifier.weight(1f)` on the text, every time.** This is the shape that has
 * produced nine defects in this codebase: a `Row` holding a variable-width
 * label beside a control squeezes rather than overflowing, so the label takes
 * whatever width it wants and the control is measured in what is left -- which
 * is often nothing. The objective lines here are long and of different lengths,
 * which is exactly the input that breaks it. `CreateProjectDialogTest` asserts
 * the radio buttons all ended up the same size.
 *
 * `selectable` on the row rather than `clickable` so the whole row is one
 * accessibility node with a selected state, and the radio button is not a
 * separate target a user has to hit.
 */
@Composable
private fun TemplateRow(
    template: ProjectTemplate,
    selected: Boolean,
    onSelect: () -> Unit,
    files: List<TemplateFile>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = template.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = template.objective,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Said here and nowhere else, because it is the one thing about a
            // template that can make it fail for a reason the user did not
            // cause. Every other template writes files and stops.
            if (template.dependencies.isNotEmpty()) {
                Text(
                    text = "Needs a download the first time it builds",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        // Tagged so a test can measure it. It is the control half of the row
        // -- the half a squeezed label eats -- and without a tag it merges into
        // the row's semantics and cannot be measured at all.
        RadioButton(
            selected = selected,
            onClick = null,
            modifier = Modifier.testTag(TEMPLATE_RADIO_TAG),
        )
    }

    // **Below the row, not inside it.** These are full paths and the longest
    // is wider than the column the objective sits in; putting them beside the
    // radio button would either squeeze the control or wrap every path. The
    // list only appears under the selected template, so at most one is open.
    if (selected && files.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
        ) {
            Text(
                text = "Creates ${files.size} ${if (files.size == 1) "file" else "files"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            files.forEach { file ->
                Text(
                    text = file.path,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // **Wraps rather than ellipsises**, and that is not a
                    // style preference. The package directory comes from the
                    // typed name, so it is the one part of the path that
                    // changes as the user types -- and it sits in the middle,
                    // which is exactly what `MiddleEllipsis` ate:
                    // `src/main/java/com/…/MainActivity.java` looked identical
                    // whatever the project was called, so the preview appeared
                    // frozen while it was in fact correct. Two lines is the
                    // most any template's longest path needs.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp,
                    modifier = Modifier.testTag(TEMPLATE_FILE_TAG),
                )
            }
        }
    }
}

/**
 * Where a repository URL is typed.
 *
 * Only `https` is offered. SSH is a separate JGit artifact and a separate
 * key-management problem that this app has not solved, and letting a user paste
 * an `ssh://` URL that can only fail is worse than not accepting one.
 */
@Composable
private fun CloneRepositoryDialog(onDismiss: () -> Unit, onClone: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    val trimmed = url.trim()
    val usable = trimmed.startsWith("https://") && trimmed.length > "https://".length

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clone a repository") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("HTTPS URL") },
                    placeholder = { Text("https://github.com/owner/repo.git") },
                    singleLine = true,
                    isError = trimmed.isNotEmpty() && !usable,
                    supportingText = {
                        Text(
                            if (trimmed.isNotEmpty() && !usable) {
                                "An https:// URL. SSH is not supported yet."
                            } else {
                                "A private repository needs an access token in Settings."
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                        .semantics { contentDescription = "Repository URL" },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onClone(trimmed) },
                enabled = usable,
                modifier = Modifier.semantics { contentDescription = "Start clone" },
            ) { Text("Clone") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Progress, and a cancel that actually stops it.
 *
 * Not dismissible by tapping away: a clone is minutes long and writes hundreds
 * of megabytes, so leaving it running behind a dismissed dialog is exactly the
 * failure the cancellation plumbing exists to prevent. The only way out is the
 * button that really cancels.
 *
 * The indicator is indeterminate because JGit reports a total it does not
 * always know, and a bar that sits at zero reads as a hang. The phase text is
 * what changes.
 */
@Composable
private fun CloneProgressDialog(status: String, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Cloning") },
        text = {
            Column {
                Text(status, modifier = Modifier.semantics { contentDescription = "Clone status" })
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.semantics { contentDescription = "Cancel clone" },
            ) { Text("Cancel") }
        },
    )
}

/**
 * The languages that run rather than build.
 *
 * Neither produces an APK, so neither has a build engine or an application ID
 * to show. Kept here rather than on [SourceLanguage] because it is a fact about
 * what this app can do with a project, not about the language.
 */
internal val RUN_ONLY = setOf(
    SourceLanguage.JAVASCRIPT,
    SourceLanguage.CSHARP,
    SourceLanguage.PYTHON,
)

/** @see CreateProjectDialog */
internal const val TEMPLATE_RADIO_TAG = "template-radio"

/** @see CreateProjectDialog */
internal const val TEMPLATE_FILE_TAG = "template-file"
