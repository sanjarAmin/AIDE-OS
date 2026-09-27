package com.osamu.aide.ai.core

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `OpenAiClient` against the real OpenAI API, from a device.
 *
 * **No request had ever reached OpenAI.** Every test of this client ran against
 * `ScriptedProviderApi`, which proves the JSON matches *our reading of the spec*
 * and nothing about whether OpenAI accepts it -- `ai/core/FINDINGS.md` listed
 * that as an open question once per provider, and this is the one that closes
 * it for the second of them.
 *
 * Modelled on [GeminiOnDeviceTest], one question per test so a failure names
 * the layer, and skipping rather than failing when the *account* cannot answer.
 *
 * **Needs a real key**, passed as an instrumentation argument so it is never
 * written into the repository:
 *
 *     ./gradlew :ai:core:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.openAiApiKey=sk-proj-...
 *
 * Every test skips without one.
 */
@RunWith(AndroidJUnit4::class)
class OpenAiOnDeviceTest {

    private lateinit var key: String

    @Before
    fun setUp() {
        val supplied = InstrumentationRegistry.getArguments().getString("openAiApiKey")
        assumeTrue("no openAiApiKey instrumentation argument; see the class comment", !supplied.isNullOrBlank())
        key = supplied!!
    }

    private fun client(model: String = AiProviderType.OPENAI.defaultModel) =
        OpenAiClient(apiKey = key, model = model)

    /**
     * An account that cannot serve requests is a skip, not a failure.
     *
     * A key with no credit answers `429 insufficient_quota` to everything,
     * whatever was asked, which would turn this suite into four failures that
     * say nothing about the code -- and make [every_offered_model_answers]
     * report the whole picker dead. Rate limiting is the same kind of fact
     * about the account rather than about the request.
     *
     * A 401 is deliberately **not** covered: a rejected key is a real answer to
     * "can this client authenticate", and hiding it would make a typo look like
     * a pass.
     */
    private fun skipIfTheAccountCannotAnswer(failure: Throwable) {
        val message = failure.message.orEmpty()
        assumeTrue(
            "the OpenAI account cannot serve requests: $message",
            !message.contains("(429)"),
        )
    }

    private suspend fun <T> live(block: suspend () -> T): T =
        try {
            block()
        } catch (failure: Exception) {
            skipIfTheAccountCannotAnswer(failure)
            throw failure
        }

    /**
     * Question 1: does OpenAI accept the request `OpenAiClient` builds?
     *
     * The whole shape at once -- system message, messages, `max_tokens` and
     * `temperature` -- because a 400 on any part of it is the failure this test
     * exists to catch, and the fake accepted all of them. `max_tokens` in
     * particular is the parameter OpenAI has been retiring in favour of
     * `max_completion_tokens`, and nothing in this repo would notice.
     */
    @Test
    fun a_request_completes_against_the_real_api() = runBlocking {
        val response = live {
            client().send(
                AiClientRequest(
                    systemInstruction = "You are terse. Answer with a single word.",
                    messages = listOf(AiMessage(AiRole.USER, "What colour is a clear midday sky?")),
                    maxTokens = 64L,
                ),
            )
        }

        Log.i(TAG, "openai text='${response.text}' finish=${response.finishReason}")
        assertTrue("the model returned nothing: $response", response.text.isNotBlank())
    }

    /**
     * Question 2: does the **default** model id actually exist?
     *
     * A retired or renamed id fails at no build and no startup check -- only as
     * an error on a user's first message. `ai/core/FINDINGS.md` §§14-15 record
     * that happening on the Gemini side, once from a rename and once from a
     * model that is listed and cannot be called.
     */
    @Test
    fun the_default_model_id_is_live() = runBlocking {
        val response = live {
            client(AiProviderType.OPENAI.defaultModel).send(
                AiClientRequest(
                    systemInstruction = "Reply with exactly: ok",
                    messages = listOf(AiMessage(AiRole.USER, "Reply with exactly: ok")),
                    maxTokens = 16L,
                ),
            )
        }
        assertTrue(
            "the default model ${AiProviderType.OPENAI.defaultModel} answered nothing",
            response.text.isNotBlank(),
        )
    }


    /** The smallest request that still exercises the whole encoder. */
    private fun probe() = AiClientRequest(
        systemInstruction = "Reply with exactly: ok",
        messages = listOf(AiMessage(AiRole.USER, "Reply with exactly: ok")),
        maxTokens = 16L,
    )

    /**
     * Question 3a: is every model the picker offers one the API knows?
     *
     * **This one does not need credit**, which is why it is separate from the
     * test below. A provider validates the model before it checks the quota, so
     * a 404 and a 429 tell different stories -- see [LiveApiOutcome]. A dead
     * entry in the picker costs a user an error with no way to know the model
     * is the problem, and this project has shipped one twice.
     *
     * Paced deliberately: a burst of probes earns empty 404s from the rate
     * limiter, which look exactly like a missing model until you read the body.
     */
    @Test
    fun every_offered_model_is_one_the_api_knows() = runBlocking {
        val unknown = mutableListOf<String>()
        for (model in AiProviderType.OPENAI.availableModels) {
            val outcome = runCatching {
                client(model).send(probe())
            }.fold(
                onSuccess = { LiveApiOutcome.Answered },
                onFailure = { LiveApiOutcome.of(it) },
            )
            Log.i(TAG, "model $model -> $outcome")
            if (outcome is LiveApiOutcome.Unknown) unknown += "$model (${outcome.detail.take(120)})"
            delay(PROBE_SPACING_MS)
        }

        assertEquals("models offered in the picker that the API does not know", emptyList<String>(), unknown)
    }

    /**
     * Question 3: every model the picker offers, so a user cannot choose a 404.
     *
     * One request each, deliberately tiny. The cost of a dead entry is a user
     * picking it and getting an error with no way to know the model is the
     * problem.
     */
    @Test
    fun every_offered_model_answers() = runBlocking {
        val dead = AiProviderType.OPENAI.availableModels.filter { model ->
            val text = runCatching {
                client(model).send(
                    AiClientRequest(
                        systemInstruction = "Reply with exactly: ok",
                        messages = listOf(AiMessage(AiRole.USER, "Reply with exactly: ok")),
                        maxTokens = 16L,
                    ),
                ).text
            }.getOrElse { failure ->
                skipIfTheAccountCannotAnswer(failure)
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
     * `AiSession`'s generic loop is built on function calls, so this is the
     * feature the provider interface exists for. **The id is the assertion**:
     * `ai/core/FINDINGS.md` §12 records that OpenAI rejects a `tool` message
     * whose `tool_call_id` it did not issue -- the opposite of Gemini, which
     * has no ids at all and matches a result to its call by name. A call that
     * came back without one would break the second turn, not this one.
     */
    @Test
    fun a_function_call_comes_back_with_an_id_its_result_can_quote() = runBlocking {
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

        val response = live {
            client().send(
                AiClientRequest(
                    systemInstruction = "Use the read_file tool when asked to read a file. Do not answer from memory.",
                    messages = listOf(AiMessage(AiRole.USER, "Read the file src/main/Main.kt and tell me what it does.")),
                    tools = listOf(tool),
                    maxTokens = 256L,
                ),
            )
        }

        val calls = response.parts.filterIsInstance<AiPart.FunctionCall>()
        Log.i(TAG, "openai calls=${calls.map { it.id to (it.name to it.args) }}")
        assertTrue("no function call came back: ${response.parts}", calls.isNotEmpty())
        assertEquals("read_file", calls.first().name)
        assertTrue(
            "the call carries no id, so its result cannot be sent back",
            calls.first().id.isNotBlank(),
        )
        assertTrue(
            "the path argument did not survive: ${calls.first().args}",
            calls.first().args["path"]?.contains("Main.kt") == true,
        )
    }

    private companion object {
        const val TAG = "AiSpike"

        /** Enough space between probes that the rate limiter stays quiet. */
        const val PROBE_SPACING_MS = 400L
    }
}
