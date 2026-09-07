package com.osamu.aide.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.ai.core.AiProviderType
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

    /**
     * clear() spares the endpoint on purpose, so it has to be reset by hand --
     * and it spares the active provider too, which is shared state one test can
     * hand to the next. These tests exercise the legacy Anthropic slot, so they
     * say so rather than inheriting whatever ran last.
     */
    private fun reset() {
        keys.clear()
        // **`clear()` spares every endpoint on purpose**, so each has to go by
        // hand. Missing the per-provider ones leaves a stored address that
        // prefills the field in the next test, and `performTextInput` appends:
        // the assertion then fails comparing one URL against two concatenated,
        // which reads as a storage bug rather than a dirty fixture.
        keys.saveBaseUrl(Endpoint.Default)
        keys.saveCustomBaseUrl(null)
        keys.saveOpenAiBaseUrl(null)
        keys.setActiveProvider(AiProviderType.ANTHROPIC)
    }

    /**
     * The section as the screen actually hosts it: inside something that
     * scrolls.
     *
     * Composed bare, the section is taller than the display as soon as a saved
     * key adds its Remove row, and everything past that point is clipped out of
     * reach -- a test failure that says the endpoint field does not exist when
     * what it means is that nothing could scroll to it. SettingsScreen puts this
     * in a LazyColumn, so the harness gives it the same.
     */
    private fun showSection() = compose.setContent {
        Column(Modifier.verticalScroll(rememberScrollState())) { ApiKeySection(keys) }
    }

    /** The endpoint is behind a disclosure unless one is already stored. */
    private fun revealEndpoint() {
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun a_typed_key_is_saved_to_the_keystore() {
        showSection()

        compose.onNodeWithContentDescription("API key").performTextInput("sk-ant-typed")
        compose.onNodeWithText("Save key").performScrollTo().performClick()
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
        showSection()

        compose.onNodeWithContentDescription("API key").performTextInput("  sk-ant-padded\n")
        compose.onNodeWithText("Save key").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("sk-ant-padded", keys.read())
    }

    @Test
    fun saving_is_refused_until_something_is_typed() {
        showSection()

        compose.onNodeWithText("Save key").performScrollTo().assertIsNotEnabled()
        assertFalse(keys.hasKey())
    }

    /** The stored key is acknowledged but never rendered back. */
    @Test
    fun an_existing_key_is_reported_without_being_shown() {
        keys.save("sk-ant-secret")
        showSection()

        compose.onNodeWithText("Anthropic key saved").assertExists()
        compose.onNodeWithText("sk-ant-secret").assertDoesNotExist()
    }

    /**
     * A key goes to the provider that is selected, and to no other.
     *
     * Every save used to write the legacy slot as well as the provider's own,
     * and the legacy slot is Anthropic's -- so saving a Gemini key made
     * Anthropic report a key it had never been given, and the assistant would
     * then send a Gemini key to Anthropic's API.
     */
    @Test
    fun a_key_saved_for_one_provider_does_not_appear_under_another() {
        keys.setActiveProvider(AiProviderType.GEMINI)
        showSection()

        compose.onNodeWithContentDescription("API key").performTextInput("AIzaTyped")
        compose.onNodeWithText("Save key").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("AIzaTyped", keys.geminiApiKey())
        assertFalse(
            "the Gemini key was also written to Anthropic's store",
            keys.hasProviderKey(AiProviderType.ANTHROPIC),
        )
    }

    // -- the endpoint -------------------------------------------------------

    @Test
    fun a_typed_endpoint_is_saved() {
        showSection()
        revealEndpoint()

        compose.onNodeWithContentDescription("API endpoint")
            .performScrollTo()
            .performTextInput("https://gateway.internal")
        compose.onNodeWithText("Save endpoint").performScrollTo().performClick()
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
        showSection()
        revealEndpoint()

        compose.onNodeWithContentDescription("API endpoint")
            .performScrollTo()
            .performTextInput("https://gateway.internal")
        compose.onNodeWithText("Save endpoint").performScrollTo().assertIsEnabled().performClick()
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
        showSection()
        revealEndpoint()

        compose.onNodeWithContentDescription("API endpoint")
            .performScrollTo()
            .performTextInput("https://gateway.internal/v1/")
        compose.onNodeWithText("Save endpoint").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("https://gateway.internal", keys.baseUrl())
        compose.onNodeWithText("https://gateway.internal").performScrollTo().assertExists()
    }

    /**
     * A stored endpoint opens the disclosure it lives in.
     *
     * A custom address is the kind of setting that is forgotten and then blamed
     * on the network, so it is never hidden once it is set.
     */
    @Test
    fun an_endpoint_that_is_already_set_is_shown_without_being_asked_for() {
        keys.saveBaseUrl(Endpoint.Custom("https://gateway.internal"))
        showSection()

        compose.onNodeWithText("https://gateway.internal").performScrollTo().assertExists()
    }

    /** A blank endpoint field is how the user goes back to the default. */
    @Test
    fun blanking_the_endpoint_restores_the_default() {
        keys.saveBaseUrl(Endpoint.Custom("https://gateway.internal"))
        showSection()

        compose.onNodeWithContentDescription("API endpoint").performScrollTo().performTextClearance()
        compose.onNodeWithText("Save endpoint").performScrollTo().performClick()
        compose.waitForIdle()

        assertNull(keys.baseUrl())
    }

    /**
     * A bad endpoint blocks its own Save, and nothing else.
     *
     * Without the message the failure surfaces on the first chat message as an
     * SDK transport error, which names neither the field nor the fix. And with
     * the single Save this screen used to have, a typo in an address the user
     * did not need also stopped them saving their key.
     */
    @Test
    fun an_endpoint_that_cannot_work_blocks_only_the_endpoint() {
        showSection()
        revealEndpoint()

        compose.onNodeWithContentDescription("API key").performTextInput("sk-ant-typed")
        compose.onNodeWithContentDescription("API endpoint")
            .performScrollTo()
            .performTextInput("http://gateway.internal")

        compose.onNodeWithText("Save endpoint").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("https is required.", substring = true).assertExists()
        compose.onNodeWithText("Save key").performScrollTo().assertIsEnabled()
    }

    /**
     * The endpoint is stored where the provider that will use it looks.
     *
     * There are three endpoint preferences and they are not interchangeable:
     * `Assistant` builds Anthropic from `baseUrl`, OpenAI from `openAiBaseUrl`
     * and Custom from `customBaseUrl`. This screen wrote `baseUrl` for all of
     * them, so the field was inert for three providers out of four -- including
     * Custom, whose only reason to exist is an address of your own. It looked
     * saved and every request still went to the default host.
     */
    @Test
    fun a_custom_providers_endpoint_is_stored_where_its_client_reads_it() {
        keys.setActiveProvider(AiProviderType.CUSTOM)
        showSection()

        compose.onNodeWithContentDescription("API endpoint")
            .performScrollTo()
            .performTextClearance()
        compose.onNodeWithContentDescription("API endpoint")
            .performTextInput("https://ollama.local")
        compose.onNodeWithText("Save endpoint").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("https://ollama.local", keys.customBaseUrl())
        assertNull("Anthropic's endpoint was written instead", keys.baseUrl())
    }

    /** A custom endpoint is where the key goes, so the screen says so. */
    @Test
    fun a_custom_endpoint_warns_that_the_key_is_sent_there() {
        showSection()
        revealEndpoint()

        compose.onNodeWithContentDescription("API endpoint")
            .performScrollTo()
            .performTextInput("https://gateway.internal")

        compose.onNodeWithText("Your key will be sent to this address.").assertExists()
    }

    // -- removal ------------------------------------------------------------

    @Test
    fun removing_clears_the_stored_key() {
        keys.save("sk-ant-secret")
        showSection()

        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()

        assertFalse(keys.hasKey())
        compose.onNodeWithText("Anthropic key saved").assertDoesNotExist()
    }

    /**
     * Remove takes one key, not all of them.
     *
     * It called `clear()`, which forgets all four providers *and* deletes the
     * shared Keystore alias, under a label that said "a key".
     */
    @Test
    fun removing_one_provider_key_leaves_the_others_readable() {
        keys.save("sk-ant-secret")
        keys.saveGeminiApiKey("AIzaKept")
        showSection()

        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()

        assertFalse(keys.hasProviderKey(AiProviderType.ANTHROPIC))
        assertEquals("AIzaKept", keys.geminiApiKey())
    }
}
