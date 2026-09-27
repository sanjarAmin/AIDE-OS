package aideos.kt.management;

public final class MemoryUsage {
    public long getInit() { return 0L; }
    public long getUsed() { return 0L; }
    public long getCommitted() { return 0L; }
    /** -1 is the JDK's own "no limit configured" value, so callers handle it. */
    public long getMax() { return -1L; }
}
