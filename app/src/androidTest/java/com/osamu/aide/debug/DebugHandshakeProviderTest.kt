package com.osamu.aide.debug

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.engine.api.DebuggerRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The IDE's answer to a debug build asking, at startup, whether to wait.
 *
 * Called through the real `ContentResolver`, so the calling package is the
 * platform's and not a parameter: this test's process is the app's own, and
 * the provider sees it as `com.osamu.aide` -- which is exactly the property the
 * provider relies on to answer only about the caller.
 */
@RunWith(AndroidJUnit4::class)
class DebugHandshakeProviderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val self = context.packageName

    @After
    fun tearDown() {
        PendingDebuggees.forget(self)
        PendingDebuggees.forget(OTHER)
    }

    private fun ask(about: String) = context.contentResolver.call(
        Uri.parse("content://" + DebugHandshakeProvider.authority(self)),
        DebuggerRequest.HANDSHAKE_METHOD,
        about,
        null,
    )

    @Test
    fun an_expected_app_is_told_to_wait_and_where_the_ide_is() {
        PendingDebuggees.expect(self)
        val answer = ask(self)
        assertNotNull(answer)
        assertTrue("an expected app was not told to wait", answer!!.getBoolean(DebuggerRequest.HANDSHAKE_EXPECTED))
        assertNotNull(
            "the answer does not say which service to bind, so the IDE can be frozen behind the app",
            answer.getString(DebuggerRequest.HANDSHAKE_IDE_SERVICE),
        )
    }

    /**
     * The expectation is for one launch.
     *
     * An app that crashes in startup and is restarted by the system must not
     * be held a second time for a debugger attached to the process that died.
     */
    @Test
    fun the_expectation_is_consumed_by_the_first_ask() {
        PendingDebuggees.expect(self)
        assertTrue(ask(self)!!.getBoolean(DebuggerRequest.HANDSHAKE_EXPECTED))
        assertFalse(
            "a second launch was held too",
            ask(self)!!.getBoolean(DebuggerRequest.HANDSHAKE_EXPECTED),
        )
    }

    /** Nothing is waiting unless the IDE said so: a launcher tap starts at once. */
    @Test
    fun an_unexpected_app_is_not_held() {
        assertFalse(ask(self)!!.getBoolean(DebuggerRequest.HANDSHAKE_EXPECTED))
    }

    /**
     * It answers only about the caller.
     *
     * The provider is exported -- the asker is the user's app, in its own
     * package -- so any app can call it. Asking about another package gets
     * "no" even when that package is expected, and does not consume the
     * expectation the real app needs.
     */
    @Test
    fun asking_about_another_package_gets_no_and_leaves_its_expectation() {
        PendingDebuggees.expect(OTHER)
        assertFalse(ask(OTHER)!!.getBoolean(DebuggerRequest.HANDSHAKE_EXPECTED))
        assertEquals(
            "asking on another app's behalf used up its expectation",
            true,
            PendingDebuggees.claim(OTHER),
        )
    }

    @Test
    fun an_expectation_that_has_lapsed_holds_nothing() {
        PendingDebuggees.expect(self, withinMs = -1)
        assertFalse(ask(self)!!.getBoolean(DebuggerRequest.HANDSHAKE_EXPECTED))
    }

    private companion object {
        const val OTHER = "com.example.someone.else"
    }
}
