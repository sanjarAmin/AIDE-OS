package com.osamu.aide.engine.fast

import android.util.Log
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.ProjectPaths
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One build's decision about what to compile, shared by ECJ and kotlinc.
 *
 * The rule is [IncrementalJava]'s: recompile the sources whose content changed,
 * against the kept classes of the rest, unless the result shows a different
 * ABI, or a source was added or removed -- then compile everything. This class
 * holds the steps of that rule so that a Java-only project and a project mixing
 * Kotlin and Java take them the same way; the compilers are run by the caller,
 * between [prepare] and [abiChanged].
 *
 * **Kotlin adds one reason for a full compile: `inline`.** An inline function's
 * body is copied into its callers, and its class file's ABI does not change
 * when the body does -- confirmed against kotlinc 2.2.10, where editing the
 * body of `inline fun twice` left every rendered signature and the Kotlin
 * metadata identical. So a changed Kotlin source whose text mentions `inline`
 * at all compiles everything. The word in a comment costs a full build; missing
 * it would ship callers running the old body.
 */
internal class IncrementalCompile(
    private val cache: IncrementalJava,
    private val dispatchers: DispatcherProvider,
) {

    /** A source as this build read it. */
    class Read(
        val path: String,
        val hash: String,
        val key: String,
        val size: Long,
        val modified: Long,
        /** Unchanged by size and time, so [hash] is the kept one and was not taken. */
        val trusted: Boolean,
        /** A changed Kotlin source that mentions `inline`; see the class comment. */
        val mentionsInline: Boolean,
    )

    /**
     * What this build compiles. [changed] is null for everything, empty for
     * nothing, and otherwise the sources to recompile.
     */
    class Plan(
        val settings: String,
        val sources: List<File>,
        val read: Map<File, Read>,
        val previous: IncrementalJava.State?,
        val changed: List<File>?,
        val reason: String,
    ) {
        val full: Boolean get() = changed == null
        val unchanged: Boolean get() = changed?.isEmpty() == true
        val keys: Set<String> get() = read.values.mapTo(HashSet()) { it.key }
    }

    /** The kept classes a partial build reused, and the ABI its changed sources had. */
    class Prepared(val reused: Set<String>, val oldAbi: Map<String, String>)

    suspend fun plan(sources: List<File>, settings: String): Plan = withContext(dispatchers.io) {
        val state = cache.load()
        val read = sources.associateWith { source ->
            // Absolute rather than canonical: resolving thousands of paths
            // through FUSE is itself a cost, and the list is built the same
            // way every build.
            val path = source.absolutePath
            val modified = source.lastModified()
            val size = source.length()
            state?.trustedHash(path, source)?.let {
                return@associateWith Read(path, it.hash, it.key, size, modified, trusted = true, mentionsInline = false)
            }
            val text = source.readText()
            // Nothing to compare a hash with means a full compile, so none is
            // taken; see IncrementalJava.
            val hash = if (state == null) NO_HASH else IncrementalJava.hash(text.toByteArray())
            Read(
                path,
                hash,
                IncrementalJava.sourceKey(source.name, text),
                size,
                modified,
                trusted = false,
                mentionsInline = source.extension == "kt" && INLINE.containsMatchIn(text),
            )
        }
        val previous = state?.takeIf { it.settings == settings }
        val byPath = read.values.associateBy { it.path }
        when {
            state == null -> Plan(settings, sources, read, null, null, "no earlier compile to reuse")
            previous == null -> Plan(settings, sources, read, null, null, "the compiler's options or classpath changed")
            byPath.keys != previous.sources.keys -> Plan(settings, sources, read, previous, null, "a source was added or removed")
            else -> {
                val changed = sources.filter {
                    val r = read.getValue(it)
                    val kept = previous.sources.getValue(r.path).hash
                    !r.trusted && (kept == NO_HASH || kept != r.hash)
                }
                if (changed.any { read.getValue(it).mentionsInline }) {
                    Plan(settings, sources, read, previous, null, "a changed Kotlin file mentions inline")
                } else {
                    Plan(settings, sources, read, previous, changed, "")
                }
            }
        }
    }

    /**
     * Puts the kept classes a partial or unchanged build reuses into [classes],
     * as hard links, and notes the ABI the changed sources had.
     */
    suspend fun prepare(plan: Plan, classes: File): Prepared = withContext(dispatchers.io) {
        val previous = checkNotNull(plan.previous)
        val changed = plan.changed.orEmpty()
        val old = changed.map { previous.sources.getValue(plan.read.getValue(it).path) }
        val stale = old.flatMapTo(HashSet()) { it.classFiles }
        val oldAbi = changed.associate { source ->
            val kept = previous.sources.getValue(plan.read.getValue(source).path)
            plan.read.getValue(source).path to IncrementalJava.abiOf(kept.classFiles.map { ClassAbi.read(File(cache.classes, it)) })
        }
        val reused = HashSet<String>()
        cache.classes.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(cache.classes).invariantSeparatorsPath
            if (relative !in stale) {
                IncrementalJava.link(file, File(classes, relative))
                reused += relative
            }
        }
        Prepared(reused, oldAbi)
    }

    /** Whether any recompiled source's classes in [classes] show an ABI other than before. */
    suspend fun abiChanged(plan: Plan, prepared: Prepared, classes: File): Boolean = withContext(dispatchers.io) {
        val produced = IncrementalJava.classFilesBySource(classes, plan.keys, excluding = prepared.reused)
        plan.changed.orEmpty().any { source ->
            val r = plan.read.getValue(source)
            val files = produced[r.key].orEmpty()
            IncrementalJava.abiOf(files.map { ClassAbi.read(File(classes, it)) }) != prepared.oldAbi[r.path]
        }
    }

    /** Records a partial build: the recompiled sources' new classes replace their old ones. */
    suspend fun savePartial(
        plan: Plan,
        prepared: Prepared,
        classes: File,
        diagnostics: List<Diagnostic>,
        projectRoot: File,
    ): List<Diagnostic> = withContext(dispatchers.io) {
        val produced = IncrementalJava.classFilesBySource(classes, plan.keys, excluding = prepared.reused)
        val next = checkNotNull(plan.previous).sources.toMutableMap()
        plan.changed.orEmpty().forEach { source ->
            val r = plan.read.getValue(source)
            val files = produced[r.key].orEmpty()
            next.getValue(r.path).classFiles.forEach { File(cache.classes, it).delete() }
            files.forEach { IncrementalJava.link(File(classes, it), File(cache.classes, it)) }
            next[r.path] = IncrementalJava.Source(r.hash, files, diagnostics.filter { it.belongsTo(source, projectRoot) }, r.key, r.size, r.modified)
        }
        cache.save(IncrementalJava.State(plan.settings, next))
        next.values.flatMap { it.diagnostics }
    }

    /** Records a full build: everything in [classes] is kept for the next. */
    suspend fun saveFull(plan: Plan, classes: File, diagnostics: List<Diagnostic>, projectRoot: File) =
        withContext(dispatchers.io) {
            val produced = IncrementalJava.classFilesBySource(classes, plan.keys)
            val next = plan.sources.associate { source ->
                val r = plan.read.getValue(source)
                r.path to IncrementalJava.Source(
                    r.hash,
                    produced[r.key].orEmpty(),
                    diagnostics.filter { it.belongsTo(source, projectRoot) },
                    r.key,
                    r.size,
                    r.modified,
                )
            }
            cache.clear()
            classes.walkTopDown().filter { it.isFile }.forEach { file ->
                IncrementalJava.link(file, File(cache.classes, file.relativeTo(classes).invariantSeparatorsPath))
            }
            cache.save(IncrementalJava.State(plan.settings, next))
        }

    /** The diagnostics the kept sources earned, for a build that compiled nothing. */
    fun keptDiagnostics(plan: Plan): List<Diagnostic> =
        plan.previous?.sources?.values?.flatMap { it.diagnostics }.orEmpty()

    suspend fun clear() = withContext(dispatchers.io) { cache.clear() }

    /** Whether a compiler reported [this] against [source]. */
    private fun Diagnostic.belongsTo(source: File, projectRoot: File): Boolean {
        val reported = file ?: return false
        val expected = ProjectPaths.relativise(source, projectRoot)
        return reported.path == expected.path || reported.path == source.path
    }

    companion object {
        /** Stands in for a hash that was not taken; never equal to a real one. */
        const val NO_HASH = "-"

        private val INLINE = Regex("""\binline\b""")

        fun log(message: String) = Log.i("IncrementalCompile", message)
    }
}
