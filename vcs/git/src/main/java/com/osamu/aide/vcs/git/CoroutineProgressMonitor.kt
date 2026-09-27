package com.osamu.aide.vcs.git

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import org.eclipse.jgit.lib.ProgressMonitor

/**
 * Bridges JGit's progress reporting to a callback, and coroutine cancellation
 * to JGit's.
 *
 * The cancellation half is the important one. JGit's network operations are
 * blocking calls on a thread; cancelling the coroutine that launched one does
 * **not** interrupt it, so without this a user who taps Cancel on a 254 s clone
 * watches it run to completion behind a dismissed dialog, writing 179 MB they
 * asked not to have. `isCancelled` is polled by JGit between work units, and
 * answering it from the scope's own [CoroutineScope.isActive] is what makes the
 * cancel real.
 *
 * [onProgress] is called from JGit's thread, not the caller's. It must not
 * touch UI state directly; a caller wanting that should hop dispatchers itself
 * rather than have this class guess which one.
 */
internal class CoroutineProgressMonitor(
    private val scope: CoroutineScope,
    private val onProgress: (GitProgress) -> Unit,
) : ProgressMonitor {

    private var title: String = ""
    private var total: Int? = null
    private var completed: Int = 0

    override fun start(totalTasks: Int) = Unit

    override fun beginTask(title: String?, totalWork: Int) {
        this.title = title.orEmpty()
        // JGit signals "I do not know how much work this is" with a constant,
        // not with a zero or a negative. Reported as null so a caller shows a
        // phase name rather than a bar stuck at zero.
        this.total = totalWork.takeIf { it != ProgressMonitor.UNKNOWN }
        this.completed = 0
        onProgress(GitProgress.Task(this.title, this.total))
    }

    override fun update(completed: Int) {
        this.completed += completed
        onProgress(GitProgress.Work(title, this.completed, total))
    }

    override fun endTask() = Unit

    /** Polled by JGit between work units; this is what makes a cancel real. */
    override fun isCancelled(): Boolean = !scope.isActive

    /**
     * JGit 6.1 added this so a monitor can say it wants byte counts rather than
     * object counts. Declined: this reports whatever the task chose, and a
     * caller that wants bytes would need JGit to be counting them, which it is
     * not for every phase.
     */
    override fun showDuration(enabled: Boolean) = Unit
}
