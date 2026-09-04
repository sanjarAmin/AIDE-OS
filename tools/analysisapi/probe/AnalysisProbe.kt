package com.osamu.aide.analysisapi.probe

import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.nio.file.Paths

/**
 * Opens an Analysis API session and asks it one semantic question.
 *
 * **This is a bridge, and it exists because of the classloader.** The Analysis
 * API lives in its own dex archive loaded at runtime, so the spike's test code
 * cannot be compiled against it — everything would have to be reflection, and
 * this API is Kotlin DSLs and lambdas that reflection renders unreadable. So
 * the real work is written here as ordinary code, compiled against the
 * relocated jars on a desktop, dexed, and shipped *inside* the archive. The
 * test then reflects over exactly one method with `String` in and `String` out.
 *
 * Everything here is deliberately the smallest thing that cannot be faked:
 * resolving a declaration's return type requires the front end to actually run.
 * Parsing alone would answer the *name* of a function; only resolution answers
 * its **type**, and only resolution can tell `String` from `kotlin.String`.
 */
object AnalysisProbe {

    /**
     * Analyses `sourceDir` and returns one line per top-level function.
     *
     * A string rather than a structured result because it crosses a classloader
     * boundary by reflection: any type declared here would be loaded by *this*
     * loader and be a different class from the caller's.
     *
     * Prefixed `OK ` or `ERR ` so a caller that cannot see this class's
     * exceptions can still tell the two apart.
     */
    @JvmStatic
    fun describeFunctions(sourceDir: String, jdkHome: String?): String = try {
        // **The thread context classloader, or nothing here can be found.**
        // The Analysis API registers its services by reading the plugin
        // descriptors, and IntelliJ's plugin loader resolves the class names in
        // them through the *context* loader rather than its own. On Android
        // that defaults to the app's loader, which has never heard of this
        // archive -- so the failure is a ClassNotFoundException naming a class
        // that is demonstrably present in the dex, which reads as a broken
        // build rather than a loader problem.
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = AnalysisProbe::class.java.classLoader

        val disposable = Disposer.newDisposable("aide-analysis-probe")
        try {
            val session = buildStandaloneAnalysisAPISession(disposable) {
                buildKtModuleProvider {
                    // The platform has to be set on the provider as well as the
                    // module; the builder reads it as the default for anything
                    // that does not override it.
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

            val files = session.modulesWithFiles.entries
                .flatMap { (module, psi) -> psi.map { module to it } }
                .filter { (_, file) -> file is KtFile }

            if (files.isEmpty()) "ERR no Kotlin files were found under $sourceDir"
            else buildString {
                append("OK ")
                for ((_, psi) in files) {
                    val file = psi as KtFile
                    for (declaration in file.declarations.filterIsInstance<KtNamedFunction>()) {
                        // The query. `analyze` opens the session's own scope;
                        // the symbol and its type only exist inside it, so the
                        // rendering has to happen here rather than escaping.
                        val described = analyze(declaration) {
                            val symbol = declaration.symbol as? KaNamedFunctionSymbol
                                ?: return@analyze "?"
                            val returnType = symbol.returnType
                            val parameters = symbol.valueParameters.joinToString(",") { parameter ->
                                parameter.returnType.toString()
                            }
                            "${symbol.name}($parameters):$returnType"
                        }
                        append(described).append(' ')
                    }
                }
            }.trim()
        } finally {
            Disposer.dispose(disposable)
            Thread.currentThread().contextClassLoader = previous
        }
    } catch (failure: Throwable) {
        // Returned rather than thrown for the same reason the result is a
        // String: the caller cannot catch a type it cannot load. The first few
        // frames come too -- without them a failure inside the API is a bare
        // class name with nothing to say where it came from.
        val where = failure.stackTrace.take(4).joinToString(" | ") {
            "${it.className}.${it.methodName}:${it.lineNumber}"
        }
        // **The cause chain, not just the top.** IntelliJ and Caffeine both
        // catch a specific failure and rethrow something vaguer -- Caffeine
        // turns a ReflectiveOperationException into an IllegalStateException
        // whose message is only a class name -- so the top frame regularly
        // says nothing about what actually went wrong.
        val causes = generateSequence(failure.cause) { it.cause }
            .take(4)
            .joinToString(" <- ") { "${it::class.java.name}: ${it.message}" }
        "ERR ${failure::class.java.name}: ${failure.message} @ $where" +
            if (causes.isEmpty()) "" else " CAUSES: $causes"
    }

    /**
     * What the loader can and cannot see, for when a descriptor will not resolve.
     *
     * IntelliJ reads the plugin descriptors through a "resources data loader",
     * and when that fails it says only "Cannot resolve <path>" -- naming
     * neither the loader it used nor the form of the path it tried. This
     * answers both directly.
     */
    @JvmStatic
    fun probeResources(): String {
        val loader = AnalysisProbe::class.java.classLoader
        val paths = listOf(
            "META-INF/analysis-api/analysis-api-fir.xml",
            "/META-INF/analysis-api/analysis-api-fir.xml",
            "META-INF/analysis-api/analysis-api-impl-base.xml",
            "META-INF/plugin.xml",
        )
        return buildString {
            append("loader=").append(loader?.javaClass?.name).append(' ')
            for (path in paths) {
                val viaLoader = loader?.getResourceAsStream(path) != null
                val viaClass = AnalysisProbe::class.java.getResourceAsStream(path) != null
                append(path).append("[cl=").append(viaLoader)
                    .append(",cls=").append(viaClass).append("] ")
            }
        }.trim()
    }

    @Suppress("unused")
    private fun unusedJdkHome(jdkHome: String?) = jdkHome
}

/** So the module info above is not the only thing this file exports. */
internal const val PROBE_VERSION = "1"
