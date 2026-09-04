/**
 * Just enough of JMX for IntelliJ's low-memory watcher to link.
 *
 * **Android has no `javax.management`.** IntelliJ's `AppScheduledExecutorService`
 * constructs a `LowMemoryWatcherManager`, which listens for memory-pool
 * notifications through JMX -- so the very first use of any IntelliJ executor
 * dies with:
 *
 * <pre>
 * NoClassDefFoundError: Failed resolution of: Ljavax/management/NotificationListener;
 *   at …util.concurrency.AppScheduledExecutorService.&lt;init&gt;
 * </pre>
 *
 * These four types are exactly what that path references -- no more -- and they
 * exist to be *linked against*, not called. Nothing on Android will ever emit a
 * memory notification into them, so the watcher simply never fires; the JVM's
 * memory pressure story is not one Android tells this way anyway.
 *
 * They live in this archive rather than the compiler's for a practical reason:
 * the compiler archive is a published, checksum-pinned 54 MB component, and the
 * classloader is flat (see `AnalysisSessionTest`), so a class supplied here
 * resolves for code loaded from there.
 *
 * The signatures are the JDK's. They have to be, or the shaded IntelliJ that
 * was compiled against the real ones will not link to these.
 */
package javax.management;
