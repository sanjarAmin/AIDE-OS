package com.osamu.aide.ui.workspace

import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.editor.CompletionSource
import com.osamu.aide.editor.EditorCompletion
import com.osamu.aide.editor.EditorCompletionKind
import com.osamu.aide.lsp.api.CompletionKind
import com.osamu.aide.lsp.java.JavaLanguageService
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Hands out the language service for a project, and keeps it.
 *
 * The keeping is the point. A [JavaLanguageService] holds a warm javac -- a
 * file manager with `android.jar` indexed and a pooled compiler context -- and
 * building a second one throws all of that away. Spike R3 measured the
 * difference at 700--1100 ms versus 82 ms per request, so a service constructed
 * per keystroke would be slower than having none at all.
 *
 * The same joining role as [ProjectBuilder], for the same reason: the service
 * takes a platform rather than finding one, and the platform is a download that
 * may not have happened yet.
 */
class LanguageServices(
    private val toolchain: com.osamu.aide.toolchain.manager.ToolchainManager,
    private val dispatchers: DispatcherProvider,
    /**
     * Where builds put their intermediates -- the same root [ProjectBuilder]
     * writes to, because the generated `R.java` under it is an input here.
     */
    private val buildOutputRoot: File,
) {

    private var current: Pair<File, JavaLanguageService>? = null

    /**
     * Null when there is nothing to analyse with.
     *
     * Java intelligence needs `android.jar` for the platform types, and without
     * it every reference to `Activity` would come back unresolved -- a file of
     * red squiggles blaming the user for the toolchain not being installed.
     * Silence is the better failure.
     */
    @Synchronized
    fun forProject(projectRoot: File, classpath: List<File> = emptyList()): JavaLanguageService? {
        current?.let { (root, service) ->
            if (root == projectRoot && service.classpath == classpath) return service
            // Replaced, so the old one has to go. It holds a file manager with
            // open handles on android.jar and every AAR; dropping the reference
            // alone leaked them, and opening a few projects in a session is
            // enough to notice on a device.
            service.close()
        }

        val androidJar = toolchain.androidJar() ?: return null
        val service = JavaLanguageService(
            platform = androidJar,
            projectRoot = projectRoot,
            dispatchers = dispatchers,
            // The dependency jars, so completion and diagnostics see AndroidX
            // rather than reporting every androidx.* reference as unresolved.
            // A changed classpath builds a new service: the warm compiler holds
            // a symbol table for the old one, and there is no way to add to it.
            classpath = classpath,
            sourcePath = listOf(
                File(projectRoot, "src/main/java"),
                // aapt2 writes R.java here during a build, and nothing else
                // ever writes it: `R` is not a file the user has. Without this
                // every `R.string.x` in a freshly created project is reported
                // as "package R does not exist" -- a real unresolved reference,
                // but one that says nothing except that the project has not
                // been built yet. After one build it resolves.
                //
                // Kept in step with ProjectBuilder.outputFor and
                // BuildWorkspace.generatedJava by construction, not by comment:
                // both derive from the project directory name under the same
                // root, which is why that root is injected rather than guessed.
                File(buildOutputRoot, "${projectRoot.name}/generated/java"),
            ),
        )
        current = projectRoot to service
        return service
    }

    /** Drops the warm compiler and everything it has entered. */
    @Synchronized
    fun release() {
        current?.second?.close()
        current = null
    }
}

/**
 * Lets the editor ask `:lsp:java` for proposals without knowing it exists.
 *
 * [runBlocking] is deliberate and safe here: sora calls `completionsAt` on its
 * own completion worker, never the main thread, and expects it to take a while.
 * The alternative -- an async bridge back into a callback -- would buy nothing,
 * because sora's contract is already "block until you have an answer, and be
 * abandoned if the user types again".
 *
 * That abandonment arrives as an interrupt, which [runBlocking] reports by
 * cancelling its coroutine and throwing [InterruptedException]. It is allowed
 * out of here on purpose; [com.osamu.aide.editor.CompletionSource] documents it,
 * and the editor turns it into a cancellation rather than a failure.
 */
class JavaCompletionSource(private val service: JavaLanguageService) : CompletionSource {

    override fun completionsAt(file: File, text: String, offset: Int): List<EditorCompletion> =
        runBlocking { service.complete(file, text, offset) }
            .map { proposal ->
                EditorCompletion(
                    label = proposal.label,
                    kind = proposal.kind.toEditorKind(),
                    insert = proposal.insert,
                    detail = proposal.detail,
                )
            }

    private fun CompletionKind.toEditorKind(): EditorCompletionKind = when (this) {
        CompletionKind.METHOD -> EditorCompletionKind.METHOD
        CompletionKind.FIELD -> EditorCompletionKind.FIELD
        CompletionKind.VARIABLE -> EditorCompletionKind.VARIABLE
        CompletionKind.CLASS -> EditorCompletionKind.CLASS
        CompletionKind.PACKAGE -> EditorCompletionKind.PACKAGE
        CompletionKind.KEYWORD -> EditorCompletionKind.KEYWORD
    }
}
