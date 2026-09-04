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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    private val openMethod: Method =
        backend.getMethod("open", String::class.java, String::class.java)
    private val diagnosticsMethod: Method =
        backend.getMethod("diagnostics", String::class.java)
    private val completeMethod: Method =
        backend.getMethod("complete", String::class.java, Int::class.javaPrimitiveType)
    private val signatureMethod: Method =
        backend.getMethod("signatureAt", String::class.java, Int::class.javaPrimitiveType)
    private val definitionMethod: Method =
        backend.getMethod("definitionAt", String::class.java, Int::class.javaPrimitiveType)
    private val closeMethod: Method = backend.getMethod("close")

    private val stdlib: File = prepared.stdlib

    /**
     * One query at a time.
     *
     * The Analysis API is not thread-safe across sessions, and the editor asks
     * on every keystroke -- so two requests overlap routinely, and the second
     * would otherwise resolve against a half-built dangling file. A mutex
     * rather than a single-threaded dispatcher because the expensive call is
     * the first one and callers should be able to cancel while it runs.
     */
    private val lock = Mutex()
    private var opened = false

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
            if (!ensureOpen()) emptyList() else records(diagnosticsMethod.invoke(null, text))
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
                records(completeMethod.invoke(null, text, offset))
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
                val fields = (definitionMethod.invoke(null, text, offset) as String)
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
                (signatureMethod.invoke(null, text, offset) as String)
                    .takeIf { it.isNotBlank() && !it.startsWith("ERR ") }
            }
        }

    override fun close() {
        runCatching { closeMethod.invoke(null) }
        opened = false
    }

    private companion object {
        const val TAG = "KotlinLanguageService"
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
        val roots = listOf(File(projectRoot, "src/main/java"), File(projectRoot, "src/main/kotlin"))
            .filter { it.isDirectory }
            .ifEmpty { listOf(projectRoot) }
        val libraries = (listOf(stdlib) + classpath)
            .filter { it.isFile }
            .joinToString(File.pathSeparator) { it.absolutePath }
        val result = runCatching {
            openMethod.invoke(
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
        return true
    }

    /**
     * On the **compiler** dispatcher, not IO.
     *
     * Resolution is CPU-bound front-end work, which is what that pool is for --
     * `DispatcherProvider` keeps it separate precisely so it cannot starve the
     * editor's file reads and autosave. Putting analysis on `io` would do
     * exactly that, on every keystroke.
     */
    private suspend fun <T> query(body: () -> T): T =
        withContext(dispatchers.compiler) { lock.withLock { body() } }

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
