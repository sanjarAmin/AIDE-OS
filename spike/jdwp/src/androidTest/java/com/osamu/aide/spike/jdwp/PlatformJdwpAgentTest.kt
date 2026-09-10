package com.osamu.aide.spike.jdwp

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Route 3, taken: the platform's *own* JDWP agent, attached to this process
 * with a socket transport instead of adbd's.
 *
 * `JdwpReachabilityTest` established that `attachJvmtiAgent` reaches `dlopen`.
 * The question this asks is whether the agent it should load is already on the
 * device: ART ships `libjdwp.so` -- the OpenJDK debugger agent, which is what
 * Android Studio talks to -- and `libdt_socket.so` beside it, and both are
 * plain JVMTI libraries. If the runtime will attach them for an ordinary
 * debuggable app, then the debuggee half of `:debugger` is **five lines of Java
 * in the build template and no native code of ours at all**, and the transport
 * question the spike opened is closed by the platform.
 *
 * What could stop it, each of which this reports rather than guesses at:
 *
 *  - `dlopen` refusing a library from the ART apex by bare name, because an
 *    app's namespace does not see it. Tried by name and then by absolute path.
 *  - `libjdwp` refusing a second load. `libadbconnection` loads it lazily when
 *    adbd asks, and on an emulator adbd is always there.
 *  - The agent attaching but `dt_socket` not being resolvable from inside it,
 *    which would surface as an attach error naming the transport.
 *
 * Success is a **handshake**, not an attach: the 14 bytes `JDWP-Handshake`
 * echoed back over the socket are the only thing that proves a JDWP server is
 * listening, and any client we write starts by sending them.
 */
@RunWith(AndroidJUnit4::class)
class PlatformJdwpAgentTest {

    @Test
    fun the_platforms_own_jdwp_agent_attaches_with_a_socket_transport() {
        val options = "transport=dt_socket,server=y,suspend=n,address=127.0.0.1:$PORT"
        val attempts = listOf("libjdwp.so", "/apex/com.android.art/lib64/libjdwp.so")
        val log = StringBuilder()

        var attached = false
        for (library in attempts) {
            val outcome = runCatching { Debug.attachJvmtiAgent(library, options, null) }
            log.append("attach($library) -> ")
                .append(outcome.fold({ "OK" }, { "${it::class.java.simpleName}: ${it.message}" }))
                .append('\n')
            if (outcome.isSuccess) {
                attached = true
                break
            }
        }

        if (attached) {
            // The agent starts its listener on its own thread; give it a moment
            // rather than racing it.
            val handshake = (1..20).asSequence()
                .map { runCatching { handshake() } }
                .firstOrNull { it.isSuccess || it.exceptionOrNull() !is java.net.ConnectException }
                ?: runCatching { handshake() }
            log.append("handshake on 127.0.0.1:$PORT -> ")
                .append(handshake.fold({ it }, { "${it::class.java.simpleName}: ${it.message}" }))
                .append('\n')
        }

        log.append("apex libs present: jdwp=${File("/apex/com.android.art/lib64/libjdwp.so").exists()}, ")
            .append("dt_socket=${File("/apex/com.android.art/lib64/libdt_socket.so").exists()}")

        report("Route 3b -- platform libjdwp over dt_socket", log.toString())
    }

    private fun handshake(): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", PORT), 1_000)
            socket.soTimeout = 2_000
            socket.getOutputStream().apply { write(HANDSHAKE); flush() }
            val reply = ByteArray(HANDSHAKE.size)
            var read = 0
            while (read < reply.size) {
                val n = socket.getInputStream().read(reply, read, reply.size - read)
                if (n < 0) return "EOF after $read bytes"
                read += n
            }
            return if (reply.contentEquals(HANDSHAKE)) {
                "JDWP-Handshake echoed. A JDWP server is listening in this process."
            } else {
                "unexpected reply: ${String(reply, Charsets.US_ASCII)}"
            }
        }
    }

    private fun report(route: String, outcome: String) {
        android.util.Log.w(TAG, "$route ->\n$outcome")
        assertTrue("$route ->\n$outcome", outcome.isNotBlank())
    }

    private companion object {
        const val TAG = "JdwpSpike"
        const val PORT = 8700
        val HANDSHAKE = "JDWP-Handshake".toByteArray(Charsets.US_ASCII)
    }
}
