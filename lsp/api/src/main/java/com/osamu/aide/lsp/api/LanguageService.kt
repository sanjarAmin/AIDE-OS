package com.osamu.aide.lsp.api

import com.osamu.aide.engine.api.Diagnostic
import java.io.File

/**
 * What the editor can ask about a source file, whatever the language.
 *
 * Written when C and C++ arrived and there were two implementations to serve
 * rather than one. The two are not alike underneath -- Java's runs a compiler
 * in this process and answers from its own symbol table; C++'s talks to a
 * `clangd` subprocess over stdio -- and the editor should not know which it
 * has. This is the seam that keeps that true, in the same relation to
 * `:lsp:java` and `:lsp:native` as `:engine:api` is to `:engine:fast`.
 *
 * **Every method may return nothing, and nothing is an ordinary answer.** These
 * are asked on keystrokes, against a buffer that is mid-edit and usually does
 * not parse. A service that threw when it had no answer would turn typing into
 * a stream of errors.
 */
interface LanguageService : AutoCloseable {

    /** Whether this service handles [file] at all. */
    fun handles(file: File): Boolean

    /** What the compiler says about [text], as the gutter renders it. */
    suspend fun diagnostics(file: File, text: String): List<Diagnostic>

    /** Proposals for the cursor at [offset], a 0-based character index. */
    suspend fun complete(file: File, text: String, offset: Int): List<CompletionItem>

    /** Where the thing under [offset] is declared, or null. */
    suspend fun definition(file: File, text: String, offset: Int): SourceLocation?

    /** A one-line signature for the thing under [offset], or null. */
    suspend fun signatureAt(file: File, text: String, offset: Int): String?

    /**
     * Releases whatever the service holds -- a warm compiler's heap, or a
     * subprocess. Not optional for either: one is hundreds of megabytes and the
     * other is a process that outlives the app if nobody stops it.
     */
    override fun close()
}
