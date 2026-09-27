package com.osamu.aide.ai.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every OpenAI session case again, through the stream a chat turn really uses.
 *
 * The chat panel always passes a listener, so a real turn goes through
 * `stream: true` and the SSE parser -- on the phone's org.json, which reads a
 * JSON `null` as the text "null" where the JVM's does not. Without these the
 * device suite only ever drove the one-shot parser.
 */
class OpenAiStreamedSessionTest : OpenAiSessionTest() {
    override val streaming = true

    @Test
    fun the_turn_really_was_streamed() = runTest {
        session(listOf(text("streamed"))).ask("ctx", "hello")

        assertTrue("the request did not ask for a stream", "\"stream\":true" in api!!.body(0))
        assertEquals("the panel received no deltas", "streamed", streamed.toString())
    }
}

/** The same for Gemini, whose stream is a different route rather than a flag. */
class GeminiStreamedSessionTest : GeminiSessionTest() {
    override val streaming = true

    @Test
    fun the_turn_really_was_streamed() = runTest {
        session(listOf(text("streamed"))).ask("ctx", "hello")

        assertTrue("the request did not use the stream route", api!!.path(0).orEmpty().contains(":streamGenerateContent"))
        assertEquals("the panel received no deltas", "streamed", streamed.toString())
    }
}
