package com.osamu.aide.analysisapi.backend

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.components.KaExtensionApplicabilityResult
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.contextModule
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.nio.file.Paths

/**
 * The Analysis API half of `:lsp:kotlin`, living **inside the dex archive**.
 *
 * `:lsp:kotlin` cannot be compiled against the Analysis API: the archive is
 * loaded by a `PathClassLoader` parented to the boot loader, so nothing in the
 * app can name a type from it. Driving Kotlin DSL builders and lambdas with
 * receivers by reflection is not an alternative -- it is unreadable and it
 * breaks silently. So the work is written here as ordinary code and the app
 * reflects over five methods.
 *
 * **The wire format is `List<String>`, and the reason is the classloader.**
 * Only types both sides already agree on can cross: `String`, `List`, `int` --
 * boot classes. A data class declared here would be loaded by *this* loader and
 * be a different class from an identical one in the app, so a cast on the other
 * side fails with a message that names the same type twice. Records are
 * tab-separated because a tab cannot appear in an identifier and a diagnostic
 * message containing one is not worth the ceremony of a real encoding.
 *
 * Failure is a value, not an exception, for the same reason. `tools/analysisapi/
 * FINDINGS.md` is the account of what this costs and where it stops.
 */
object KotlinBackend {

    private const val FIELD = "\t"

    /**
     * The completion marker.
     *
     * A buffer at a caret usually does not parse -- `s.` is a syntax error and
     * there is no node for "the thing being typed". IntelliJ splices a dummy
     * identifier in at the caret so the file parses and the reference has a
     * node; this is the same trick, and the name only has to be one nothing
     * else matches.
     */
    private const val MARKER = "AideCompletionMarkerZzz"

    private var session: StandaloneAnalysisAPISession? = null
    private var disposable: Disposable? = null
    private var key: String? = null

    /**
     * Builds the session, or reuses the one already built for these roots.
     *
     * Separate from the queries because it is the expensive call -- ~1.8 s on
     * an emulator, against ~59 ms for a query -- and the caller decides when to
     * pay it. Returns `OK` or `ERR <message>`.
     */
    @JvmStatic
    fun open(sourceRoots: String, libraryJars: String): String = guarded {
        val wanted = "$sourceRoots|$libraryJars"
        if (session != null && key == wanted) return@guarded "OK"
        close()

        val roots = sourceRoots.split(java.io.File.pathSeparatorChar).filter { it.isNotBlank() }
        val jars = libraryJars.split(java.io.File.pathSeparatorChar).filter { it.isNotBlank() }

        val owner = Disposer.newDisposable("aide-kotlin-backend")
        val built = buildStandaloneAnalysisAPISession(owner) {
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform

                // **Libraries, even though only members resolve through them
                // today.** A session with no library module still answers for
                // `String` -- those are builtins the front end carries -- so
                // leaving them out looks like it works. FINDINGS.md section 16.
                val libraries = jars.map { jar ->
                    buildKtLibraryModule {
                        libraryName = java.io.File(jar).name
                        platform = JvmPlatforms.defaultJvmPlatform
                        addBinaryRoot(Paths.get(jar))
                    }
                }
                libraries.forEach { addModule(it) }

                addModule(
                    buildKtSourceModule {
                        moduleName = "aide"
                        platform = JvmPlatforms.defaultJvmPlatform
                        roots.forEach { addSourceRoot(Paths.get(it)) }
                        libraries.forEach { addRegularDependency(it) }
                    },
                )
            }
        }
        session = built
        disposable = owner
        key = wanted
        "OK"
    }

    /**
     * `severity \t line \t column \t message` per diagnostic, from [text] alone.
     *
     * [text] is the buffer and is never read from disk -- see [bufferFile].
     */
    @JvmStatic
    fun diagnostics(text: String): List<String> = guardedList {
        val file = bufferFile(text)
        analyze(file) {
            file.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS).map { found ->
                val offset = found.textRanges.firstOrNull()?.startOffset ?: 0
                val (line, column) = lineAndColumn(text, offset)
                listOf(
                    found.severity.name,
                    line.toString(),
                    column.toString(),
                    found.defaultMessage.replace('\t', ' '),
                ).joinToString(FIELD)
            }
        }
    }

    /**
     * `label \t kind \t insert \t detail` per proposal at [offset].
     *
     * Members of the receiver when the caret follows a dot, and what is in
     * scope otherwise. Filtered by the identifier already typed, because the
     * caller shows the list as-is.
     */
    @JvmStatic
    fun complete(text: String, offset: Int): List<String> = guardedList {
        val prefix = text.take(offset).takeLastWhile { it.isJavaIdentifierPart() }
        val at = offset - prefix.length
        val file = bufferFile(text.take(at) + MARKER + text.substring(offset))

        val reference = file.findElementAt(at)?.parent as? KtNameReferenceExpression
            ?: return@guardedList emptyList()
        val receiver = (reference.parent as? KtDotQualifiedExpression)?.receiverExpression

        analyze(file) {
            val symbols = if (receiver != null) {
                val type = receiver.expressionType
                    ?: return@analyze emptyList<String>()
                val members = type.scope?.getCallableSignatures()?.map { it.symbol }.orEmpty()

                // Applicability is the API's to decide -- type parameters,
                // smart casts and the receiver's own generic arguments all
                // matter -- and this is the checker IntelliJ's completion uses.
                // What it cannot do is find candidates the scope will not
                // enumerate; FINDINGS.md section 16 is that wall.
                val checker = createExtensionCandidateChecker(file, reference, receiver)
                val extensions = file.scopeContext(reference).scopes.asSequence()
                    .flatMap { it.scope.callables { true } }
                    .filter { it.isExtension }
                    .filter {
                        checker.computeApplicability(it) is
                            KaExtensionApplicabilityResult.Applicable
                    }
                members + extensions
            } else {
                file.scopeContext(reference).scopes.asSequence()
                    .flatMap { it.scope.declarations }
            }

            symbols
                .mapNotNull { symbol ->
                    val name = (symbol as? KaNamedSymbol)?.name?.asString() ?: return@mapNotNull null
                    if (name.startsWith(MARKER) || !name.startsWith(prefix)) return@mapNotNull null
                    val kind = when (symbol) {
                        is KaNamedFunctionSymbol -> "METHOD"
                        is KaVariableSymbol -> "FIELD"
                        is KaClassLikeSymbol -> "CLASS"
                        else -> "VARIABLE"
                    }
                    val label = when (symbol) {
                        is KaNamedFunctionSymbol ->
                            name + symbol.valueParameters.joinToString(
                                prefix = "(", postfix = ")",
                            ) { readable(it.returnType) }
                        else -> name
                    }
                    val detail = when (symbol) {
                        is KaNamedFunctionSymbol -> readable(symbol.returnType)
                        is KaVariableSymbol -> readable(symbol.returnType)
                        else -> ""
                    }
                    listOf(label, kind, name, detail.replace('\t', ' ')).joinToString(FIELD)
                }
                .distinct()
                .toList()
        }
    }

    /**
     * A type as a person reads it, not as the compiler spells it.
     *
     * **`KaType.toString()` is a debug rendering and it reaches the user.**
     * It produces `android/view/View!` and `kotlin/collections/List<kotlin/Int>`
     * -- internal names, slashes and all -- and driving the app is how that was
     * noticed: the completion list offered `setContentView(android/view/View!)`
     * where an IDE offers `setContentView(View!)`.
     *
     * The `!` stays. It marks a platform type, which is real information about
     * whether null is possible, and IntelliJ shows it too.
     */
    private fun KaSession.readable(type: KaType): String =
        type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)

    /** A one-line signature for the declaration at [offset], or an empty string. */
    @JvmStatic
    fun signatureAt(text: String, offset: Int): String = guarded {
        val file = bufferFile(text)
        val reference = file.findElementAt(offset)?.parent as? KtNameReferenceExpression
            ?: return@guarded ""
        analyze(file) {
            val symbol = reference.mainReference.resolveToSymbol() as? KaNamedFunctionSymbol
                ?: return@analyze ""
            val parameters = symbol.valueParameters.joinToString(", ") {
                "${it.name.asString()}: ${readable(it.returnType)}"
            }
            "${symbol.name.asString()}($parameters): ${readable(symbol.returnType)}"
        }
    }

    /** Drops the session. Not optional: it holds the front end's whole graph. */
    @JvmStatic
    fun close() {
        disposable?.let { Disposer.dispose(it) }
        disposable = null
        session = null
        key = null
    }

    /**
     * The buffer as a **dangling file** -- in memory, bound to the real module.
     *
     * `markGenerated = false` is load-bearing. The default marks the file
     * generated, which excludes it from resolution and yields an *empty scope
     * rather than an error*: completion returns nothing and looks like a file
     * with nothing in it.
     */
    private fun bufferFile(text: String): KtFile {
        val live = session ?: error("open() was not called")
        val file = KtPsiFactory(live.project, false).createFile("AideBuffer.kt", text)
        file.contextModule = live.modulesWithFiles.keys.first { it is KaSourceModule }
        return file
    }

    /** 1-based line and column, which is what `Diagnostic` documents. */
    private fun lineAndColumn(text: String, offset: Int): Pair<Int, Int> {
        var line = 1
        var lineStart = 0
        for (index in 0 until minOf(offset, text.length)) {
            if (text[index] == '\n') {
                line++
                lineStart = index + 1
            }
        }
        return line to (minOf(offset, text.length) - lineStart + 1)
    }

    /**
     * The context classloader, and failure as a value.
     *
     * IntelliJ's plugin loader resolves the names in its descriptors through
     * the *context* loader rather than its own, which on Android is the app's
     * and has never heard of this archive. Without this the failure is a
     * ClassNotFoundException naming a class that is plainly in the dex.
     */
    private inline fun guarded(body: () -> String): String = try {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = KotlinBackend::class.java.classLoader
        try {
            body()
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    } catch (failure: Throwable) {
        "ERR ${failure::class.java.name}: ${failure.message}"
    }

    private inline fun guardedList(body: () -> List<String>): List<String> = try {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = KotlinBackend::class.java.classLoader
        try {
            body()
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    } catch (failure: Throwable) {
        listOf("ERR$FIELD${failure::class.java.name}: ${failure.message}")
    }
}
