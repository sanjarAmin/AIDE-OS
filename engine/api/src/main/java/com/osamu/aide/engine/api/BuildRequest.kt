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
)
