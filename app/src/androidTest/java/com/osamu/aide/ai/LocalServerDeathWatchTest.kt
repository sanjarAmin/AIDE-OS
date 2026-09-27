package com.osamu.aide.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.ai.core.AiProviderType
import com.osamu.aide.ai.core.ApiKeyStore
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.toolchain.manager.ToolchainManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **When `llama-server` dies on its own, does the app stop claiming it is there?**
 *
 * Found by measuring something else. After the §9 termination run the app was
 * still up, `llama-server` was gone from `ps`, and `local.baseUrl` still named
 * the port it had published -- so [ApiKeyStore.isReady] reported the local
 * provider ready and the next message would have gone to a closed socket.
 * `adoptOrForgetExistingServer` does not cover it: that probes at launch, and
 * nothing had relaunched.
 *
 * **A child dying mid-session is the ordinary case here, not the exotic one.**
 * The phone had 218 MB free with a 1.5B resident; lmkd killing the biggest
 * process that is not an app is exactly what it is for. There was no tombstone,
 * which rules out a native abort but says nothing about a SIGKILL.
 * `tools/localai/FINDINGS.md` §10.
 *
 * The process here is `sleep`, not a server: the signal the watch depends on is
 * the child's output stream closing, and every process closes it the same way.
 * Using a real server would need a gigabyte of model on the device and would
 * make this a skip on any device without one.
 */
@RunWith(AndroidJUnit4::class)
class LocalServerDeathWatchTest {

    private lateinit var keys: ApiKeyStore
    private lateinit var server: LocalModelServer
    private var savedAddress: String? = null

    @Before
    fun setUp() {
        // These are the app's own preferences -- an androidTest in :app runs in
        // the app's process -- so the address the user has published is saved
        // and put back. Losing it would strand a running server.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        keys = ApiKeyStore(context)
        savedAddress = keys.localBaseUrl()
        server = LocalModelServer(context, ToolchainManager(context, DefaultDispatcherProvider()), keys)
    }

    @After
    fun tearDown() {
        keys.saveLocalBaseUrl(savedAddress)
    }

    private fun shortLived(): Process =
        ProcessBuilder("/system/bin/sleep", "0").redirectErrorStream(true).start()

    @Test
    fun a_server_that_exits_has_its_address_withdrawn() {
        val address = "http://127.0.0.1:1"
        keys.saveLocalBaseUrl(address)

        val child = shortLived()
        server.adopt(child, address)
        child.waitFor()

        awaitWithdrawal(address)
        assertNull("the address outlived the process", keys.localBaseUrl())
        // The readiness signal is the address, so this is the assertion that
        // matters to a user: the provider must stop offering itself.
        assertEquals(false, keys.isReady(AiProviderType.LOCAL))
    }

    /**
     * **A restart must not be undone by the corpse of the last one.**
     *
     * The drain thread of a stopped server can reach its exit handler after a
     * new server has published a new port. Withdrawing by address alone would
     * clear the live one; the guard is process identity.
     */
    @Test
    fun a_stale_watch_does_not_withdraw_a_newer_address() {
        val first = shortLived()
        server.adopt(first, "http://127.0.0.1:1")

        val second = ProcessBuilder("/system/bin/sleep", "30").redirectErrorStream(true).start()
        val live = "http://127.0.0.1:2"
        server.adopt(second, live)
        keys.saveLocalBaseUrl(live)

        first.waitFor()
        Thread.sleep(SETTLE_MS)

        assertEquals("the old watch withdrew the new server's address", live, keys.localBaseUrl())
        second.destroyForcibly()
    }

    private fun awaitWithdrawal(address: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && keys.localBaseUrl() == address) {
            Thread.sleep(50)
        }
    }

    private companion object {
        /** Long enough for a drain thread to run its exit handler. */
        const val SETTLE_MS = 1_000L
    }
}
