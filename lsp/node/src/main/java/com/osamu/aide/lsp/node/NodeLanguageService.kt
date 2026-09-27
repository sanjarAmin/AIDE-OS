package com.osamu.aide.lsp.node

import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.ProjectPaths
import com.osamu.aide.lsp.api.CompletionItem
import com.osamu.aide.lsp.api.LanguageService
import com.osamu.aide.lsp.api.SourceLocation
import com.osamu.aide.toolchain.nativetools.NodeToolchain
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * JavaScript diagnostics, from the runtime that will run the file.
 *
 * **Diagnostics only.** Completion, definitions and signatures all answer
 * nothing, which [LanguageService] documents as an ordinary answer -- and it is
 * the honest one here. Node ships no language server; what it ships is
 * `--check`, which reports exactly the errors V8 raises before executing a
 * file, including redeclaration. Claiming more by wiring up a keyword list
 * would make the editor look like it understood the file.
 *
 * A process per check, unlike the other three services, which stay warm. That
 * is affordable because the editor debounces and because `--check` parses and
 * stops -- but it is why [close] has nothing to release, and why this is not
 * the shape to copy if a real JavaScript server ever arrives.
 */
class NodeLanguageService(
    private val node: NodeToolchain,
    private val dispatchers: DispatcherProvider,
    /**
     * A directory this may write to. The check runs against a copy of the
     * buffer rather than the file on disk, because the buffer is what the user
     * is looking at and it has usually not been saved.
     */
    private val scratch: File,
    /**
     * Only so diagnostics can name a path a user recognises.
     *
     * `node --check` resolves nothing, so unlike the other three services this
     * has no classpath and no session to invalidate -- the root is here for
     * presentation alone. Without it the Problems tab showed
     * `/storage/emulated/0/Android/data/com.osamu.aide/files/projects/...`,
     * three wrapped lines of a phone screen, which is exactly the outcome
     * [Diagnostic] documents and asks producers to avoid.
     */
    private val projectRoot: File,
) : LanguageService {

    override fun handles(file: File): Boolean = file.extension.lowercase() in EXTENSIONS

    override suspend fun diagnostics(file: File, text: String): List<Diagnostic> {
        if (!node.isInstalled || !handles(file)) return emptyList()

        // `runInterruptible`, not `withContext`: a blocked read ignores
        // coroutine cancellation, and these are asked on keystrokes -- the
        // answer to the character before this one is worthless the moment
        // another arrives, and its thread should not still be held.
        return runInterruptible(dispatchers.io) {
            // Named for the real file so that node's own message reads sensibly
            // if it ever reaches a log, and kept per-service rather than per
            // call: a temp file per keystroke is a directory that fills up.
            val copy = File(scratch, "check-${file.name}").apply {
                parentFile?.mkdirs()
                writeText(text)
            }
            val plan = node.plan(listOf("--check", copy.absolutePath))
            val process = runCatching {
                ProcessBuilder(plan.command)
                    .directory(scratch)
                    .apply { environment().putAll(plan.environment) }
                    // One stream to read: --check writes the error to stderr and
                    // nothing to stdout, and merging removes a reason to drain
                    // two of them for a process that produces a dozen lines.
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull() ?: return@runInterruptible emptyList()

            try {
                val output = process.inputStream.bufferedReader().readText()
                // A check that has not finished in this long is not going to
                // say anything useful about a file the user is still typing in.
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    emptyList()
                } else if (process.exitValue() == 0) {
                    emptyList()
                } else {
                    NodeSyntaxCheck.parse(output, ProjectPaths.relativise(file, projectRoot))
                }
            } catch (interrupted: Exception) {
                // The read was cut short, which here means the caller lost
                // interest: another keystroke has already arrived.
                emptyList()
            } finally {
                // Harmless on a process that has exited, and the only thing
                // that stops one that has not.
                process.destroyForcibly()
            }
        }
    }

    override suspend fun complete(file: File, text: String, offset: Int): List<CompletionItem> =
        emptyList()

    override suspend fun definition(file: File, text: String, offset: Int): SourceLocation? = null

    override suspend fun signatureAt(file: File, text: String, offset: Int): String? = null

    /** Nothing is held: every check is its own process, and it has exited. */
    override fun close() = Unit

    private companion object {
        val EXTENSIONS = setOf("js", "mjs", "cjs")
        const val TIMEOUT_SECONDS = 10L
    }
}
