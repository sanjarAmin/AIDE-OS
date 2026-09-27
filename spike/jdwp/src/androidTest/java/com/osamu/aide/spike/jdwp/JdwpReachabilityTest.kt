package com.osamu.aide.spike.jdwp

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * What an ordinary app can reach of the debugging machinery, on a real device.
 *
 * **In the app's own process, deliberately.** `adb shell` runs as `shell`,
 * which holds privileges no app has, and `run-as` runs in `runas_app`, which
 * holds different ones again -- `tools/clang/FINDINGS.md` section 7 records
 * this project believing a hand probe through `run-as` over an instrumented
 * test, and being wrong. Only this answers the question `:debugger` depends on.
 *
 * Nothing here asserts a *desired* outcome. Each test records what the platform
 * actually does and says so in its failure message, because a spike that fails
 * when the platform says no is a spike that has to be edited before it can
 * report anything.
 */
@RunWith(AndroidJUnit4::class)
class JdwpReachabilityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Route 1: the abstract socket `adbd` and the runtime use.
     *
     * `/proc/net/unix` shows `@jdwp-control` listening, so the name is real and
     * bound. The question is whether `untrusted_app` may connect to it: an
     * abstract socket has no filesystem permissions, so SELinux is the whole of
     * the access control, and a denial arrives as a plain `ConnectException`
     * with nothing about policy in it.
     */
    @Test
    fun what_happens_when_an_app_connects_to_jdwp_control() {
        val outcome = runCatching { connectAbstract("jdwp-control") }
        report(
            "Route 1 -- @jdwp-control from an app",
            outcome.fold(
                onSuccess = { "CONNECTED. JDWP is reachable without root." },
                onFailure = { "refused: ${it::class.java.simpleName}: ${it.message}" },
            ),
        )
    }

    /**
     * Route 2: adbd's TCP port, which wireless debugging opens.
     *
     * Off by default -- `service.adb.tcp.port` is unset and nothing is
     * listening -- so a refusal here is expected and is not the finding. The
     * finding is whether an app may *reach* the port at all once it is open,
     * because that decides whether the debugger's prerequisite is a settings
     * toggle the user can perform or a root prompt they cannot.
     */
    @Test
    fun what_happens_when_an_app_connects_to_adbd_over_tcp() {
        val port = System.getProperty("service.adb.tcp.port")?.toIntOrNull() ?: DEFAULT_ADB_PORT
        val outcome = runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS) }
        }
        report(
            "Route 2 -- adbd on 127.0.0.1:$port",
            outcome.fold(
                onSuccess = { "CONNECTED. Wireless debugging is reachable in-process." },
                onFailure = { "refused: ${it::class.java.simpleName}: ${it.message}" },
            ),
        )
    }

    /**
     * Route 3: a JVMTI agent, in the process that asks for it.
     *
     * This is the one route that does not need a permission, because it is not
     * crossing a process boundary: `attachJvmtiAgent` loads an agent into the
     * *caller*. It cannot debug another app -- but AIDE-OS controls what it
     * builds, so a stub in the template could open a channel from inside the
     * debuggee. Worth knowing whether the platform still allows it at all, and
     * whether it needs the app to be debuggable.
     */
    @Test
    fun whether_an_app_can_attach_a_jvmti_agent_to_itself() {
        // A path that does not exist: the call is expected to fail either way,
        // and *how* it fails is the answer. A SecurityException means the
        // mechanism is closed; a FileNotFound-shaped failure means it is open
        // and only the agent is missing.
        val agent = File(context.cacheDir, "no-such-agent.so")
        val outcome = runCatching { Debug.attachJvmtiAgent(agent.absolutePath, null, null) }
        report(
            "Route 3 -- attachJvmtiAgent on self",
            outcome.fold(
                onSuccess = { "attached (unexpected: the agent does not exist)" },
                onFailure = { "${it::class.java.simpleName}: ${it.message}" },
            ),
        )
    }

    /** What the process can see of its own debug state, for context. */
    @Test
    fun what_the_process_knows_about_its_own_debuggability() {
        val flags = context.applicationInfo.flags
        val debuggable = flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        report(
            "Context",
            "this process debuggable=$debuggable, " +
                "isDebuggerConnected=${Debug.isDebuggerConnected()}, " +
                "jdwp sockets visible=${jdwpSocketLines()}",
        )
    }

    /**
     * Connects to an abstract unix socket by name.
     *
     * Android exposes no API for the abstract namespace, and `LocalSocket` is
     * the only way in from Java -- it prefixes the name with a NUL itself.
     */
    private fun connectAbstract(name: String) {
        val socket = android.net.LocalSocket()
        socket.use {
            it.connect(
                android.net.LocalSocketAddress(name, android.net.LocalSocketAddress.Namespace.ABSTRACT),
            )
        }
    }

    private fun jdwpSocketLines(): Int = runCatching {
        File("/proc/net/unix").readLines().count { it.contains("jdwp") }
    }.getOrDefault(-1)

    /**
     * Records an outcome without asserting it.
     *
     * A spike exists to find out, so a test that fails when the answer is "no"
     * would have to be edited before it could report anything -- and the report
     * is the deliverable. The assertion is only that the probe ran.
     */
    private fun report(route: String, outcome: String) {
        android.util.Log.w(TAG, "$route -> $outcome")
        assertTrue("$route -> $outcome", outcome.isNotBlank())
    }

    private companion object {
        const val TAG = "JdwpSpike"
        const val DEFAULT_ADB_PORT = 5555
        const val CONNECT_TIMEOUT_MS = 1_500
    }
}
