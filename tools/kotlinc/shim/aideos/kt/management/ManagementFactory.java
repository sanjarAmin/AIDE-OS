package aideos.kt.management;

import java.util.Collections;
import java.util.List;

/**
 * Android has no java.lang.management. The Kotlin compiler reaches for it only
 * to report build timings and to dump threads on error, so returning inert
 * beans costs nothing that matters for compilation.
 *
 * References in the compiler's bytecode are rewritten from
 * `java/lang/management/` to `aideos/kt/management/` -- both are exactly 21
 * characters, so the constant pool is patched byte-for-byte with no reassembly.
 */
public final class ManagementFactory {

    private ManagementFactory() {}

    private static final ThreadMXBean THREAD_BEAN = new ThreadMXBean() {
        // Not true CPU time, but monotonic and in nanoseconds, so the
        // compiler's duration arithmetic yields sane numbers rather than zeros.
        @Override public long getCurrentThreadCpuTime() { return System.nanoTime(); }
        @Override public long getCurrentThreadUserTime() { return System.nanoTime(); }
        @Override public void setThreadCpuTimeEnabled(boolean enable) {}
        @Override public ThreadInfo[] dumpAllThreads(boolean m, boolean s) { return new ThreadInfo[0]; }
        @Override public long[] getAllThreadIds() { return new long[0]; }
        @Override public ThreadInfo[] getThreadInfo(long[] ids, int maxDepth) { return new ThreadInfo[0]; }
    };

    private static final CompilationMXBean COMPILATION_BEAN = () -> 0L;

    private static final MemoryMXBean MEMORY_BEAN = new MemoryMXBean() {
        @Override public MemoryUsage getHeapMemoryUsage() { return new MemoryUsage(); }
        @Override public MemoryUsage getNonHeapMemoryUsage() { return new MemoryUsage(); }
    };

    public static ThreadMXBean getThreadMXBean() { return THREAD_BEAN; }
    public static CompilationMXBean getCompilationMXBean() { return COMPILATION_BEAN; }
    public static MemoryMXBean getMemoryMXBean() { return MEMORY_BEAN; }

    public static List<GarbageCollectorMXBean> getGarbageCollectorMXBeans() {
        return Collections.emptyList();
    }

    public static List<MemoryPoolMXBean> getMemoryPoolMXBeans() {
        return Collections.emptyList();
    }
}
