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

    private fun options(debuggable: Boolean) = buildList {
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
     * Recompiles what changed, or everything when that is not safe.
     *
     * Not safe, and so a full compile: no previous state, different options or
     * classpath, a source added or removed, or a changed source whose classes
     * now show a different ABI -- see [IncrementalJava] for why the last is not
     * narrowed further.
     */
    private suspend fun compileIncrementally(
        sources: List<File>,
        options: List<String>,
        classpath: List<File>,
        workspace: BuildWorkspace,
        projectRoot: File,
        cache: IncrementalJava,
    ): StageResult<File> {
        class Read(
            val path: String,
            val hash: String,
            val key: String,
            val size: Long,
            val modified: Long,
            /** Unchanged by size and time, so [hash] is the kept one and not compared. */
            val trusted: Boolean = false,
        )
        val startedAt = System.nanoTime()
        val settings = withContext(dispatchers.io) { IncrementalJava.settings(options, classpath.filter { it != workspace.classes }) }
        val state = withContext(dispatchers.io) { cache.load() }
        var hashed = 0
        val read = withContext(dispatchers.io) {
            sources.associateWith { source ->
                // Absolute rather than canonical: resolving 3,000 paths through
                // FUSE is itself a cost, and the sources list is built the same
                // way every build.
                val path = source.absolutePath
                val modified = source.lastModified()
                val size = source.length()
                state?.trustedHash(path, source)?.let { return@associateWith Read(path, it.hash, it.key, size, modified, trusted = true) }
                if (state == null) {
                    // Nothing to compare a hash with, so none is taken: this build
                    // compiles everything anyway, and the next compares size and
                    // time first. A source without a kept hash that fails that
                    // comparison simply counts as changed. The package is still
                    // needed, and a first line is enough to find it.
                    return@associateWith Read(path, NO_HASH, IncrementalJava.sourceKey(source.name, source.readText()), size, modified)
                }
                hashed++
                // One read of each source for both its hash and its package.
                val bytes = source.readBytes()
                Read(path, IncrementalJava.hash(bytes), IncrementalJava.sourceKey(source.name, String(bytes)), size, modified)
            }
        }
        val readAt = System.nanoTime()
        Log.i(TAG, "timing: hashed $hashed of ${sources.size} sources in ${(readAt - startedAt) / 1_000_000} ms")
        val byPath = read.values.associateBy { it.path }
        val keys = read.values.mapTo(HashSet()) { it.key }

        val previous = state?.takeIf { it.settings == settings }
        val structural = previous == null || byPath.keys != previous.sources.keys
        val changed = if (structural) {
            emptyList()
        } else {
            sources.filter {
                val r = read.getValue(it)
                val kept = previous!!.sources.getValue(r.path).hash
                !r.trusted && (kept == NO_HASH || kept != r.hash)
            }
        }

        if (!structural && changed.isEmpty()) {
            // Nothing to compile: the last build's classes are this build's.
            withContext(dispatchers.io) { linkTree(cache.classes, workspace.classes, excluding = emptySet()) }
            Log.i(TAG, "timing: linked in ${(System.nanoTime() - readAt) / 1_000_000} ms")
            Log.i(TAG, "Java: 0 of ${sources.size} sources compiled")
            return StageResult.ok(workspace.classes, previous!!.sources.values.flatMap { it.diagnostics })
        }

        if (!structural) {
            val old = changed.associate { source -> read.getValue(source).path to previous!!.sources.getValue(read.getValue(source).path) }
            val stale = old.values.flatMapTo(HashSet()) { it.classFiles }
            val oldAbi = withContext(dispatchers.io) {
                old.mapValues { (_, source) -> IncrementalJava.abiOf(source.classFiles.map { ClassAbi.read(File(cache.classes, it)) }) }
            }
            withContext(dispatchers.io) { linkTree(cache.classes, workspace.classes, excluding = stale) }

            val result = runEcj(options, classpath + workspace.classes, changed, workspace.classes, projectRoot)
            if (result.failure != null) return StageResult.failed(result.failure, result.diagnostics)

            val produced = withContext(dispatchers.io) {
                // Anything in the output that was not linked in was written just now.
                val reused = (previous!!.sources.values.flatMapTo(HashSet()) { it.classFiles }) - stale
                IncrementalJava.classFilesBySource(workspace.classes, keys, excluding = reused)
            }
            val abiChanged = withContext(dispatchers.io) {
                changed.any { source ->
                    val files = produced[read.getValue(source).key].orEmpty()
                    IncrementalJava.abiOf(files.map { ClassAbi.read(File(workspace.classes, it)) }) != oldAbi[read.getValue(source).path]
                }
            }
            if (!abiChanged) {
                val next = withContext(dispatchers.io) {
                    val next = previous!!.sources.toMutableMap()
                    changed.forEach { source ->
                        val r = read.getValue(source)
                        val files = produced[r.key].orEmpty()
                        // The cache follows the build: old classes out, new in.
                        next.getValue(r.path).classFiles.forEach { File(cache.classes, it).delete() }
                        files.forEach { IncrementalJava.link(File(workspace.classes, it), File(cache.classes, it)) }
                        next[r.path] = IncrementalJava.Source(r.hash, files, result.diagnostics.filter { it.belongsTo(source, projectRoot) }, r.key, r.size, r.modified)
                    }
                    cache.save(IncrementalJava.State(settings, next))
                    next
                }
                Log.i(TAG, "Java: ${changed.size} of ${sources.size} sources compiled")
                return StageResult.ok(workspace.classes, next.values.flatMap { it.diagnostics })
            }
            Log.i(TAG, "Java: an edit changed a class's ABI; compiling all ${sources.size} sources")
            withContext(dispatchers.io) { workspace.classes.listFiles()?.forEach { it.deleteRecursively() } }
        }

        // In full. Classes compiled by kotlinc would be in the output already,
        // which is why the caller passes no cache for a project with Kotlin.
        val ecjAt = System.nanoTime()
        val result = runEcj(options, classpath, sources, workspace.classes, projectRoot)
        Log.i(TAG, "timing: full ECJ in ${(System.nanoTime() - ecjAt) / 1_000_000} ms")
        withContext(dispatchers.io) { cache.clear() }
        // A failed build leaves nothing trustworthy to compare against.
        if (result.failure != null) return StageResult.failed(result.failure, result.diagnostics)
        withContext(dispatchers.io) {
            val produced = IncrementalJava.classFilesBySource(workspace.classes, keys)
            val next = sources.associate { source ->
                val r = read.getValue(source)
                r.path to IncrementalJava.Source(
                    r.hash,
                    produced[r.key].orEmpty(),
                    result.diagnostics.filter { it.belongsTo(source, projectRoot) },
                    r.key,
                    r.size,
                    r.modified,
                )
            }
            linkTree(workspace.classes, cache.classes, excluding = emptySet())
            cache.save(IncrementalJava.State(settings, next))
        }
        Log.i(TAG, "timing: bookkeeping in ${(System.nanoTime() - ecjAt) / 1_000_000} ms since ECJ started")
        Log.i(TAG, "Java: all ${sources.size} sources compiled")
        return StageResult.ok(workspace.classes, result.diagnostics)
    }

    /** Every file under [from] linked into [to] at the same relative path, except [excluding]. */
    private fun linkTree(from: File, to: File, excluding: Set<String>) {
        from.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(from).invariantSeparatorsPath
            if (relative !in excluding) IncrementalJava.link(file, File(to, relative))
        }
    }

    /** Whether ECJ reported [this] against [source]; paths come back relative to the project when inside it. */
    private fun Diagnostic.belongsTo(source: File, projectRoot: File): Boolean {
        val reported = file ?: return false
        val expected = com.osamu.aide.engine.api.ProjectPaths.relativise(source, projectRoot)
        return reported.path == expected.path || reported.path == source.path
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

        /** Stands in for a hash that was not taken; never equal to a real one. */
        const val NO_HASH = "-"
    }
}
