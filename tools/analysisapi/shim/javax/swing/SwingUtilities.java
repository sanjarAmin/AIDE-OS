package javax.swing;

/**
 * Enough Swing for IntelliJ to ask whether it is on the event dispatch thread.
 *
 * **Android has no Swing, and no EDT.** IntelliJ's `MockApplication.isDispatchThread`
 * calls `SwingUtilities.isEventDispatchThread()`, and the Analysis API calls
 * *that* on the way into every `analyze {}` block -- its permission checker
 * refuses analysis on the EDT, because on a desktop IDE that would freeze the
 * UI. So the first query dies with:
 *
 * <pre>
 * NoClassDefFoundError: Failed resolution of: Ljavax/swing/SwingUtilities;
 *   at …mock.MockApplication.isDispatchThread
 *   at …permissions.KaBaseAnalysisPermissionChecker.isProhibitedEdtAnalysis
 * </pre>
 *
 * **`false` is the right answer, not a convenient one.** There is no Swing event
 * dispatch thread in this process, so no thread here is ever on it, and the
 * check the API is making -- "am I about to block the UI thread?" -- is
 * correctly answered "no". Android's own main thread is a different thing that
 * Swing knows nothing about; keeping analysis off *it* is the caller's job, and
 * on this project that is what the `:build` process and the language-service
 * dispatchers are for.
 */
public final class SwingUtilities {

    private SwingUtilities() {
    }

    /** Always false: this process has no Swing event dispatch thread. */
    public static boolean isEventDispatchThread() {
        return false;
    }

    /**
     * Runs the work on the caller's thread.
     *
     * Present because code that asks the question above often follows it with
     * this. With no EDT to hand off to, running inline is the only behaviour
     * available and the only one that preserves ordering.
     */
    public static void invokeLater(Runnable doRun) {
        doRun.run();
    }

    /** Likewise, and already synchronous by contract. */
    public static void invokeAndWait(Runnable doRun) {
        doRun.run();
    }
}
