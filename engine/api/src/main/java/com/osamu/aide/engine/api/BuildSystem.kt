package com.osamu.aide.engine.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile

/**
 * An engine that turns a project into an installable APK.
 *
 * Implemented twice: by the bundled fast pipeline, and by a bridge to Gradle in
 * the optional rootfs. Which one a project uses is [com.osamu.aide.core.fs.Project.engine].
 */
interface BuildSystem {

    /**
     * Builds [request], reporting progress as it goes.
     *
     * Cold: nothing runs until collected, and cancelling the collection cancels
     * the build. The flow always ends with [BuildEvent.Finished] -- a build that
     * fails completes normally carrying a [BuildResult.Failure], and only a
     * defect in the engine itself throws.
     */
    fun build(request: BuildRequest): Flow<BuildEvent>
}

/**
 * Runs a build and returns only how it ended, discarding progress.
 *
 * For callers that cannot show progress anyway -- tests, and any "build then
 * install" path where the UI is already listening on its own collection.
 */
suspend fun Flow<BuildEvent>.awaitResult(): BuildResult {
    var result: BuildResult? = null
    // transformWhile rather than first(): it stops collecting at Finished, so
    // the build is not left running after its result is known.
    transformWhile { event ->
        if (event is BuildEvent.Finished) {
            result = event.result
            emit(event)
            false
        } else {
            true
        }
    }.collect { }
    return checkNotNull(result) {
        "build flow completed without emitting BuildEvent.Finished"
    }
}
