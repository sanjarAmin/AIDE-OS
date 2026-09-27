package com.osamu.aide.engine.fast

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.OutputMode
import com.android.tools.r8.Version
import android.util.Log
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
        dependencies: List<File> = emptyList(),
        /**
         * Where a debug build keeps each shard's dex between builds, or null to
         * dex everything every time. Outside the workspace, which each build
         * empties. See [DexShards].
         */
        cacheDir: File? = null,
    ): StageResult<List<File>> {
        val classFiles = classesDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()

        // Dependencies are **program** input, not library input: their classes
        // have to end up in the APK's dex or the app links against them at
        // compile time and throws NoClassDefFoundError on the device. Only
        // android.jar is a library here, because the platform provides it.
        val dependencyJars = dependencies.filter { it.isFile }

        // A project with resources and no code is legal; it links to an APK
        // carrying no dex at all, and that is not this stage's problem.
        if (classFiles.isEmpty() && dependencyJars.isEmpty()) return StageResult.ok(emptyList())

        if (cacheDir != null && DexShards.applies(debuggable, minSdk)) {
            dexInShards(classesDir, classFiles, dependencyJars, platform, workspace, minSdk, projectRoot, cacheDir)
                ?.let { return it }
            // Null is the one case shards cannot handle: the same class in two
            // of them. Whole-program below, which cannot produce that.
            workspace.dex.listFiles()?.forEach { it.delete() }
        }

        val collector = DiagnosticCollector(projectRoot)
        val thrown = runD8(classFiles + dependencyJars, workspace.dex, platform, minSdk, debuggable, collector)

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
     * Dexes what changed, a shard at a time, and returns every shard's dex.
     *
     * Null when the shards turned out to define a class twice, which the caller
     * answers with a whole-program dex. Warnings from a shard taken from the
     * cache are not repeated: they were reported by the build that dexed it,
     * and they are about code that has not changed since.
     */
    private suspend fun dexInShards(
        classesDir: File,
        classFiles: List<File>,
        dependencyJars: List<File>,
        platform: AndroidPlatform,
        workspace: BuildWorkspace,
        minSdk: Int,
        projectRoot: File,
        cacheDir: File,
    ): StageResult<List<File>>? {
        val collector = DiagnosticCollector(projectRoot)
        val shards = DexShards.plan(classesDir, classFiles, dependencyJars)
        val version = Version.getVersionString()
        val used = HashSet<String>()
        val outputs = mutableListOf<File>()
        var dexed = 0
        withContext(dispatchers.io) { cacheDir.mkdirs() }

        for (shard in shards) {
            val name = DexShards.directoryName(shard)
            used += name
            val dir = File(cacheDir, name)
            val key = withContext(dispatchers.io) {
                DexShards.key(shard, classesDir, minSdk, version, platform.androidJar)
            }
            val stamp = File(dir, KEY_FILE)
            val current = withContext(dispatchers.io) { stamp.isFile && stamp.readText() == key }
            if (!current) {
                // Into a sibling first, and renamed with its key already inside:
                // a build killed half way leaves either the old entry or no
                // entry, never a new key over an old or partial dex.
                val staging = File(cacheDir, "$name.partial")
                withContext(dispatchers.io) {
                    staging.deleteRecursively()
                    staging.mkdirs()
                }
                val thrown = runD8(shard.inputs, staging, platform, minSdk, debuggable = true, collector)
                val diagnostics = collector.diagnostics()
                if (thrown != null || diagnostics.hasErrors) {
                    withContext(dispatchers.io) { staging.deleteRecursively() }
                    return StageResult.failed(summarise(diagnostics, thrown), diagnostics)
                }
                withContext(dispatchers.io) {
                    File(staging, KEY_FILE).writeText(key)
                    dir.deleteRecursively()
                    check(staging.renameTo(dir)) { "could not keep the dex for ${shard.name}" }
                }
                dexed++
            }
            outputs += dexFiles(dir)
        }

        return withContext(dispatchers.io) {
            // Packages and jars that are gone: a deleted class must not linger
            // in the cache, although only the shards in this plan are packaged.
            cacheDir.listFiles()?.filter { it.name !in used }?.forEach { it.deleteRecursively() }

            val duplicates = DexShards.duplicateClasses(outputs)
            if (duplicates.isNotEmpty()) {
                Log.w(TAG, "shards define ${duplicates.size} classes twice, e.g. ${duplicates.first()}; dexing whole")
                return@withContext null
            }

            // classes.dex, classes2.dex, ... in plan order, which is stable.
            outputs.forEachIndexed { index, dex ->
                dex.copyTo(File(workspace.dex, if (index == 0) "classes.dex" else "classes${index + 1}.dex"), overwrite = true)
            }
            Log.i(TAG, "dexed $dexed of ${shards.size} shards; ${shards.size - dexed} from the cache")
            val diagnostics = collector.diagnostics()
            val produced = dexFiles(workspace.dex)
            if (produced.isEmpty()) {
                StageResult.failed("Dexing produced no output.", diagnostics)
            } else {
                StageResult.ok(produced, diagnostics)
            }
        }
    }

    /** One D8 run over [inputs] into [output]; what it threw, if anything. */
    private suspend fun runD8(
        inputs: List<File>,
        output: File,
        platform: AndroidPlatform,
        minSdk: Int,
        debuggable: Boolean,
        collector: DiagnosticCollector,
    ): Throwable? = withContext(dispatchers.compiler) {
            runCatching {
                D8.run(
                    D8Command.builder(collector)
                        .addProgramFiles(inputs.map { it.toPath() })
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
                        .setOutput(output.toPath(), OutputMode.DexIndexed)
                        .build(),
                )
            }.exceptionOrNull()
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

    private companion object {
        const val TAG = "DexStage"
        const val KEY_FILE = "key"
    }
}
