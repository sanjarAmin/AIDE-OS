package aideos.kt.management;

public interface ThreadMXBean {
    long getCurrentThreadCpuTime();
    long getCurrentThreadUserTime();
    void setThreadCpuTimeEnabled(boolean enable);
    ThreadInfo[] dumpAllThreads(boolean lockedMonitors, boolean lockedSynchronizers);
    long[] getAllThreadIds();
    ThreadInfo[] getThreadInfo(long[] ids, int maxDepth);
}
