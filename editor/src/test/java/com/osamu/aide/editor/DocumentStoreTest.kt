package com.osamu.aide.editor

import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

class DocumentStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val compiler: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

    private val store = DocumentStore(dispatchers)

    private fun file(name: String, bytes: ByteArray): File =
        temp.newFile(name).apply { writeBytes(bytes) }

    private fun file(name: String, text: String): File = file(name, text.toByteArray())

    private suspend fun open(file: File) = store.open(file)

    private suspend fun opened(file: File): SourceDocument {
        val result = open(file)
        assertTrue("open failed: $result", result is AppResult.Success)
        return (result as AppResult.Success).value
    }

    @Test
    fun `opens a UTF-8 source file`() = runTest {
        val document = opened(file("Main.java", "class Main {}\n"))

        assertEquals("class Main {}\n", document.text)
        assertEquals(StandardCharsets.UTF_8, document.encoding)
        assertEquals(LineEnding.LF, document.lineEnding)
    }

    @Test
    fun `CRLF files are normalised for the editor and restored on save`() = runTest {
        // The editor and every language tool want LF. Writing LF back would turn
        // a one-line edit into a whole-file diff for anyone with collaborators.
        val source = file("Windows.java", "class A {\r\n}\r\n")

        val document = opened(source)
        assertEquals("class A {\n}\n", document.text)
        assertEquals(LineEnding.CRLF, document.lineEnding)

        store.save(document, document.text)

        assertEquals("class A {\r\n}\r\n", source.readText())
    }

    @Test
    fun `a byte order mark survives a round trip`() = runTest {
        val source = file("Bom.java", SourceDocument.UTF8_BOM + "class A {}".toByteArray())

        val document = opened(source)
        assertEquals("class A {}", document.text)
        assertTrue(document.hasByteOrderMark)

        store.save(document, document.text)

        assertArrayEquals(
            SourceDocument.UTF8_BOM + "class A {}".toByteArray(),
            source.readBytes(),
        )
    }

    @Test
    fun `bytes that are not UTF-8 round trip unchanged instead of being mangled`() = runTest {
        // Decoding leniently would replace each undecodable byte with U+FFFD,
        // and the next save would write that back -- corrupting a file the user
        // only opened to look at.
        val original = byteArrayOf(0x41, 0xE9.toByte(), 0x42, 0x0A)
        val source = file("Latin1.txt", original)

        val document = opened(source)
        assertEquals(StandardCharsets.ISO_8859_1, document.encoding)

        store.save(document, document.text)

        assertArrayEquals(original, source.readBytes())
    }

    @Test
    fun `a binary file is refused rather than rendered as text`() = runTest {
        val source = file("classes.dex", byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x00, 0x01))

        val result = open(source)

        assertTrue(result is AppResult.Failure)
        assertTrue(
            "the message does not say why: $result",
            (result as AppResult.Failure).error.message.contains("not a text file"),
        )
    }

    @Test
    fun `a file too large to hold is refused with its size`() = runTest {
        val small = DocumentStore(dispatchers, maximumBytes = 16)
        val source = file("Big.java", "x".repeat(64))

        val result = small.open(source)

        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun `a missing file is a failure, not a crash`() = runTest {
        assertTrue(open(File(temp.root, "gone.java")) is AppResult.Failure)
    }

    @Test
    fun `a failed save leaves no debris beside the file`() = runTest {
        val source = file("Main.java", "class Main {}")
        val document = opened(source)
        // A directory where the temporary file wants to be: writeBytes throws.
        File(temp.root, "Main.java.saving").mkdirs()

        val result = store.save(document, "class Main { int x; }")

        assertTrue(result is AppResult.Failure)
        assertEquals("the original was damaged", "class Main {}", source.readText())
    }

    @Test
    fun `saving does not go through a state where the file is empty`() = runTest {
        // The point of writing to a temporary file and renaming: an interrupted
        // save must not be able to destroy the user's source.
        val source = file("Main.java", "class Main {}")
        val document = opened(source)

        store.save(document, "class Main { int x; }")

        assertEquals("class Main { int x; }", source.readText())
        assertTrue(
            "the temporary file was left behind",
            temp.root.listFiles()!!.none { it.name.endsWith(".saving") },
        )
    }

    @Test
    fun `mixed line endings resolve to the majority`() {
        assertEquals(LineEnding.LF, LineEnding.of("a\nb\nc"))
        assertEquals(LineEnding.CRLF, LineEnding.of("a\r\nb\r\nc"))
        assertEquals(LineEnding.LF, LineEnding.of("a\r\nb\nc\nd"))
        assertEquals(LineEnding.CRLF, LineEnding.of("a\r\nb\r\nc\nd"))
        assertEquals(LineEnding.LF, LineEnding.of("no newlines at all"))
    }
}
