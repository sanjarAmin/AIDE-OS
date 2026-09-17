package com.osamu.aide.ai.core

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **Does a local model finish?** Measured against the real server, not the UI.
 *
 * `tools/localai/FINDINGS.md` §9 recorded the blocker this answers: asked a
 * bare `hello`, Qwen2.5-Coder 1.5B called `list_files` twelve times and never
 * replied. The fix is two-part -- a rebalanced system prompt in
 * `AiSession.sendGeneric` and a guard that refuses to re-run a call it has
 * already run -- and both need measuring on a phone, because a 1.5B's
 * behaviour is not something a scripted server can stand in for.
 *
 * **Driving the chat panel was tried first and abandoned.** Three readings in a
 * row were wrong for reasons that had nothing to do with the model: one dump
 * caught the workspace instead of the sheet and reported zero tool calls, a
 * swipe meant to scroll the history dismissed the sheet, and a tap missed the
 * Send button. This goes at `AiSession` directly, which is the thing under
 * test, and is repeatable.
 *
 * **The address comes in as an instrumentation argument**, not from
 * `ApiKeyStore`. This runs in `:ai:core`'s own test process, which has its own
 * preferences -- reading them found nothing and skipped all three cases while a
 * server was plainly running, which is the "a skip reports as OK" trap CLAUDE.md
 * warns about, arrived at from a new direction.
 *
 *     -Pandroid.testInstrumentationRunnerArguments.localBaseUrl=http://127.0.0.1:PORT
 *     -Pandroid.testInstrumentationRunnerArguments.localModel=qwen2.5-coder-1.5b
 *
 * Skips with a message naming the flag when it is absent, so a run without a
 * server says why rather than passing quietly.
 */
@RunWith(AndroidJUnit4::class)
class LocalModelTerminationTest {

    private lateinit var root: File
    private lateinit var toolset: ProjectToolset
    private lateinit var address: String
    private lateinit var model: String

    private val direct = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val io: CoroutineDispatcher get() = Dispatchers.IO
        override val compiler: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        address = arguments.getString("localBaseUrl").orEmpty()
        assumeTrue(
            "pass -Pandroid.testInstrumentationRunnerArguments.localBaseUrl=http://127.0.0.1:PORT " +
                "with a llama-server already running on that port",
            address.isNotBlank(),
        )
        model = arguments.getString("localModel") ?: AiProviderType.LOCAL.defaultModel

        // A project the size of the template the app creates, because prompt
        // length is the cost that dominates on a phone and a one-file fixture
        // would measure something the user never sees.
        root = File(context.cacheDir, "local-term-${System.nanoTime()}").apply { mkdirs() }
        File(root, "src/main/java/com/example/demo").mkdirs()
        File(root, "src/main/java/com/example/demo/MainActivity.java").writeText(
            """
            package com.example.demo;

            import android.app.Activity;
            import android.os.Bundle;
            import android.widget.TextView;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    TextView text = new TextView(this);
                    text.setText(R.string.greeting);
                    setContentView(text);
                }
            }
            """.trimIndent(),
        )
        File(root, "src/main/res/values").mkdirs()
        File(root, "src/main/res/values/strings.xml").writeText(
            "<resources><string name=\"greeting\">Hello from AIDE-OS</string></resources>",
        )
        toolset = ProjectToolset(ProjectFiles(root))
    }

    private fun session() = AiSession(
        aiClient = OpenAiClient(
            apiKey = null,
            customBaseUrl = address,
            model = model,
            provider = AiProviderType.LOCAL,
        ),
        toolset = toolset,
        approver = Approver { _, _ -> true },
        dispatchers = direct,
    )

    private val context = """
        Project: Demo (Java)
        Files:
          src/main/java/com/example/demo/MainActivity.java
          src/main/res/values/strings.xml
    """.trimIndent()

    /**
     * Runs one question and records what it cost.
     *
     * **Written to a file, not only to the log.** This phone returns nothing to
     * `adb logcat` for an app process -- every reading in this session that
     * looked like a log line actually came from test XML -- so a measurement
     * that exists only in `Log.i` is a measurement nobody can collect. The
     * report lands in the test's external files directory and is pulled with
     * `adb pull`; the log call stays for devices where it does work.
     */
    private fun measure(question: String): Reply {
        val started = System.currentTimeMillis()
        val reply = runBlocking { session().send(context, question) }
        val seconds = (System.currentTimeMillis() - started) / 1000.0

        val line = "$model | ${"%.1f".format(seconds)}s | " +
            "${reply.toolRuns.size} runs ${reply.toolRuns.map { it.name }} | " +
            "truncated=${reply.truncated} | $question -> ${reply.text.replace('\n', ' ').take(240)}"
        Log.i(TAG, line)
        // **Internal storage, read back with `run-as`.** The first attempt
        // wrote to `getExternalFilesDir`, which is null for this test package,
        // and the `runCatching` around it swallowed that silently -- so the run
        // passed and produced no measurements, which is the same "looks fine,
        // measured nothing" shape as a skip reporting OK.
        val out = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            REPORT,
        )
        out.appendText(line + "\n")
        return reply
    }

    /** A greeting needs no tools, and used to consume all twelve rounds. */
    @Test
    fun a_greeting_is_answered_without_tools() {
        val reply = measure("hello")

        assertFalse("the greeting hit the round limit: ${reply.toolRuns.map { it.name }}", reply.truncated)
        assertTrue("a greeting called tools: ${reply.toolRuns.map { it.name }}", reply.toolRuns.isEmpty())
        assertTrue("no answer came back", reply.text.isNotBlank())
    }

    /** And so does a question the context already answers. */
    @Test
    fun a_question_the_context_answers_needs_no_tools() {
        val reply = measure("what does this project do?")

        assertFalse("hit the round limit", reply.truncated)
        assertTrue("called tools for something already in the prompt", reply.toolRuns.isEmpty())
        assertTrue("no answer came back", reply.text.isNotBlank())
    }

    /**
     * **The control in the other direction, and the one that caught a bad fix.**
     *
     * The first version of the prompt listed only when *not* to call a tool,
     * and this model then refused outright -- "the code is not provided ... so
     * I cannot read the file" -- while holding a `read_file` tool. A refusal is
     * worse than a loop: it is wrong, fast, and sounds certain. So this asserts
     * the tool ran **and** that prose came back afterwards.
     */
    @Test
    fun reading_a_file_uses_one_tool_and_then_answers() {
        val reply = measure("read MainActivity.java and tell me what it displays")

        assertTrue(
            "no tool ran, so the model refused a job it can do",
            reply.toolRuns.any { it.name == "read_file" },
        )
        assertTrue(
            "ran ${reply.toolRuns.size} tools; the guard should keep a simple read to a few",
            reply.toolRuns.size <= 3,
        )
        assertFalse("hit the round limit", reply.truncated)
        assertTrue(
            "the tool ran but no answer followed it -- an empty reply reads as a hang",
            reply.text.isNotBlank(),
        )
    }

    private companion object {
        const val TAG = "LocalModelTermination"

        /**
         * Read with
         * `adb shell run-as com.osamu.aide.ai.core.test cat files/<this>`.
         * See [measure].
         */
        const val REPORT = "local-model-termination.txt"
    }
}
