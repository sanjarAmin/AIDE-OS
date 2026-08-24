package com.osamu.aide.editor

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The editor's half of completion: that sora's request reaches a
 * [CompletionSource] and its answers come back as items the widget can show.
 *
 * Deliberately fed by a fake rather than by `:lsp:java`. What can break here is
 * the plumbing -- whether a source is consulted at all, whether the prefix
 * length is right, whether kinds survive the crossing -- and a real compiler in
 * the middle would only make a failure harder to place. Whether the proposals
 * are *correct* is `:lsp:java`'s question and is tested there.
 */
@RunWith(AndroidJUnit4::class)
class CompletionSourceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var languages: EditorLanguages

    /** Records what it was asked, and answers with a fixed list. */
    private class RecordingSource(private val answers: List<EditorCompletion>) : CompletionSource {
        var askedFile: File? = null
        var askedOffset: Int = -1
        var askedText: String? = null

        override fun completionsAt(file: File, text: String, offset: Int): List<EditorCompletion> {
            askedFile = file
            askedText = text
            askedOffset = offset
            return answers
        }
    }

    @Before
    fun setUp() {
        assumeTrue("tree-sitter did not load", TreeSitterRuntime.isAvailable)
        languages = EditorLanguages(context)
    }

    /**
     * Runs one completion request and returns what the widget would show.
     *
     * The draining is not ceremony. [CompletionPublisher] stages additions in a
     * private candidate list and only swaps them into the list the widget reads
     * when the runnable `updateList` posts to its handler actually runs. Reading
     * `items` straight after the call returns an empty list every time --
     * looking exactly like a source that answered nothing. So the publisher gets
     * a handler this test owns, and a barrier posted behind the swap.
     */
    private fun complete(
        text: String,
        offset: Int,
        source: CompletionSource,
    ): List<SimpleCompletionItem> {
        languages.completionSource = source
        val language = languages.languageFor(File("/project/src/main/java/Main.java"))

        val thread = HandlerThread("completion-test").apply { start() }
        try {
            val content = Content(text)
            val publisher = CompletionPublisher(Handler(thread.looper), {}, 1)
            language.requireAutoComplete(
                ContentReference(content),
                content.indexer.getCharPosition(offset),
                publisher,
                Bundle(),
            )

            val drained = CountDownLatch(1)
            handlerBarrier(thread, drained)
            assertTrue("publisher never drained", drained.await(5, TimeUnit.SECONDS))

            return publisher.items.filterIsInstance<SimpleCompletionItem>()
        } finally {
            thread.quitSafely()
        }
    }

    /** Posted behind the swap, so it runs after it: the queue is FIFO. */
    private fun handlerBarrier(thread: HandlerThread, latch: CountDownLatch) {
        Handler(thread.looper).post { latch.countDown() }
    }

    @Test
    fun proposals_from_the_source_become_items_the_widget_can_show() {
        val source = RecordingSource(
            listOf(
                EditorCompletion("getSystemService", EditorCompletionKind.METHOD, detail = "(String)"),
                EditorCompletion("finish", EditorCompletionKind.METHOD),
            ),
        )
        val text = "class Main { void f() { this. } }"

        val items = complete(text, text.indexOf("this.") + "this.".length, source)

        assertEquals(listOf("getSystemService", "finish"), items.map { it.label.toString() })
        assertEquals(CompletionItemKind.Method, items.first().kind)
        assertEquals("getSystemService", items.first().commitText)
    }

    @Test
    fun the_source_is_asked_about_the_file_and_offset_being_edited() {
        val source = RecordingSource(emptyList())
        val text = "class Main { void f() { this.get } }"
        val offset = text.indexOf("this.get") + "this.get".length

        complete(text, offset, source)

        assertEquals(File("/project/src/main/java/Main.java"), source.askedFile)
        assertEquals(offset, source.askedOffset)
        assertEquals(text, source.askedText)
    }

    /**
     * The prefix length decides how much the editor deletes before inserting.
     *
     * Getting it wrong is visible: too small leaves `getgetSystemService`, too
     * large eats the dot before it.
     */
    @Test
    fun a_typed_prefix_is_replaced_rather_than_appended_to() {
        val source = RecordingSource(
            listOf(EditorCompletion("getSystemService", EditorCompletionKind.METHOD)),
        )
        val text = "class Main { void f() { this.getSys } }"

        val items = complete(text, text.indexOf("this.getSys") + "this.getSys".length, source)

        // "getSys" is six characters, and all six should be replaced.
        assertEquals(6, items.single().prefixLength)
    }

    /**
     * Being superseded must read as cancellation, not failure.
     *
     * sora cancels a stale completion by **interrupting the worker thread**, and
     * it does that on almost every keystroke. A source that blocks -- any real
     * one will, this is a compiler -- surfaces that as an [InterruptedException],
     * and the first version of this wiring let it escape: sora logged
     * "Completion failed" and the user saw no list at all. Found by running the
     * app, not by any test that existed at the time, which is why it has one now.
     *
     * The interrupt flag has to survive too, or code further up the stack is
     * told the thread was never interrupted.
     */
    @Test
    fun an_interrupted_source_reports_cancellation_and_preserves_the_flag() {
        var flagInsideSource = false
        languages.completionSource = CompletionSource { _, _, _ ->
            flagInsideSource = Thread.currentThread().isInterrupted
            throw InterruptedException("superseded")
        }
        val language = languages.languageFor(File("/project/Main.java"))
        val content = Content("class Main { void f() { this.get } }")
        val publisher = CompletionPublisher(Handler(Looper.getMainLooper()), {}, 1)

        var cancelled = false
        var interruptSurvived = false
        val worker = Thread {
            try {
                language.requireAutoComplete(
                    ContentReference(content),
                    content.indexer.getCharPosition(32),
                    publisher,
                    Bundle(),
                )
            } catch (expected: CompletionCancelledException) {
                cancelled = true
            } catch (leaked: InterruptedException) {
                cancelled = false
            }
            interruptSurvived = Thread.currentThread().isInterrupted
        }
        worker.start()
        worker.join(10_000)

        assertFalse("the source should not see the flag already set", flagInsideSource)
        assertTrue(
            "an interrupted source must surface as CompletionCancelledException",
            cancelled,
        )
        assertTrue("the interrupt flag was swallowed", interruptSurvived)
    }

    /** And the language has to keep working afterwards. */
    @Test
    fun a_request_after_an_interrupted_one_still_produces_items() {
        var first = true
        val source = CompletionSource { _, _, _ ->
            if (first) {
                first = false
                throw InterruptedException("superseded")
            }
            listOf(EditorCompletion("getWindow", EditorCompletionKind.METHOD))
        }
        languages.completionSource = source

        val language = languages.languageFor(File("/project/Main.java"))
        val content = Content("class Main {}")
        val worker = Thread {
            runCatching {
                language.requireAutoComplete(
                    ContentReference(content),
                    content.indexer.getCharPosition(5),
                    CompletionPublisher(Handler(Looper.getMainLooper()), {}, 1),
                    Bundle(),
                )
            }
            // Leaving the flag set would make every later blocking call throw.
            Thread.interrupted()
        }
        worker.start()
        worker.join(10_000)

        val items = complete("class Main { void f() { this.get } }", 32, source)
        assertEquals(listOf("getWindow"), items.map { it.label.toString() })
    }

    @Test
    fun no_source_means_no_proposals_and_no_crash() {
        val items = complete("class Main {}", 5, CompletionSource { _, _, _ -> emptyList() })
        assertTrue("expected nothing to be published", items.isEmpty())

        // And with no source configured at all, which is the state before a
        // project is opened or when the platform is not installed.
        languages.completionSource = null
        val language = languages.languageFor(File("/project/Main.java"))
        val content = Content("class Main {}")
        val publisher = CompletionPublisher(Handler(Looper.getMainLooper()), {}, 1)
        language.requireAutoComplete(
            ContentReference(content),
            content.indexer.getCharPosition(5),
            publisher,
            Bundle(),
        )
        assertTrue("expected nothing to be published", publisher.items.isEmpty())
    }
}
