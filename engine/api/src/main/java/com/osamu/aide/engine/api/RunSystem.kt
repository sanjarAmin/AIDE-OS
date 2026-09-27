package com.osamu.aide.engine.api

import kotlinx.coroutines.flow.Flow

/**
 * An engine that *runs* a project, as a sibling to [BuildSystem].
 *
 * **Deliberately not a mode of [BuildSystem].** A build turns sources into an
 * artefact and then ends; the APK is the point and the output is incidental.
 * A run has no artefact, may never end on its own, and its output *is* the
 * point. Folding the second into the first would mean a `BuildResult` with no
 * `apk`, a `BuildStage` for something that has no stages, and every existing
 * caller learning to ignore both -- which is how a contract stops describing
 * anything.
 *
 * The two share a shape where sharing is honest: cold flows, streamed progress,
 * a terminal event that always arrives. `:engine:fast` and `:engine:gradle`
 * implement the first; `:engine:node` implements this.
 */
interface RunSystem {

    /** True when this engine can run [request]'s project at all. */
    fun handles(request: RunRequest): Boolean

    /**
     * Runs [request], streaming output as the process produces it.
     *
     * Cold: nothing starts until collected, and **cancelling the collection
     * kills the process**. That is not the same guarantee a build makes and it
     * is the more important one here -- a build that outlives its collector
     * wastes CPU, whereas a server that does is still holding the port when the
     * user tries again.
     *
     * The flow always ends with [RunEvent.Finished], including when the program
     * fails to start: a missing runtime is an ordinary outcome of asking to
     * run, not an exception. Only a defect in the engine itself throws.
     */
    fun run(request: RunRequest): Flow<RunEvent>
}
