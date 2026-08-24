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
 * as the platform, (3) it answers a completion-shaped query inside the
 * latency budget, (4) it keeps producing an AST for a file that does not
 * parse -- the property batch ECJ does not need and an editor cannot live
 * without.
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

    @Test
    fun completion_on_activity_members_answers_inside_the_budget() {
        // One full request per iteration -- new task, parse, analyze, scope,
        // members -- because that is the conservative bound: no reuse between
        // keystrokes, every request pays full price. If even this fits the
        // budget, an :lsp:java that reuses anything only gets faster.
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
        // The plan's M3 acceptance is < 200 ms. Assert it of the warm request;
        // the cold one is startup cost, reported rather than asserted, because
        // an app pays it once.
        assertTrue("warm completion took $warmMillis ms; the budget is 200 ms", warmMillis < 200)
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
