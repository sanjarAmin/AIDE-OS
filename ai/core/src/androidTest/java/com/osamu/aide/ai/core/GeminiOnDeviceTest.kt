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
 *     ./gradlew :ai:core:connectedDebugAndroidTest \
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
     * An account that cannot serve requests is a skip, not a failure.
     *
     * Google answers an out-of-credit project with 429 RESOURCE_EXHAUSTED on
     * every request, whatever was asked. That turns this suite into four
     * failures that say nothing about the code -- and it makes
     * [every_offered_model_answers] report the entire picker dead, which is a
     * false accusation against a model list that was answering an hour before.
     * The same status covers rate limiting, which is the same kind of fact
     * about the account rather than about the request.
     *
     * Anything else -- a 400 on our JSON, a 404 on a retired id -- stays a
     * failure, because those are the questions this suite exists to ask.
     */
    private fun skipIfTheAccountCannotAnswer(failure: Throwable) {
        assumeTrue(
            "the Gemini account cannot serve requests: ${failure.message}",
            LiveApiOutcome.of(failure) !is LiveApiOutcome.Unpaid,
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
     * Question 1: does Google accept the request `GeminiAiClient` builds?
     *
     * The whole shape at once -- system instruction, contents, generationConfig
     * and the thinking budget -- because a 400 on any part of it is the failure
     * this test exists to catch, and the fake accepted all of them.
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
        val response = live {
            client(AiProviderType.GEMINI.defaultModel).send(
                AiClientRequest(
                    systemInstruction = "Reply with exactly: ok",
                    messages = listOf(AiMessage(AiRole.USER, "Reply with exactly: ok")),
                    maxTokens = PROBE_TOKENS,
                ),
            )
        }
        assertTrue(
            "the default model ${AiProviderType.GEMINI.defaultModel} answered nothing",
            response.text.isNotBlank(),
        )
    }


    /** The smallest request that still exercises the whole encoder. */
    private fun probe() = AiClientRequest(
        systemInstruction = "Reply with exactly: ok",
        messages = listOf(AiMessage(AiRole.USER, "Reply with exactly: ok")),
        maxTokens = PROBE_TOKENS,
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
        for (model in AiProviderType.GEMINI.availableModels) {
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
     * The negative control for the test above, and for the whole technique.
     *
     * `every_offered_model_is_one_the_api_knows` can only fail if a dead model
     * is really distinguishable from an unpayable one, and on a credit-less
     * account every *live* model reports the same 429 -- so nothing in that
     * test would notice if the distinction stopped working. `gemini-2.5-pro` is
     * the known-retired id from `ai/core/FINDINGS.md` §15: it must classify as
     * [LiveApiOutcome.Unknown] while the picker's own models classify as
     * [LiveApiOutcome.Unpaid].
     *
     * If Google ever answers a retired model with something other than a 404
     * naming it, this fails and the oracle needs rewriting -- which is the
     * point of having it.
     */
    @Test
    fun a_retired_model_is_told_apart_from_an_unpayable_one() = runBlocking {
        fun outcome(model: String) = runCatching { runBlocking { client(model).send(probe()) } }
            .fold(onSuccess = { LiveApiOutcome.Answered }, onFailure = { LiveApiOutcome.of(it) })

        val retired = outcome("gemini-2.5-pro")
        // The rate limiter's empty 404 is neither answer; it says nothing about
        // the oracle, so it is a skip rather than a failure of it.
        assumeTrue("the probe was throttled: $retired", retired !is LiveApiOutcome.Inconclusive)
        assertTrue(
            "a model FINDINGS section 15 records as retired came back as $retired",
            retired is LiveApiOutcome.Unknown,
        )

        // The other half this promised and never checked: a live model must
        // *not* classify as unknown, whether or not the account can pay for it.
        delay(PROBE_SPACING_MS)
        val live = outcome(AiProviderType.GEMINI.defaultModel)
        assumeTrue("the probe was throttled: $live", live !is LiveApiOutcome.Inconclusive)
        assertTrue("the default model was classified as $live", live is LiveApiOutcome.Answered || live is LiveApiOutcome.Unpaid)
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
        // **Dead means the API says so**, not "this request did not come back
        // with text". Back-to-back probes earn the rate limiter's empty 404s,
        // and those were counted as dead models -- the false accusation
        // [LiveApiOutcome.Inconclusive] exists to prevent. So each probe is
        // classified, spaced, and only an unknown model or a successful reply
        // with no text counts against the picker.
        val dead = mutableListOf<String>()
        for (model in AiProviderType.GEMINI.availableModels) {
            val result = runCatching { client(model).send(probe()) }
            result.exceptionOrNull()?.let { failure ->
                skipIfTheAccountCannotAnswer(failure)
                val outcome = LiveApiOutcome.of(failure)
                Log.w(TAG, "model $model -> $outcome")
                if (outcome is LiveApiOutcome.Unknown) dead += "$model (${outcome.detail.take(120)})"
            }
            result.getOrNull()?.let { response ->
                Log.i(TAG, "model $model -> '${response.text.take(40)}' finish=${response.finishReason}")
                if (response.text.isBlank()) dead += "$model (answered with no text, finish=${response.finishReason})"
            }
            delay(PROBE_SPACING_MS)
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

        /** Enough space between probes that the rate limiter stays quiet. */
        const val PROBE_SPACING_MS = 400L

        /**
         * Room to answer. Sixteen, as these used, is spent by a thinking model
         * before it writes a word, and an empty MAX_TOKENS reply read as a dead
         * model on an account that could pay for it.
         */
        const val PROBE_TOKENS = 256L
    }
}
