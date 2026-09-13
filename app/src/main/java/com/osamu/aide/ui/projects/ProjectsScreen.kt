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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.ui.util.FileIcons
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
                itemsIndexed(state.projects, key = { _, it -> it.rootDir.absolutePath }) { index, project ->
                    ProjectRow(project, index = index) { onOpenProject(project.rootDir) }
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
            onCreate = { name, language ->
                viewModel.createProject(name, language)
                showCreateDialog = false
            },
        )
    }
}

@Composable
private fun ProjectRow(project: Project, index: Int, onClick: () -> Unit) {
    val dummyFile = remember(project.language) {
        val extension = when (project.language) {
            SourceLanguage.KOTLIN -> "kt"
            SourceLanguage.JAVASCRIPT -> "js"
            SourceLanguage.CSHARP -> "cs"
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
                            val runtime = if (project.language == SourceLanguage.JAVASCRIPT) "Node" else "Mono"
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

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, SourceLanguage) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var language by remember { mutableStateOf(SourceLanguage.JAVA) }

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
                // FlowRow, not Row. Four chips do not fit the width of a
                // dialog on a phone, and a Row does not wrap -- it clips. The
                // C# chip shipped hanging off the right edge, half a pixel of
                // it visible and unreachable, and every test passed because a
                // test asks the ViewModel for the language rather than tapping
                // the chip. Driving the app is what found it.
                // FlowRow, not Row. Four chips do not fit the width of a
                // dialog on a phone, and a Row does not overflow -- it
                // *squeezes*: C# shipped 19 dp wide against JavaScript's 99,
                // its label wrapped inside it, and it was unreadable. Every
                // test passed, because a test that wants a C# project asks the
                // repository for one rather than tapping a chip.
                FlowRow(
                    modifier = Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Java and Kotlin build an APK; JavaScript does not build
                    // at all, it runs. The picker does not say so, because the
                    // ▶ button does the right thing for each and explaining the
                    // difference here would explain it to everyone who did not
                    // need to know.
                    listOf(
                        SourceLanguage.JAVA,
                        SourceLanguage.KOTLIN,
                        SourceLanguage.JAVASCRIPT,
                        SourceLanguage.CSHARP,
                    ).forEach { option ->
                        FilterChip(
                            selected = language == option,
                            onClick = { language = option },
                            label = { Text(option.displayName) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), language) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
private val RUN_ONLY = setOf(SourceLanguage.JAVASCRIPT, SourceLanguage.CSHARP)
