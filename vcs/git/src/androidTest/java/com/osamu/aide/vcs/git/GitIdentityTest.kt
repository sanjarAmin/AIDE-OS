package com.osamu.aide.vcs.git

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The committing identity: what is refused, and what survives a restart.
 *
 * Validation is deliberately narrow. `git` accepts any address without
 * whitespace, hosting providers differ on what they will match to an account,
 * and a stricter rule here would reject addresses that work while still
 * admitting ones that do not. So the tests pin the *unambiguous* mistakes, and
 * pin that ordinary unusual addresses are left alone.
 */
@RunWith(AndroidJUnit4::class)
class GitIdentityTest {

    private lateinit var store: GitIdentityStore

    @Before
    fun setUp() {
        store = GitIdentityStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.clear()
    }

    @After
    fun tearDown() = store.clear()

    @Test
    fun an_identity_round_trips() {
        assertNull("something was already stored", store.read())

        assertNull(store.save(GitIdentity("Ada Lovelace", "ada@example.com")))

        assertEquals(GitIdentity("Ada Lovelace", "ada@example.com"), store.read())
    }

    @Test
    fun surrounding_whitespace_is_trimmed() {
        store.save(GitIdentity("  Ada Lovelace  ", "  ada@example.com  "))

        assertEquals(GitIdentity("Ada Lovelace", "ada@example.com"), store.read())
    }

    @Test
    fun the_unambiguous_mistakes_are_refused() {
        assertNotNull("an empty name was allowed", GitIdentity("", "ada@example.com").validate())
        assertNotNull("a blank name was allowed", GitIdentity("   ", "ada@example.com").validate())
        assertNotNull("an empty email was allowed", GitIdentity("Ada", "").validate())
        assertNotNull("an address with no @ was allowed", GitIdentity("Ada", "ada").validate())
        assertNotNull(
            "an address with a space was allowed",
            GitIdentity("Ada", "ada @example.com").validate(),
        )
    }

    /**
     * git's commit object format delimits the address with angle brackets, so
     * these cannot survive a round trip through a commit.
     */
    @Test
    fun angle_brackets_are_refused_in_both_fields() {
        assertNotNull(GitIdentity("Ada <Lovelace>", "ada@example.com").validate())
        assertNotNull(GitIdentity("Ada", "<ada@example.com>").validate())
    }

    /** Unusual but legal addresses are not the validator's business. */
    @Test
    fun an_unusual_address_is_accepted() {
        assertNull(GitIdentity("Ada", "ada+aide@sub.example.co.uk").validate())
        assertNull(GitIdentity("Ada", "1234567+ada@users.noreply.github.com").validate())
    }

    /** Nothing invalid reaches disk, even if a caller skipped validation. */
    @Test
    fun saving_something_invalid_stores_nothing() {
        assertNotNull(store.save(GitIdentity("Ada", "not-an-address")))

        assertNull("an invalid identity was stored", store.read())
    }
}
