package com.osamu.aide.ui.workspace

import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.engine.gradle.GradleEditorInputs
import java.io.File

/**
 * Decides what the editor's language services see of a project.
 *
 * The two engines answer differently because they know different things. A
 * fast-engine project is one module whose dependencies `aide.json` declares, so
 * its classpath is what [ProjectDependencies] resolves from that. A Gradle
 * project is whatever its build scripts make it, which only Gradle can say, so
 * its sources are every module's and its classpath is what the last build --
 * or the last sync -- recorded; see [GradleEditorInputs]. A project that has
 * had neither gets nothing here, which is why opening one syncs it --
 * `WorkspaceViewModel.syncIfNothingRecorded`. The platform types resolve
 * meanwhile.
 */
class EditorContexts(private val dependencies: ProjectDependencies) {

    suspend fun contextFor(project: Project): EditorContext = when (project.engine) {
        BuildEngine.GRADLE -> EditorContext(
            sourceRoots = GradleEditorInputs.sourceRoots(project.rootDir),
            classpath = GradleEditorInputs.classpath(project.rootDir),
        )
        BuildEngine.FAST -> EditorContext(
            sourceRoots = listOf(File(project.rootDir, "src/main/kotlin")).filter { it.isDirectory },
            classpath = if (project.dependencies.isEmpty()) {
                emptyList()
            } else {
                runCatching { dependencies.classpathFor(project) }.getOrDefault(emptyList())
            },
        )
    }
}
