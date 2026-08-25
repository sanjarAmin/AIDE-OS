package com.osamu.aide.spike.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.ThinkingConfigParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * Spike R5: the Anthropic SDK on ART, asked the questions M5 depends on.
 *
 * `docs/PLAN.md` settles this in a parenthesis -- "(OkHttp works fine on
 * Android)". OkHttp does. The SDK wrapped around it is the question, and this
 * project has been wrong about "pure JVM, therefore fine on ART" three times:
 * kotlinc needed seven startup fixes, ECJ a shim plus a stubs jar,
 * maven-resolver four workarounds that ruled out half its versions.
 *
 * Each test isolates one layer so a failure names the layer rather than the
 * spike: the client constructs, a request completes, streaming arrives
 * incrementally, and prompt caching -- which the plan calls "the cost lever" --
 * actually reads back a cache hit.
 *
 * **Needs a real key**, passed as an instrumentation argument so it is never
 * written to disk and never committed:
 *
 *     ./gradlew :spike:ai:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.anthropicApiKey=sk-ant-...
 *
 * Every test skips without one rather than failing: a checkout with no key is
 * the normal state, and a red suite would say the wrong thing about the code.
 */
@RunWith(AndroidJUnit4::class)
class AnthropicOnDeviceTest {

    private lateinit var client: AnthropicClient

    @Before
    fun setUp() {
        val key = InstrumentationRegistry.getArguments().getString("anthropicApiKey")
        assumeTrue("no anthropicApiKey instrumentation argument; see the class comment", !key.isNullOrBlank())
        client = AnthropicOkHttpClient.builder().apiKey(key!!).build()
    }

    /**
     * Question 1: does the SDK's object graph build on ART at all?
     *
     * Deliberately separate from making a request. A JVM SDK that cannot be
     * constructed fails in a completely different place from one that cannot
     * reach the network, and conflating them is how a spike ends up blaming
     * TLS for a Jackson problem.
     */
    @Test
    fun the_client_constructs_on_art() {
        Log.i(TAG, "client = ${client.javaClass.name}")
        assertTrue("no client", client.messages() != null)
    }

    /** Question 2: a real request completes and comes back with content. */
    @Test
    fun a_request_completes_against_the_real_api() {
        val message = client.messages().create(
            MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(64)
                .thinking(ThinkingConfigParam.ofAdaptive(ThinkingConfigAdaptive.builder().build()))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage("Reply with exactly the word: pong")
                .build(),
        )

        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        Log.i(TAG, "stop=${message.stopReason()} text='${text.trim()}' usage=${message.usage()}")

        // A refusal arrives as a successful HTTP response, so the stop reason
        // has to be checked before the content is read or an empty answer looks
        // like a broken request.
        assertTrue("model refused: ${message.stopReason()}", text.isNotBlank())
    }

    /**
     * Question 3: does streaming arrive incrementally, or in one lump?
     *
     * The plan wants token-by-token output in the chat panel. A stream that
     * only yields once at the end satisfies the API and not the feature, and
     * the two are indistinguishable from the final text alone -- so this counts
     * events and measures when the first one lands.
     */
    @Test
    fun streaming_arrives_in_pieces_rather_than_all_at_once() {
        var events = 0
        var firstEventMillis = -1L
        val builder = StringBuilder()

        val total = measureTimeMillis {
            client.messages().createStreaming(
                MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(256)
                    .thinking(ThinkingConfigParam.ofAdaptive(ThinkingConfigAdaptive.builder().build()))
                    .addUserMessage("Count slowly from one to twenty in words, one per line.")
                    .build(),
            ).use { response ->
                val startedAt = System.currentTimeMillis()
                response.stream().forEach { event ->
                    if (events == 0) firstEventMillis = System.currentTimeMillis() - startedAt
                    events++
                    event.contentBlockDelta().ifPresent { delta ->
                        delta.delta().text().ifPresent { builder.append(it.text()) }
                    }
                }
            }
        }

        Log.i(TAG, "$events events, first after $firstEventMillis ms, total $total ms")
        Log.i(TAG, "streamed ${builder.length} chars")

        assertTrue("no stream events at all", events > 0)
        assertTrue("nothing was streamed", builder.isNotEmpty())
        // Many events is the whole point; one would mean the SDK buffered.
        assertTrue("only $events event(s) -- this is not streaming", events > 5)
    }

    /**
     * Question 4: does prompt caching work, and can the hit be seen?
     *
     * The plan calls caching "the cost lever" and designs the whole prompt
     * layout around it. It is also the feature most likely to silently not
     * work: a cache miss costs money and returns a perfectly good answer, so
     * nothing surfaces unless `cache_read_input_tokens` is read back.
     *
     * The system prompt has to clear roughly 1024 tokens before anything is
     * cached at all, which is why this one is padded rather than short.
     */
    @Test
    fun prompt_caching_reports_a_hit_on_the_second_turn() {
        val system = TextBlockParam.builder()
            .text(cacheableSystemPrompt())
            .cacheControl(CacheControlEphemeral.builder().build())
            .build()

        fun ask(question: String) = client.messages().create(
            MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(32)
                .systemOfTextBlockParams(listOf(system))
                .addUserMessage(question)
                .build(),
        )

        // First turn writes the cache; second must read it. Different questions
        // on purpose -- the volatile part goes after the breakpoint, and if the
        // prefix is stable only identical requests hitting would prove nothing.
        val first = ask("Reply with the word: one")
        val second = ask("Reply with the word: two")

        val written = first.usage().cacheCreationInputTokens().orElse(0L)
        val read = second.usage().cacheReadInputTokens().orElse(0L)
        Log.i(TAG, "cache written=$written read=$read")

        assertTrue("nothing was cached on the first turn", written > 0)
        assertTrue("the second turn read nothing from cache", read > 0)
    }

    /** Long enough to clear the ~1024-token minimum cacheable prefix. */
    private fun cacheableSystemPrompt(): String = buildString {
        append("You are a terse assistant embedded in an on-device Android IDE.\n")
        repeat(120) { line ->
            append(
                "Rule ${line + 1}: answer in as few words as possible, never explain " +
                    "your reasoning unless asked, and prefer concrete file paths over " +
                    "descriptions of where something might live.\n",
            )
        }
    }

    private companion object {
        const val TAG = "AiSpike"

        /**
         * The plan's choice, and still the current identifier. Passed as a
         * string rather than an enum constant so a model rename is a one-line
         * change here rather than a compile error against the SDK's snapshot.
         */
        const val MODEL = "claude-opus-5"
    }
}
