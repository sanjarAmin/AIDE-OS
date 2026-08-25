package com.osamu.aide.engine.fast

import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Compiles the project's Kotlin, ahead of its Java.
 *
 * The order is the interesting part of mixed compilation and it is not
 * symmetric. kotlinc is given the Java sources as well as the Kotlin ones --
 * it reads them for signatures but emits nothing for them -- so Kotlin can
 * refer to Java. ECJ then runs with kotlinc's output directory on its
 * classpath, so Java can refer to Kotlin. Reverse the order and every Kotlin
 * type is unresolved in the Java half.
 *
 * Both compilers write into the same `classes/` directory, which is what makes
 * the dexer's job unchanged: it walks one tree and does not care which compiler
 * produced what.
 */
internal class KotlinCompileStage(
    private val compiler: KotlinCompiler,
    private val dispatchers: DispatcherProvider,
) {

    suspend fun compile(
        kotlinSources: List<File>,
        /** Passed for signatures only; ECJ still compiles these itself. */
        javaSources: List<File>,
        platform: AndroidPlatform,
        workspace: BuildWorkspace,
        projectRoot: File,
        moduleName: String,
        dependencies: List<File> = emptyList(),
        onDiagnostic: (Diagnostic) -> Unit = {},
    ): StageResult<File> {
        if (kotlinSources.isEmpty()) return StageResult.ok(workspace.classes)

        val outcome = withContext(dispatchers.compiler) {
            compiler.compile(
                sources = kotlinSources + javaSources,
                classpath = platform.compileClasspath + dependencies,
                outputDir = workspace.classes,
                moduleName = moduleName,
            )
        }

        val diagnostics = KotlincDiagnostics.parse(outcome.output, projectRoot)
        diagnostics.forEach(onDiagnostic)

        if (outcome.succeeded) return StageResult.ok(workspace.classes, diagnostics)

        // A failure the parser found nothing in is worse than no parser at all:
        // the user is told the build failed and shown a list of warnings. When
        // that happens the raw output is the only explanation there is, so it
        // goes in the message rather than being dropped for being unstructured.
        val explained = diagnostics.any { it.severity == DiagnosticSeverity.ERROR }
        return StageResult.failed(
            if (explained) {
                "Compiling Kotlin failed."
            } else {
                "Compiling Kotlin failed: ${outcome.output.trim().ifEmpty { "no output" }}"
            },
            diagnostics,
        )
    }
}
