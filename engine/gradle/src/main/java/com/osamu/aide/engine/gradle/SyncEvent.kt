package com.osamu.aide.engine.gradle

/**
 * What a sync is doing, as it does it.
 *
 * Deliberately not [com.osamu.aide.engine.api.BuildEvent]. A sync has no
 * stages, produces no APK and reports no compiler diagnostics -- it never
 * compiles anything -- so ending one with a `BuildResult` would mean either
 * inventing an artifact it does not have or a failure it did not suffer. The
 * panel that shows both keeps them apart for the same reason it keeps a run
 * apart from a build.
 */
sealed interface SyncEvent {

    /** Something to say before there is any progress to report. */
    data class Note(val message: String) : SyncEvent

    /** A module whose classpath has just been recorded, as Gradle names it. */
    data class Recorded(val module: String) : SyncEvent

    data class Finished(
        val succeeded: Boolean,
        val message: String,
        val durationMillis: Long,
    ) : SyncEvent
}
