package com.osamu.aide.engine.api

import com.osamu.aide.core.fs.Project
import java.io.File

/**
 * What to build, and where to put it.
 *
 * [outputDir] is passed in rather than derived from the project so that a build
 * never writes into the source tree -- intermediates belong in cache, which the
 * system may clear, and the user's project directory should contain only the
 * things they wrote.
 */
data class BuildRequest(
    val project: Project,
    val outputDir: File,
    /**
     * Debug builds are signed with the device's own key and skip optimisation.
     * A release build is a different signing story entirely and is not yet
     * modelled; see docs/PLAN.md.
     */
    val debuggable: Boolean = true,
    /**
     * Dependency artifacts, already resolved and unpacked.
     *
     * Plain files rather than a resolver type on purpose: this module is the
     * contract between a screen and a build engine and should stay ignorant of
     * how dependencies were found. `:engine:deps` produces these;
     * `:engine:gradle` will produce them differently.
     */
    val dependencies: DependencyInputs = DependencyInputs(),
)

/**
 * What a build needs from its dependencies, split by the tool that reads it.
 *
 * [classpath] goes to the compiler and the dexer; [resourceDirectories] go to
 * aapt2. An AAR contributes to both, a plain jar only to the first, and this
 * type exists so no stage has to know which it was handed.
 */
data class DependencyInputs(
    val classpath: List<File> = emptyList(),
    val resourceDirectories: List<File> = emptyList(),
) {
    val isEmpty: Boolean get() = classpath.isEmpty() && resourceDirectories.isEmpty()
}
