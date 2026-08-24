package com.osamu.aide.lsp.java

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.util.JavacTask
import com.sun.source.util.Trees
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
 * `android.app.Activity` and its supertypes out of `android.jar`. Holding one
 * [StandardJavaFileManager] across requests brings that to roughly 200 ms as
 * its caches and ART's JIT warm up. That is the reason this class exists and
 * the reason it is a field somewhere long-lived rather than a local.
 *
 * It is also not enough on its own. The remaining cost is re-entering platform
 * symbols into a fresh symbol table every time, and fixing that means keeping
 * javac's `Context` alive across tasks -- machinery this does not have yet.
 * `tools/javals/FINDINGS.md` records the measurements and the order to attack
 * them in.
 *
 * **Single-threaded by construction.** javac is not thread-safe and neither is
 * a file manager being read by one; every request takes [lock]. Callers get
 * serialisation for free, and pay for it in latency when they ask for two
 * things at once -- which is the right trade for an editor, where the second
 * request usually invalidates the first.
 */
internal class ResidentCompiler(
    private val platform: File,
    private val classpath: List<File>,
) {

    private val tool: JavacTool = JavacTool.create()
    private val lock = Mutex()

    private val fileManager: StandardJavaFileManager by lazy {
        tool.getStandardFileManager(null, null, StandardCharsets.UTF_8).apply {
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
            val task = tool.getTask(
                null,
                fileManager,
                diagnostics,
                // -source 8 because the device has no module system to satisfy
                // 9+. -proc:none because annotation processors would run user
                // code on every keystroke.
                listOf("-source", "8", "-target", "8", "-proc:none", "-XDide.mode=true"),
                null,
                listOf(SourceText(file, text)),
            ) as JavacTask

            // parse() then analyze(): attribution is what resolves names, and
            // without it every element query comes back empty.
            val unit = task.parse().first()
            task.analyze()

            block(Compilation(task, unit, Trees.instance(task), diagnostics, file))
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
