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
import org.jetbrains.kotlin.com.intellij.psi.PsiNameIdentifierOwner
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
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
     * Package -> its top-level callables, read from the library jars.
     *
     * **The API can resolve one of these by name and cannot list them.** Three
     * routes were tried against a binary library and all return nothing: the
     * star-importing scope, `findPackage().packageScope`, and
     * `KotlinDeclarationProvider`. Meanwhile
     * `findTopLevelCallables(FqName, Name)` resolves out of the same jar
     * perfectly well, and extension applicability filters correctly on the
     * result. So the gap is names, and only names -- which is a question about
     * the jars rather than about the API. [MetadataIndex] answers it from
     * `@kotlin.Metadata`. FINDINGS.md sections 19 and 20.
     */
    private var topLevelNames: Map<String, List<MetadataIndex.Callable>> = emptyMap()

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
        // Scanned here rather than shipped: the jars that matter include the
        // project's own dependencies, which no build of ours can know. A jar
        // with no Kotlin in it is skipped whole, so android.jar costs one
        // directory read rather than forty thousand class parses.
        topLevelNames = runCatching {
            MetadataIndex.scan(jars.map { java.io.File(it) })
        }.getOrDefault(emptyMap())
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

                // What the scopes will yield: locals, the file's own
                // declarations, and explicitly imported names. Cheap, and the
                // only source for anything the index does not cover.
                val inScope = file.scopeContext(reference).scopes.asSequence()
                    .flatMap { it.scope.callables { true } }
                    .filter { it.isExtension }

                members + (inScope + indexedExtensions(file, prefix))
                    .filter {
                        checker.computeApplicability(it) is
                            KaExtensionApplicabilityResult.Applicable
                    }
            } else {
                // No receiver: locals, the file's own declarations, and
                // whatever the scopes will name -- plus the same index, which
                // is the only way a star-imported top-level function like
                // `println` is ever offered. Extensions are excluded here:
                // without a receiver there is nothing for them to extend, and
                // offering one produces code that does not compile.
                file.scopeContext(reference).scopes.asSequence()
                    .flatMap { it.scope.declarations } +
                    indexedTopLevel(file, prefix, wantExtensions = false)
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
     * Extension candidates from the name index, narrowed by [prefix] first.
     *
     * **Prefix first, and that ordering is the whole design.** The obvious
     * shape -- enumerate what is visible, then ask which applies -- costs in
     * proportion to what the scopes happen to yield, which has nothing to do
     * with what was typed: measured at four times the latency for an answer
     * that did not change. Here the index is filtered by the typed prefix
     * before a single symbol is resolved, so the cost tracks the request.
     *
     * **Nothing until a character is typed.** Right after `.` the prefix is
     * empty and every top-level callable in every default-imported package
     * would qualify -- hundreds of resolutions and applicability checks for a
     * list nobody can read anyway. Members alone answer that keystroke, and
     * extensions arrive with the first letter. That is a deliberate trade of
     * completeness for the 200 ms budget, not an oversight.
     */
    private fun KaSession.indexedExtensions(
        file: KtFile,
        prefix: String,
    ): Sequence<org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol> =
        // **Filtered before resolution, not after.** The index records whether
        // each callable is an extension, so a member request never resolves the
        // non-extensions and a bare-identifier request never resolves the
        // extensions. Half the candidates, and none of the wrong ones.
        indexedTopLevel(file, prefix, wantExtensions = true)

    /**
     * Top-level callables from the index whose name starts with [prefix].
     *
     * Serves both halves of completion, because both hit the same wall: a
     * member request needs the extensions, and a bare-identifier request needs
     * `println` -- and neither is enumerable from any scope. [wantExtensions]
     * picks the half, from the index rather than from resolved symbols, so the
     * unwanted half is never resolved at all.
     */
    private fun KaSession.indexedTopLevel(
        file: KtFile,
        prefix: String,
        wantExtensions: Boolean,
    ): Sequence<org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol> {
        if (prefix.isEmpty() || topLevelNames.isEmpty()) return emptySequence()

        // Only packages whose names are actually visible here: Kotlin's
        // default imports, plus whatever the file star-imports. A candidate
        // from an unimported package would resolve and then be uncallable.
        val visible = DEFAULT_IMPORTS + file.importDirectives
            .filter { it.isAllUnder }
            .mapNotNull { it.importedFqName?.asString() }

        return visible.asSequence()
            .flatMap { packageName ->
                topLevelNames[packageName].orEmpty().asSequence()
                    .filter { it.name.startsWith(prefix) && it.isExtension == wantExtensions }
                    .map { packageName to it.name }
            }
            .distinct()
            .take(CANDIDATE_LIMIT)
            .flatMap { (packageName, name) ->
                findTopLevelCallables(FqName(packageName), Name.identifier(name))
            }
    }

    /**
     * Kotlin's default imports, which are what is visible with no import at all.
     *
     * `kotlin.jvm` is in the list because this is the JVM platform; on another
     * it would not be. Nothing here is derived from the session, which is a
     * simplification worth knowing about if a target other than JVM ever
     * arrives.
     */
    private val DEFAULT_IMPORTS = listOf(
        "kotlin",
        "kotlin.annotation",
        "kotlin.collections",
        "kotlin.comparisons",
        "kotlin.io",
        "kotlin.jvm",
        "kotlin.ranges",
        "kotlin.sequences",
        "kotlin.text",
    )

    /**
     * A ceiling on resolutions per request, so a one-letter prefix cannot stall
     * a keystroke. Reached only for the commonest first letters.
     */
    private const val CANDIDATE_LIMIT = 120

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

    /**
     * Where the thing at [offset] is declared: `path<TAB>line<TAB>col<TAB>endCol`.
     *
     * **Resolved, never looked up by name.** An index answers "what is called
     * `foo`"; navigation has to answer "which of the seven overloads of `foo`
     * does *this* call site bind to", through this module's dependencies and
     * content scopes. Only the analysis session knows that, and delegating it
     * means there is no second notion of visibility to keep in step. The
     * approach is CodeOnTheGo's ADR 0010, and so is the fallback below.
     *
     * Empty when there is nothing to go to, which is most of the time: a
     * keyword, whitespace, or a symbol whose declaration is in a **library**.
     * Library symbols have no source PSI at all -- the stdlib, `android.jar`,
     * any jar -- so navigating into them would need decompilation or source
     * jars, and neither exists here. That is a limit of the approach rather
     * than a defect, and it is why this returns nothing rather than guessing.
     *
     * The path is absolute; the caller makes it project-relative, because only
     * the caller knows the root.
     */
    @JvmStatic
    fun definitionAt(text: String, offset: Int): String = guarded {
        val file = bufferFile(text)
        val element = file.findElementAt(offset)
            ?: return@guarded ""

        analyze(file) {
            val symbol = resolveSymbolAt(element)
                ?: return@analyze ""
            val declaration = symbol.psi
                ?: return@analyze ""

            // The *name*, not the whole declaration: jumping to a function
            // should land on its identifier, and selecting its entire body
            // would be correct and useless. SourceLocation says so too.
            val nameRange = (declaration as? PsiNameIdentifierOwner)
                ?.nameIdentifier
                ?.textRange
                ?: declaration.textRange
                ?: return@analyze ""

            val containing = declaration.containingFile ?: return@analyze ""
            val target = containing.virtualFile?.path
                // A declaration in the buffer itself lives in the dangling
                // file, which has no path on disk. Answering with its made-up
                // name would send the editor to a file that does not exist, so
                // the empty path means "the file you asked about" and the
                // caller substitutes it.
                ?: ""

            val content = containing.text
            val (line, column) = lineAndColumn(content, nameRange.startOffset)
            val (_, endColumn) = lineAndColumn(content, nameRange.endOffset)
            listOf(target, line.toString(), column.toString(), endColumn.toString())
                .joinToString(FIELD)
        }
    }

    /**
     * The symbol at [element], through a name reference or through a call.
     *
     * **Two doors, because not every reference has a name.** `a + b`, `a[i]`,
     * `by lazy`, destructuring and `for` loops all resolve to a declaration and
     * none of them has a name reference to ask -- so a name-only implementation
     * silently does nothing on exactly the syntax that looks most like magic
     * and most needs explaining.
     */
    private fun KaSession.resolveSymbolAt(
        element: org.jetbrains.kotlin.com.intellij.psi.PsiElement,
    ): org.jetbrains.kotlin.analysis.api.symbols.KaSymbol? {
        val named = element.parent as? KtNameReferenceExpression
        named?.mainReference?.resolveToSymbol()?.let { return it }

        val enclosing = generateSequence(element) { it.parent }
            .filterIsInstance<KtElement>()
            .take(PARENT_WALK_LIMIT)
            .firstOrNull { it.resolveToCall()?.singleFunctionCallOrNull() != null }
            ?: return null
        return enclosing.resolveToCall()?.singleFunctionCallOrNull()?.symbol
    }

    /**
     * How far to walk up looking for a call.
     *
     * A caret sits inside a leaf; the call it belongs to is a parent or two
     * above. Walking to the file would find *some* enclosing call for almost
     * any caret and jump somewhere the user was not pointing at.
     */
    private const val PARENT_WALK_LIMIT = 4

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
