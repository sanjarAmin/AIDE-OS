package com.osamu.aide.spike.pty

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import java.io.File

/**
 * An Activity that starts a shell and then can be sent to the background.
 *
 * Exists only for [BackgroundSurvivalTest]. The question it answers cannot be
 * asked from the instrumentation process: that process is never *cached*, so a
 * shell forked from it is never subject to the treatment Android gives a
 * backgrounded app. A real Activity in a real task is.
 *
 * The shell appends to a file once a second. That is the measurement: whether
 * the file keeps growing while nobody is looking.
 */
class ShellHostActivity : Activity() {

    private var pty: Pty? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "shell host" })

        val heartbeat = File(cacheDir, HEARTBEAT)
        heartbeat.delete()

        pty = Pty.open(workingDirectory = cacheDir.absolutePath).also { shell ->
            File(cacheDir, PID_FILE).writeText(shell.processId.toString())
            // Appends a line a second, forever. `date` is a toybox applet and
            // is present; the loop is a shell built-in, so nothing here depends
            // on being able to list /system/bin.
            shell.input.write(
                "while true; do date +%s >> ${heartbeat.absolutePath}; sleep 1; done\n"
                    .toByteArray(),
            )
            shell.input.flush()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Deliberately not killed on pause or stop: the whole question is what
        // the platform does to it while the app is away.
        pty?.signal(9)
        pty?.close()
        pty = null
    }

    companion object {
        const val HEARTBEAT = "shell-heartbeat.txt"
        const val PID_FILE = "shell-pid.txt"
    }
}
