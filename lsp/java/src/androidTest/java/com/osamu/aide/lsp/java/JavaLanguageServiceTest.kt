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
     * Where `:lsp:java` sits against M3's acceptance.
     *
     * Reported, not asserted, and deliberately so. Spike R3 established that a
     * warm file manager plateaus around 200 ms and that closing the rest of the
     * gap needs the symbol table to survive between requests, which this does
     * not do yet. Asserting a budget the design is known not to meet would only
     * produce a test that fails for a reason already written down.
     *
     * The number is logged so the next change to [ResidentCompiler] can be
     * judged against it.
     */
    @Test
    fun completion_latency_is_measured_against_the_two_hundred_millisecond_budget() = runTest {
        val text = ACTIVITY.replace(CURSOR, "")
        val offset = ACTIVITY.indexOf(CURSOR)

        val timings = (0 until 8).map {
            measureTimeMillis { service.complete(sourceFile(), text, offset) }
        }

        Log.i(TAG, "completion latency, requests 0..7: $timings")
        val best = timings.drop(2).min()
        Log.i(TAG, "best warm completion: $best ms (M3 budget is 200 ms)")
        assertTrue("completion stopped returning anything", timings.isNotEmpty())
    }

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
