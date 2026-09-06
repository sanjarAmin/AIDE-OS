package com.osamu.aide.lsp.kotlin

import android.util.Log
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import com.osamu.aide.lsp.api.CompletionItem
import com.osamu.aide.lsp.api.CompletionKind
import com.osamu.aide.lsp.api.LanguageService
import com.osamu.aide.lsp.api.SourceLocation
import dalvik.system.PathClassLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.lang.reflect.Method

/**
 * Kotlin intelligence, from the Analysis API running in this process.
 *
 * The third shape of language service in this project, and it is neither of the
 * other two. `:lsp:java` holds a warm javac and calls it directly; `:lsp:native`
 * talks to a clangd subprocess. This one runs in-process like the first, but
 * behind a classloader nothing here can name -- so every call crosses a
 * reflection boundary into `KotlinBackend`, which lives inside the archive.
 * `tools/analysisapi/probe/../backend/KotlinBackend.kt` says why that is not
 * avoidable and why the wire format is `List<String>`.
 *
 * **The session is resident, and that is the whole design.** Building it costs
 * ~1.8 s; a query against one that is up costs ~59 ms, inside the 200 ms budget
 * M3 holds Java completion to. A service constructed per request would be
 * slower than having none -- the same conclusion spike R3 reached for javac,
 * and the reason [close] is not optional.
 *
 * What this does not do yet: extension completion. `String` offers its members
 * and not `uppercase`, because the star-importing scope will not enumerate its
 * name universe. `tools/analysisapi/FINDINGS.md` section 16 is the diagnosis and
 * what was ruled out.
 */
class KotlinLanguageService(
    private val archives: KotlinArchives,
    private val projectRoot: File,
    private val dispatchers: DispatcherProvider,
    /**
     * `android.jar` and the project's dependency jars, as library modules.
     *
     * **Not optional, and leaving it empty is not a smaller version of this
     * feature.** A session that cannot see `android.jar` resolves `Activity`,
     * `Bundle` and `onCreate` to nothing, and the front end is right to say so
     * -- so a freshly created, perfectly correct project comes back with
     * fifteen errors blaming the user for a toolchain they have not installed.
     * That is what driving the app showed, and it is the same trap
     * `LanguageServices.forProject` already documents for javac.
     *
     * A changed classpath needs a new service: the session holds a resolved
     * view of these and there is no way to add to it.
     */
    val classpath: List<File> = emptyList(),
) : LanguageService {

    /**
     * Declared first because Kotlin initialises properties in order and
     * everything below reads it: the jars have to be staged read-only before a
     * loader is pointed at them.
     */
    private val prepared: KotlinArchives.Prepared = archives.prepare()

    /**
     * Loaded eagerly; the **session** is not.
     *
     * Loading the classes is cheap and its failures are configuration ones
     * worth surfacing at construction. Building the session is the 1.8 s, and
     * it should not be paid by a project whose Kotlin files are never opened.
     */
    private val backend: Class<*> = load()

    /**
     * The backend's entry points, or null when the installed archive is older
     * than this app.
     *
     * **Resolved defensively, because the component ships separately.** These
     * were `val x: Method = backend.getMethod(...)`, which throws
     * `NoSuchMethodException` from the **constructor** for a backend that
     * predates a method -- and `LanguageServices.kotlinFor` constructs this
     * unguarded, on the editor's completion thread. A user whose installed
     * component is one release behind therefore got an exception per keystroke
     * rather than an editor without Kotlin intelligence.
     *
     * That is not hypothetical: the archive published as
     * `kotlin-analysis-2.2.10` has no `definitionAt`, which was added after it.
     * FINDINGS.md §27.
     */
    private val methods: Map<String, Method>? = runCatching {
        mapOf(
            "open" to backend.getMethod("open", String::class.java, String::class.java),
            "diagnostics" to backend.getMethod("diagnostics", String::class.java),
            "complete" to backend.getMethod(
                "complete", String::class.java, Int::class.javaPrimitiveType,
            ),
            "signatureAt" to backend.getMethod(
                "signatureAt", String::class.java, Int::class.javaPrimitiveType,
            ),
            "definitionAt" to backend.getMethod(
                "definitionAt", String::class.java, Int::class.javaPrimitiveType,
            ),
            "close" to backend.getMethod("close"),
        )
    }.onFailure {
        Log.w(TAG, "the installed Kotlin backend is not the one this app expects: ${it.message}")
    }.getOrNull()

    private val openMethod: Method? get() = methods?.get("open")
    private val diagnosticsMethod: Method? get() = methods?.get("diagnostics")
    private val completeMethod: Method? get() = methods?.get("complete")
    private val signatureMethod: Method? get() = methods?.get("signatureAt")
    private val definitionMethod: Method? get() = methods?.get("definitionAt")
    private val closeMethod: Method? get() = methods?.get("close")

    private val stdlib: File = prepared.stdlib

    private var opened = false

    /**
     * Where the warm-up runs. Cancelled by [close], so a service that is
     * replaced does not keep resolving against a project nobody is editing.
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.compiler)

    /**
     * The warm-up, exposed so a test can wait for it instead of sleeping.
     *
     * Null until the session opens. Joining it is the only way to measure what
     * a warmed session costs without a timing guess that is flaky on one
     * machine and slow on every other.
     */
    internal var warmUp: Job? = null
        private set

    /**
     * How many questions the *editor* has asked.
     *
     * The warm-up reads it to know when to get out of the way. See [warmUp].
     */
    @Volatile
    private var queries = 0L

    /**
     * Why the session would not open, remembered so it is not retried.
     *
     * **A failure here must not reach the editor as an exception.** These
     * methods are called on every keystroke, from sora's completion thread,
     * and `LanguageServices` invokes them without a guard -- so a session that
     * cannot build would throw on every character typed rather than once.
     * `LanguageService` says nothing is an ordinary answer; this is how that
     * stays true when the toolchain itself is broken.
     *
     * Not retried, because the causes are permanent within a service: a corrupt
     * archive, or a project the front end will not accept. Rebuilding takes
     * seconds and would be paid per keystroke to fail the same way. A service
     * is constructed fresh when the project or classpath changes, which is the
     * point at which a retry makes sense.
     */
    private var openFailure: String? = null

    override fun handles(file: File): Boolean =
        file.extension == "kt" || file.extension == "kts"

    override suspend fun diagnostics(file: File, text: String): List<Diagnostic> =
        query {
            if (!ensureOpen()) emptyList() else records(diagnosticsMethod?.invoke(null, text))
        }
            .mapNotNull { fields ->
                if (fields.size < 4) return@mapNotNull null
                Diagnostic(
                    severity = when (fields[0]) {
                        "ERROR" -> DiagnosticSeverity.ERROR
                        "WARNING" -> DiagnosticSeverity.WARNING
                        else -> DiagnosticSeverity.INFO
                    },
                    message = fields[3],
                    // Relative, because that is what Diagnostic documents and
                    // what the editor's gutter can match against an open tab.
                    file = file.relativeToOrNull(projectRoot) ?: file,
                    line = fields[1].toIntOrNull() ?: Diagnostic.UNKNOWN,
                    column = fields[2].toIntOrNull() ?: Diagnostic.UNKNOWN,
                )
            }

    override suspend fun complete(file: File, text: String, offset: Int): List<CompletionItem> =
        query {
            if (!ensureOpen()) {
                emptyList()
            } else {
                records(completeMethod?.invoke(null, text, offset))
            }
        }
            .mapNotNull { fields ->
                if (fields.size < 4) return@mapNotNull null
                CompletionItem(
                    label = fields[0],
                    kind = runCatching { CompletionKind.valueOf(fields[1]) }
                        .getOrDefault(CompletionKind.VARIABLE),
                    insert = fields[2],
                    detail = fields[3].ifBlank { null },
                )
            }

    /**
     * Where the thing under the caret is declared.
     *
     * **Null is the common answer and not a failure.** A caret on a keyword or
     * on whitespace has nothing to go to, and neither does one on a symbol
     * declared in a **library**: the stdlib, `android.jar` and every jar have
     * no source PSI, so there is nowhere to jump. Navigating into those would
     * need decompilation or source jars, and neither exists here.
     *
     * An empty path from the backend means the declaration is in the buffer
     * being edited -- the dangling file has no path on disk, and answering with
     * its made-up name would send the editor to a file that does not exist.
     */
    override suspend fun definition(file: File, text: String, offset: Int): SourceLocation? =
        query {
            if (!ensureOpen()) {
                null
            } else {
                val fields = (definitionMethod?.invoke(null, text, offset) as? String).orEmpty()
                    .takeIf { it.isNotBlank() && !it.startsWith("ERR ") }
                    ?.split('\t')
                if (fields == null || fields.size < 4) {
                    null
                } else {
                    val target = fields[0].ifBlank { null }?.let(::File) ?: file
                    SourceLocation(
                        file = target.relativeToOrNull(projectRoot) ?: target,
                        line = fields[1].toIntOrNull() ?: return@query null,
                        column = fields[2].toIntOrNull() ?: return@query null,
                        endColumn = fields[3].toIntOrNull() ?: return@query null,
                    )
                }
            }
        }

    override suspend fun signatureAt(file: File, text: String, offset: Int): String? =
        query {
            if (!ensureOpen()) {
                null
            } else {
                (signatureMethod?.invoke(null, text, offset) as? String).orEmpty()
                    .takeIf { it.isNotBlank() && !it.startsWith("ERR ") }
            }
        }

    /**
     * Stops the warm-up and closes the session, **without blocking the caller**.
     *
     * Two constraints pull against each other here. The session may not be
     * closed while a query is running -- cancelling the warm-up does not
     * interrupt a reflective call, so one can still be in flight -- and this is
     * called from `WorkspaceViewModel.onCleared()`, which is **the main
     * thread**. An earlier version took the lock inline and got the first right
     * by getting the second wrong: leaving a project could block the UI for a
     * whole query, and the first query after a session build is measured in
     * seconds.
     *
     * So the wait happens on the compiler dispatcher instead. Ordering against
     * the *next* session survives because the lock is process-wide: a new
     * service's first query takes the same mutex and therefore lands after this
     * teardown, without anything on the main thread waiting for it.
     */
    override fun close() {
        if (!opened && warmUp == null) return
        warmUp?.cancel()
        opened = false
        teardownPending = true
        scope.launch { lock.withLock { closeAbandonedSession() } }
    }

    private companion object {
        const val TAG = "KotlinLanguageService"

        /**
         * One query at a time, **across every instance**.
         *
         * The Analysis API is not thread-safe across sessions, and the editor
         * asks on every keystroke -- so two requests overlap routinely, and the
         * second would otherwise resolve against a half-built dangling file. A
         * mutex rather than a single-threaded dispatcher because the expensive
         * call is the first one and callers should be able to cancel while it
         * runs.
         *
         * **It is `companion` because the thing it guards is `static`.**
         * `KotlinBackend` holds one session per process -- every call here is
         * `invoke(null, ...)` -- so a per-instance lock let two services
         * believe they were excluding each other while both drove the same
         * session. That window is real: `LanguageServices` closes the old
         * service and builds a new one whenever the project or classpath
         * changes, and [close] hands its teardown to a coroutine rather than
         * blocking the caller. Sharing the lock is what actually orders a
         * teardown against the next session's first query.
         */
        val lock = Mutex()

        /**
         * A session has been abandoned and not yet closed.
         *
         * [close] does its work on a coroutine so the caller is not blocked,
         * which means the teardown and the *next* service's open are two jobs
         * racing for [lock] -- and the lock says only that they do not overlap,
         * not which goes first. Losing that race closes the session the new
         * service has just opened, and every query after it answers nothing.
         * Three tests in the neighbouring suite started failing with empty
         * results the moment a second test class began opening sessions.
         *
         * So the flag is set synchronously in [close], and whoever reaches the
         * lock first settles it: the teardown coroutine if it wins, and
         * [ensureOpen] itself if it does not. Ordering no longer depends on the
         * race.
         */
        @Volatile
        var teardownPending = false

        /**
         * Enough to reach the flat part of the curve above, and no more. The
         * cost is background time on a session that is already up.
         */
        const val WARM_UP_QUERIES = 20

        /** Long enough for a keystroke already in flight to be counted. */
        const val WARM_UP_START_DELAY_MS = 400L

        /**
         * A receiver of a library type with a partial member name -- the shape
         * that exercises the most of the query path: parsing a dangling file,
         * resolving an expression's type, enumerating a library type's members
         * and running the extension index against a prefix.
         */
        val WARM_UP_BUFFER = """
            package aide.warmup

            fun warmUp() {
                val text: String = ""
                text.le
            }
        """.trimIndent()

        val WARM_UP_OFFSET = WARM_UP_BUFFER.indexOf("text.le") + "text.le".length
    }

    /**
     * Builds the session on first use, against the project's Kotlin sources.
     *
     * The stdlib goes in beside [classpath] rather than instead of it: without
     * a library module at all the session still answers for `String`, because
     * those are builtins the front end carries, so its absence looks like
     * success. FINDINGS.md section 16.
     */
    private fun ensureOpen(): Boolean {
        if (opened) return true
        openFailure?.let { return false }
        val open = openMethod ?: run {
            openFailure = "the installed Kotlin component is older than this app expects"
            Log.w(TAG, "Kotlin is silent here: $openFailure")
            return false
        }
        // Before building one, finish closing any the last service left behind.
        closeAbandonedSession()
        val roots = listOf(File(projectRoot, "src/main/java"), File(projectRoot, "src/main/kotlin"))
            .filter { it.isDirectory }
            .ifEmpty { listOf(projectRoot) }
        val libraries = (listOf(stdlib) + classpath)
            .filter { it.isFile }
            .joinToString(File.pathSeparator) { it.absolutePath }
        val result = runCatching {
            open.invoke(
                null,
                roots.joinToString(File.pathSeparator) { it.absolutePath },
                libraries,
            ) as String
        }.getOrElse { "ERR ${it.cause?.message ?: it.message}" }

        if (result != "OK") {
            openFailure = result
            Log.w(TAG, "the Kotlin session would not open, so Kotlin is silent here: $result")
            return false
        }
        opened = true
        warmUp()
        return true
    }

    /**
     * Closes a session abandoned by a previous service. **Call holding [lock].**
     */
    private fun closeAbandonedSession() {
        if (!teardownPending) return
        teardownPending = false
        runCatching { closeMethod?.invoke(null) }
    }

    /**
     * Answers a few questions nobody asked, so the user's first ones are fast.
     *
     * **A built session is not a warm one.** Measured on the emulator, the same
     * completion repeated thirty times against one session:
     *
     * ```
     * call 1        4064 ms   building the session
     * calls 2-6     ~200 ms   over M3's 200 ms budget
     * calls 7-16    ~130 ms
     * calls 17-30   ~107 ms   steady state
     * ```
     *
     * So the ~220 ms this project recorded as "warm completion" was measured
     * two to five calls in, on the plateau -- not at rest.
     * `tools/analysisapi/FINDINGS.md` §25.
     *
     * The warm-up is **global, not per query shape**, which is what makes this
     * worth doing: running five different completion shapes in one order and
     * then the reverse, the medians tracked the call count and not the query --
     * the shape that cost 164 ms when it ran first cost 92 ms when it ran last.
     * So questions about a buffer the user has never seen warm the ones they
     * will actually ask.
     *
     * **In the background, one lock acquisition at a time.** The session build
     * is already paid off the keystroke path -- diagnostics run when the file
     * opens -- and this must not be added to it, or the first diagnostic would
     * arrive three seconds later than it does now. Taking the lock per call
     * rather than holding it for all of them means a real keystroke waits for
     * at most one warm-up query, and those queries were going to cost the same
     * whether they were the user's or ours.
     */
    private fun warmUp() {
        if (warmUp != null) return
        // The query that opened the session has already been counted, so
        // anything past this is the editor asking something new.
        val asOfOpen = queries
        warmUp = scope.launch {
            // **A beat before the first one, so a busy editor wins the race.**
            // The check below only sees queries that have *started*; an editor
            // typing the instant the session opens would otherwise queue behind
            // warm-up query one and pay for both. Waiting costs nothing when
            // nobody is typing, which is the only case this is for.
            delay(WARM_UP_START_DELAY_MS)

            var done = 0
            while (done < WARM_UP_QUERIES && isActive) {
                // **Stops the moment the editor wants the session.** Warming
                // and answering contend for the same lock, and a real query
                // that queues behind a warm-up one pays for both: measured, a
                // completion during the warm-up cost 371 ms against 188 ms
                // without it. That is a regression at the exact moment latency
                // is most visible -- a person typing.
                //
                // Giving up then costs nothing, because a user who is already
                // typing warms the session with their own queries, which is
                // what happened before any of this existed. The warm-up only
                // ever spends time nobody was waiting on.
                if (queries > asOfOpen) {
                    Log.i(TAG, "warm-up yielded to the editor after $done queries")
                    return@launch
                }
                lock.withLock {
                    runCatching { completeMethod?.invoke(null, WARM_UP_BUFFER, WARM_UP_OFFSET) }
                }
                done++
                yield()
            }
            Log.i(TAG, "Kotlin session warmed with $done queries")
        }
    }

    /**
     * On the **compiler** dispatcher, not IO.
     *
     * Resolution is CPU-bound front-end work, which is what that pool is for --
     * `DispatcherProvider` keeps it separate precisely so it cannot starve the
     * editor's file reads and autosave. Putting analysis on `io` would do
     * exactly that, on every keystroke.
     */
    private suspend fun <T> query(body: () -> T): T {
        queries++
        return withContext(dispatchers.compiler) { lock.withLock { body() } }
    }

    /**
     * Splits the backend's records, dropping its error sentinel.
     *
     * **Failure arrives as a value, and dropping it here is deliberate.** These
     * are asked on keystrokes against a buffer that usually does not parse; the
     * contract in `LanguageService` is that nothing is an ordinary answer. An
     * error that mattered would have to reach the user as something better than
     * a squiggle on every character they type.
     */
    private fun records(raw: Any?): List<List<String>> {
        @Suppress("UNCHECKED_CAST")
        val lines = raw as? List<String> ?: return emptyList()
        if (lines.size == 1 && lines.first().startsWith("ERR\t")) return emptyList()
        return lines.map { it.split('\t') }
    }

    private fun load(): Class<*> {
        // **One flat loader, parented to boot.** Chaining the archives fails:
        // IntelliJ's MockComponentManager resolves plugin classes with
        // Class.forName, which uses its own loader -- the parent -- and a parent
        // cannot see a child, so the error is a ClassNotFoundException for a
        // class plainly present in the dex. Parenting to the *app's* loader is
        // the other trap: the archive would inherit the app's own kotlin-stdlib
        // synthetics. FINDINGS.md sections 9 and 7.
        val loader = PathClassLoader(prepared.dexPath, null)
        return loader.loadClass("com.osamu.aide.analysisapi.backend.KotlinBackend")
    }
}
