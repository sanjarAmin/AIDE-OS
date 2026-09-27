package com.osamu.aide.engine.api

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order the generated agent does things in, which is its security.
 *
 * JDWP on the agent's port is unauthenticated: whatever connects can run code
 * as the debugged app. The agent used to open it on every launch and only then
 * ask the IDE whether a debugger was coming, so a debug build left installed
 * listened for any app on the phone indefinitely. `DebugAgentTest` proves the
 * built app carries the agent; this proves the agent asks first.
 */
class DebugAgentSourceTest {

    private val source = DebugAgentSource.javaSource(
        DebuggerRequest(port = 38123, handshakeAuthority = "com.osamu.aide.debug-handshake"),
    )

    @Test
    fun the_ide_is_asked_before_the_port_opens() {
        val onCreate = source.substringAfter("public boolean onCreate()")
        val ask = onCreate.indexOf("askTheIde()")
        val attach = onCreate.indexOf("attachJvmtiAgent")
        assertTrue("onCreate never asks the IDE", ask >= 0)
        assertTrue("onCreate never attaches", attach >= 0)
        assertTrue("the port opens before the IDE is asked", ask < attach)
    }

    @Test
    fun a_launch_nobody_expects_neither_waits_nor_listens() {
        val onCreate = source.substringAfter("public boolean onCreate()").substringBefore("attachJvmtiAgent")
        assertTrue(
            "an unexpected launch must return before attaching:\n$onCreate",
            "if (answer == null) return true;" in onCreate,
        )
    }
}
