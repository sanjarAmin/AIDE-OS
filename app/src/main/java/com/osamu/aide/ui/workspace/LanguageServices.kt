package com.osamu.aide.ui.workspace

import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.editor.CompletionSource
import com.osamu.aide.editor.EditorCompletion
import com.osamu.aide.editor.EditorCompletionKind
import com.osamu.aide.engine.fast.NativeToolchainProvider
import com.osamu.aide.lsp.api.CompletionKind
import com.osamu.aide.lsp.api.LanguageService
import com.osamu.aide.lsp.nativelsp.ClangdService
import com.osamu.aide.lsp.java.JavaLanguageService
import com.osamu.aide.lsp.kotlin.KotlinArchives
import com.osamu.aide.lsp.kotlin.KotlinLanguageService
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
    private val native: NativeToolchainProvider,
    private val toolchain: com.osamu.aide.toolchain.manager.ToolchainManager,
    private val dispatchers: DispatcherProvider,
    /**
     * Where builds put their intermediates -- the same root [ProjectBuilder]
     * writes to, because the generated `R.java` under it is an input here.
     */
    private val buildOutputRoot: File,
) {

    private var current: Pair<File, JavaLanguageService>? = null


    private var nativeCurrent: Pair<File, ClangdService>? = null

    private var kotlinCurrent: Pair<File, KotlinLanguageService>? = null

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

    /**
     * The service that handles [file], or null if nothing here does.
     *
     * The editor asks this rather than choosing, so adding a language is a
     * matter of adding a service that claims its files. Java's needs a
     * classpath and rebuilds when it changes; clangd's needs neither, because
     * it reads `compile_flags.txt` from the project and re-reads it itself.
     */
    @Synchronized
    fun serviceFor(
        file: File,
        projectRoot: File,
        classpath: List<File> = emptyList(),
    ): LanguageService? {
        val java = forProject(projectRoot, classpath)
        if (java != null && java.handles(file)) return java

        val clangd = nativeFor(projectRoot)
        if (clangd != null && clangd.handles(file)) return clangd

        val kotlin = kotlinFor(projectRoot, classpath)
        if (kotlin != null && kotlin.handles(file)) return kotlin
        return null
    }

    /**
     * The Kotlin service for [projectRoot], or null when it cannot run here.
     *
     * Null is the ordinary state, and there are two ways to reach it: the
     * archives are dexed at API 30 and the app supports 26, and they are a
     * separate download most projects never need. The same shape as [nativeFor]
     * -- silence rather than an error, because a device that cannot do this is
     * not a device with a problem.
     *
     * **Null without `android.jar`, for the reason [forProject] gives.** A
     * Kotlin session that cannot see the platform resolves `Activity` and
     * `onCreate` to nothing and reports every line of a correct template as an
     * error. Driving the app showed exactly that -- fifteen problems on a
     * freshly created project -- and silence is the better failure.
     */
    private fun kotlinFor(
        projectRoot: File,
        classpath: List<File>,
    ): KotlinLanguageService? {
        if (!KotlinArchives.isSupported) return null

        kotlinCurrent?.let { (root, service) ->
            // A changed classpath needs a new service, the same as javac: the
            // session holds a resolved view of its libraries and cannot be
            // added to.
            if (root == projectRoot && service.classpath == kotlinClasspath(classpath)) {
                return service
            }
            // Holds the front end's whole object graph and the open jars it
            // resolves against; dropping the reference alone leaks both.
            service.close()
            kotlinCurrent = null
        }

        val files = toolchain.kotlinAnalysisArchives() ?: return null
        val resolved = kotlinClasspath(classpath)
        if (resolved.isEmpty()) return null
        val service = KotlinLanguageService(
            archives = KotlinArchives(
                compilerJar = files.compilerJar,
                stdlibJar = files.stdlibJar,
                analysisApiJar = files.analysisApiJar,
                backendJar = files.backendJar,
                workingDir = File(buildOutputRoot.parentFile, "kotlin-lsp"),
            ),
            projectRoot = projectRoot,
            dispatchers = dispatchers,
            classpath = resolved,
        )
        kotlinCurrent = projectRoot to service
        return service
    }

    /**
     * The platform first, then the project's dependencies.
     *
     * Empty when the platform is not installed, which the caller reads as "no
     * Kotlin service" rather than "a service with nothing to resolve against".
     */
    private fun kotlinClasspath(classpath: List<File>): List<File> {
        val platform = toolchain.androidJar() ?: return emptyList()
        return listOf(platform) + classpath
    }

    /**
     * The clangd service for [projectRoot], started at most once.
     *
     * Null when no C/C++ toolchain is installed, which is the usual state: it
     * is a 152 MiB download and most projects have no native code. The service
     * itself starts clangd lazily, so holding one costs nothing until a C file
     * is opened.
     */
    private fun nativeFor(projectRoot: File): ClangdService? {
        nativeCurrent?.let { (root, service) ->
            if (root == projectRoot) return service
            service.close()
            nativeCurrent = null
        }
        val toolchain = native.toolchain() ?: return null
        val service = ClangdService(toolchain, projectRoot, dispatchers)
        nativeCurrent = projectRoot to service
        return service
    }

    /** Drops the warm compiler and stops the language server. */
    @Synchronized
    fun release() {
        current?.second?.close()
        current = null
        kotlinCurrent?.second?.close()
        kotlinCurrent = null
        nativeCurrent?.second?.close()
        nativeCurrent = null
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
class ServiceCompletionSource(
    /**
     * Resolved per call rather than captured, because which service owns a
     * file is a property of the file. One editor may hold a `.java` and a
     * `.cpp` tab at once, and a source that had captured a single service
     * would answer for both with whichever it happened to be given.
     */
    private val serviceFor: (File) -> LanguageService?,
) : CompletionSource {

    override fun completionsAt(file: File, text: String, offset: Int): List<EditorCompletion> =
        runBlocking { serviceFor(file)?.complete(file, text, offset).orEmpty() }
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
