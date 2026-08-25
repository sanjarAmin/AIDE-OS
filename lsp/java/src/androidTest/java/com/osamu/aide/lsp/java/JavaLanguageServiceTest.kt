package com.osamu.aide.lsp.java

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.engine.api.DiagnosticSeverity
import com.osamu.aide.engine.api.errors
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * `:lsp:java`, asked the questions the editor will ask it.
 *
 * These assert on the answers, not on the machinery: whether a proposal list
 * contains the member a user is reaching for, and whether a diagnostic points
 * at a place they can tap. The latency test is separate and honest about the
 * fact that the budget is not met yet -- see `tools/javals/FINDINGS.md`.
 */
@RunWith(AndroidJUnit4::class)
class JavaLanguageServiceTest {

    private val context = InstrumentationRegistry.getInstrumentation().context

    private lateinit var projectRoot: File
    private lateinit var service: JavaLanguageService

    private val androidJar: File by lazy {
        val target = File(context.cacheDir, "android.jar")
        if (!target.isFile) {
            context.assets.open("android.jar").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        target
    }

    @Before
    fun setUp() {
        projectRoot = File(context.cacheDir, "lsp-project").apply {
            deleteRecursively()
            mkdirs()
        }
        service = JavaLanguageService(
            platform = androidJar,
            projectRoot = projectRoot,
            dispatchers = DefaultDispatcherProvider(),
        )
    }

    private fun sourceFile(name: String = "MainActivity.java"): File =
        File(projectRoot, "src/main/java/com/example/$name").apply { parentFile?.mkdirs() }

    @Test
    fun completing_after_a_dot_offers_members_of_the_receiver() = runTest {
        val text = ACTIVITY.replace(CURSOR, "")
        val items = service.complete(sourceFile(), text, ACTIVITY.indexOf(CURSOR))

        val labels = items.map { it.label }
        assertTrue("no proposals at all", labels.isNotEmpty())
        assertTrue("expected an inherited public member, got ${labels.take(20)}",
            "getSystemService" in labels)
        assertTrue("expected a member from Object via the supertype chain",
            "hashCode" in labels)
        assertTrue(
            "expected methods to be typed as methods",
            items.first { it.label == "getSystemService" }.kind == CompletionKind.METHOD,
        )
    }

    @Test
    fun a_typed_prefix_filters_the_proposals() = runTest {
        val source = ACTIVITY.replace(CURSOR, "getSys")
        val items = service.complete(sourceFile(), source, ACTIVITY.indexOf(CURSOR) + "getSys".length)

        val labels = items.map { it.label }
        assertTrue("expected the prefixed member, got ${labels.take(20)}",
            "getSystemService" in labels)
        assertTrue(
            "every proposal should match the prefix, got ${labels.take(20)}",
            labels.all { it.startsWith("getSys", ignoreCase = true) },
        )
    }

    @Test
    fun completing_a_bare_identifier_offers_what_is_in_scope() = runTest {
        val source = SCOPE.replace(CURSOR, "sav")
        val items = service.complete(sourceFile(), source, SCOPE.indexOf(CURSOR) + "sav".length)

        assertTrue(
            "expected the method parameter in scope, got ${items.map { it.label }.take(20)}",
            "savedInstanceState" in items.map { it.label },
        )
    }

    @Test
    fun diagnostics_carry_a_severity_a_message_and_a_project_relative_place() = runTest {
        val file = sourceFile("Broken.java")
        val diagnostics = service.diagnostics(file, BROKEN)

        val errors = diagnostics.errors
        assertTrue("expected an error for an undefined symbol", errors.isNotEmpty())

        val located = errors.first { it.hasLocation }
        assertEquals(DiagnosticSeverity.ERROR, located.severity)
        assertEquals(
            "the diagnostic should point inside the project, not at a cache path",
            File("src/main/java/com/example/Broken.java"),
            located.file,
        )
        assertTrue("line should be 1-based and real, was ${located.line}", located.line > 0)
        Log.i(TAG, "diagnostic: ${located.describe()}")
    }

    @Test
    fun a_clean_file_reports_no_errors() = runTest {
        val diagnostics = service.diagnostics(sourceFile(), ACTIVITY.replace(CURSOR, "finish();"))
        assertTrue("unexpected errors: ${diagnostics.map { it.describe() }}",
            diagnostics.errors.isEmpty())
    }

    /**
     * A reused context must not remember the last request's classes.
     *
     * This is the hazard the pool introduces and the reason it is worth a test
     * rather than a comment. The symbol table survives between requests on
     * purpose -- that is what makes it fast -- so the question is whether the
     * *source* classes survive with it. If they do, renaming a type leaves the
     * old name resolvable, deleting a member leaves it completing, and the
     * editor tells the user their file is fine when it is not.
     *
     * Compiled twice through the same pooled context: first a class with a
     * `first()` method, then the same file with that method renamed. If the
     * second compilation still sees `first`, pooling is unsafe as configured.
     */
    @Test
    fun pooling_does_not_leak_symbols_between_requests() = runTest {
        val file = sourceFile("Renamed.java")

        val before = service.diagnostics(file, renamedSource(method = "first"))
        assertTrue("first version should be clean: ${before.map { it.describe() }}",
            before.errors.isEmpty())

        // Same file, method renamed, and the caller below still says first().
        val after = service.diagnostics(file, renamedSource(method = "second", caller = "first"))
        assertTrue(
            "the previous compilation's method was still resolvable -- the pooled " +
                "context is leaking source symbols between requests",
            after.errors.isNotEmpty(),
        )

        // And the other direction: the renamed method really is usable now.
        val renamed = service.diagnostics(file, renamedSource(method = "second", caller = "second"))
        assertTrue("renamed version should be clean: ${renamed.map { it.describe() }}",
            renamed.errors.isEmpty())
    }

    /**
     * Reuse must not change the answers, only the time they take.
     *
     * Runs the same completion repeatedly through one pooled context. A symbol
     * table that degraded across requests -- dropping platform classes it had
     * cleared, or accumulating duplicates -- would show up here as a proposal
     * list that changes shape after the first call.
     */
    @Test
    fun repeated_completions_return_a_stable_answer() = runTest {
        val text = ACTIVITY.replace(CURSOR, "")
        val offset = ACTIVITY.indexOf(CURSOR)

        val runs = (0 until 5).map { service.complete(sourceFile(), text, offset).map { it.label } }

        runs.forEachIndexed { index, labels ->
            assertTrue("request $index returned nothing", labels.isNotEmpty())
            assertTrue("request $index lost an inherited member", "getSystemService" in labels)
        }
        assertEquals(
            "the proposal set changed between identical requests",
            1,
            runs.map { it.toSet() }.distinct().size,
        )
    }

    /**
     * M3's acceptance criterion, as an assertion.
     *
     * The plan asks for completion under 200 ms and this is where that is
     * enforced, the way `:engine:fast` enforces its ten-second build: a
     * regression here fails the build rather than being noticed later.
     *
     * The median of the warm requests is the measure, not the best of them. One
     * fast request proves the path can be fast; the median is what the user
     * feels. The first request is excluded outright -- it pays for classloading
     * and the first read of `android.jar`, and an app pays that once.
     */
    @Test
    fun completion_answers_inside_the_two_hundred_millisecond_budget() = runTest {
        val text = ACTIVITY.replace(CURSOR, "")
        val offset = ACTIVITY.indexOf(CURSOR)

        val timings = (0 until 8).map {
            measureTimeMillis { service.complete(sourceFile(), text, offset) }
        }

        val warm = timings.drop(1).sorted()
        val median = warm[warm.size / 2]
        Log.i(TAG, "completion latency, requests 0..7: $timings (warm median $median ms)")

        assertTrue(
            "warm completion median was $median ms against a 200 ms budget; " +
                "all requests: $timings",
            median < 200,
        )
    }

    @Test
    fun jumping_to_a_definition_lands_on_the_name_in_the_same_file() = runTest {
        val text = LOCAL_DEFINITION.replace(CURSOR, "")
        val location = service.definition(
            sourceFile("Local.java"),
            text,
            LOCAL_DEFINITION.indexOf(CURSOR),
        )

        val found = requireNotNull(location) { "no definition found" }
        assertEquals(File("src/main/java/com/example/Local.java"), found.file)

        // The span must cover the identifier, not the whole declaration: the
        // point of the jump is to put the cursor on the name.
        val line = text.lines()[found.line - 1]
        assertEquals(
            "expected the span to cover the declared name, got '${
                line.substring(found.column - 1, found.endColumn - 1)
            }' on line: $line",
            "target",
            line.substring(found.column - 1, found.endColumn - 1),
        )
    }

    /**
     * The case that needs a source path, and the reason one is configured.
     *
     * A reference to a class the user wrote in another file has to resolve
     * before it can be jumped to. Without `SOURCE_PATH` javac never reads the
     * other file, the type is unresolved, and this returns null -- which is
     * indistinguishable from "nothing under the cursor" unless it is tested.
     */
    @Test
    fun jumping_to_a_definition_crosses_files() = runTest {
        File(projectRoot, "src/main/java/com/example/Helper.java").apply {
            parentFile?.mkdirs()
            writeText(HELPER)
        }

        val text = CROSS_FILE.replace(CURSOR, "")
        val location = service.definition(
            sourceFile("Caller.java"),
            text,
            CROSS_FILE.indexOf(CURSOR),
        )

        val found = requireNotNull(location) { "no definition found across files" }
        assertEquals(
            "should have jumped into the other file",
            File("src/main/java/com/example/Helper.java"),
            found.file,
        )
        val line = HELPER.lines()[found.line - 1]
        assertEquals(
            "expected to land on the method name, got line: $line",
            "help",
            line.substring(found.column - 1, found.endColumn - 1),
        )
    }

    @Test
    fun a_definition_inside_the_platform_has_nowhere_to_go() = runTest {
        // `Activity` is declared in android.jar: a symbol with no source. Null
        // is the honest answer; a location would send the user nowhere.
        val text = ACTIVITY.replace(CURSOR, "")
        val onActivity = text.indexOf("extends Activity") + "extends ".length + 2

        assertEquals(null, service.definition(sourceFile(), text, onActivity))
    }

    @Test
    fun the_signature_of_the_call_the_cursor_is_inside_is_reported() = runTest {
        val text = SIGNATURE.replace(CURSOR, "")
        val signature = service.signatureAt(sourceFile(), text, SIGNATURE.indexOf(CURSOR))

        val found = requireNotNull(signature) { "no signature inside a call" }
        assertTrue("expected the method name, got '$found'", found.startsWith("setTitle("))

        // An empty argument list matches no overload, so this is the fallback
        // path: the widest candidate, plus a count of the rest. Activity
        // declares setTitle(CharSequence) and setTitle(int).
        assertTrue("expected a parameter type, got '$found'", "CharSequence" in found || "int" in found)
        assertTrue("expected the other overload to be counted, got '$found'", "more" in found)

        // android.jar carries no parameter names, and arg0 is worse than
        // nothing -- it reads as the platform's own naming.
        assertTrue("synthetic parameter name leaked, got '$found'", "arg0" !in found)
        assertTrue(
            "types should be unqualified so the hint fits a phone, got '$found'",
            "java.lang" !in found,
        )
    }

    /**
     * Outside a call there is nothing to hint, and saying so matters.
     *
     * A signature that lingers after the caret leaves the parentheses is worse
     * than none: it describes a call the user is no longer writing.
     */
    @Test
    fun there_is_no_signature_outside_a_call() = runTest {
        val text = SIGNATURE.replace(CURSOR, "")
        // The class declaration line, well clear of any argument list.
        val outside = text.indexOf("public class") + "public cl".length

        assertEquals(null, service.signatureAt(sourceFile(), text, outside))
    }

    private fun renamedSource(method: String, caller: String = method): String = """
        package com.example;

        public class Renamed {
            int $method() {
                return 1;
            }

            int call() {
                return $caller();
            }
        }
    """.trimIndent()

    private companion object {
        const val TAG = "LspJava"
        const val CURSOR = "/*^*/"

        val ACTIVITY = """
            package com.example;

            import android.app.Activity;
            import android.os.Bundle;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    this./*^*/
                }
            }
        """.trimIndent()

        val SCOPE = """
            package com.example;

            import android.app.Activity;
            import android.os.Bundle;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    Object local = /*^*/;
                }
            }
        """.trimIndent()

        val LOCAL_DEFINITION = """
            package com.example;

            public class Local {
                int target() {
                    return 1;
                }

                int call() {
                    return /*^*/target();
                }
            }
        """.trimIndent()

        val HELPER = """
            package com.example;

            public class Helper {
                static int help() {
                    return 7;
                }
            }
        """.trimIndent()

        val CROSS_FILE = """
            package com.example;

            public class Caller {
                int call() {
                    return Helper./*^*/help();
                }
            }
        """.trimIndent()

        val SIGNATURE = """
            package com.example;

            import android.app.Activity;
            import android.os.Bundle;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    setTitle(/*^*/);
                }
            }
        """.trimIndent()

        val BROKEN = """
            package com.example;

            public class Broken {
                public int broken() {
                    return notAThing;
                }
            }
        """.trimIndent()
    }
}
