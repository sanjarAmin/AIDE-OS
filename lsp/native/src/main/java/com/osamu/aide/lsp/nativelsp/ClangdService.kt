package com.osamu.aide.lsp.nativelsp

import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import com.osamu.aide.lsp.api.CompletionItem
import com.osamu.aide.lsp.api.CompletionKind
import com.osamu.aide.lsp.api.LanguageService
import com.osamu.aide.lsp.api.SourceLocation
import com.osamu.aide.toolchain.nativetools.ClangToolchain
import com.osamu.aide.toolchain.nativetools.LinkerLaunch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * C and C++ intelligence, from a `clangd` subprocess.
 *
 * The opposite arrangement to [com.osamu.aide.lsp.java.JavaLanguageService] in
 * every respect that matters: the work happens in another process, answers
 * arrive asynchronously, and the server is a downloaded binary that cannot be
 * executed directly -- it is started through the platform's dynamic linker, the
 * same route the compiler uses.
 *
 * **Started lazily, on the first question.** clangd is 18.6 MB against a
 * 139 MB `libLLVM.so`, and most sessions never open a C file; paying that at
 * project-open would tax every user for a feature few need.
 *
 * `tools/clang/FINDINGS.md` §9 records what makes this work at all: no
 * `--query-driver`, and a `compile_flags.txt` supplying what it would otherwise
 * have executed the compiler to discover.
 */
class ClangdService(
    private val clang: ClangToolchain,
    private val projectRoot: File,
    private val dispatchers: DispatcherProvider,
    private val launch: LinkerLaunch = LinkerLaunch.forThisProcess(),
) : LanguageService {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private var process: Process? = null
    private var connection: LspConnection? = null
    private var started = false

    /** Open documents, the version last sent for each, and what was sent. */
    private val versions = mutableMapOf<String, Int>()
    private val lastText = mutableMapOf<String, String>()

    /** The latest diagnostics per URI, and whoever is waiting for the next set. */
    private val published = mutableMapOf<String, List<Diagnostic>>()
    private val waiting = mutableMapOf<String, CompletableDeferred<List<Diagnostic>>>()

    override fun handles(file: File): Boolean =
        file.extension.lowercase() in HANDLED

    private fun uriOf(file: File) = "file://${file.absolutePath}"

    /**
     * Starts the server, once.
     *
     * Returns false when there is no usable toolchain, which is an ordinary
     * state -- the answer is then silence rather than an error, for the reason
     * every method here returns nothing rather than throwing.
     */
    private suspend fun ensureStarted(): Boolean = withContext(dispatchers.io) {
        if (started) return@withContext connection != null
        started = true
        if (!clang.isInstalled || !launch.isAvailable) return@withContext false

        val binary = clang.languageServer() ?: return@withContext false

        CompileFlags.write(projectRoot, clang)

        val plan = launch.plan(
            executable = binary,
            arguments = listOf(
                "--compile-commands-dir=${projectRoot.absolutePath}",
                // Its own logging goes to stderr and is noise here; the
                // diagnostics that matter arrive as protocol messages.
                "--log=error",
                // Background indexing walks the whole project. Worth it for
                // go-to-definition across files, and it is off the request path.
                "--background-index",
            ),
            libraryPath = clang.libraryPath(),
        )

        val started = runCatching {
            ProcessBuilder(plan.command)
                .directory(projectRoot)
                .also { it.environment().putAll(plan.environment) }
                .start()
        }.getOrNull() ?: return@withContext false

        process = started
        val link = LspConnection(started.inputStream, started.outputStream, scope)
        link.on("textDocument/publishDiagnostics") { message ->
            val params = message.optJSONObject("params") ?: return@on
            val uri = params.optString("uri")
            val list = diagnosticsOf(params.optJSONArray("diagnostics"), uri)
            synchronized(published) {
                published[uri] = list
                waiting.remove(uri)?.complete(list)
            }
        }
        link.start()
        connection = link

        val result = link.request(
            "initialize",
            JSONObject()
                .put("processId", JSONObject.NULL)
                .put("rootUri", "file://${projectRoot.absolutePath}")
                .put("capabilities", clientCapabilities()),
            INITIALIZE_TIMEOUT_MS,
        )
        if (result == null) {
            close()
            return@withContext false
        }
        link.notify("initialized", JSONObject())
        true
    }


    /**
     * What this client understands.
     *
     * **Not optional, and an empty object is not a neutral default.** A server
     * tailors its replies to what the client says it can read, so announcing
     * nothing makes clangd fall back to its most conservative output: bare
     * completion labels with no signature, no `kind` on any item -- so every
     * proposal draws the same icon -- and hover as legacy `MarkedString`
     * instead of `MarkupContent`. All of it looks like the server working
     * poorly rather than the client having asked for less.
     *
     * `snippetSupport` is false deliberately. With it on, clangd returns
     * `repeat(${1:int times})` as the text to insert, and an editor that does
     * not implement tab stops puts that literal string in the buffer.
     */
    private fun clientCapabilities(): JSONObject = JSONObject().put(
        "textDocument",
        JSONObject()
            .put(
                "completion",
                JSONObject()
                    .put(
                        "completionItem",
                        JSONObject()
                            .put("snippetSupport", false)
                            .put("documentationFormat", JSONArray().put("plaintext")),
                    )
                    .put(
                        "completionItemKind",
                        // The kinds LSP defines, 1..25. Saying which are
                        // understood is what makes the server send any at all.
                        JSONObject().put(
                            "valueSet",
                            JSONArray().apply { for (kind in 1..25) put(kind) },
                        ),
                    ),
            )
            .put("hover", JSONObject().put("contentFormat", JSONArray().put("markdown").put("plaintext")))
            .put("definition", JSONObject().put("linkSupport", false))
            .put("publishDiagnostics", JSONObject().put("relatedInformation", false)),
    )

    /**
     * Tells the server what the buffer holds now.
     *
     * Full text on every change rather than incremental edits. clangd accepts
     * both; incremental would mean this class tracking the editor's edit
     * history to describe ranges, and getting that wrong desynchronises the
     * server silently -- it answers confidently about a file that no longer
     * exists as it thinks.
     */
    private fun sync(link: LspConnection, file: File, text: String): Boolean {
        val uri = uriOf(file)
        if (lastText[uri] == text) return false
        val version = (versions[uri] ?: 0) + 1
        versions[uri] = version
        lastText[uri] = text
        if (version == 1) {
            link.notify(
                "textDocument/didOpen",
                JSONObject().put(
                    "textDocument",
                    JSONObject()
                        .put("uri", uri)
                        .put("languageId", if (file.extension.lowercase() == "c") "c" else "cpp")
                        .put("version", version)
                        .put("text", text),
                ),
            )
        } else {
            link.notify(
                "textDocument/didChange",
                JSONObject()
                    .put("textDocument", JSONObject().put("uri", uri).put("version", version))
                    .put(
                        "contentChanges",
                        JSONArray().put(JSONObject().put("text", text)),
                    ),
            )
        }
        return true
    }

    /**
     * Sends the buffer and waits until clangd has actually parsed it.
     *
     * **Asking a question before the parse finishes gets a confident wrong
     * answer.** clangd answers completion from an identifier index when it has
     * no AST yet: every proposal comes back with `kind: 1` (Text) and a label
     * with a leading space, so `repeat` is offered as a word that appears in
     * the file rather than as a method on the type -- alongside `return`, which
     * is not a member of anything. Nothing reports an error; the list is simply
     * junk, drawn with the wrong icons.
     *
     * The publication of diagnostics is the signal that an AST exists, so a
     * changed buffer waits for one. An unchanged buffer sends nothing and waits
     * for nothing, which is the common case while a user reads code.
     */
    private suspend fun syncAndSettle(link: LspConnection, file: File, text: String) {
        val uri = uriOf(file)
        val parsed = CompletableDeferred<List<Diagnostic>>()
        synchronized(published) { waiting[uri] = parsed }
        if (!sync(link, file, text)) {
            synchronized(published) { waiting.remove(uri) }
            return
        }
        withTimeoutOrNull(PARSE_TIMEOUT_MS) { parsed.await() }
        synchronized(published) { waiting.remove(uri) }
    }

    override suspend fun diagnostics(file: File, text: String): List<Diagnostic> {
        val link = connect() ?: return emptyList()
        val uri = uriOf(file)
        val pending = CompletableDeferred<List<Diagnostic>>()
        synchronized(published) { waiting[uri] = pending }
        if (!sync(link, file, text)) {
            // Nothing changed, so nothing new will be published. What is known
            // is already the answer.
            synchronized(published) { waiting.remove(uri) }
            return synchronized(published) { published[uri].orEmpty() }
        }

        // Diagnostics are pushed, not answered, so there is nothing to
        // correlate against -- only the next publication for this file. A
        // timeout returns what was last known rather than nothing: a stale
        // squiggle is closer to the truth than a cleared gutter.
        return withTimeoutOrNull(DIAGNOSTICS_TIMEOUT_MS) { pending.await() }
            ?: synchronized(published) { published[uri].orEmpty() }
    }

    override suspend fun complete(file: File, text: String, offset: Int): List<CompletionItem> {
        val link = connect() ?: return emptyList()
        syncAndSettle(link, file, text)
        val position = Positions.of(text, offset)

        val result = link.request(
            "textDocument/completion",
            JSONObject()
                .put("textDocument", JSONObject().put("uri", uriOf(file)))
                .put("position", JSONObject().put("line", position.line).put("character", position.character)),
            REQUEST_TIMEOUT_MS,
        ) ?: return emptyList()

        // Either a bare array of items or a CompletionList carrying one. Both
        // are legal; clangd sends the second, but accepting the first costs a
        // line and removes a way for this to break against another server.
        val items = when (result) {
            is JSONArray -> result
            is JSONObject -> result.optJSONArray("items")
            else -> null
        } ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            // clangd pads a label with a leading space when it has no
            // signature to show beside it; that space is presentation, not name.
            val label = item.optString("label").trim()
            if (label.isEmpty()) return@mapNotNull null
            CompletionItem(
                label = label,
                kind = kindOf(item.optInt("kind", 0)),
                // insertText is what actually goes in the buffer; label carries
                // the signature clangd shows, which must not be typed.
                insert = item.optString("insertText").ifBlank { label.substringBefore('(') },
                detail = item.optString("detail").ifBlank { null },
            )
        }
    }

    override suspend fun definition(file: File, text: String, offset: Int): SourceLocation? {
        val link = connect() ?: return null
        syncAndSettle(link, file, text)
        val position = Positions.of(text, offset)

        val reply = link.request(
            "textDocument/definition",
            JSONObject()
                .put("textDocument", JSONObject().put("uri", uriOf(file)))
                .put("position", JSONObject().put("line", position.line).put("character", position.character)),
            REQUEST_TIMEOUT_MS,
        ) ?: return null

        val target = firstLocation(reply) ?: return null
        val uri = target.optString("uri").removePrefix("file://")
        val range = target.optJSONObject("range") ?: return null
        val start = range.optJSONObject("start") ?: return null
        val end = range.optJSONObject("end") ?: start

        return SourceLocation(
            file = relativise(File(uri)),
            // LSP counts from zero; SourceLocation and the editor count from one.
            line = start.optInt("line") + 1,
            column = start.optInt("character") + 1,
            endColumn = end.optInt("character") + 1,
        )
    }

    override suspend fun signatureAt(file: File, text: String, offset: Int): String? {
        val link = connect() ?: return null
        syncAndSettle(link, file, text)
        val position = Positions.of(text, offset)

        val reply = link.request(
            "textDocument/hover",
            JSONObject()
                .put("textDocument", JSONObject().put("uri", uriOf(file)))
                .put("position", JSONObject().put("line", position.line).put("character", position.character)),
            REQUEST_TIMEOUT_MS,
        ) ?: return null

        // clangd's hover is Markdown with the declaration in a fenced block.
        // The declaration is the useful line; the prose around it is not a
        // signature and would not fit where this is shown.
        val value = hoverText(reply) ?: return null
        return value.lineSequence()
            .dropWhile { !it.startsWith("```") }
            .drop(1)
            .takeWhile { !it.startsWith("```") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifBlank { null }
    }


    /**
     * The text of a hover reply, in any of the three shapes it may take.
     *
     * `MarkupContent` is what a client advertising `contentFormat` gets, but
     * `MarkedString` -- a bare string, or `{language, value}` -- and arrays of
     * either are still legal and are what an older server sends. Accepting all
     * of them costs a few lines; assuming one and getting another produces a
     * hover that is silently always empty.
     */
    private fun hoverText(reply: Any): String? {
        val contents = (reply as? JSONObject)?.opt("contents") ?: return null
        return when (contents) {
            is String -> contents
            is JSONObject -> contents.optString("value").ifBlank { null }
            is JSONArray -> (0 until contents.length())
                .mapNotNull { index ->
                    when (val entry = contents.opt(index)) {
                        is String -> entry
                        is JSONObject -> entry.optString("value").ifBlank { null }
                        else -> null
                    }
                }
                .joinToString("\n")
                .ifBlank { null }
            else -> null
        }
    }

    private suspend fun connect(): LspConnection? =
        if (ensureStarted()) connection else null

    /**
     * The first target of a definition reply, whatever shape it came in.
     *
     * `textDocument/definition` may answer with a single Location, an array of
     * them, or an array of LocationLinks -- which spell the range
     * `targetSelectionRange` instead. clangd picks based on what the client
     * advertised, and this client advertises nothing, so all three are
     * accepted rather than assumed.
     */
    private fun firstLocation(reply: Any): JSONObject? {
        val candidate = when (reply) {
            is JSONArray -> reply.optJSONObject(0)
            is JSONObject -> reply
            else -> null
        } ?: return null
        if (candidate.has("range")) return candidate
        // A LocationLink: the target uri and range are named differently.
        val range = candidate.optJSONObject("targetSelectionRange")
            ?: candidate.optJSONObject("targetRange")
            ?: return null
        return JSONObject()
            .put("uri", candidate.optString("targetUri"))
            .put("range", range)
    }

    private fun relativise(file: File): File {
        val root = runCatching { projectRoot.canonicalPath }.getOrDefault(projectRoot.path)
        val path = runCatching { file.canonicalPath }.getOrDefault(file.path)
        val prefix = root.trimEnd('/') + "/"
        return if (path.startsWith(prefix)) File(path.removePrefix(prefix)) else file
    }

    /**
     * [uri] names the file the diagnostics are about, and it has to reach the
     * [Diagnostic]s: `hasLocation` is false without a file, so the gutter
     * ignores them and a C++ file with errors renders as clean. LSP publishes
     * per-file, so the uri on the notification is the answer -- there is
     * nothing to infer.
     */
    private fun diagnosticsOf(array: JSONArray?, uri: String): List<Diagnostic> {
        if (array == null) return emptyList()
        val file = relativise(File(uri.removePrefix("file://")))
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val range = item.optJSONObject("range") ?: return@mapNotNull null
            val start = range.optJSONObject("start") ?: return@mapNotNull null
            Diagnostic(
                severity = severityOf(item.optInt("severity", 1)),
                message = item.optString("message"),
                file = file,
                line = start.optInt("line") + 1,
                column = start.optInt("character") + 1,
            )
        }
    }

    private fun severityOf(value: Int): DiagnosticSeverity = when (value) {
        1 -> DiagnosticSeverity.ERROR
        2 -> DiagnosticSeverity.WARNING
        else -> DiagnosticSeverity.INFO
    }

    /** LSP's `CompletionItemKind`, reduced to the kinds the editor draws. */
    private fun kindOf(value: Int): CompletionKind = when (value) {
        2, 3, 4 -> CompletionKind.METHOD
        5, 10 -> CompletionKind.FIELD
        6 -> CompletionKind.VARIABLE
        7, 22, 23 -> CompletionKind.CLASS
        9 -> CompletionKind.PACKAGE
        14 -> CompletionKind.KEYWORD
        else -> CompletionKind.VARIABLE
    }

    /**
     * Stops the server.
     *
     * `destroyForcibly` rather than a polite `shutdown` exchange: this is
     * called when a project closes, the server has nothing to flush, and a
     * clangd left running holds a background index and a share of a 139 MB
     * library for a project nobody has open.
     */
    override fun close() {
        connection?.close()
        connection = null
        process?.destroyForcibly()
        process = null
        scope.cancel()
    }

    private companion object {
        val HANDLED = setOf("c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx")

        /** Generous: the first request pays for clangd faulting in libLLVM. */
        const val INITIALIZE_TIMEOUT_MS = 30_000L
        const val REQUEST_TIMEOUT_MS = 10_000L
        const val DIAGNOSTICS_TIMEOUT_MS = 15_000L

        /** How long to let a parse finish before asking anyway. */
        const val PARSE_TIMEOUT_MS = 15_000L
    }
}
