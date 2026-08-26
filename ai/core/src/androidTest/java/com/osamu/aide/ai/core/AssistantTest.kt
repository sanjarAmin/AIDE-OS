package com.osamu.aide.ai.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AssistantTest {

    private lateinit var keys: ApiKeyStore
    private lateinit var root: File
    private var api: ScriptedApi? = null

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        keys = ApiKeyStore(context)
        keys.clear()
        root = File(context.cacheDir, "assistant-${System.nanoTime()}").apply { mkdirs() }
        File(root, "src/Main.kt").apply { parentFile?.mkdirs() }.writeText("fun main() = Unit")
        File(root, "build/generated/Junk.kt").apply { parentFile?.mkdirs() }.writeText("x")
    }

    @After
    fun tearDown() {
        api?.stop()
        keys.clear()
        root.deleteRecursively()
    }

    private fun assistant() = Assistant(
        keys = keys,
        dispatchers = DefaultDispatcherProvider(),
        clientFactory = { ScriptedApi(listOf(ScriptedApi.text("hi"))).also { api = it }.client() },
    )

    /** No key is the state every user starts in, so it must not be an error. */
    @Test
    fun there_is_no_session_without_a_key() {
        assertNull(assistant().session(root, Approver { _, _ -> true }))
    }

    @Test
    fun a_stored_key_produces_a_working_session() = runTest {
        keys.save("sk-ant-test")
        val session = assistant().session(root, Approver { _, _ -> true })

        assertNotNull(session)
        assertEquals("hi", session!!.send(projectContext(ProjectFiles(root)), "hello").text)
    }

    /**
     * The key is read per session rather than cached.
     *
     * A user who fixes a wrong key in settings and gets a 401 anyway has no way
     * to tell that the app is still holding the old one.
     */
    @Test
    fun a_replaced_key_is_picked_up_by_the_next_session() {
        val seen = mutableListOf<String>()
        val assistant = Assistant(
            keys = keys,
            dispatchers = DefaultDispatcherProvider(),
            clientFactory = { key ->
                seen += key
                ScriptedApi(listOf(ScriptedApi.text("hi"))).also { api?.stop(); api = it }.client()
            },
        )

        keys.save("sk-ant-first")
        assistant.session(root, Approver { _, _ -> true })
        keys.save("sk-ant-second")
        assistant.session(root, Approver { _, _ -> true })

        assertEquals(listOf("sk-ant-first", "sk-ant-second"), seen)
    }

    /** The context is the project and only the project — see PromptAssembler. */
    @Test
    fun the_project_context_is_the_file_tree_without_build_output() {
        val context = projectContext(ProjectFiles(root))

        assertTrue("the source file is missing from the context:\n$context", "src/Main.kt" in context)
        assertTrue("build output leaked into the cached prefix:\n$context", "Junk.kt" !in context)
    }

    /** Twice in a row, because the prefix has to be byte-stable to cache. */
    @Test
    fun the_project_context_does_not_vary_between_calls() {
        assertEquals(projectContext(ProjectFiles(root)), projectContext(ProjectFiles(root)))
    }
}
