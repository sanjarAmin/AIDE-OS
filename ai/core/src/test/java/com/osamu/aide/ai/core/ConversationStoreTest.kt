package com.osamu.aide.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The file-level behaviour of the history, which the controller tests take for
 * granted: that a corrupt file does not take the list down, that two projects
 * with the same name stay separate, and that a round trip preserves what the
 * transcript needs to redraw itself.
 */
class ConversationStoreTest {

    private lateinit var root: File
    private lateinit var projectDir: File

    private fun tempDir(prefix: String) = File.createTempFile(prefix, "").let {
        it.delete(); it.mkdirs(); it
    }

    @Before
    fun setUp() {
        root = tempDir("store-root")
        projectDir = tempDir("project")
    }

    private fun store(project: File = projectDir) = ConversationStore(root, project)

    @Test
    fun `a round trip preserves every entry kind`() {
        val entries = listOf(
            ChatEntry.FromUser("what is in Main.kt?"),
            ChatEntry.Tool(
                name = "read_file",
                detail = "Main.kt",
                declined = false,
                failed = false,
                input = mapOf("path" to "Main.kt"),
                result = "fun main() {}",
                durationMs = 42,
            ),
            ChatEntry.FromAssistant("An empty main.", provider = AiProviderType.LOCAL, model = "q"),
        )

        store().save("c-1", entries)
        val loaded = store().load("c-1")

        assertEquals(3, loaded.size)
        val tool = loaded[1] as ChatEntry.Tool
        assertEquals("read_file", tool.name)
        assertEquals(mapOf("path" to "Main.kt"), tool.input)
        assertEquals("fun main() {}", tool.result)
        assertEquals(42, tool.durationMs)
        val assistant = loaded[2] as ChatEntry.FromAssistant
        // Attribution survives, so a chat that switched providers reads honestly.
        assertEquals(AiProviderType.LOCAL, assistant.provider)
        assertEquals("q", assistant.model)
    }

    @Test
    fun `a restored tool card with no result has none rather than an empty one`() {
        store().save("c-1", listOf(ChatEntry.Tool("list_files", "", declined = true, failed = false)))

        val tool = store().load("c-1").single() as ChatEntry.Tool

        assertNull("an absent result came back as an empty string", tool.result)
        assertTrue(tool.declined)
    }

    @Test
    fun `a reloaded entry keeps an id the list can key on`() {
        store().save("c-1", listOf(ChatEntry.FromUser("one"), ChatEntry.FromAssistant("two")))

        val ids = store().load("c-1").map { it.id }

        assertEquals("ids collided after a reload", ids.size, ids.distinct().size)
    }

    @Test
    fun `an unreadable file does not hide the others`() {
        store().save("c-good", listOf(ChatEntry.FromUser("fine")))
        // A crash mid-write, or a full disk.
        File(root, "chats").listFiles()!!.first()
            .let { dir -> File(dir, "c-broken.json").writeText("{\"id\": \"c-broken\", ") }

        val listed = store().list()

        assertEquals(1, listed.size)
        assertEquals("c-good", listed.single().id)
    }

    @Test
    fun `saving nothing removes the conversation`() {
        store().save("c-1", listOf(ChatEntry.FromUser("hi")))
        assertEquals(1, store().list().size)

        store().save("c-1", emptyList())

        assertTrue(store().list().isEmpty())
    }

    @Test
    fun `two projects with the same name keep separate histories`() {
        // The reason the key is the path and not the name: a `demo` here and a
        // `demo` there are different projects, and merging them is silent.
        val first = File(tempDir("parent-one"), "demo").apply { mkdirs() }
        val second = File(tempDir("parent-two"), "demo").apply { mkdirs() }
        store(first).save("c-1", listOf(ChatEntry.FromUser("about the first")))

        assertEquals(1, store(first).list().size)
        assertTrue(store(second).list().isEmpty())
    }

    @Test
    fun `newest first`() {
        store().save("c-old", listOf(ChatEntry.FromUser("older")))
        Thread.sleep(5)
        store().save("c-new", listOf(ChatEntry.FromUser("newer")))

        assertEquals(listOf("c-new", "c-old"), store().list().map { it.id })
    }

    @Test
    fun `a title comes from the first thing the user said`() {
        val title = ConversationStore.titleFrom(
            listOf(
                ChatEntry.FromAssistant("a greeting that arrived first somehow"),
                ChatEntry.FromUser("Why does the release build fail?"),
            ),
        )

        assertEquals("Why does the release build fail?", title)
    }

    @Test
    fun `an attached-file marker is not the title`() {
        // The composer prepends this; every chat started from an open file
        // would otherwise be called "[@Main.kt]".
        val title = ConversationStore.titleFrom(listOf(ChatEntry.FromUser("[@Main.kt]\nexplain this")))

        assertEquals("explain this", title)
    }

    @Test
    fun `a long first message is cut on a word boundary`() {
        val title = ConversationStore.titleFrom(
            listOf(ChatEntry.FromUser("Explain why the incremental build keeps invalidating the whole module")),
        )

        assertTrue("ran long: $title", title.length <= 50)
        assertTrue("cut mid-word: $title", title.endsWith("…"))
        assertTrue(title.startsWith("Explain why the incremental build keeps"))
    }

    @Test
    fun `a conversation with no user message still gets a name`() {
        assertEquals(
            ConversationStore.UNTITLED,
            ConversationStore.titleFrom(listOf(ChatEntry.FromAssistant("orphan"))),
        )
    }

    @Test
    fun `a rename survives a reload`() {
        store().save("c-1", listOf(ChatEntry.FromUser("original")))

        store().rename("c-1", "Release build")

        assertEquals("Release build", store().titleOf("c-1"))
        assertEquals("Release build", store().list().single().title)
    }

    @Test
    fun `an id with path characters cannot escape the directory`() {
        // Ids are generated, not typed -- but they land in a file name, and a
        // traversal here would write outside the store.
        store().save("../../escape", listOf(ChatEntry.FromUser("hi")))

        assertTrue(File(root, "chats").listFiles()!!.isNotEmpty())
        assertTrue(File(root.parentFile, "escape.json").exists().not())
    }
}
