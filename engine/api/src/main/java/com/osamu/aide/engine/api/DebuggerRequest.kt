package com.osamu.aide.engine.api

/**
 * A request to build a debugger into the app.
 *
 * The build generates an agent -- a `ContentProvider`, so it runs before the
 * app's own code -- that attaches ART's `libjdwp.so` and listens on [port].
 * `debugger/FINDINGS.md` and `tools/jdwp/FINDINGS.md` have the account.
 *
 * **The constants are this type's, not the debugger's**, because they describe
 * what the build puts into an app: the class the agent is generated as and the
 * field that holds its startup. The debugger module speaks JDWP to anything
 * listening and is told these names rather than knowing them.
 */
data class DebuggerRequest(
    val port: Int,
    /**
     * The authority of the IDE's provider, which the agent asks at startup
     * whether a debugger is on its way. Null never waits.
     *
     * **Asked rather than always waiting, because a debug build outlives the
     * session.** The agent cannot tell a launch from the IDE's Debug button
     * from a tap on the launcher an hour later. Waiting unconditionally would
     * put a startup delay on every later launch; waiting never would let the
     * app run past a breakpoint in `onCreate` before the debugger has placed
     * it. The IDE knows which launch it started, so the agent asks it.
     */
    val handshakeAuthority: String? = null,
) {
    companion object {
        /** The generated agent, as a qualified class name. */
        const val AGENT_CLASS = "aide.debug.AideDebugAgent"

        /** The agent's static `boolean` the debugger sets to let startup continue. */
        const val RELEASE_FIELD = "released"

        /** The `ContentProvider.call` method the agent asks. */
        const val HANDSHAKE_METHOD = "isDebuggerExpected"

        /** The `boolean` in the answer's `Bundle`. */
        const val HANDSHAKE_EXPECTED = "expected"

        /**
         * A flattened `ComponentName` in the answer: the IDE's service the
         * agent binds to, whose package is also the IDE to bring forward.
         */
        const val HANDSHAKE_IDE_SERVICE = "ideService"


        /**
         * The agent's static `boolean` the debugger sets to have the app bring
         * the IDE forward.
         *
         * The app does it, not the IDE, because the app is the one on screen:
         * an app in the background may not start activities, and the IDE is in
         * the background at exactly the moment a breakpoint is hit.
         */
        const val COME_FORWARD_FIELD = "comeForward"

        /**
         * How long startup is held for a debugger that was expected.
         *
         * Bounded, so a debugger that crashed after being expected costs a
         * slow launch rather than an app that never starts.
         */
        const val STARTUP_HOLD_MS = 15_000

        val AGENT_SIGNATURE: String get() = "L" + AGENT_CLASS.replace('.', '/') + ";"

        /**
         * The generated agent's authority in an app, which the IDE holds a
         * reference to for the length of a session.
         *
         * **References are what keep either app from being frozen.** Android
         * 14 freezes cached processes, and during a session one of the two is
         * always in the background: a frozen debuggee answers no JDWP command,
         * and a frozen IDE never resumes a thread a `ClassPrepare` held. A
         * process whose provider or service is held by the app on screen is
         * not cached. The IDE holds this provider; the app binds the IDE's
         * service. `debugger/FINDINGS.md` section 9.
         *
         * A provider and not a bound service on this side, because creating a
         * service runs on the app's main thread -- which is the thread a
         * breakpoint in `onCreate` has suspended. The service never finished
         * starting and the system killed the app for it twenty seconds later.
         * The provider was published before any of the app's code ran.
         */
        fun agentAuthority(applicationId: String): String = "$applicationId.aide-debug-agent"
    }
}
