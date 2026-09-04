package com.osamu.aide.analysisapi.probe

import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.projectStructure.contextModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.nio.file.Paths

/**
 * The question a language service actually has to answer: **the buffer, not the
 * file on disk.**
 *
 * `AnalysisProbe` analyses a source root, which is what a batch tool does. An
 * editor asks about text that exists only in a buffer, changes on every
 * keystroke, and usually does not parse. Rebuilding the session per query is
 * not an option -- `AnalysisProbe.timeQueries` measured that at 1808 ms.
 *
 * The Analysis API's mechanism for this is a **dangling file**: a `KtFile`
 * built in memory whose `contextModule` points at a real module, analysed
 * against that module's resolution scope without being part of it. This exists
 * because it is what IntelliJ itself does for a modified editor buffer.
 *
 * Whether it works on ART -- and what it costs there -- is what this measures.
 * `../FINDINGS.md` section 15 lists "nothing has been edited" as the untested
 * case an editor lives in; this is that case.
 */
object EditingProbe {

    /**
     * The session, held across calls.
     *
     * Deliberately mirrors what `:lsp:kotlin` would do, because a probe that
     * rebuilds per call measures construction and tells us nothing. Keyed by
     * source root so a second root does not silently answer from the first.
     */
    private var residentKey: String? = null
    private var resident: StandaloneAnalysisAPISession? = null
    private var residentDisposable: Disposable? = null

    /**
     * The completion marker.
     *
     * A buffer at a cursor is usually *unparseable* -- `s.` is a syntax error,
     * and there is no PSI for "the thing being typed". IntelliJ's own answer is
     * to splice a dummy identifier in at the caret so the file parses and the
     * reference has a node; this is the same trick. The name only has to be one
     * nothing else will match.
     */
    private const val MARKER = "AideCompletionMarkerZzz"

    private fun sessionFor(sourceDir: String): StandaloneAnalysisAPISession {
        resident?.let { if (residentKey == sourceDir) return it }
        residentDisposable?.let { Disposer.dispose(it) }

        val disposable = Disposer.newDisposable("aide-editing-probe")
        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform
                addModule(
                    buildKtSourceModule {
                        moduleName = "probe"
                        platform = JvmPlatforms.defaultJvmPlatform
                        addSourceRoot(Paths.get(sourceDir))
                    },
                )
            }
        }
        residentDisposable = disposable
        residentKey = sourceDir
        resident = session
        return session
    }

    /**
     * Member completions for the receiver before [offset], from [text] alone.
     *
     * [text] is never written to disk. It is spliced with the marker, parsed
     * into a dangling file bound to the source module, and resolved -- so what
     * comes back reflects the buffer, including edits the file on disk does not
     * have.
     *
     * Returns `OK <count> <name> <name> ...`, capped, or `ERR ...`.
     */
    @JvmStatic
    fun completeInMemory(sourceDir: String, text: String, offset: Int): String = try {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = EditingProbe::class.java.classLoader
        try {
            val session = sessionFor(sourceDir)
            val contextModule = session.modulesWithFiles.keys.first()

            val marked = text.substring(0, offset) + MARKER + text.substring(offset)
            // markGenerated = false: a generated file is excluded from
            // resolution, which yields an empty scope rather than an error --
            // exactly the silent-wrong-answer this repo keeps meeting.
            val file = KtPsiFactory(session.project, false)
                .createFile("AideBuffer.kt", marked)
            file.contextModule = contextModule

            val reference = file.findElementAt(offset)?.parent as? KtNameReferenceExpression
                ?: return "ERR no reference at the marker"
            val receiver = (reference.parent as? KtDotQualifiedExpression)?.receiverExpression
                ?: return "ERR the marker is not a qualified reference"

            analyze(file) {
                val type = receiver.expressionType
                    ?: return@analyze "ERR the receiver has no type"
                val scope = type.scope
                    ?: return@analyze "ERR the receiver's type has no scope"
                val names = scope.getCallableSignatures()
                    .mapNotNull { (it.symbol as? KaNamedSymbol)?.name?.asString() }
                    .distinct()
                    .toList()
                "OK ${names.size} " + names.sorted().joinToString(" ")
            }
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    } catch (failure: Throwable) {
        "ERR ${failure::class.java.name}: ${failure.message}"
    }

    /**
     * Diagnostics for [text] alone, the same way -- errors before a build.
     *
     * Returns `OK <count> | <message> | <message> ...`.
     */
    @JvmStatic
    fun diagnoseInMemory(sourceDir: String, text: String): String = try {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = EditingProbe::class.java.classLoader
        try {
            val session = sessionFor(sourceDir)
            val file = KtPsiFactory(session.project, false).createFile("AideBuffer.kt", text)
            file.contextModule = session.modulesWithFiles.keys.first()

            analyze(file) {
                val found = file.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
                "OK ${found.size} | " + found.joinToString(" | ") { it.defaultMessage }
            }
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    } catch (failure: Throwable) {
        "ERR ${failure::class.java.name}: ${failure.message}"
    }

    /** Drops the resident session, so a test can measure a cold one again. */
    @JvmStatic
    fun release() {
        residentDisposable?.let { Disposer.dispose(it) }
        residentDisposable = null
        resident = null
        residentKey = null
    }
}
