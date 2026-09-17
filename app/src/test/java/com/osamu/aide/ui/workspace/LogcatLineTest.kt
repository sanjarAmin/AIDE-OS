package com.osamu.aide.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading the uid column out of a log line.
 *
 * This decides whether the Logcat tab tells the user it can only see AIDE-OS,
 * and **the obvious version of it was wrong**: the first attempt compared
 * *pids*, and the log buffer holds this app's own earlier processes -- so a
 * restarted app saw a dozen foreign pids that were all itself and concluded it
 * had access it did not have. A uid is per app; a pid is per run.
 */
class LogcatLineTest {

    @Test
    fun `the uid column is the third field`() {
        val line = "09-09 00:09:17.732 10118   740   740 D StatusBarIconController: ignoring"

        assertEquals("10118", uidOf(line))
    }

    /**
     * The platform prints a name for uids it has one for. Those are foreign
     * either way, so the raw token is what this returns rather than an Int.
     */
    @Test
    fun `a named uid comes back as its name`() {
        val line = "09-09 00:09:17.528  wifi   727   727 I wpa_supplicant: CTRL-EVENT-BEACON-LOSS"

        assertEquals("wifi", uidOf(line))
    }

    @Test
    fun `a separator is not a log line`() {
        assertNull(uidOf("--------- beginning of main"))
    }

    @Test
    fun `something too short to be a log line is not one`() {
        assertNull(uidOf(""))
        assertNull(uidOf("09-09 00:09:17.528 10118"))
    }

    /**
     * The regression this exists for: our own line and another app's line must
     * not compare equal, whatever their pids are.
     */
    @Test
    fun `our own earlier process is still our own app`() {
        val ours = "09-09 00:05:50.120 10886 28904 28923 D EGL_emulation: app_time_stats"
        val alsoOurs = "09-09 00:01:11.000 10886 26633 26653 D EGL_emulation: app_time_stats"
        val theirs = "09-09 00:09:17.732 10118   740   740 D StatusBarIconController: ignoring"

        assertEquals(uidOf(ours), uidOf(alsoOurs))
        assertEquals("10118", uidOf(theirs))
    }

    @Test
    fun `parses log levels accurately`() {
        val debugLine = "09-09 00:09:17.732 10118   740   740 D StatusBarIconController: ignoring"
        val infoLine = "09-09 00:09:17.528  wifi   727   727 I wpa_supplicant: CTRL-EVENT-BEACON-LOSS"
        val warnLine = "09-09 00:09:18.000 10118   740   740 W Tag: something warned"
        val errorLine = "09-09 00:09:19.000 10118   740   740 E AndroidRuntime: FATAL EXCEPTION"
        val verboseLine = "09-09 00:09:20.000 10118   740   740 V Tag: verbose detail"

        assertEquals(LogcatLevel.DEBUG, levelOf(debugLine))
        assertEquals(LogcatLevel.INFO, levelOf(infoLine))
        assertEquals(LogcatLevel.WARN, levelOf(warnLine))
        assertEquals(LogcatLevel.ERROR, levelOf(errorLine))
        assertEquals(LogcatLevel.VERBOSE, levelOf(verboseLine))
    }
}
