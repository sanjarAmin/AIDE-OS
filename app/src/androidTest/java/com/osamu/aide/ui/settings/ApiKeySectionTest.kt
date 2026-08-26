package com.osamu.aide.ui.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.ai.core.ApiKeyStore
import com.osamu.aide.ai.core.Endpoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        reset()
    }

    @After
    fun tearDown() = reset()

    /** clear() spares the endpoint on purpose, so it has to be reset by hand. */
    private fun reset() {
        keys.clear()
        keys.saveBaseUrl(Endpoint.Default)
    }

    @Test
    fun a_typed_key_is_saved_to_the_keystore() {
        compose.setContent { ApiKeySection(keys) }

        compose.onNodeWithContentDescription("API key").performTextInput("sk-ant-typed")
        compose.onNodeWithText("Save").performClick()
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
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertEquals("sk-ant-padded", keys.read())
    }

    @Test
    fun saving_is_refused_until_something_is_typed() {
        compose.setContent { ApiKeySection(keys) }

        compose.onNodeWithText("Save").assertIsNotEnabled()
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

    // -- the endpoint -------------------------------------------------------

    @Test
    fun a_typed_endpoint_is_saved() {
        compose.setContent { ApiKeySection(keys) }

        compose.onNodeWithContentDescription("API endpoint")
            .performTextInput("https://gateway.internal")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertEquals("https://gateway.internal", keys.baseUrl())
    }

    /**
     * The endpoint can be changed without retyping the key.
     *
     * The key field is empty whenever a key is already saved -- it is never
     * rendered back -- so a Save that required it would make the endpoint
     * unchangeable without rotating the credential.
     */
    @Test
    fun the_endpoint_can_be_saved_while_the_key_field_is_empty() {
        keys.save("sk-ant-existing")
        compose.setContent { ApiKeySection(keys) }

        compose.onNodeWithContentDescription("API endpoint")
            .performTextInput("https://gateway.internal")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals("https://gateway.internal", keys.baseUrl())
        assertEquals("the key was disturbed by an endpoint change", "sk-ant-existing", keys.read())
    }

    /**
     * What was corrected is shown, not just applied.
     *
     * `parseEndpoint` strips a trailing `/v1` because the SDK appends its own.
     * Doing that silently and leaving the typed text on screen would leave the
     * user believing something other than what is stored.
     */
    @Test
    fun the_field_is_rewritten_with_what_was_actually_stored() {
        compose.setContent { ApiKeySection(keys) }

        compose.onNodeWithContentDescription("API endpoint")
            .performTextInput("https://gateway.internal/v1/")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertEquals("https://gateway.internal", keys.baseUrl())
        compose.onNodeWithText("https://gateway.internal").assertExists()
    }

    /** A blank endpoint field is how the user goes back to Anthropic. */
    @Test
    fun blanking_the_endpoint_restores_the_default() {
        keys.saveBaseUrl(Endpoint.Custom("https://gateway.internal"))
        compose.setContent { ApiKeySection(keys) }

        compose.onNodeWithContentDescription("API endpoint").performTextClearance()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertNull(keys.baseUrl())
    }

    /**
     * A bad endpoint cannot be saved, and says why on the way.
     *
     * Without this the failure surfaces on the first chat message as an SDK
     * transport error, which names neither the field nor the fix.
     */
    @Test
    fun an_endpoint_that_cannot_work_blocks_saving_and_explains_itself() {
        compose.setContent { ApiKeySection(keys) }

        compose.onNodeWithContentDescription("API key").performTextInput("sk-ant-typed")
        compose.onNodeWithContentDescription("API endpoint")
            .performTextInput("http://gateway.internal")

        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("https is required.", substring = true).assertExists()
        assertFalse("a refused endpoint must not take the key with it", keys.hasKey())
    }

    /** A custom endpoint is where the key goes, so the screen says so. */
    @Test
    fun a_custom_endpoint_warns_that_the_key_is_sent_there() {
        compose.setContent { ApiKeySection(keys) }

        compose.onNodeWithContentDescription("API endpoint")
            .performTextInput("https://gateway.internal")

        compose.onNodeWithText("Your key will be sent to this address.").assertExists()
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
