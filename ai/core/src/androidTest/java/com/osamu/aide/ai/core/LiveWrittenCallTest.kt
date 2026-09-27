package com.osamu.aide.ai.core

import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * **What the live model actually sends, when the app says it recovers nothing.**
 *
 * Every scripted test passes -- the client recovers the written call on this
 * device, and so does the session with the real toolset, using the exact text
 * read back out of the conversation the phone stored. The app still shows the
 * JSON. So the scripted text is not what the client receives, and the only way
 * to find out what is, is to ask the model.
 *
 * This runs the app's own path against the running server and writes down both
 * halves: what tools ran, and the reply. Read with
 * `adb shell run-as com.osamu.aide.ai.core.test cat files/live-written-call.txt`.
 *
 *     -e localBaseUrl http://127.0.0.1:PORT
 */
class LiveWrittenCallTest {

    private val unconfined = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val io: CoroutineDispatcher get() = Dispatchers.IO
        override val compiler: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

    @Test
    fun what_the_model_sends_for_a_shell_request() {
        val arguments = InstrumentationRegistry.getArguments()
        val address = arguments.getString("localBaseUrl").orEmpty()
        assumeTrue("pass -e localBaseUrl http://127.0.0.1:PORT", address.isNotBlank())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "live-${System.nanoTime()}").apply { mkdirs() }
        File(root, "Main.java").writeText("class Main {}")

        val client = OpenAiClient(
            apiKey = null,
            customBaseUrl = address,
            model = "qwen2.5-coder-1.5b",
            provider = AiProviderType.LOCAL,
        )
        val session = AiSession(
            aiClient = client,
            toolset = ProjectToolset(ProjectFiles(root)),
            approver = Approver { _, _ -> true },
            dispatchers = unconfined,
        )

        // Every delta, so the report holds the raw stream and not only the
        // conclusion -- the conclusion is what the app already showed.
        val streamed = StringBuilder()
        val reply = runBlocking {
            session.send(
                projectContext = "Project: demo\nFiles:\n  Main.java",
                userText = "run ls -la for me",
                listener = object : TurnListener {
                    override fun onTextDelta(delta: String) {
                        streamed.append(delta)
                    }
                },
            )
        }

        val report = buildString {
            appendLine("tools: ${reply.toolRuns.map { it.name }}")
            appendLine("truncated: ${reply.truncated}")
            appendLine("--- streamed ---")
            appendLine(streamed.toString())
            appendLine("--- reply.text ---")
            appendLine(reply.text)
        }
        File(context.filesDir, "live-written-call.txt").writeText(report)
        println(report)
    }
}
