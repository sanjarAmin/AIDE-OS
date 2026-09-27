package com.osamu.aide.engine.fast

import android.util.Log
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import com.osamu.aide.engine.api.hasErrors
import kotlinx.coroutines.withContext
import org.eclipse.jdt.core.compiler.batch.BatchCompiler
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Compiles Java to `.class` files with ECJ, in this process.
 *
 * No subprocess and no dex archive: ECJ is an ordinary dependency of this
 * module, which is the whole reason the fast path needs no Linux userland.
 *
 * Several of the arguments below look arbitrary and are not; `tools/ecj/FINDINGS.md`
 * records what happens without each of them.
 */
internal class JavaCompileStage(private val dispatchers: DispatcherProvider) {

    suspend fun compile(
        sources: List<File>,
        platform: AndroidPlatform,
        workspace: BuildWorkspace,
        projectRoot: File,
        dependencies: List<File> = emptyList(),
        /**
         * Whether to emit the local variable table a debugger needs.
         *
         * **ECJ's default is `-g:lines,source`, which is not enough.** Line
         * numbers alone let a debugger stop on a line and show a stack trace,
         * so everything looks like it works -- and then every local and every
         * parameter comes back with an empty name, because the names live in
         * an attribute that was never emitted. Found by attaching `:debugger`
         * to an app this pipeline built and reading `[this:..., :I@3]` where
         * `counter` should have been.
         */
        debuggable: Boolean = true,
        /**
         * Where to keep this build's classes and what produced them, so the next
         * build recompiles only what changed; null compiles everything. Only for
         * a project with no Kotlin, whose classes are already in the output
         * before Java runs. See [IncrementalJava].
         */
        cacheDir: File? = null,
    ): StageResult<File> {
        if (sources.isEmpty()) return StageResult.ok(workspace.classes)

        val options = options(debuggable)
        val classpath = platform.compileClasspath + dependencies
        if (cacheDir == null) {
            val result = runEcj(options, classpath, sources, workspace.classes, projectRoot)
            return if (result.failure != null) StageResult.failed(result.failure, result.diagnostics)
            else StageResult.ok(workspace.classes, result.diagnostics)
        }
        return compileIncrementally(sources, options, classpath, workspace, projectRoot, IncrementalJava(cacheDir))
    }

    /** ECJ's arguments besides the classpath and files, for a cache key that has to change with them. */
    fun options(debuggable: Boolean) = buildList {
        add("-source"); add(SOURCE_LEVEL)
        add("-target"); add(SOURCE_LEVEL)

        // Annotation processing needs most of javax.lang.model, which
        // Android does not have. Left on, ECJ looks for processor services
        // it cannot load. Anything relying on generated code is a
        // Gradle-path project for now.
        add("-proc:none")

        // A release build keeps line numbers, because that is what makes a
        // crash report readable, and drops variable names, which are of no
        // use in a shipped app and are a small disclosure in one.
        add(if (debuggable) "-g" else "-g:lines,source")

        // One line per problem instead of five around a rule. See
        // EcjDiagnostics.
        add("-Xemacs")
    }

    private class EcjResult(val diagnostics: List<Diagnostic>, val failure: String?)

    private suspend fun runEcj(
        options: List<String>,
        classpath: List<File>,
        sources: List<File>,
        output: File,
        projectRoot: File,
    ): EcjResult {
        val args = buildList {
            addAll(options)
            // -classpath, not -bootclasspath: ECJ rejects -bootclasspath at
            // source level 9 and above. The stubs are what make lambdas
            // compile -- android.jar has no java.lang.invoke bootstrap classes.
            add("-classpath")
            // Platform first, dependencies after. ECJ takes the first
            // definition of a duplicated type, and a library shading its own
            // copy of an android.* class must not win over the real one.
            add(classpath.joinToString(File.pathSeparator) { it.absolutePath })

            add("-d"); add(output.absolutePath)
            addAll(sources.map { it.absolutePath })
        }

        val out = StringWriter()
        val err = StringWriter()
        val succeeded = withContext(dispatchers.compiler) {
            BatchCompiler.compile(args.toTypedArray(), PrintWriter(out), PrintWriter(err), null)
        }

        // ECJ reports problems on stderr and says almost nothing on stdout, but
        // read both rather than assume: a compiler that fails without
        // explanation is the worst thing to hand a user.
        val diagnostics = EcjDiagnostics.parse("$err\n$out", projectRoot)

        // Trust the diagnostics over the return value. ECJ returns true when
        // it merely emitted warnings, and a build that quietly proceeds past
        // an error produces an APK missing classes.
        val failed = !succeeded || diagnostics.hasErrors
        return EcjResult(diagnostics, if (failed) summarise(diagnostics, err.toString()) else null)
    }

    /**
     * Recompiles what changed, or everything when that is not safe; the rule
     * and its reasons are [IncrementalCompile]'s.
     */
    private suspend fun compileIncrementally(
        sources: List<File>,
        options: List<String>,
        classpath: List<File>,
        workspace: BuildWorkspace,
        projectRoot: File,
        cache: IncrementalJava,
    ): StageResult<File> {
        val incremental = IncrementalCompile(cache, dispatchers)
        val settings = withContext(dispatchers.io) { IncrementalJava.settings(options, classpath.filter { it != workspace.classes }) }
        val plan = incremental.plan(sources, settings)

        if (!plan.full) {
            val prepared = incremental.prepare(plan, workspace.classes)
            if (plan.unchanged) {
                Log.i(TAG, "Java: 0 of ${sources.size} sources compiled")
                return StageResult.ok(workspace.classes, incremental.keptDiagnostics(plan))
            }
            val changed = plan.changed.orEmpty()
            val result = runEcj(options, classpath + workspace.classes, changed, workspace.classes, projectRoot)
            if (result.failure != null) return StageResult.failed(result.failure, result.diagnostics)
            if (!incremental.abiChanged(plan, prepared, workspace.classes)) {
                val diagnostics = incremental.savePartial(plan, prepared, workspace.classes, result.diagnostics, projectRoot)
                Log.i(TAG, "Java: ${changed.size} of ${sources.size} sources compiled")
                return StageResult.ok(workspace.classes, diagnostics)
            }
            Log.i(TAG, "Java: an edit changed a class's ABI; compiling all ${sources.size} sources")
            withContext(dispatchers.io) { workspace.classes.listFiles()?.forEach { it.deleteRecursively() } }
        } else {
            Log.i(TAG, "Java: compiling all ${sources.size} sources: ${plan.reason}")
        }

        val result = runEcj(options, classpath, sources, workspace.classes, projectRoot)
        if (result.failure != null) {
            // A failed build leaves nothing trustworthy to compare against.
            incremental.clear()
            return StageResult.failed(result.failure, result.diagnostics)
        }
        incremental.saveFull(plan, workspace.classes, result.diagnostics, projectRoot)
        return StageResult.ok(workspace.classes, result.diagnostics)
    }

    private fun summarise(diagnostics: List<Diagnostic>, raw: String): String {
        val errors = diagnostics.count { it.severity == DiagnosticSeverity.ERROR }
        return when {
            errors == 1 -> "1 error."
            errors > 1 -> "$errors errors."
            // Nothing parsed, so nothing would be shown. Pass the raw output
            // through rather than report a failure with no explanation at all.
            raw.isNotBlank() -> "Compiling Java failed: ${raw.trim().lineSequence().first()}"
            else -> "Compiling Java failed."
        }
    }

    private companion object {
        /**
         * Java 11. Higher levels work as far as ECJ is concerned, but every
         * level above 8 needs the invokedynamic bootstrap stubs, and moving it
         * should be a deliberate change with a test behind it, not a default
         * that drifts.
         */
        const val SOURCE_LEVEL = "11"
        const val TAG = "JavaCompileStage"

    }
}
