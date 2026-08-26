package com.osamu.aide.ai.core

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The user's API key, at rest.
 *
 * Instrumented rather than unit-tested, and it has to be: the Android Keystore
 * is a platform service with no JVM equivalent, so a Robolectric or plain-JVM
 * test would either fail or — worse — pass against a fake that encrypts
 * nothing. The whole value of this class is a platform guarantee, and only a
 * device can check it.
 */
@RunWith(AndroidJUnit4::class)
class ApiKeyStoreTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: ApiKeyStore

    @Before
    fun setUp() {
        store = ApiKeyStore(context)
        store.clear()
        // See clear()'s doc: the endpoint outlives the key on purpose, so a
        // test has to reset it or it carries into the next one.
        store.saveBaseUrl(Endpoint.Default)
    }

    @After
    fun tearDown() {
        store.clear()
        store.saveBaseUrl(Endpoint.Default)
    }

    @Test
    fun a_saved_key_reads_back() {
        store.save(KEY)

        assertTrue("hasKey should be true after saving", store.hasKey())
        assertEquals(KEY, store.read())
    }

    @Test
    fun no_key_reads_as_null_rather_than_failing() {
        assertFalse(store.hasKey())
        assertNull("an absent key must not throw", store.read())
    }

    @Test
    fun saving_again_replaces_the_previous_key() {
        store.save(KEY)
        store.save(OTHER_KEY)

        assertEquals(OTHER_KEY, store.read())
    }

    @Test
    fun clearing_forgets_the_key() {
        store.save(KEY)
        store.clear()

        assertFalse(store.hasKey())
        assertNull(store.read())
    }

    /**
     * The assertion the whole class exists for.
     *
     * Everything above would pass just as happily against an implementation
     * that wrote the key out in plaintext — round-tripping proves storage, not
     * secrecy. This reads the preferences file off disk as raw bytes and looks
     * for the key in it, which is exactly what someone with a rooted device or
     * a stolen backup would do.
     */
    @Test
    fun the_key_is_not_recoverable_from_the_preferences_file() {
        store.save(KEY)

        // Located rather than assumed: the data directory differs between an
        // app and a self-instrumenting library test, and a hard-coded path that
        // silently missed the file would make this test pass for the wrong
        // reason -- the worst possible outcome for the one test that checks the
        // key is actually encrypted.
        val preferences = File(context.filesDir.parentFile, "shared_prefs/aide-ai.xml")
        assertTrue(
            "no preferences file at ${preferences.absolutePath}; this test cannot " +
                "verify encryption without reading what was written",
            preferences.isFile,
        )

        val onDisk = preferences.readText()
        assertFalse(
            "the API key is sitting in plaintext on disk:\n$onDisk",
            onDisk.contains(KEY),
        )
        // And not merely obscured: a base64 of the plaintext would also be
        // trivially recoverable, so check the obvious encoding too.
        assertFalse(
            "the API key is base64-encoded on disk rather than encrypted",
            onDisk.contains(android.util.Base64.encodeToString(KEY.toByteArray(), android.util.Base64.NO_WRAP)),
        )
    }

    /**
     * GCM is broken by IV reuse, so two saves must not share one.
     *
     * Not a hypothetical: reusing an IV under the same key leaks the XOR of the
     * plaintexts, and the two plaintexts here are two API keys. The cipher
     * chooses the IV, so this pins that the chosen one is stored per save
     * rather than a constant being reused.
     */
    @Test
    fun each_save_uses_a_fresh_initialisation_vector() {
        store.save(KEY)
        val first = ivOnDisk()

        store.save(KEY)
        val second = ivOnDisk()

        assertNotEquals("the same IV was reused across two saves", first, second)
    }

    // -- the endpoint, which lives beside the key but is not one -------------

    @Test
    fun no_endpoint_reads_as_null_meaning_the_default() {
        assertNull("null is what tells the client to use the SDK's own URL", store.baseUrl())
    }

    @Test
    fun a_saved_endpoint_reads_back() {
        store.saveBaseUrl(Endpoint.Custom("https://gateway.internal"))

        assertEquals("https://gateway.internal", store.baseUrl())
    }

    @Test
    fun saving_the_default_removes_a_custom_endpoint() {
        store.saveBaseUrl(Endpoint.Custom("https://gateway.internal"))
        store.saveBaseUrl(Endpoint.Default)

        assertNull(store.baseUrl())
    }

    /**
     * A rejected endpoint changes nothing.
     *
     * The UI disables Save while one is on screen, so this is the second line
     * rather than the first -- but "the validation had a hole" must not become
     * "the endpoint was silently blanked and every request went to Anthropic
     * with a key meant for somewhere else".
     */
    @Test
    fun a_rejected_endpoint_leaves_the_stored_one_alone() {
        store.saveBaseUrl(Endpoint.Custom("https://gateway.internal"))
        store.saveBaseUrl(Endpoint.Rejected("nope"))

        assertEquals("https://gateway.internal", store.baseUrl())
    }

    /**
     * Removing the key does not move the endpoint.
     *
     * It is a separate, visible control: silently resetting a setting the user
     * can read on screen, from a button labelled Remove next to the key, is a
     * worse surprise than leaving it where they put it.
     */
    @Test
    fun clearing_the_key_leaves_the_endpoint_in_place() {
        store.saveBaseUrl(Endpoint.Custom("https://gateway.internal"))
        store.save(KEY)

        store.clear()

        assertFalse(store.hasKey())
        assertEquals("https://gateway.internal", store.baseUrl())
    }

    /** Not a secret, and not pretending to be: it is on screen either way. */
    @Test
    fun the_endpoint_is_stored_in_the_clear_unlike_the_key() {
        store.saveBaseUrl(Endpoint.Custom("https://gateway.internal"))

        val preferences = File(context.filesDir.parentFile, "shared_prefs/aide-ai.xml")
        assertTrue(preferences.isFile)
        assertTrue(preferences.readText().contains("https://gateway.internal"))
    }

    private fun ivOnDisk(): String? = context
        .getSharedPreferences("aide-ai", Context.MODE_PRIVATE)
        .getString("apiKey.iv", null)

    private companion object {
        /** Shaped like a real key, and deliberately not one. */
        const val KEY = "sk-ant-api03-NOT-A-REAL-KEY-0123456789abcdefghijklmnop"
        const val OTHER_KEY = "sk-ant-api03-ALSO-NOT-REAL-zyxwvutsrqponmlkjihgfedcba"
    }
}
