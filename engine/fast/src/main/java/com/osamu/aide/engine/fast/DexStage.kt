package com.osamu.aide.engine.fast

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.OutputMode
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import com.osamu.aide.engine.api.hasErrors
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import com.android.tools.r8.Diagnostic as R8Diagnostic

/**
 * Translates `.class` files into Android's `.dex`, with D8, in this process.
 *
 * D8 is where the fast path stops looking like a desktop Java build. It is also
 * what makes the compile stage's stub jar work: ECJ emits an `invokedynamic`
 * against `LambdaMetafactory`, a class Android does not have, and D8 rewrites it
 * into a class it synthesises. Nothing else in the pipeline notices.
 */
internal class DexStage(private val dispatchers: DispatcherProvider) {

    suspend fun dex(
        classesDir: File,
        platform: AndroidPlatform,
        workspace: BuildWorkspace,
        minSdk: Int,
        debuggable: Boolean,
        projectRoot: File,
    ): StageResult<List<File>> {
        val classFiles = classesDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()

        // A project with resources and no code is legal; it links to an APK
        // carrying no dex at all, and that is not this stage's problem.
        if (classFiles.isEmpty()) return StageResult.ok(emptyList())

        val collector = DiagnosticCollector(projectRoot)
        val thrown = withContext(dispatchers.compiler) {
            runCatching {
                D8.run(
                    D8Command.builder(collector)
                        .addProgramFiles(classFiles.map { it.toPath() })
                        // android.jar only. The platform stubs must never appear
                        // here or on the program path: their method bodies throw,
                        // and D8 has no need of them -- it desugars the
                        // invokedynamic they exist to let ECJ compile.
                        .addLibraryFiles(platform.androidJar.toPath())
                        .setMinApiLevel(minSdk)
                        .setMode(
                            if (debuggable) CompilationMode.DEBUG else CompilationMode.RELEASE,
                        )
                        // DexIndexed, not DexFilePerClass: it is what an APK
                        // wants, and it splits into classes2.dex and beyond by
                        // itself once a build crosses the 64k method limit.
                        .setOutput(workspace.dex.toPath(), OutputMode.DexIndexed)
                        .build(),
                )
            }.exceptionOrNull()
        }

        val diagnostics = collector.diagnostics()
        val produced = dexFiles(workspace.dex)

        return when {
            thrown != null || diagnostics.hasErrors ->
                StageResult.failed(summarise(diagnostics, thrown), diagnostics)

            // D8 signals every real failure through the handler or an exception,
            // so this should be unreachable -- which is exactly why it is worth
            // saying out loud rather than shipping an APK with no code in it.
            produced.isEmpty() ->
                StageResult.failed("Dexing produced no output.", diagnostics)

            else -> StageResult.ok(produced, diagnostics)
        }
    }

    /**
     * The dex files in packaging order: `classes.dex`, `classes2.dex`, ...
     *
     * Sorted on the index rather than the name, because sorting on the name puts
     * `classes10.dex` before `classes2.dex`, and the order a dex is packaged in
     * is the order the runtime searches it.
     */
    private fun dexFiles(dir: File): List<File> =
        dir.listFiles { file -> file.isFile && file.extension == "dex" }
            .orEmpty()
            .sortedBy { file ->
                file.name.removeSurrounding("classes", ".dex").toIntOrNull() ?: 1
            }

    private fun summarise(diagnostics: List<Diagnostic>, thrown: Throwable?): String {
        val errors = diagnostics.count { it.severity == DiagnosticSeverity.ERROR }
        return when {
            errors == 1 -> "1 error."
            errors > 1 -> "$errors errors."
            // D8's own exception message is usually "Compilation failed to
            // complete" with the detail in a diagnostic we did not parse.
            // Passing it through beats reporting a failure with no cause.
            thrown != null -> "Dexing failed: ${thrown.message ?: thrown::class.java.simpleName}"
            else -> "Dexing failed."
        }
    }

    /**
     * Collects what D8 reports as it runs.
     *
     * D8 calls these from its own worker threads, so the list is synchronised.
     * `info` is dropped: it is progress chatter about individual classes, and on
     * a real project it is thousands of lines nobody reads.
     */
    private class DiagnosticCollector(private val projectRoot: File) : DiagnosticsHandler {

        private val collected = Collections.synchronizedList(mutableListOf<Diagnostic>())

        override fun error(diagnostic: R8Diagnostic) = collect(diagnostic, DiagnosticSeverity.ERROR)

        override fun warning(diagnostic: R8Diagnostic) =
            collect(diagnostic, DiagnosticSeverity.WARNING)

        override fun info(diagnostic: R8Diagnostic) = Unit

        private fun collect(diagnostic: R8Diagnostic, severity: DiagnosticSeverity) {
            collected += DexDiagnostics.convert(diagnostic, severity, projectRoot)
        }

        fun diagnostics(): List<Diagnostic> = synchronized(collected) { collected.toList() }
    }
}
