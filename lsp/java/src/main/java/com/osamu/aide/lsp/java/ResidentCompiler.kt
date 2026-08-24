package com.osamu.aide.lsp.java

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.util.JavacTask
import com.sun.source.util.Trees
import com.sun.tools.javac.api.JavacTaskPool
import com.sun.tools.javac.api.JavacTool
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.StandardLocation

/**
 * One javac, kept alive between requests.
 *
 * Spike R3 measured a fresh compilation task per request at 700--1100 ms
 * against a 200 ms budget, of which ~95 % was `analyze()` re-entering
 * `android.app.Activity` and its supertypes out of `android.jar`. Two things
 * are held across requests to avoid paying that repeatedly:
 *
 * - **The file manager**, so the platform jar is opened and indexed once.
 * - **javac's `Context`**, via [JavacTaskPool], so the symbol table survives.
 *   This is the one that matters. A fresh `Context` means a fresh `Symtab`,
 *   and a fresh `Symtab` means every platform class is read out of
 *   `android.jar` and entered again on every keystroke.
 *
 * The pool is javac's own, not ours. AndroidIDE hand-rolled the equivalent --
 * `ReusableCompiler`, `ReusableContext`, `ReusableJavaCompiler` -- because they
 * needed hooks for partial reparse and cancellation on top. We need neither
 * yet, and upstream's version is already in the artifact, tested by the people
 * who wrote the compiler.
 *
 * **What the pool clears between uses** is the part to be careful about. It
 * drops the previous compilation's own classes, so redefining a type in the
 * buffer does not leave the old one resolvable; it keeps symbols read from the
 * classpath, which is the whole point. A context it considers polluted is
 * thrown away rather than reused. `pooling_does_not_leak_symbols_between_requests`
 * in the tests is the assertion that this holds on ART.
 *
 * **Single-threaded by construction.** javac is not thread-safe, neither is a
 * file manager being read by one, and a pooled context least of all: two
 * requests sharing one would interleave into the same symbol table. Every
 * request takes [lock]. Callers get serialisation for free and pay for it in
 * latency when they ask for two things at once -- the right trade for an
 * editor, where the second request usually invalidates the first.
 */
internal class ResidentCompiler(
    private val platform: File,
    private val classpath: List<File>,
) {

    // One context. Requests are serialised, so a second would never be live at
    // the same time as the first, and each one held is a whole symbol table of
    // platform classes sitting in the editor's heap.
    private val pool = JavacTaskPool(1)
    private val lock = Mutex()

    private val fileManager: StandardJavaFileManager by lazy {
        JavacTool.create().getStandardFileManager(null, null, StandardCharsets.UTF_8).apply {
            // android.jar is the platform, not a library on the classpath. Put
            // it anywhere else and javac looks for a JDK that is not there.
            setLocation(StandardLocation.PLATFORM_CLASS_PATH, listOf(platform))
            setLocation(StandardLocation.CLASS_PATH, classpath)
        }
    }

    /**
     * Compiles [text] as [file] and hands the result to [block].
     *
     * The result is only valid inside [block]: a [JavacTask]'s trees and
     * elements belong to its own context, and reading them after the next
     * request has started is how you get symbols from two compilations mixed
     * together.
     */
    suspend fun <T> withCompilation(file: File, text: String, block: (Compilation) -> T): T =
        lock.withLock {
            val diagnostics = DiagnosticCollector<JavaFileObject>()
            pool.getTask(
                null,
                fileManager,
                diagnostics,
                OPTIONS,
                null,
                listOf(SourceText(file, text)),
            ) { task ->
                // parse() then analyze(): attribution is what resolves names,
                // and without it every element query comes back empty.
                val unit = task.parse().first()
                task.analyze()

                block(Compilation(task, unit, Trees.instance(task), diagnostics, file))
            }
        }

    private companion object {
        /**
         * Constant, because the pool keys its cached contexts on them. Varying
         * the options per request would hand back a cold context every time and
         * quietly undo the reuse this class exists for.
         *
         * `-source 8` because the device has no module system to satisfy 9+.
         * `-proc:none` because annotation processors would otherwise run user
         * code on every keystroke.
         */
        val OPTIONS = listOf("-source", "8", "-target", "8", "-proc:none")
    }

    /** The editor's buffer, which has no file on disk yet, as something javac reads. */
    private class SourceText(file: File, private val text: String) :
        SimpleJavaFileObject(URI.create("file://${file.absolutePath}"), JavaFileObject.Kind.SOURCE) {
        override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = text
    }
}

/** One compilation, valid only for the duration of the call it was handed to. */
internal class Compilation(
    val task: JavacTask,
    val unit: CompilationUnitTree,
    val trees: Trees,
    val diagnostics: DiagnosticCollector<JavaFileObject>,
    val file: File,
)
