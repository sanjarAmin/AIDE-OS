package com.osamu.aide.ui.projects

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.ProjectTemplate
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.core.fs.TemplateFile
import com.osamu.aide.core.fs.preview
import com.osamu.aide.ui.util.FileIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    onOpenProject: (File) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ProjectsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "AIDE-OS",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(10.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(MaterialTheme.colorScheme.secondary, CircleShape),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "v1.0 · Ready",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showCloneDialog = true },
                        enabled = state.cloneStatus == null,
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = "Clone a repository",
                        )
                    }
                    IconButton(
                        onClick = { importLauncher.launch(null) },
                        enabled = !state.isImporting,
                    ) {
                        Icon(
                            Icons.Default.DriveFolderUpload,
                            contentDescription = "Import a project folder",
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "New project")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.isImporting) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(top = padding.calculateTopPadding()),
            )
        }
        when {
            state.isLoading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.projects.isEmpty() -> EmptyProjects(
                onCreateClick = { showCreateDialog = true },
                onCloneClick = { showCloneDialog = true },
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
            ) {
                item(key = "projects-search-bar") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = viewModel::setSearchQuery,
                            placeholder = { Text("Search projects…", style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                            },
                            trailingIcon = {
                                if (state.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search projects" },
                        )

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = state.selectedLanguage == null,
                                onClick = { viewModel.setLanguageFilter(null) },
                                label = { Text("All", fontSize = 12.sp) },
                                modifier = Modifier.semantics { contentDescription = "Filter all languages" },
                            )
                            SourceLanguage.entries.forEach { lang ->
                                FilterChip(
                                    selected = state.selectedLanguage == lang,
                                    onClick = {
                                        viewModel.setLanguageFilter(if (state.selectedLanguage == lang) null else lang)
                                    },
                                    label = { Text(lang.displayName, fontSize = 12.sp) },
                                    modifier = Modifier.semantics { contentDescription = "Filter ${lang.displayName}" },
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }

                if (state.filteredProjects.isEmpty()) {
                    item(key = "no-matching-projects") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (state.searchQuery.isNotBlank()) "No projects matching \"${state.searchQuery}\"" else "No projects matching this filter",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    itemsIndexed(state.filteredProjects, key = { _, it -> it.rootDir.absolutePath }) { index, project ->
                        ProjectRow(
                            project = project,
                            index = index,
                            onClick = { onOpenProject(project.rootDir) },
                            onDelete = { confirmingDelete = project },
                        )
                    }
                }
            }
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
            text = { Text("Are you sure you want to delete '${project.name}'? This permanently removes the project files from device storage.") },
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

@Composable
private fun ProjectRow(
    project: Project,
    index: Int,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val projectSize by produceState(initialValue = "…", project.rootDir) {
        value = withContext(Dispatchers.IO) {
            val bytes = project.rootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                else -> String.format(java.util.Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
            }
        }
    }
    val dummyFile = remember(project.language) {
        val extension = when (project.language) {
            SourceLanguage.KOTLIN -> "kt"
            SourceLanguage.JAVASCRIPT -> "js"
            SourceLanguage.CSHARP -> "cs"
            SourceLanguage.PYTHON -> "py"
            SourceLanguage.C -> "c"
            SourceLanguage.CPP -> "cpp"
            SourceLanguage.JAVA -> "java"
        }
        File("dummy.$extension")
    }
    val iconInfo = FileIcons.infoFor(dummyFile, isDirectory = false)

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(280 + index * 50)) +
            slideInVertically(animationSpec = tween(280 + index * 50)) { 24 },
    ) {
        OutlinedCard(
            onClick = onClick,
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(iconInfo.tint.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = iconInfo.icon,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = iconInfo.tint,
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        project.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Spacer(Modifier.height(4.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = iconInfo.tint.copy(alpha = 0.12f),
                        ) {
                            Text(
                                text = project.language.displayName.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = iconInfo.tint,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }

                        if (project.language !in RUN_ONLY) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    text = project.engine.displayName.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = if (project.language in RUN_ONLY) {
                            val runtime = when (project.language) {
                                SourceLanguage.JAVASCRIPT -> "Node"
                                SourceLanguage.PYTHON -> "Python"
                                else -> "Mono"
                            }
                            "Runs on $runtime"
                        } else {
                            project.applicationId
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(32.dp).semantics { contentDescription = "Project options for ${project.name}" },
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Delete Project", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                            )
                        }
                    }
                    Text(
                        text = projectSize,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyProjects(
    onCreateClick: () -> Unit,
    onCloneClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "emptyStateGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowScale",
    )

    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .scale(glowScale)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha * 0.2f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Text("No projects yet", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Create one to get started. Projects are stored on this device and can be opened from a desktop over USB.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            textAlign = TextAlign.Center,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onCreateClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New project")
            }
            OutlinedButton(
                onClick = onCloneClick,
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Clone")
            }
        }
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

    AlertDialog(
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

                // **Says how many there are, because the list does not.**
                // Four chips used to show every choice at once; fifteen rows
                // cannot, and the first screenful ends tidily after the last
                // Kotlin template with clear space beneath it -- so it reads as
                // a complete list of four rather than the top of a list of
                // fifteen. There is no scrollbar in a dialog to say otherwise.
                // Counting from the catalog, so it cannot drift from it.
                Text(
                    text = "${ProjectTemplate.ALL.size} templates — scroll for the rest",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // **A scrolling Column, not a LazyColumn.** An AlertDialog
                // measures its body with unbounded height, and a lazy list
                // given no height at all composes one item and reports itself
                // as tall as that -- which looks like a picker that lost every
                // template but the first. `heightIn` is what gives it a bound;
                // the list is eleven items, so nothing is saved by laziness.
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
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
                            text = language.displayName.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
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
private val RUN_ONLY = setOf(
    SourceLanguage.JAVASCRIPT,
    SourceLanguage.CSHARP,
    SourceLanguage.PYTHON,
)

/** @see CreateProjectDialog */
internal const val TEMPLATE_RADIO_TAG = "template-radio"

/** @see CreateProjectDialog */
internal const val TEMPLATE_FILE_TAG = "template-file"
