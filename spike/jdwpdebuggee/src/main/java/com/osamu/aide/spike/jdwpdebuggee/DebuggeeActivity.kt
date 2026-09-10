package com.osamu.aide.spike.jdwpdebuggee

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.widget.TextView

/**
 * An app that opens a JDWP server on itself, the way an AIDE-OS debug build
 * would.
 *
 * **The whole of the debuggee side is [attachDebugger], and it is four lines.**
 * ART ships OpenJDK's `libjdwp.so` and `libdt_socket.so` in its apex, and
 * `Debug.attachJvmtiAgent` will load them into a debuggable process on request.
 * Nothing here is written by us and nothing is downloaded: the agent that
 * Android Studio talks to over adb is the same agent, told to listen on a
 * socket instead.
 *
 * [work] exists to be interrupted. A debugger is only proved by stopping
 * something and reading its state, so there is a loop with a local variable
 * that changes, in a method with a name a test can look up.
 */
class DebuggeeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "JDWP debuggee" })

        attachDebugger(intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT)

        // A daemon thread, so the loop below never keeps the app alive on its
        // own -- a debuggee that will not die is worse than one that will not
        // stop.
        Thread({ work() }, "debuggee-work").apply { isDaemon = true }.start()
    }

    /**
     * Starts the platform's JDWP agent listening on loopback.
     *
     * `server=y` makes it accept rather than dial, and `suspend=n` lets the app
     * finish starting -- `suspend=y` would block in `onCreate` and Android
     * would kill it for not drawing. A real debugger that needs to stop before
     * `main` has to solve that separately; stopping a *running* app is the
     * common case and the one this proves.
     */
    private fun attachDebugger(port: Int) {
        val options = "transport=dt_socket,server=y,suspend=n,address=127.0.0.1:$port"
        runCatching { Debug.attachJvmtiAgent("libjdwp.so", options, null) }
            .onSuccess { Log.w(TAG, "JDWP listening on 127.0.0.1:$port") }
            .onFailure { Log.w(TAG, "could not attach the JDWP agent: $it") }
    }

    /** Something to breakpoint, with a local worth reading when you do. */
    private fun work() {
        var counter = 0
        while (true) {
            counter = step(counter)
            Thread.sleep(50)
        }
    }

    /**
     * One iteration, on its own line, in its own method.
     *
     * A breakpoint is set on a (class, method, code index), so the thing being
     * stopped has to be findable by name. An inlined or synthetic frame is not,
     * and a lambda's name is not stable enough to write a test against.
     */
    private fun step(counter: Int): Int {
        val next = counter + 1
        Log.d(TAG, "step $next")
        return next
    }

    companion object {
        const val TAG = "JdwpDebuggee"
        const val EXTRA_PORT = "port"
        const val DEFAULT_PORT = 8700
    }
}
