package com.osamu.aide.spike.pty

import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * A pseudoterminal with a shell running in it.
 *
 * The Kotlin side of spike R7, kept as thin as the C side: this exists to be
 * driven by a test, not to be a terminal. There is no emulation, no escape
 * parsing, and no buffering policy, because none of those is what is in doubt.
 *
 * **Every negative return from the native side is `-errno`.** A terminal that
 * fails on one device and works on another usually fails at a specific syscall
 * for a specific policy reason, and "it returned false" is not something a
 * finding can be written from.
 */
class Pty private constructor(private val pid: Int, private val fd: Int) {

    private val descriptor = FileDescriptor().also { setFd(it, fd) }

    /** Bytes the shell wrote. Reading blocks until there are some, as a TTY does. */
    val output = FileInputStream(descriptor)

    /** Bytes typed at the shell. */
    val input = FileOutputStream(descriptor)

    /** The child's pid, which is also its process group: `forkpty` calls `setsid`. */
    val processId: Int get() = pid

    fun resize(columns: Int, rows: Int): Int = nativeResize(fd, columns, rows)

    /**
     * The terminal's foreground process group.
     *
     * The job-control question asked directly. A shell that really took the
     * terminal reports its own group here; a command it is running in the
     * foreground reports that command's. A failure means the fd is a pipe
     * wearing a terminal's clothes.
     */
    fun foregroundGroup(): Int = nativeForegroundGroup(fd)

    /** Signals the shell's own process group. */
    fun signal(sig: Int): Int = nativeKill(pid, sig)

    /**
     * Signals a process group, which is what pressing Ctrl-C does.
     *
     * The group comes from [foregroundGroup]: a terminal signals whatever is
     * in the foreground, not the shell. Signalling the shell instead is the
     * mistake that makes an interrupt look like it works -- the shell survives
     * SIGINT, so nothing obviously breaks, and the running command never dies.
     */
    fun signalGroup(group: Int, sig: Int): Int = nativeKill(group, sig)

    /** Blocks for the child; the exit status, or 128 + signal if it was killed. */
    fun waitFor(): Int = nativeWait(pid)

    /** The exit status, or -1 while the child is still running. */
    fun poll(): Int = nativePoll(pid)

    fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        nativeClose(fd)
    }

    companion object {
        init {
            System.loadLibrary("aide-pty")
        }

        /**
         * Opens a PTY and execs [shell] in it.
         *
         * Throws rather than returning null, with the `errno` name in the
         * message: the whole point of the spike is knowing *which* call the
         * platform refused.
         */
        fun open(
            shell: String = DEFAULT_SHELL,
            workingDirectory: String? = null,
            columns: Int = 80,
            rows: Int = 24,
        ): Pty {
            val fd = IntArray(1)
            val pid = nativeOpen(shell, workingDirectory, columns, rows, fd)
            check(pid > 0) { "forkpty failed: errno ${-pid}" }
            return Pty(pid, fd[0])
        }

        /**
         * `/system/bin/sh`, not `/bin/sh`.
         *
         * Android has no `/bin`. JGit's `FS.detect()` looks for one and finds
         * nothing, which is finding 1 of `tools/git/FINDINGS.md`; the same fact
         * decides what a terminal can launch.
         */
        const val DEFAULT_SHELL = "/system/bin/sh"

        /**
         * Sets the private `descriptor` field of a [FileDescriptor].
         *
         * There is no public way to build one around an integer fd, and the
         * alternative is another JNI entry point doing the same reflection from
         * the other side. On Android the field has been `int descriptor` since
         * forever, and this is the same trick every terminal app on the
         * platform uses.
         */
        private fun setFd(descriptor: FileDescriptor, fd: Int) {
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.setInt(descriptor, fd)
        }

        @JvmStatic
        private external fun nativeOpen(
            shell: String,
            cwd: String?,
            columns: Int,
            rows: Int,
            outFd: IntArray,
        ): Int

        @JvmStatic
        private external fun nativeResize(fd: Int, columns: Int, rows: Int): Int

        @JvmStatic
        private external fun nativeForegroundGroup(fd: Int): Int

        @JvmStatic
        private external fun nativeWait(pid: Int): Int

        @JvmStatic
        private external fun nativePoll(pid: Int): Int

        @JvmStatic
        private external fun nativeKill(pid: Int, sig: Int): Int

        @JvmStatic
        private external fun nativeClose(fd: Int)
    }
}
