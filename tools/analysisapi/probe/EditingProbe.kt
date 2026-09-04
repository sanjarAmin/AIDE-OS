package com.osamu.aide.analysisapi.probe

import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.components.KaExtensionApplicabilityResult
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.contextModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.analysis.api.platform.declarations.createDeclarationProvider
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
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
     * The library modules, kept because the session will not give them back.
     *
     * `modulesWithFiles` maps modules to their PSI files, and a binary library
     * has none -- so it holds only source modules, and a query about a library
     * silently asks the wrong thing. This cost one round of "the index returns
     * nothing" that was really "the index was never asked".
     */
    private var residentLibraries: List<KaModule> = emptyList()

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

    private fun sessionFor(sourceDir: String, libraryJars: String): StandaloneAnalysisAPISession {
        val key = "$sourceDir|$libraryJars"
        resident?.let { if (residentKey == key) return it }
        residentDisposable?.let { Disposer.dispose(it) }

        val jars = libraryJars.split(':').filter { it.isNotBlank() }.map { Paths.get(it) }
        val disposable = Disposer.newDisposable("aide-editing-probe")
        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                platform = JvmPlatforms.defaultJvmPlatform

                // **The libraries, and without them the answer is wrong rather
                // than absent.** A session with no library module still
                // resolves `String` -- `kotlin.String` is a *builtin*, carried
                // by the front end itself -- so it looks like the standard
                // library is present. It is not: `uppercase` lives in
                // `kotlin.text` in kotlin-stdlib.jar, and with no library
                // module the star-importing scope holds 30 callables instead of
                // hundreds. Nothing fails; completion is just quietly thin.
                val libraries = jars.map { jar ->
                    buildKtLibraryModule {
                        libraryName = jar.fileName.toString()
                        platform = JvmPlatforms.defaultJvmPlatform
                        addBinaryRoot(jar)
                    }
                }
                libraries.forEach { addModule(it) }
                residentLibraries = libraries

                addModule(
                    buildKtSourceModule {
                        moduleName = "probe"
                        platform = JvmPlatforms.defaultJvmPlatform
                        addSourceRoot(Paths.get(sourceDir))
                        libraries.forEach { addRegularDependency(it) }
                    },
                )
            }
        }
        residentDisposable = disposable
        residentKey = key
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
    fun completeInMemory(
        sourceDir: String,
        libraryJars: String,
        text: String,
        offset: Int,
    ): String = try {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = EditingProbe::class.java.classLoader
        try {
            val session = sessionFor(sourceDir, libraryJars)
            val contextModule = session.modulesWithFiles.keys.first { it is KaSourceModule }

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

                val members = scope.getCallableSignatures()
                    .mapNotNull { (it.symbol as? KaNamedSymbol)?.name?.asString() }

                // **Extensions, which is most of what a Kotlin user reaches
                // for.** `uppercase`, `map` and `filter` are not members of
                // anything -- they are extensions in `kotlin.text` and
                // `kotlin.collections`, so a member scope alone answers eight
                // names for `String` where an IDE answers hundreds.
                //
                // Applicability is asked of the API rather than decided here.
                // "Is this extension callable on this receiver" is not a
                // subtype test: it involves type parameters, smart casts and
                // the receiver's own generic arguments, and
                // `createExtensionCandidateChecker` is the answer IntelliJ's
                // own completion uses.
                val checker = createExtensionCandidateChecker(file, reference, receiver)
                val extensions = file.scopeContext(reference).scopes
                    .asSequence()
                    .flatMap { it.scope.callables { true } }
                    .filter { it.isExtension }
                    .filter {
                        checker.computeApplicability(it) is
                            KaExtensionApplicabilityResult.Applicable
                    }
                    .mapNotNull { (it as? KaNamedSymbol)?.name?.asString() }

                // Reported separately because the two come from different
                // places and fail differently: members are exhaustive, and
                // extensions are whatever the importing scopes will enumerate.
                val memberNames = members.distinct().toList()
                val extensionNames = extensions.distinct().toList()
                val names = (memberNames + extensionNames).distinct().sorted()
                "OK ${names.size} members=${memberNames.size} ext=${extensionNames.size} " +
                    "[ext: ${extensionNames.sorted().joinToString(" ")}] " +
                    names.joinToString(" ")
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
    fun diagnoseInMemory(sourceDir: String, libraryJars: String, text: String): String = try {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = EditingProbe::class.java.classLoader
        try {
            val session = sessionFor(sourceDir, libraryJars)
            val file = KtPsiFactory(session.project, false).createFile("AideBuffer.kt", text)
            file.contextModule = session.modulesWithFiles.keys.first { it is KaSourceModule }

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

    /**
     * What scopes are visible at the cursor, and what is in them.
     *
     * A diagnostic, not a feature. "Extensions came back empty" has at least
     * three causes -- no importing scopes, scopes that will not enumerate, or
     * every candidate judged inapplicable -- and they are indistinguishable
     * from the outside.
     */
    @JvmStatic
    fun scopeReport(
        sourceDir: String,
        libraryJars: String,
        text: String,
        offset: Int,
    ): String = try {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = EditingProbe::class.java.classLoader
        try {
            val session = sessionFor(sourceDir, libraryJars)
            val marked = text.substring(0, offset) + MARKER + text.substring(offset)
            val file = KtPsiFactory(session.project, false)
                .createFile("AideBuffer.kt", marked)
            file.contextModule = session.modulesWithFiles.keys.first { it is KaSourceModule }

            val reference = file.findElementAt(offset)?.parent as? KtNameReferenceExpression
                ?: return "ERR no reference at the marker"
            val receiver = (reference.parent as? KtDotQualifiedExpression)?.receiverExpression
                ?: return "ERR the marker is not a qualified reference"

            analyze(file) {
                val checker = createExtensionCandidateChecker(file, reference, receiver)
                buildString {
                    val scopes = file.scopeContext(reference).scopes
                    append("scopes=").append(scopes.size).append(' ')
                    for (withKind in scopes) {
                        val callables = withKind.scope.callables { true }.toList()
                        val extensions = callables.filter { it.isExtension }
                        val applicable = extensions.count {
                            checker.computeApplicability(it) is
                                KaExtensionApplicabilityResult.Applicable
                        }
                        append(withKind.kind::class.java.simpleName)
                            .append("[n=").append(callables.size)
                            .append(",ext=").append(extensions.size)
                            .append(",ok=").append(applicable).append("] ")
                        if (extensions.isNotEmpty()) {
                            // Distinct names, not symbols: the interesting
                            // number is how many *names* a scope will yield,
                            // and printing 76 overloads of `plus` hides that.
                            append("{")
                            append(
                                extensions.distinctBy {
                                    (it as? KaNamedSymbol)?.name?.asString()
                                }.joinToString(" ") { symbol ->
                                    val name = (symbol as? KaNamedSymbol)?.name?.asString() ?: "?"
                                    val verdict = checker.computeApplicability(symbol)
                                    if (verdict is KaExtensionApplicabilityResult.Applicable) {
                                        "$name!"
                                    } else {
                                        name
                                    }
                                },
                            )
                            append("} ")
                        }
                    }
                }
            }
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    } catch (failure: Throwable) {
        "ERR ${failure::class.java.name}: ${failure.message}"
    }

    /**
     * Whether a **package scope** enumerates what an importing scope will not.
     *
     * §16 stopped at a wall: `DefaultStarImportingScope` answers only for names
     * it has already been told about, so extensions from `kotlin.text` never
     * appear. `KaSymbolProvider.findPackage` plus `getPackageScope` is the
     * other way in, and it asks the symbol provider -- which reads jars --
     * rather than an import scope.
     *
     * Returns `OK <package> callables=<n> ext=<n> applicable=<n> [sample]`.
     */
    @JvmStatic
    fun packageScopeReport(
        sourceDir: String,
        libraryJars: String,
        text: String,
        offset: Int,
        packageName: String,
    ): String = try {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = EditingProbe::class.java.classLoader
        try {
            val session = sessionFor(sourceDir, libraryJars)
            val marked = text.substring(0, offset) + MARKER + text.substring(offset)
            val file = KtPsiFactory(session.project, false)
                .createFile("AideBuffer.kt", marked)
            file.contextModule = session.modulesWithFiles.keys.first { it is KaSourceModule }

            val reference = file.findElementAt(offset)?.parent as? KtNameReferenceExpression
                ?: return "ERR no reference at the marker"
            val receiver = (reference.parent as? KtDotQualifiedExpression)?.receiverExpression
                ?: return "ERR the marker is not a qualified reference"

            analyze(file) {
                val symbol = findPackage(FqName(packageName))
                    ?: return@analyze "ERR no package symbol for $packageName"
                val callables = symbol.packageScope.callables { true }.toList()
                val extensions = callables.filter { it.isExtension }
                val checker = createExtensionCandidateChecker(file, reference, receiver)
                val applicable = extensions.filter {
                    checker.computeApplicability(it) is
                        KaExtensionApplicabilityResult.Applicable
                }
                val sample = applicable
                    .mapNotNull { (it as? KaNamedSymbol)?.name?.asString() }
                    .distinct()
                    .sorted()
                    .take(12)
                "OK $packageName callables=${callables.size} ext=${extensions.size} " +
                    "applicable=${applicable.size} [${sample.joinToString(" ")}]"
            }
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    } catch (failure: Throwable) {
        "ERR ${failure::class.java.name}: ${failure.message}"
    }

    /**
     * Does a **declaration provider** know the names in a binary library?
     *
     * The scopes are a dead end: neither the star-importing scope (§16) nor a
     * package scope (`packageScopeReport`) will enumerate. Both answer for
     * names they are given. So the question is only ever "where do the names
     * come from", and `KotlinDeclarationProvider.getTopLevelCallableNamesInPackage`
     * is the method with exactly that shape.
     *
     * The doubt is whether it covers **binaries**. It is documented over PSI --
     * `KtNamedFunction`s in Kotlin source -- and `kotlin.text.uppercase` exists
     * only as a class file. This asks it, per module, rather than assuming.
     *
     * Returns one clause per module: `<module>=<n names>[sample]`.
     */
    @JvmStatic
    fun declarationIndexReport(
        sourceDir: String,
        libraryJars: String,
        packageName: String,
    ): String = try {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = EditingProbe::class.java.classLoader
        try {
            val session = sessionFor(sourceDir, libraryJars)
            val fqName = FqName(packageName)
            buildString {
                append("OK ").append(packageName).append(' ')
                for (module in session.modulesWithFiles.keys + residentLibraries) {
                    val provider = runCatching {
                        session.project.createDeclarationProvider(module.contentScope, module)
                    }.getOrNull()
                    if (provider == null) {
                        append(module.javaClass.simpleName).append("=<no provider> ")
                        continue
                    }
                    val names = runCatching {
                        provider.getTopLevelCallableNamesInPackage(fqName)
                    }.getOrElse { emptySet() }
                    append(module.javaClass.simpleName)
                        .append('=').append(names.size)
                        .append('[')
                        .append(names.map { it.asString() }.sorted().take(8).joinToString(" "))
                        .append("] ")
                }
            }
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    } catch (failure: Throwable) {
        "ERR ${failure::class.java.name}: ${failure.message}"
    }

    /**
     * The fourth route: **name the facade class and ask for its members.**
     *
     * The three enumeration routes are all closed, and yet types resolve --
     * which says the symbol provider answers by `ClassId` on demand and simply
     * cannot list. So stop asking it to list.
     *
     * Kotlin's top-level functions compile into per-file *facade* classes:
     * everything in `kotlin/text/Strings.kt` becomes a static member of
     * `kotlin.text.StringsKt`. Those are ordinary classes with ordinary
     * ClassIds, and `findClass` takes a ClassId. If their members come back,
     * extensions are reachable without any index the API refuses to build --
     * and the facades themselves are findable by listing `*Kt.class` in the
     * jars we ship, which is our own file to read.
     *
     * Returns `OK <classId> declared=<n> static=<n> ext=<n> [sample]`.
     */
    @JvmStatic
    fun facadeReport(
        sourceDir: String,
        libraryJars: String,
        packageName: String,
        facade: String,
    ): String = try {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = EditingProbe::class.java.classLoader
        try {
            val session = sessionFor(sourceDir, libraryJars)
            val file = KtPsiFactory(session.project, false)
                .createFile("AideBuffer.kt", "package probe")
            file.contextModule = session.modulesWithFiles.keys.first { it is KaSourceModule }

            analyze(file) {
                val id = ClassId(FqName(packageName), Name.identifier(facade))
                val symbol = findClass(id)
                    ?: return@analyze "ERR no class $packageName.$facade"
                val declared = symbol.declaredMemberScope.callables { true }.toList()
                val static = symbol.staticDeclaredMemberScope.callables { true }.toList()
                val extensions = (declared + static).filter { it.isExtension }
                val sample = extensions
                    .mapNotNull { (it as? KaNamedSymbol)?.name?.asString() }
                    .distinct().sorted().take(10)
                "OK $packageName.$facade declared=${declared.size} static=${static.size} " +
                    "ext=${extensions.size} [${sample.joinToString(" ")}]"
            }
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    } catch (failure: Throwable) {
        "ERR ${failure::class.java.name}: ${failure.message}"
    }

    /**
     * **Ask for a top-level callable by name**, which is the one thing that works.
     *
     * `findClass` will not find `kotlin.text.StringsKt` even though that class
     * is plainly in the jar, and it is right not to: a facade is a JVM
     * implementation detail, and in the Kotlin view `uppercase` is a *top-level
     * callable of the package `kotlin.text`*, not a member of anything.
     * `KaSymbolProvider.findTopLevelCallables` is the method shaped for exactly
     * that, and it takes a name rather than returning a list.
     *
     * If this resolves, the extension problem is no longer "can the API reach
     * these declarations" -- it is only "where does a candidate name come
     * from", which is a question about our own jars rather than about the API.
     */
    @JvmStatic
    fun topLevelCallableReport(
        sourceDir: String,
        libraryJars: String,
        packageName: String,
        callable: String,
    ): String = try {
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = EditingProbe::class.java.classLoader
        try {
            val session = sessionFor(sourceDir, libraryJars)
            val marked = "package probe" + System.lineSeparator() +
                "fun q() { val local: String = \"x\"; local." + MARKER + " }"
            val file = KtPsiFactory(session.project, false)
                .createFile("AideBuffer.kt", marked)
            file.contextModule = session.modulesWithFiles.keys.first { it is KaSourceModule }
            val reference = file.collectMarker()
                ?: return "ERR the marker did not parse as a reference"
            val receiver = (reference.parent as? KtDotQualifiedExpression)?.receiverExpression
                ?: return "ERR the marker is not a qualified reference"

            analyze(file) {
                val found = findTopLevelCallables(
                    FqName(packageName), Name.identifier(callable),
                ).toList()
                if (found.isEmpty()) return@analyze "OK $packageName.$callable found=0"
                val checker = createExtensionCandidateChecker(file, reference, receiver)
                val applicable = found.count {
                    checker.computeApplicability(it) is
                        KaExtensionApplicabilityResult.Applicable
                }
                "OK $packageName.$callable found=${found.size} " +
                    "ext=${found.count { it.isExtension }} applicableOnString=$applicable"
            }
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    } catch (failure: Throwable) {
        "ERR ${failure::class.java.name}: ${failure.message}"
    }

    /** The marker reference, found by walking rather than by offset arithmetic. */
    private fun org.jetbrains.kotlin.psi.KtFile.collectMarker(): KtNameReferenceExpression? {
        var found: KtNameReferenceExpression? = null
        accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
            override fun visitSimpleNameExpression(
                expression: org.jetbrains.kotlin.psi.KtSimpleNameExpression,
            ) {
                if (found == null && expression.getReferencedName() == MARKER) {
                    found = expression as? KtNameReferenceExpression
                }
                super.visitSimpleNameExpression(expression)
            }
        })
        return found
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
