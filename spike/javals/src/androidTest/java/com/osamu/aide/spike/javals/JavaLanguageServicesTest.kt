package com.osamu.aide.spike.javals

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sun.source.tree.MethodTree
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import com.sun.tools.javac.api.JavacTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardLocation
import kotlin.system.measureTimeMillis

/**
 * Spike R3: nb-javac on ART, asked the questions an editor asks.
 *
 * Each test isolates one layer, so a failure names the layer rather than the
 * spike: (1) the compiler's classes load at all, (2) it can read android.jar
 * as the platform, (3) it answers a completion-shaped query correctly and at
 * what cost, (4) it keeps producing an AST for a file that does not parse --
 * the property batch ECJ does not need and an editor cannot live without.
 *
 * **The result, in one line: correctness is free, latency is not.** A fresh
 * compilation task per request costs 700--1100 ms against a 200 ms budget, and
 * ~95 % of that is `analyze()`. The two measurement tests here exist to say
 * where the time goes and how much of it reuse recovers; both write their
 * numbers to logcat under [TAG]. `tools/javals/FINDINGS.md` is the write-up
 * and is the document `:lsp:java` should be designed from.
 *
 * The classpath strategy under test is `-source 8` with android.jar as
 * PLATFORM_CLASS_PATH. At source 9+ javac wants a module system, which the
 * device does not have; whether 9+ needs AndroidIDE's custom-JDK-image
 * treatment is recorded as an open question in tools/javals/FINDINGS.md, not
 * answered here.
 */
@RunWith(AndroidJUnit4::class)
class JavaLanguageServicesTest {

    private val context = InstrumentationRegistry.getInstrumentation().context

    private val androidJar: File by lazy {
        val target = File(context.cacheDir, "android.jar")
        if (!target.isFile) {
            context.assets.open("android.jar").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        target
    }

    private class StringSource(className: String, private val code: String) :
        SimpleJavaFileObject(
            URI.create("string:///${className.replace('.', '/')}.java"),
            JavaFileObject.Kind.SOURCE,
        ) {
        override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = code
    }

    private fun newTask(
        source: JavaFileObject,
        diagnostics: DiagnosticCollector<JavaFileObject>? = null,
    ): JavacTask {
        val tool = JavacTool.create()
        val fileManager = tool.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)
        fileManager.setLocation(StandardLocation.PLATFORM_CLASS_PATH, listOf(androidJar))
        fileManager.setLocation(StandardLocation.CLASS_PATH, emptyList())
        return tool.getTask(
            null,
            fileManager,
            diagnostics,
            listOf("-source", "8", "-target", "8", "-proc:none"),
            null,
            listOf(source),
        ) as JavacTask
    }

    @Test
    fun the_compiler_loads_and_parses_without_a_classpath() {
        val task = newTask(StringSource("com.example.Hello", HELLO))
        val units = task.parse().toList()
        assertEquals(1, units.size)
        val classes = units.single().typeDecls
        assertEquals(1, classes.size)
    }

    @Test
    fun analysis_resolves_a_type_from_android_jar() {
        val task = newTask(StringSource("com.example.MainActivity", ACTIVITY))
        task.parse()
        task.analyze()
        val activity = task.elements.getTypeElement("android.app.Activity")
        assertNotNull("android.app.Activity did not resolve from android.jar", activity)
        val bundle = task.elements.getTypeElement("android.os.Bundle")
        assertNotNull(bundle)
    }

    /**
     * Completion is *correct*, and a fresh task per request is far too slow.
     *
     * One full request per iteration -- new task, parse, analyze, scope, members
     * -- which is the shape an `:lsp:java` written the obvious way would have.
     * It costs 700--1100 ms against a 200 ms budget, so the obvious way is out;
     * `tools/javals/FINDINGS.md` records where the time goes and what to do
     * instead.
     *
     * The timing assertion is deliberately inverted. This is a characterisation
     * test: it pins the finding rather than the requirement, so that if a
     * future runtime or artifact makes the naive shape fast, this fails and
     * sends the reader back to the document instead of letting it quietly rot.
     * M3's real acceptance assertion belongs to `:lsp:java`, which does not
     * meet it yet.
     */
    @Test
    fun completion_is_correct_but_a_fresh_task_per_request_misses_the_budget() {
        var coldMillis = 0L
        var warmMillis = 0L
        var proposals: List<String> = emptyList()

        repeat(3) { iteration ->
            val millis = measureTimeMillis {
                proposals = completeActivityMembers()
            }
            if (iteration == 0) coldMillis = millis else warmMillis = millis
            Log.i(TAG, "completion iteration $iteration: $millis ms, ${proposals.size} proposals")
        }

        assertTrue("no proposals at all", proposals.isNotEmpty())
        assertTrue("expected inherited public member getSystemService, got ${proposals.take(20)}",
            "getSystemService" in proposals)
        assertTrue("expected protected member onCreate visible from a subclass",
            "onCreate" in proposals)
        assertTrue("expected Object members via the full supertype chain",
            "hashCode" in proposals)

        Log.i(TAG, "cold $coldMillis ms, warm $warmMillis ms")
        assertTrue(
            "a fresh task per request took $warmMillis ms. If this is now under " +
                "200 ms, tools/javals/FINDINGS.md is out of date -- read it before " +
                "deleting this assertion.",
            warmMillis > 200,
        )
    }

    /**
     * Where the ~900 ms of a fresh request actually goes.
     *
     * The budget test above says the naive shape is too slow; this says which
     * part to attack, which is the only reason the number is worth having. Each
     * phase is measured separately over several iterations so a one-off is
     * visible as a one-off.
     */
    @Test
    fun the_cost_of_a_fresh_request_is_broken_down_by_phase() {
        repeat(3) { iteration ->
            var task: JavacTask? = null
            var unit: com.sun.source.tree.CompilationUnitTree? = null

            val setup = measureTimeMillis {
                task = newTask(StringSource("com.example.MainActivity", ACTIVITY))
            }
            val parse = measureTimeMillis { unit = task!!.parse().toList().single() }
            val analyze = measureTimeMillis { task!!.analyze() }
            val query = measureTimeMillis {
                val trees = Trees.instance(task!!)
                var methodPath: TreePath? = null
                object : TreePathScanner<Unit?, Unit?>() {
                    override fun visitMethod(node: MethodTree, p: Unit?): Unit? {
                        if (node.name.contentEquals("onCreate")) methodPath = currentPath
                        return super.visitMethod(node, p)
                    }
                }.scan(TreePath(unit), null)
                val scope = trees.getScope(checkNotNull(methodPath))
                val activity = checkNotNull(task!!.elements.getTypeElement("android.app.Activity"))
                val declared = task!!.types.getDeclaredType(activity)
                task!!.elements.getAllMembers(activity)
                    .filter {
                        trees.isAccessible(
                            scope,
                            it,
                            declared as javax.lang.model.type.DeclaredType,
                        )
                    }
                    .map { it.simpleName.toString() }
            }

            Log.i(
                TAG,
                "phase $iteration: setup=$setup parse=$parse analyze=$analyze query=$query " +
                    "total=${setup + parse + analyze + query}",
            )
        }
    }

    /**
     * Does sharing the file manager across requests pay for itself?
     *
     * The cheap half of reuse, and the question M3's design turns on. Nearly all
     * of a request is `analyze()`, and the suspicion is that it is dominated by
     * entering `android.app.Activity` and its supertypes out of android.jar --
     * work a shared file manager might cache and a fresh one certainly repeats.
     *
     * If this alone reaches the budget, `:lsp:java` needs a cached file manager
     * and little else. If it does not, the symbol table has to survive between
     * requests too, which is a far larger piece of machinery.
     */
    @Test
    fun sharing_the_file_manager_across_requests_is_measured() {
        val tool = JavacTool.create()
        val shared = tool.getStandardFileManager(null, null, StandardCharsets.UTF_8)
        shared.setLocation(StandardLocation.PLATFORM_CLASS_PATH, listOf(androidJar))
        shared.setLocation(StandardLocation.CLASS_PATH, emptyList())

        repeat(10) { iteration ->
            var analyze = 0L
            val total = measureTimeMillis {
                val task = tool.getTask(
                    null,
                    shared,
                    null,
                    listOf("-source", "8", "-target", "8", "-proc:none"),
                    null,
                    listOf(StringSource("com.example.MainActivity", ACTIVITY)),
                ) as JavacTask
                task.parse()
                analyze = measureTimeMillis { task.analyze() }
            }
            Log.i(TAG, "shared-fm $iteration: total=$total analyze=$analyze")
        }
    }

    @Test
    fun a_file_that_does_not_parse_still_yields_an_ast_and_a_positioned_error() {
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val task = newTask(StringSource("com.example.Broken", BROKEN), diagnostics)
        val unit = task.parse().toList().single()
        task.analyze()

        // The editor's requirement: the good parts of the file survive the bad
        // line. The class declaration and the intact method must still be in
        // the tree.
        var methods = 0
        object : TreePathScanner<Unit?, Unit?>() {
            override fun visitMethod(node: MethodTree, p: Unit?): Unit? {
                methods++
                return super.visitMethod(node, p)
            }
        }.scan(TreePath(unit), null)
        assertTrue("expected the intact method to survive in the AST", methods >= 1)

        val errors = diagnostics.diagnostics.filter { it.kind == Diagnostic.Kind.ERROR }
        assertTrue("expected at least one error diagnostic", errors.isNotEmpty())
        val positioned = errors.first()
        assertTrue("error carries no line number", positioned.lineNumber > 0)
        Log.i(TAG, "diagnostic: line ${positioned.lineNumber}: ${positioned.getMessage(null)}")
    }

    /** One completion-shaped request: members of `this` inside onCreate. */
    private fun completeActivityMembers(): List<String> {
        val task = newTask(StringSource("com.example.MainActivity", ACTIVITY))
        val unit = task.parse().toList().single()
        task.analyze()
        val trees = Trees.instance(task)

        var methodPath: TreePath? = null
        object : TreePathScanner<Unit?, Unit?>() {
            override fun visitMethod(node: MethodTree, p: Unit?): Unit? {
                if (node.name.contentEquals("onCreate")) methodPath = currentPath
                return super.visitMethod(node, p)
            }
        }.scan(TreePath(unit), null)
        val scope = trees.getScope(checkNotNull(methodPath))

        val activity = checkNotNull(task.elements.getTypeElement("android.app.Activity"))
        val declared = task.types.getDeclaredType(activity)
        return task.elements.getAllMembers(activity)
            .filter { trees.isAccessible(scope, it, declared as javax.lang.model.type.DeclaredType) }
            .map { it.simpleName.toString() }
    }

    private companion object {
        const val TAG = "JavalsSpike"

        val HELLO = """
            package com.example;

            public class Hello {
                public String greet(String name) {
                    return "Hello, " + name;
                }
            }
        """.trimIndent()

        val ACTIVITY = """
            package com.example;

            import android.app.Activity;
            import android.os.Bundle;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    setTitle("spike");
                }
            }
        """.trimIndent()

        val BROKEN = """
            package com.example;

            public class Broken {
                int x = ;

                public int intact() {
                    return 42;
                }
            }
        """.trimIndent()
    }
}
