package com.osamu.aide.lsp.java

import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.engine.api.Diagnostic
import com.sun.source.util.TreePath
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Java intelligence for one project: completion, diagnostics and go-to-
 * definition, in process.
 *
 * Holds a warm compiler, so it is meant to live as long as the project is open
 * and to be shared by everything that asks it questions. Constructing one per
 * request throws away the only thing making it fast enough to use -- see
 * [ResidentCompiler] and `tools/javals/FINDINGS.md`.
 *
 * Requests are serialised against each other by the compiler underneath. They
 * run on the compiler dispatcher rather than IO for the same reason
 * `:toolchain:native` does: this work is long and CPU-bound, and sharing the IO
 * pool would let completion stall the editor's file reads.
 */
class JavaLanguageService(
    platform: File,
    private val projectRoot: File,
    private val dispatchers: DispatcherProvider,
    /**
     * Readable so a caller can tell whether the service it is holding was built
     * for the classpath it now wants. There is no way to add to a warm
     * compiler's symbol table, so a change means a new service.
     */
    val classpath: List<File> = emptyList(),
    sourcePath: List<File> = listOf(File(projectRoot, "src/main/java")),
) {

    private val compiler = ResidentCompiler(platform, classpath, sourcePath)

    /** What javac says about [text], as the gutter renders it. */
    suspend fun diagnostics(file: File, text: String): List<Diagnostic> =
        withContext(dispatchers.compiler) {
            compiler.withCompilation(file, text) { compilation ->
                JavacDiagnostics.of(compilation.diagnostics.diagnostics, projectRoot)
            }
        }

    /**
     * Proposals for the cursor at [offset], a 0-based character index.
     *
     * Returns nothing rather than throwing when the cursor is somewhere no
     * proposal makes sense. A completion request is speculative by nature --
     * it fires on a keystroke, against a buffer that is mid-edit and usually
     * does not compile -- so "no answer" is an ordinary outcome and not a
     * failure worth surfacing.
     */
    suspend fun complete(file: File, text: String, offset: Int): List<CompletionItem> =
        withContext(dispatchers.compiler) {
            val prefix = prefixAt(text, offset)
            val source = withCursorAnchored(text, offset, prefix)

            compiler.withCompilation(file, source) { compilation ->
                val path: TreePath? = FindCursor(compilation.task)
                    .scan(compilation.unit, offset.toLong())
                if (path == null) emptyList() else {
                    runCatching { JavaCompletions.at(compilation, path, prefix) }
                        .getOrDefault(emptyList())
                }
            }
        }

    /**
     * Where the thing at [offset] was declared, or null if there is nowhere to
     * go.
     *
     * Null covers two ordinary cases as well as the failures: the cursor is on
     * nothing in particular, or it is on something declared in `android.jar`,
     * which has symbols but no source to open.
     */
    suspend fun definition(file: File, text: String, offset: Int): SourceLocation? =
        withContext(dispatchers.compiler) {
            compiler.withCompilation(file, text) { compilation ->
                val path = FindCursor(compilation.task).scan(compilation.unit, offset.toLong())
                    ?: return@withCompilation null
                runCatching { JavaDefinitions.at(compilation, path, projectRoot) }.getOrNull()
            }
        }

    /**
     * The signature of the call the cursor is inside, or null if it is not in
     * one.
     *
     * Cheap relative to completion only because the compiler is already warm;
     * it is the same parse and attribute, so callers should debounce it exactly
     * as they debounce diagnostics.
     */
    suspend fun signatureAt(file: File, text: String, offset: Int): String? =
        withContext(dispatchers.compiler) {
            compiler.withCompilation(file, text) { compilation ->
                runCatching { JavaSignatures.at(compilation, offset) }.getOrNull()
            }
        }

    /** The partial identifier immediately before the cursor. */
    private fun prefixAt(text: String, offset: Int): String {
        var start = offset.coerceIn(0, text.length)
        while (start > 0 && Character.isJavaIdentifierPart(text[start - 1])) start--
        return text.substring(start, offset.coerceIn(0, text.length))
    }

    /**
     * Gives the parser something to bind when the cursor sits just after a dot.
     *
     * `activity.` on its own does not parse as a member select; javac salvages
     * it into an erroneous tree whose expression is often gone, and there is
     * then nothing to ask for members of. Inserting an identifier at the cursor
     * makes it `activity.x`, which parses, resolves, and reports the same
     * member-select path the real request wants.
     *
     * Only when the prefix is empty: with any prefix typed the source already
     * parses, and rewriting it would move every position after the cursor.
     */
    private fun withCursorAnchored(text: String, offset: Int, prefix: String): String {
        if (prefix.isNotEmpty()) return text
        val cursor = offset.coerceIn(0, text.length)
        val previous = text.take(cursor).trimEnd().lastOrNull()
        return if (previous == '.') {
            text.substring(0, cursor) + CURSOR_ANCHOR + text.substring(cursor)
        } else {
            text
        }
    }

    private companion object {
        /**
         * Deliberately a legal identifier and deliberately unlikely. It is
         * filtered out by the empty prefix it stands in for, never proposed.
         */
        const val CURSOR_ANCHOR = "aideCursorAnchor"
    }
}
