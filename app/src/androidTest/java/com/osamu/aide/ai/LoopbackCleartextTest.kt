package com.osamu.aide.ai

import android.security.NetworkSecurityPolicy
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/**
 * **Can this app talk to a server on its own loopback over plain http?**
 *
 * The question the local-model feature turns on, and it was asserted rather
 * than measured. `tools/localai/FINDINGS.md` §5 says cleartext to loopback is
 * blocked and `Endpoint.kt` repeats it; neither cites a run. The observation
 * that prompted this test -- the phone's *browser* happily opening
 * `http://127.0.0.1:8080` served by the Node template -- does not settle it
 * either way, because the cleartext policy is **per app**: the browser ships
 * its own network-security-config and says nothing about ours.
 *
 * This app ships none, so the platform default applies at targetSdk 37, and
 * what that default does for `127.0.0.1` is the whole question.
 */
@RunWith(AndroidJUnit4::class)
class LoopbackCleartextTest {

    @Test
    fun the_platform_policy_for_loopback_and_for_a_public_host() {
        val policy = NetworkSecurityPolicy.getInstance()
        val loopback = policy.isCleartextTrafficPermitted("127.0.0.1")
        val localhost = policy.isCleartextTrafficPermitted("localhost")
        val public = policy.isCleartextTrafficPermitted("example.com")

        Log.i(TAG, "cleartext permitted: 127.0.0.1=$loopback localhost=$localhost example.com=$public")
        // Printed rather than demanded: this test exists to establish the
        // answer, and the end-to-end one below is the assertion that bites.
        assertTrue("the policy object answered", loopback || !loopback)
    }

    /**
     * **Measured: it is refused.** A real server, a real request, this process.
     *
     * ```
     * java.io.IOException: Cleartext HTTP traffic to 127.0.0.1 not permitted
     * ```
     *
     * Asserted in that direction on purpose. This is a characterisation test:
     * it pins the platform behaviour the local-model feature has to work
     * around, and it **will fail the day someone adds a loopback exemption to
     * the manifest** -- which is the signal wanted, not a nuisance. When that
     * happens, flip the assertion and update `tools/localai/FINDINGS.md` §5.
     *
     * The browser opening the Node template's `http://127.0.0.1:8080` is not a
     * counter-example: cleartext policy is per app, and the browser ships its
     * own config.
     */
    @Test
    fun cleartext_to_this_apps_own_loopback_is_refused() {
        val server = ServerSocket(0)
        val port = server.localPort
        thread(isDaemon = true) {
            runCatching {
                val client: Socket = server.accept()
                client.getInputStream().bufferedReader().readLine()
                client.getOutputStream().write(
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray(),
                )
                client.getOutputStream().flush()
                client.close()
            }
        }

        val outcome = runCatching {
            val connection = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            val code = connection.responseCode
            val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            "$code $body"
        }

        Log.i(TAG, "loopback http result: $outcome")
        server.close()

        val failure = outcome.exceptionOrNull()
        assertTrue(
            "cleartext to loopback now SUCCEEDS. If a network-security-config was " +
                "added deliberately, flip this assertion and update " +
                "tools/localai/FINDINGS.md §5 and Endpoint.parseEndpoint. Result: $outcome",
            failure != null,
        )
        assertTrue(
            "refused, but not for the cleartext policy -- so something else is " +
                "wrong with loopback here: $failure",
            failure!!.message?.contains("Cleartext") == true,
        )
    }

    private companion object {
        const val TAG = "LoopbackCleartextTest"
    }
}
