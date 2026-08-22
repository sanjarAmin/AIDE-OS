package com.osamu.aide.engine.fast

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
    ): StageResult<File> {
        if (sources.isEmpty()) return StageResult.ok(workspace.classes)

        val args = buildList {
            add("-source"); add(SOURCE_LEVEL)
            add("-target"); add(SOURCE_LEVEL)

            // Annotation processing needs most of javax.lang.model, which
            // Android does not have. Left on, ECJ looks for processor services
            // it cannot load. Anything relying on generated code is a
            // Gradle-path project for now.
            add("-proc:none")

            // One line per problem instead of five around a rule. See
            // EcjDiagnostics.
            add("-Xemacs")

            // -classpath, not -bootclasspath: ECJ rejects -bootclasspath at
            // source level 9 and above. The stubs are what make lambdas
            // compile -- android.jar has no java.lang.invoke bootstrap classes.
            add("-classpath")
            add(platform.compileClasspath.joinToString(File.pathSeparator) { it.absolutePath })

            add("-d"); add(workspace.classes.absolutePath)
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

        return when {
            // Trust the diagnostics over the return value. ECJ returns true when
            // it merely emitted warnings, and a build that quietly proceeds past
            // an error produces an APK missing classes.
            !succeeded || diagnostics.hasErrors ->
                StageResult.failed(summarise(diagnostics, err.toString()), diagnostics)

            else -> StageResult.ok(workspace.classes, diagnostics)
        }
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
    }
}
