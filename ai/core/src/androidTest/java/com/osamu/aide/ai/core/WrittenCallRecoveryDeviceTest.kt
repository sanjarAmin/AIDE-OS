package com.osamu.aide.ai.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * **Does the written-call recovery work on ART, with the exact text a phone
 * produced?**
 *
 * The JVM unit test of this passes and the app does not, which leaves exactly
 * one suspect: `org.json`. On the JVM the tests link the real org.json
 * artifact; on a device it is the platform's own implementation, and the two
 * differ in what they accept. A recovery decided by "does this parse" is
 * therefore not settled by a JVM test at all -- the same rule
 * `tools/clang/FINDINGS.md` §7 states for exec, arriving from a new direction.
 *
 * The content below is not a reconstruction. It was read back out of the
 * conversation the phone stored, so the whitespace and the brace that the fence
 * swallowed are exactly what the model emitted.
 */
class WrittenCallRecoveryDeviceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private val listFiles = AideTool(
        name = "list_files",
        description = "list files",
        risk = ToolRisk.READ_ONLY,
        parameters = mapOf("path" to AideTool.Parameter("string", "the path")),
        required = emptyList(),
    ) { ProjectFiles.Outcome.Refused("not called in this test") }

    /** Exactly what `files/chats/.../c-mudcb7m2-2de.json` holds. */
    private val emitted = "```json{\n \"name\": \"list_files\",\n \"arguments\": {\n \"path\": \"\"\n }\n}\n```"

    private fun enqueueStreamed(content: String) {
        val chunk = JSONObject().put(
            "choices",
            JSONArray().put(JSONObject().put("delta", JSONObject().put("content", content))),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: $chunk\n\ndata: [DONE]\n\n"),
        )
    }

    private fun client() = OpenAiClient(
        apiKey = null,
        customBaseUrl = server.url("/").toString(),
        model = "qwen2.5-coder-1.5b",
        provider = AiProviderType.LOCAL,
    )

    private fun request() = AiClientRequest(
        systemInstruction = "system",
        messages = listOf(AiMessage(AiRole.USER, "run ls -la for me")),
        tools = listOf(listFiles),
    )

    @Test
    fun the_call_the_phone_emitted_is_recovered_on_this_device() {
        enqueueStreamed(emitted)

        val response = runBlocking { client().send(request()) {} }

        val call = response.functionCalls.firstOrNull()
        assertNotNull(
            "nothing was recovered from the text the phone stored; response.text=${response.text}",
            call,
        )
        assertEquals("list_files", call!!.name)
        assertEquals(mapOf("path" to ""), call.args)
    }

    /**
     * The same content unstreamed, to say whether any difference is in the
     * streaming path or in the recovery itself.
     */
    @Test
    fun the_one_shot_path_recovers_it_too() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                JSONObject().put(
                    "choices",
                    JSONArray().put(
                        JSONObject()
                            .put("message", JSONObject().put("content", emitted))
                            .put("finish_reason", "stop"),
                    ),
                ).toString(),
            ),
        )

        val response = runBlocking { client().send(request()) }

        assertNotNull(
            "the one-shot path lost it too; response.text=${response.text}",
            response.functionCalls.firstOrNull(),
        )
    }

    /**
     * **The whole path the app takes**, not just the client.
     *
     * The client recovers it and the app does not, so the difference is
     * somewhere between them: the session builds the request with the real
     * toolset rather than one tool, runs the loop, and applies its own guards.
     * This drives that, with the same scripted content, so the answer is a
     * measurement rather than another guess.
     */
    @Test
    fun the_session_runs_the_recovered_call_with_the_real_toolset() {
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        val root = java.io.File(context.cacheDir, "recovery-${System.nanoTime()}").apply { mkdirs() }
        java.io.File(root, "Main.java").writeText("class Main {}")

        // Round one: the written-out call. Round two: a plain answer, so the
        // loop can finish the way it would in the app.
        enqueueStreamed(emitted)
        enqueueStreamed("There is one file, Main.java.")

        val dispatchers = object : com.osamu.aide.core.common.DispatcherProvider {
            override val main get() = kotlinx.coroutines.Dispatchers.Unconfined
            override val default get() = kotlinx.coroutines.Dispatchers.Unconfined
            override val io get() = kotlinx.coroutines.Dispatchers.Unconfined
            override val compiler get() = kotlinx.coroutines.Dispatchers.Unconfined
        }
        val session = AiSession(
            aiClient = client(),
            toolset = ProjectToolset(ProjectFiles(root)),
            approver = Approver { _, _ -> true },
            dispatchers = dispatchers,
        )

        // **With a listener, because that is what the app passes.** Without
        // one the session takes the one-shot path -- and a first attempt here
        // did exactly that, then threw `JSONException: Value data ... cannot be
        // converted to JSONObject` because it was handed an event stream. The
        // panel always streams, so a test of the panel's path must too.
        val reply = runBlocking {
            session.send(
                projectContext = "Project: demo",
                userText = "run ls -la for me",
                listener = object : TurnListener {},
            )
        }

        assertEquals(
            "the session ran no tool; its reply was \"${reply.text}\"",
            listOf("list_files"),
            reply.toolRuns.map { it.name },
        )
    }

    /**
     * What the platform's `org.json` actually does with the two candidates.
     *
     * If this is where the JVM and ART disagree, it is worth seeing the
     * disagreement stated rather than inferred from a tool that did not run.
     */
    @Test
    fun the_platform_json_parser_accepts_what_the_recovery_assumes() {
        val greedy = Regex("""\{[\s\S]*\}""").find(emitted)?.value
        assertNotNull("the greedy match found no object at all", greedy)

        val parsed = runCatching { JSONObject(greedy!!) }.getOrNull()
        assertNotNull("this device's org.json rejected the whole object", parsed)
        assertEquals("list_files", parsed!!.optString("name"))
        assertNotNull("arguments did not come back as an object", parsed.optJSONObject("arguments"))
    }
}
