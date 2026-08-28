package com.osamu.aide.ui.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.vcs.git.GitCredentialStore
import com.osamu.aide.vcs.git.GitIdentity
import com.osamu.aide.vcs.git.GitIdentityStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The git settings, driven through the composition and asserted against the
 * stores.
 *
 * Instrumented for the same reason [ApiKeySectionTest] is: the token half
 * writes to the Android Keystore, and a fake would prove only that a button
 * calls a method.
 */
class GitSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var identities: GitIdentityStore
    private lateinit var credentials: GitCredentialStore
    private lateinit var tokenFile: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        identities = GitIdentityStore(context)
        credentials = GitCredentialStore(context)
        tokenFile = File(context.dataDir, "shared_prefs/aide-git-credentials.xml")
        identities.clear()
        credentials.clear()
    }

    @After
    fun tearDown() {
        identities.clear()
        credentials.clear()
    }

    private fun section() = compose.setContent { GitSection(identities, credentials) }

    @Test
    fun a_typed_identity_is_saved() {
        section()

        compose.onNodeWithContentDescription("Git name").performTextInput("Ada Lovelace")
        compose.onNodeWithContentDescription("Git email").performTextInput("ada@example.com")
        compose.onNodeWithContentDescription("Save git identity").performClick()
        compose.waitForIdle()

        assertEquals(GitIdentity("Ada Lovelace", "ada@example.com"), identities.read())
    }

    /**
     * The button is the validation, not a dialog after the fact.
     *
     * An identity that cannot make a valid commit must never reach the store,
     * because by the time the user finds out, commits are signed with it and
     * fixing them means rewriting history.
     */
    @Test
    fun an_invalid_identity_cannot_be_saved() {
        section()

        compose.onNodeWithContentDescription("Git name").performTextInput("Ada")
        compose.onNodeWithContentDescription("Git email").performTextInput("not-an-address")

        compose.onNodeWithContentDescription("Save git identity").assertIsNotEnabled()
        assertNull("something was stored anyway", identities.read())
    }

    @Test
    fun the_save_button_is_off_until_something_changes() {
        identities.save(GitIdentity("Ada Lovelace", "ada@example.com"))
        section()

        compose.onNodeWithContentDescription("Save git identity").assertIsNotEnabled()

        compose.onNodeWithContentDescription("Git name").performTextInput("!")
        compose.onNodeWithContentDescription("Save git identity").assertIsEnabled()
    }

    /**
     * A stored identity is shown back, unlike a key or a token.
     *
     * It is printed into every commit and pushed to a public host, so hiding it
     * would imply a secrecy it does not have -- and an identity nobody can see
     * is one nobody notices is wrong.
     */
    @Test
    fun a_stored_identity_is_displayed() {
        identities.save(GitIdentity("Ada Lovelace", "ada@example.com"))
        section()

        compose.onNodeWithText("Ada Lovelace").assertExists()
        compose.onNodeWithText("ada@example.com").assertExists()
    }

    @Test
    fun a_typed_token_is_encrypted_and_the_field_is_cleared() {
        section()

        compose.onNodeWithContentDescription("Git access token").performTextInput(TOKEN)
        compose.onNodeWithContentDescription("Save git token").performClick()
        compose.waitForIdle()

        assertEquals(TOKEN, credentials.read("github.com"))
        assertFalse("the token is on disk in the clear", TOKEN in tokenFile.readText())

        // Cleared from the field too: it is the only place the plaintext still
        // exists, and a text field survives into the recents thumbnail.
        compose.onNodeWithText(TOKEN).assertDoesNotExist()
    }

    @Test
    fun a_saved_host_is_listed_and_can_be_removed() {
        section()

        compose.onNodeWithContentDescription("Git access token").performTextInput(TOKEN)
        compose.onNodeWithContentDescription("Save git token").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Remove token for github.com").performClick()
        compose.waitForIdle()

        assertNull(credentials.read("github.com"))
        assertTrue(credentials.hosts().isEmpty())
    }

    private companion object {
        const val TOKEN = "ghp_notARealTokenButItLooksLikeOne01234567890"
    }
}
