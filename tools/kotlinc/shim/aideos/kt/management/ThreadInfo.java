package aideos.kt.management;

/** Stand-in for java.lang.management.ThreadInfo, which Android does not ship. */
public final class ThreadInfo {
    private final Thread thread;

    ThreadInfo(Thread thread) { this.thread = thread; }

    public String getThreadName() { return thread == null ? "" : thread.getName(); }
    public Thread.State getThreadState() { return thread == null ? Thread.State.NEW : thread.getState(); }
    public StackTraceElement[] getStackTrace() {
        return thread == null ? new StackTraceElement[0] : thread.getStackTrace();
    }
    public String getLockName() { return null; }
    public String getLockOwnerName() { return null; }
    public long getLockOwnerId() { return -1L; }
    public boolean isInNative() { return false; }
    public boolean isSuspended() { return false; }
}
