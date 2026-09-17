package com.osamu.aide.ai

import android.security.NetworkSecurityPolicy
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
 * **Answered, and then changed.** The platform default blocks loopback too:
 * this test first ran without a `network-security-config` and got
 * `IOException: Cleartext HTTP traffic to 127.0.0.1 not permitted`. The app now
 * ships one scoped to the loopback addresses, so cleartext to them is permitted
 * and to everything else is not — which is what the two tests below assert, in
 * that order.
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

        // **Both directions, because the point of the config is that it is
        // narrow.** `usesCleartextTraffic="true"` would satisfy the first two
        // assertions and break the third, and the third is the one protecting
        // the remote providers' API keys from travelling in plaintext.
        assertTrue("cleartext to 127.0.0.1 is not permitted", loopback)
        assertTrue("cleartext to localhost is not permitted", localhost)
        assertTrue(
            "cleartext is permitted to a public host, so the exemption is not " +
                "scoped -- an API key in a request header would go out in the clear",
            !public,
        )
    }

    /**
     * **The exemption works: a real server, a real request, this process.**
     *
     * Without `res/xml/network_security_config.xml` this failed with
     * `IOException: Cleartext HTTP traffic to 127.0.0.1 not permitted`, which
     * is the measurement that justified adding the file. It now has to pass,
     * because the local-model provider cannot reach a `llama-server` otherwise.
     *
     * The browser opening the Node template's `http://127.0.0.1:8080` was never
     * a counter-example, and is worth remembering when this next confuses
     * someone: cleartext policy is per app, and the browser ships its own.
     */
    @Test
    fun cleartext_to_this_apps_own_loopback_is_permitted() {
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

        assertTrue(
            "cleartext to 127.0.0.1 was refused, so the local-model provider " +
                "cannot reach its own server. Check that the manifest still " +
                "sets android:networkSecurityConfig and that the file still " +
                "lists this address: ${outcome.exceptionOrNull()}",
            outcome.isSuccess,
        )
        assertEquals("200 ok", outcome.getOrNull())
    }

    private companion object {
        const val TAG = "LoopbackCleartextTest"
    }
}
