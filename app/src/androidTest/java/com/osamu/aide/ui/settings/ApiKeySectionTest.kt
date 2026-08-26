package com.osamu.aide.ui.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.ai.core.ApiKeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The key entry, driven through the composition and asserted against the store.
 *
 * Instrumented rather than a unit test because the store it writes to is the
 * Android Keystore: a fake would prove the button calls a method, and the thing
 * worth knowing is that a key typed into this field survives as a real
 * encrypted entry on a real device.
 */
class ApiKeySectionTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var keys: ApiKeyStore

    @Before
    fun setUp() {
        keys = ApiKeyStore(InstrumentationRegistry.getInstrumentation().targetContext)
        keys.clear()
    }

    @After
    fun tearDown() = keys.clear()

    @Test
    fun a_typed_key_is_saved_to_the_keystore() {
        compose.setContent { ApiKeySection(keys) }

        compose.onNodeWithContentDescription("API key").performTextInput("sk-ant-typed")
        compose.onNodeWithText("Save key").performClick()
        compose.waitForIdle()

        assertTrue(keys.hasKey())
        assertEquals("sk-ant-typed", keys.read())
    }

    /**
     * Whitespace is trimmed on the way in.
     *
     * A key pasted from a browser arrives with a trailing newline often enough
     * that "invalid x-api-key" on a key the user can see is correct is a real
     * support question.
     */
    @Test
    fun surrounding_whitespace_is_trimmed() {
        compose.setContent { ApiKeySection(keys) }

        compose.onNodeWithContentDescription("API key").performTextInput("  sk-ant-padded\n")
        compose.onNodeWithText("Save key").performClick()
        compose.waitForIdle()

        assertEquals("sk-ant-padded", keys.read())
    }

    @Test
    fun saving_is_refused_until_something_is_typed() {
        compose.setContent { ApiKeySection(keys) }

        compose.onNodeWithText("Save key").assertIsNotEnabled()
        assertFalse(keys.hasKey())
    }

    /** The stored key is acknowledged but never rendered back. */
    @Test
    fun an_existing_key_is_reported_without_being_shown() {
        keys.save("sk-ant-secret")
        compose.setContent { ApiKeySection(keys) }

        compose.onNodeWithText("A key is saved.").assertExists()
        compose.onNodeWithText("sk-ant-secret").assertDoesNotExist()
    }

    @Test
    fun removing_clears_the_stored_key() {
        keys.save("sk-ant-secret")
        compose.setContent { ApiKeySection(keys) }

        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()

        assertFalse(keys.hasKey())
        compose.onNodeWithText("A key is saved.").assertDoesNotExist()
    }
}
