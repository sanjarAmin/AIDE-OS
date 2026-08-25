package com.osamu.aide.spike.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.ThinkingConfigParam
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * Spike R5, platform half: does the Anthropic SDK run on ART?
 *
 * This is the question that motivated the spike. `docs/PLAN.md` designs M5
 * around `com.anthropic:anthropic-java` and settles the platform in a
 * parenthesis -- "(OkHttp works fine on Android)" -- which is the same shape of
 * claim as "pure JVM, therefore fine on ART". That has been wrong three times
 * here: kotlinc needed seven startup fixes, ECJ a `javax.lang.model` shim plus
 * a stubs jar, and maven-resolver four workarounds that ruled out half its
 * released versions.
 *
 * **Answered against a local server, and that is not a compromise.** Jackson
 * does not care whether the JSON it reflects over arrived from Anthropic or
 * from 127.0.0.1; if the SDK cannot construct, serialise, deserialise or
 * stream here, it cannot do those things against the real API either. No key,
 * no billing, and it runs in CI.
 *
 * What it cannot settle -- whether the real API accepts this exact request
 * shape, and whether prompt caching reports a hit -- stays in
 * [AnthropicOnDeviceTest], skipped until someone supplies a key. A fake must
 * not be allowed to look like it answered those.
 */
@RunWith(AndroidJUnit4::class)
class AnthropicPlatformTest {

    private var fake: FakeAnthropic? = null

    @After
    fun tearDown() {
        fake?.stop()
    }

    private fun clientFor(server: FakeAnthropic) = AnthropicOkHttpClient.builder()
        .apiKey("sk-ant-not-a-real-key")
        .baseUrl(server.baseUrl)
        .build()

    /**
     * Question 1: does the SDK's object graph build on ART?
     *
     * Separate from making a request on purpose. A library that cannot be
     * constructed fails in a completely different place from one that cannot
     * reach the network, and conflating the two is how a spike ends up blaming
     * TLS for a reflection problem.
     */
    @Test
    fun the_client_constructs_on_art() {
        val server = FakeAnthropic().also { fake = it }
        val client = clientFor(server)

        Log.i(TAG, "client = ${client.javaClass.name}")
        assertTrue("no messages service", client.messages() != null)
    }

    /**
     * Question 2: a full request and response, serialised and parsed on device.
     *
     * This is the one that would catch Jackson failing to reflect under ART --
     * the most likely way a JVM SDK dies on Android, and one that no amount of
     * reading the source predicts.
     */
    @Test
    fun a_request_round_trips_through_the_sdk() {
        val server = FakeAnthropic().also { fake = it }
        val client = clientFor(server)

        val message = client.messages().create(
            MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(64)
                .thinking(ThinkingConfigParam.ofAdaptive(ThinkingConfigAdaptive.builder().build()))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage("ping")
                .build(),
        )

        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        Log.i(TAG, "parsed response: '$text', usage=${message.usage()}")

        assertEquals("pong", text)
        assertEquals(1, server.requests)
    }

    /**
     * The request the SDK actually put on the wire.
     *
     * Worth asserting because the plan's cost model depends on request shape.
     * `thinking` and `effort` are the two settings M5 varies per feature -- low
     * effort for inline completion, high for chat -- and a builder call that
     * silently did not reach the JSON would be invisible until a bill arrived.
     */
    @Test
    fun thinking_and_effort_reach_the_wire() {
        val server = FakeAnthropic().also { fake = it }
        val client = clientFor(server)

        client.messages().create(
            MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(64)
                .thinking(ThinkingConfigParam.ofAdaptive(ThinkingConfigAdaptive.builder().build()))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage("ping")
                .build(),
        )

        val body = server.lastRequestBody
        Log.i(TAG, "request body: $body")

        assertTrue("model missing from $body", body.contains("\"model\":\"$MODEL\""))
        assertTrue("adaptive thinking missing from $body", body.contains("\"adaptive\""))
        assertTrue("effort missing from $body", body.contains("\"effort\""))
    }

    /**
     * Question 3: does streaming arrive in pieces, or in one lump?
     *
     * The plan wants token-by-token output in the chat panel. A stream that
     * yields once at the end satisfies the API and not the feature, and the two
     * are indistinguishable from the final text -- so this counts events.
     */
    @Test
    fun streaming_arrives_in_pieces() {
        val chunks = listOf("one ", "two ", "three ", "four ", "five ", "six ", "seven")
        val server = FakeAnthropic(streamChunks = chunks).also { fake = it }
        val client = clientFor(server)

        var deltas = 0
        val text = StringBuilder()
        val millis = measureTimeMillis {
            client.messages().createStreaming(
                MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(256)
                    .addUserMessage("count")
                    .build(),
            ).use { response ->
                response.stream().forEach { event ->
                    event.contentBlockDelta().ifPresent { delta ->
                        delta.delta().text().ifPresent {
                            deltas++
                            text.append(it.text())
                        }
                    }
                }
            }
        }

        Log.i(TAG, "$deltas deltas in $millis ms -> '$text'")
        assertEquals("every chunk should arrive as its own delta", chunks.size, deltas)
        assertEquals(chunks.joinToString(""), text.toString())
    }

    private companion object {
        const val TAG = "AiSpike"

        /** The plan's choice, and current. A string, so a rename is one line. */
        const val MODEL = "claude-opus-5"
    }
}
