package com.osamu.aide.ai.core

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `GeminiAiClient` against the real Google API, from a device.
 *
 * **This is the provider that matters most and was tested least.** Gemini is
 * the app's default, so it is the path every new user takes -- and every test
 * of it ran against `ScriptedProviderApi`, which proves the JSON matches *our
 * reading of the spec* and nothing about whether Google accepts it.
 * `ai/core/FINDINGS.md` listed that as the largest open question in the
 * provider work.
 *
 * Modelled on `:spike:ai`'s `AnthropicOnDeviceTest`, one question per test so a
 * failure names the layer. It lives here rather than beside that one because
 * `:spike:ai` is a *platform* spike -- does a vendor SDK survive ART -- and does
 * not depend on our code at all. The difference is that this one drives **our own client** rather
 * than a vendor SDK: the request shape being checked is the JSON in
 * `GeminiAiClient`, which is exactly the thing a fake could never validate.
 *
 * **Needs a real key**, passed as an instrumentation argument so it is never
 * written to disk and never committed:
 *
 *     ./gradlew :spike:ai:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.geminiApiKey=...
 *
 * Every test skips without one.
 */
@RunWith(AndroidJUnit4::class)
class GeminiOnDeviceTest {

    private lateinit var key: String

    @Before
    fun setUp() {
        val supplied = InstrumentationRegistry.getArguments().getString("geminiApiKey")
        assumeTrue("no geminiApiKey instrumentation argument; see the class comment", !supplied.isNullOrBlank())
        key = supplied!!
    }

    private fun client(model: String = AiProviderType.GEMINI.defaultModel) =
        GeminiAiClient(apiKey = key, model = model)

    /**
     * Question 1: does Google accept the request `GeminiAiClient` builds?
     *
     * The whole shape at once -- system instruction, contents, generationConfig
     * and the thinking budget -- because a 400 on any part of it is the failure
     * this test exists to catch, and the fake accepted all of them.
     */
    @Test
    fun a_request_completes_against_the_real_api() = runBlocking {
        val response = client().send(
            AiClientRequest(
                systemInstruction = "You are terse. Answer with a single word.",
                messages = listOf(AiMessage(AiRole.USER, "What colour is a clear midday sky?")),
                maxTokens = 64L,
            ),
        )

        Log.i(TAG, "gemini text='${response.text}' finish=${response.finishReason}")
        assertTrue("the model returned nothing: $response", response.text.isNotBlank())
    }

    /**
     * Question 2: does the **default** model id actually exist?
     *
     * A retired or renamed id fails at no build and no startup check -- only as
     * a 404 on a user's first message, which `ai/core/FINDINGS.md` §14 records
     * having already happened once. Asserting the default specifically, because
     * that is the one nobody chooses deliberately.
     */
    @Test
    fun the_default_model_id_is_live() = runBlocking {
        val response = client(AiProviderType.GEMINI.defaultModel).send(
            AiClientRequest(
                systemInstruction = "Reply with exactly: ok",
                messages = listOf(AiMessage(AiRole.USER, "Reply with exactly: ok")),
                maxTokens = 16L,
            ),
        )
        assertTrue(
            "the default model ${AiProviderType.GEMINI.defaultModel} answered nothing",
            response.text.isNotBlank(),
        )
    }

    /**
     * Question 3: every model the picker offers, so a user cannot choose a 404.
     *
     * One request each, deliberately tiny. The list is short and the cost of a
     * dead entry is a user picking it and getting an error with no way to know
     * the model is the problem.
     */
    @Test
    fun every_offered_model_answers() = runBlocking {
        val dead = AiProviderType.GEMINI.availableModels.filter { model ->
            val text = runCatching {
                client(model).send(
                    AiClientRequest(
                        systemInstruction = "Reply with exactly: ok",
                        messages = listOf(AiMessage(AiRole.USER, "Reply with exactly: ok")),
                        maxTokens = 16L,
                    ),
                ).text
            }.getOrElse { failure ->
                Log.w(TAG, "model $model failed: ${failure.message}")
                ""
            }
            Log.i(TAG, "model $model -> '${text.take(40)}'")
            text.isBlank()
        }

        assertEquals("models offered in the picker that do not answer", emptyList<String>(), dead)
    }

    /**
     * Question 4: does native function calling round-trip?
     *
     * The provider work's whole point is that `AiSession`'s generic loop can
     * drive Gemini, and that loop is built on function calls. Gemini has no
     * call ids and matches results by name -- a rule `ai/core/FINDINGS.md` §12
     * records as *not* portable from the Anthropic path -- so this asserts the
     * name comes back, not an id.
     */
    @Test
    fun a_function_call_comes_back_with_its_name_and_arguments() = runBlocking {
        val tool = AideTool(
            name = "read_file",
            description = "Read one file in the project.",
            risk = ToolRisk.READ_ONLY,
            parameters = mapOf(
                "path" to AideTool.Parameter("string", "File to read, relative to the project root."),
            ),
            required = listOf("path"),
            handler = { ProjectFiles.Outcome.Ok("never executed in this test") },
        )

        val response = client().send(
            AiClientRequest(
                systemInstruction = "Use the read_file tool when asked to read a file. Do not answer from memory.",
                messages = listOf(AiMessage(AiRole.USER, "Read the file src/main/Main.kt and tell me what it does.")),
                tools = listOf(tool),
                maxTokens = 256L,
            ),
        )

        val calls = response.parts.filterIsInstance<AiPart.FunctionCall>()
        Log.i(TAG, "gemini calls=${calls.map { it.name to it.args }}")
        assertTrue("no function call came back: ${response.parts}", calls.isNotEmpty())
        assertEquals("read_file", calls.first().name)
        assertTrue(
            "the path argument did not survive: ${calls.first().args}",
            calls.first().args["path"]?.contains("Main.kt") == true,
        )
    }

    private companion object {
        const val TAG = "AiSpike"
    }
}
