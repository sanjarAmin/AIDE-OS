package com.osamu.aide.vcs.git

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The token store, on a device -- the only place the Android Keystore exists.
 *
 * The claim that matters is not "a token round-trips" but "what is on disk is
 * not the token", and that can only be checked by reading the preferences file
 * the way an attacker with the device would. A test that only round-trips
 * would pass on an implementation that stored plaintext.
 */
@RunWith(AndroidJUnit4::class)
class GitCredentialStoreTest {

    private lateinit var store: GitCredentialStore
    private lateinit var preferencesFile: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = GitCredentialStore(context).apply { clear() }
        preferencesFile = File(context.dataDir, "shared_prefs/aide-git-credentials.xml")
    }

    @After
    fun tearDown() = store.clear()

    @Test
    fun a_token_round_trips() {
        assertFalse(store.hasToken("github.com"))
        assertNull(store.read("github.com"))

        store.save("github.com", TOKEN)

        assertTrue(store.hasToken("github.com"))
        assertEquals(TOKEN, store.read("github.com"))
    }

    /** The whole point of the class. */
    @Test
    fun what_is_written_to_disk_is_not_the_token() {
        store.save("github.com", TOKEN)

        assertTrue("no preferences file was written", preferencesFile.isFile)
        val onDisk = preferencesFile.readText()
        assertFalse("the token is on disk in the clear", TOKEN in onDisk)
        assertTrue("nothing was stored for the host", "github.com" in onDisk)
    }

    /**
     * GCM is broken by IV reuse under one key: two ciphertexts sharing an IV
     * leak the plaintext difference. Saving the same token twice must not
     * produce the same bytes.
     */
    @Test
    fun each_write_uses_a_fresh_initialisation_vector() {
        store.save("github.com", TOKEN)
        val first = preferencesFile.readText()
        store.save("github.com", TOKEN)
        val second = preferencesFile.readText()

        assertFalse("the same IV was reused for two writes", first == second)
        assertEquals(TOKEN, store.read("github.com"))
    }

    /** Hosts are case-insensitive; a token saved once must be found either way. */
    @Test
    fun host_lookup_ignores_case() {
        store.save("GitHub.com", TOKEN)
        assertEquals(TOKEN, store.read("github.com"))
        assertTrue(store.hasToken("GITHUB.COM"))
    }

    @Test
    fun tokens_are_kept_per_host() {
        store.save("github.com", TOKEN)
        store.save("gitlab.com", OTHER)

        assertEquals(TOKEN, store.read("github.com"))
        assertEquals(OTHER, store.read("gitlab.com"))
        assertEquals(listOf("github.com", "gitlab.com"), store.hosts())
    }

    /**
     * Forgetting one host must not invalidate the others. The Keystore secret
     * is shared, so deleting it here would silently break every stored token --
     * which is not what removing one of them means.
     */
    @Test
    fun forgetting_one_host_leaves_the_others_readable() {
        store.save("github.com", TOKEN)
        store.save("gitlab.com", OTHER)

        store.forget("github.com")

        assertFalse(store.hasToken("github.com"))
        assertEquals("the other host's token stopped decrypting", OTHER, store.read("gitlab.com"))
    }

    @Test
    fun clearing_removes_everything() {
        store.save("github.com", TOKEN)
        store.save("gitlab.com", OTHER)

        store.clear()

        assertEquals(emptyList<String>(), store.hosts())
        assertNull(store.read("github.com"))
        assertNull(store.read("gitlab.com"))
    }

    @Test
    fun a_host_is_read_out_of_a_url() {
        assertEquals("github.com", GitCredentialStore.hostOf("https://github.com/o/r.git"))
        assertEquals("github.com", GitCredentialStore.hostOf("https://GitHub.com/o/r.git"))
        // A self-hosted instance on a non-default port is a different endpoint
        // with a different token.
        assertEquals("git.example.com:8443", GitCredentialStore.hostOf("https://git.example.com:8443/r"))
        assertNull("a file URL has no host to key a token on", GitCredentialStore.hostOf("file:///tmp/r"))
        assertNull(GitCredentialStore.hostOf("not a url at all"))
    }

    /**
     * JGit's transport exceptions quote the URL they were given, and
     * `https://token@host/repo.git` is a shape users paste. Every path from a
     * transport failure to a screen or a log goes through [redact].
     */
    @Test
    fun a_credential_in_a_url_is_redacted() {
        assertEquals(
            "cannot read https://***@github.com/o/r.git",
            GitCredentialStore.redact("cannot read https://$TOKEN@github.com/o/r.git"),
        )
        assertEquals(
            "https://***@github.com/o/r.git",
            GitCredentialStore.redact("https://user:$TOKEN@github.com/o/r.git"),
        )
        // Untouched when there is nothing to hide -- a redactor that mangles
        // ordinary messages is one people route around.
        assertEquals(
            "cannot read https://github.com/o/r.git",
            GitCredentialStore.redact("cannot read https://github.com/o/r.git"),
        )
    }

    private companion object {
        const val TOKEN = "ghp_thisIsNotARealTokenButItLooksLikeOne0123456789"
        const val OTHER = "glpat-alsoNotRealButDifferent9876543210"
    }
}
