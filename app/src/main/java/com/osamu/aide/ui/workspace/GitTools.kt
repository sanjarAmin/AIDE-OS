package com.osamu.aide.ui.workspace

import com.osamu.aide.ai.core.AideTool
import com.osamu.aide.ai.core.ProjectFiles
import com.osamu.aide.ai.core.ToolRisk
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.fs.Project
import com.osamu.aide.vcs.git.GitCommit
import com.osamu.aide.vcs.git.GitRepository
import com.osamu.aide.vcs.git.GitStatus
import com.osamu.aide.vcs.git.GitWorkspace
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Git inspection tools offered to the AI assistant.
 *
 * All git inspection tools are [ToolRisk.READ_ONLY]. They query repository status,
 * unified diffs, and commit history without mutating the working tree or index.
 */
fun gitTools(
    project: () -> Project?,
    git: GitWorkspace?,
    workspaceRoot: File?,
): List<AideTool> = listOf(
    AideTool(
        name = "git_status",
        description =
            "Report the git working tree status of the project: current branch, " +
                "and lists of modified, added, removed, untracked, and conflicting files.",
        risk = ToolRisk.READ_ONLY,
        parameters = emptyMap(),
        required = emptyList(),
        handler = {
            withGitRepository(project(), git, workspaceRoot) { repo ->
                val branch = repo.currentBranch() ?: "(detached HEAD)"
                when (val result = repo.status()) {
                    is AppResult.Failure -> ProjectFiles.Outcome.Refused(result.error.message)
                    is AppResult.Success -> {
                        val status = result.value
                        ProjectFiles.Outcome.Ok(formatStatus(branch, status))
                    }
                }
            }
        },
    ),
    AideTool(
        name = "git_diff",
        description =
            "Show the unified git diff for a specific file or the entire repository. " +
                "Specify 'path' for a single file, or omit it to see all changes. " +
                "Set 'staged' to 'true' to see changes staged in the index (index vs HEAD), " +
                "or 'false' (default) to see unstaged changes in the working tree.",
        risk = ToolRisk.READ_ONLY,
        parameters = mapOf(
            "path" to AideTool.Parameter(
                "string",
                "File path relative to the project root. Omit to diff all modified files.",
            ),
            "staged" to AideTool.Parameter(
                "string",
                "'true' for staged changes (index vs HEAD), 'false' for unstaged changes.",
            ),
        ),
        required = emptyList(),
        handler = { input ->
            val path = input["path"]?.trim()?.takeIf { it.isNotEmpty() }
            val staged = input["staged"]?.trim()?.equals("true", ignoreCase = true) == true
            withGitRepository(project(), git, workspaceRoot) { repo ->
                if (path != null) {
                    when (val diffResult = repo.diff(path, staged)) {
                        is AppResult.Failure -> ProjectFiles.Outcome.Refused(diffResult.error.message)
                        is AppResult.Success -> {
                            val diffText = diffResult.value.trim()
                            if (diffText.isEmpty()) {
                                ProjectFiles.Outcome.Ok("No ${if (staged) "staged" else "unstaged"} changes in $path.")
                            } else {
                                ProjectFiles.Outcome.Ok(diffText)
                            }
                        }
                    }
                } else {
                    // Diff all modified files
                    when (val statusResult = repo.status()) {
                        is AppResult.Failure -> ProjectFiles.Outcome.Refused(statusResult.error.message)
                        is AppResult.Success -> {
                            val filesToDiff = if (staged) {
                                (statusResult.value.added + statusResult.value.changed + statusResult.value.removed).sorted()
                            } else {
                                (statusResult.value.modified + statusResult.value.missing).sorted()
                            }
                            if (filesToDiff.isEmpty()) {
                                return@withGitRepository ProjectFiles.Outcome.Ok(
                                    "No ${if (staged) "staged" else "unstaged"} changes in the repository.",
                                )
                            }
                            val output = StringBuilder()
                            var totalBytes = 0
                            for (file in filesToDiff) {
                                when (val singleDiff = repo.diff(file, staged)) {
                                    is AppResult.Success -> {
                                        val text = singleDiff.value.trim()
                                        if (text.isNotEmpty()) {
                                            if (output.isNotEmpty()) output.append("\n\n")
                                            output.append(text)
                                            totalBytes += text.length
                                            if (totalBytes > MAX_AGGREGATE_DIFF_BYTES) {
                                                output.append("\n\n... truncated diff (exceeded ${MAX_AGGREGATE_DIFF_BYTES / 1024} kB).")
                                                break
                                            }
                                        }
                                    }
                                    is AppResult.Failure -> Unit
                                }
                            }
                            if (output.isEmpty()) {
                                ProjectFiles.Outcome.Ok("No textual changes found.")
                            } else {
                                ProjectFiles.Outcome.Ok(output.toString())
                            }
                        }
                    }
                }
            }
        },
    ),
    AideTool(
        name = "git_log",
        description =
            "Show recent git commits in the repository. " +
                "Optional 'limit' specifies the number of commits to show (default 10, max 30).",
        risk = ToolRisk.READ_ONLY,
        parameters = mapOf(
            "limit" to AideTool.Parameter(
                "string",
                "Number of commits to return (default 10, max 30).",
            ),
        ),
        required = emptyList(),
        handler = { input ->
            val limit = (input["limit"]?.toIntOrNull() ?: 10).coerceIn(1, 30)
            withGitRepository(project(), git, workspaceRoot) { repo ->
                when (val result = repo.log(limit)) {
                    is AppResult.Failure -> ProjectFiles.Outcome.Refused(result.error.message)
                    is AppResult.Success -> {
                        val commits = result.value
                        if (commits.isEmpty()) {
                            ProjectFiles.Outcome.Ok("No commits yet in this repository.")
                        } else {
                            ProjectFiles.Outcome.Ok(formatCommits(commits))
                        }
                    }
                }
            }
        },
    ),
)

private const val MAX_AGGREGATE_DIFF_BYTES = 24 * 1024

private suspend fun withGitRepository(
    project: Project?,
    git: GitWorkspace?,
    workspaceRoot: File?,
    block: suspend (GitRepository) -> ProjectFiles.Outcome,
): ProjectFiles.Outcome {
    if (project == null) {
        return ProjectFiles.Outcome.Refused("No project is open, so git is not available.")
    }
    if (git == null) {
        return ProjectFiles.Outcome.Refused("Git workspace service is not available.")
    }

    val ceiling = workspaceRoot ?: project.rootDir.parentFile ?: project.rootDir
    val repoRoot = git.enclosingRepository(project.rootDir, ceiling = ceiling)
        ?: if (git.isRepository(project.rootDir)) project.rootDir else null

    if (repoRoot == null) {
        return ProjectFiles.Outcome.Refused("Project ${project.name} is not under git version control.")
    }

    return when (val opened = git.open(repoRoot)) {
        is AppResult.Failure -> ProjectFiles.Outcome.Refused("Could not open git repository: ${opened.error.message}")
        is AppResult.Success -> {
            val repo = opened.value
            try {
                block(repo)
            } finally {
                repo.close()
            }
        }
    }
}

private fun formatStatus(branch: String, status: GitStatus): String = buildString {
    append("On branch ").append(branch).append("\n")
    if (status.isClean) {
        append("Working tree clean, nothing to commit.")
        return@buildString
    }

    if (status.conflicting.isNotEmpty()) {
        append("\nConflicting files (${status.conflicting.size}):\n")
        status.conflicting.sorted().forEach { append("  conflict: ").append(it).append("\n") }
    }
    if (status.added.isNotEmpty() || status.changed.isNotEmpty() || status.removed.isNotEmpty()) {
        append("\nChanges staged for commit:\n")
        status.added.sorted().forEach { append("  new file: ").append(it).append("\n") }
        status.changed.sorted().forEach { append("  modified: ").append(it).append("\n") }
        status.removed.sorted().forEach { append("  deleted:  ").append(it).append("\n") }
    }
    if (status.modified.isNotEmpty() || status.missing.isNotEmpty()) {
        append("\nChanges not staged for commit:\n")
        status.modified.sorted().forEach { append("  modified: ").append(it).append("\n") }
        status.missing.sorted().forEach { append("  deleted:  ").append(it).append("\n") }
    }
    if (status.untracked.isNotEmpty()) {
        append("\nUntracked files (${status.untracked.size}):\n")
        status.untracked.sorted().take(25).forEach { append("  ").append(it).append("\n") }
        if (status.untracked.size > 25) {
            append("  ...and ").append(status.untracked.size - 25).append(" more.\n")
        }
    }
}.trim()

private fun formatCommits(commits: List<GitCommit>): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    return buildString {
        commits.forEachIndexed { index, commit ->
            if (index > 0) append("\n")
            append(commit.abbreviated).append(" ")
            append(dateFormat.format(Date(commit.timestamp))).append(" ")
            append("<").append(commit.authorName).append("> ")
            append(commit.summary.lineSequence().firstOrNull()?.take(80).orEmpty())
        }
    }.trim()
}
