package com.osamu.aide.debugger

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/**
 * `:spike:jdwpdebuggee`, the app these tests debug, made ready before each one.
 *
 * **It used to be installed and started by hand, and a sweep did neither.** A
 * full `connectedDebugAndroidTest` reported every test here as skipped --
 * twelve of them, all of `:debugger`'s device coverage -- and the sweep before
 * that met a debuggee left running in the background, frozen by Android's
 * cached-app freezer, which answered a connect and then nothing. Gradle now
 * installs it before these tests run (`installDebug`, which a connected run does
 * not uninstall), and each test brings it to the front first: starting an
 * activity that is already running only raises it, which is also what thaws a
 * frozen one. `debugger/FINDINGS.md` section 9 has the freezer.
 */
internal object Debuggee {

    const val PACKAGE = "com.osamu.aide.spike.jdwpdebuggee"

    fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun installed(): Boolean = shell("pm path $PACKAGE").contains("package:")

    /**
     * Starts the debuggee listening on [port], or raises the one already
     * running. `-W` waits for it to be on screen, so a frozen process is thawed
     * before anything connects.
     */
    fun bringForward(port: Int) {
        if (installed()) shell("am start -W -n $PACKAGE/.DebuggeeActivity --ei port $port")
    }
}
