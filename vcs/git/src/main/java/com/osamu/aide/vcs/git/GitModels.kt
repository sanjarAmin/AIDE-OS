package com.osamu.aide.vcs.git

/**
 * What the working tree looks like relative to HEAD and the index.
 *
 * Paths are repository-relative and use `/` on every platform, which is git's
 * own convention and not the device's -- a caller turning one back into a
 * `File` must resolve it against the work tree rather than treat it as a
 * system path.
 *
 * The sets are kept separate rather than folded into one "changed" list because
 * the distinction is the whole content of a git status screen: staged and
 * unstaged versions of the same file coexist, and a file can be in both.
 */
data class GitStatus(
    /** Staged: new to the index. */
    val added: Set<String> = emptySet(),
    /** Staged: content differs between HEAD and the index. */
    val changed: Set<String> = emptySet(),
    /** Staged: deleted from the index. */
    val removed: Set<String> = emptySet(),
    /** Unstaged: content differs between the index and the working tree. */
    val modified: Set<String> = emptySet(),
    /** Unstaged: gone from the working tree. */
    val missing: Set<String> = emptySet(),
    /** Not tracked and not ignored. */
    val untracked: Set<String> = emptySet(),
    /** Left in conflict by a merge. Nothing can be committed while non-empty. */
    val conflicting: Set<String> = emptySet(),
) {
    val isClean: Boolean
        get() = added.isEmpty() && changed.isEmpty() && removed.isEmpty() &&
            modified.isEmpty() && missing.isEmpty() && untracked.isEmpty() &&
            conflicting.isEmpty()

    /** Everything the index would commit right now. */
    val staged: Set<String> get() = added + changed + removed

    /** Everything differing from the index, ignoring untracked files. */
    val unstaged: Set<String> get() = modified + missing
}

/**
 * One commit, flattened to what a list on a phone screen can show.
 *
 * [id] is the full 40-character object name and is what any later operation
 * must use; [abbreviated] exists only to be displayed. Keeping both means no
 * caller is tempted to re-derive one from the other and get the length wrong.
 */
data class GitCommit(
    val id: String,
    val abbreviated: String,
    val summary: String,
    val fullMessage: String,
    val authorName: String,
    val authorEmail: String,
    /** Commit time in epoch milliseconds, in UTC. */
    val timestamp: Long,
)

/**
 * Progress for an operation that talks to a remote.
 *
 * Modelled on what JGit's `ProgressMonitor` actually reports rather than on
 * what a progress bar wants: it announces a task, then counts work units
 * against a total that **may be unknown**. Spike R6 measured a 254 s clone, so
 * the difference between "83%" and "still going" is the difference between a
 * user waiting and a user force-quitting.
 */
sealed interface GitProgress {
    /** A named phase began: "Receiving objects", "Resolving deltas". */
    data class Task(val title: String, val totalWork: Int?) : GitProgress

    /**
     * [completed] units of [total] done in the current task.
     *
     * [total] is null when JGit does not know it, which is common early in a
     * clone and is not a defect. A UI must handle it rather than divide by it.
     */
    data class Work(val title: String, val completed: Int, val total: Int?) : GitProgress

    /** Every task finished. Not a success signal -- the call's result is that. */
    data object Done : GitProgress
}
