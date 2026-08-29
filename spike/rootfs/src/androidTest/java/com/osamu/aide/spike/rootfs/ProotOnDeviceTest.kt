package com.osamu.aide.spike.rootfs

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Spike R11: does PRoot still work, and can a Linux userland run on the device?
 *
 * M9 (the Gradle path) rests on this. Gradle needs a real JVM and ART is not
 * one, so the plan is a Linux rootfs entered without root — which means PRoot.
 * `docs/PLAN.md` carries it as risk **R4**, "PRoot broken on Android 15+
 * seccomp", and until that is settled everything above it is speculation.
 *
 * PRoot works by `ptrace`-ing its child and rewriting the paths in every
 * syscall, which is exactly the kind of thing a platform tightens. So the
 * question is not whether the binary starts — it is whether a guest process
 * runs *and its filesystem is really the guest's*. A PRoot that started and
 * then executed the host's `/bin/sh` would look like success.
 *
 * Staged out of band like the C/C++ toolchain; see `tools/rootfs/fetch-rootfs.sh`.
 */
@RunWith(AndroidJUnit4::class)
class ProotOnDeviceTest {

    private lateinit var context: Context
    private lateinit var home: File
    private lateinit var proot: File
    private lateinit var alpine: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        home = File(context.filesDir, "rootfs")
        install()
        proot = File(home, "proot/bin/proot")
        alpine = File(home, "alpine")
        assumeTrue(
            "no rootfs staged: build one with tools/rootfs/fetch-rootfs.sh and " +
                "`adb push rootfs.tar ${context.getExternalFilesDir(null)}/$ARCHIVE`",
            proot.exists() && File(alpine, "bin/busybox").exists(),
        )
    }

    /** Unpacked by this process, for the SELinux reason in tools/clang/FINDINGS.md §4. */
    private fun install() {
        if (File(home, "proot/bin/proot").exists()) return
        val archive = File(context.getExternalFilesDir(null), ARCHIVE)
        if (!archive.isFile) return
        home.mkdirs()
        ProcessBuilder("/system/bin/tar", "-xf", archive.absolutePath, "-C", home.absolutePath)
            .redirectErrorStream(true)
            .start()
            .apply { inputStream.readBytes(); waitFor(5, TimeUnit.MINUTES) }
    }

    private data class Run(val ok: Boolean, val output: String, val millis: Long)

    /**
     * Runs PRoot through the dynamic linker, the only way a downloaded binary
     * starts at all here (spike R9).
     *
     * `PROOT_TMP_DIR` and `TMPDIR` are set inside app storage because PRoot
     * writes there and `/tmp` does not exist on Android. `-0` makes the guest
     * believe it is root, which is what lets a package manager work without
     * any real privilege being involved.
     */
    private fun proot(vararg command: String, timeoutSeconds: Long = 120): Run {
        val started = System.currentTimeMillis()
        val builder = ProcessBuilder(
            listOf(
                LINKER,
                proot.absolutePath,
                "-0",
                "-r", alpine.absolutePath,
                "-b", "/proc",
                "-b", "/dev",
                "-w", "/root",
            ) + command,
        ).redirectErrorStream(true)
        builder.directory(home)
        builder.environment().apply {
            put("LD_LIBRARY_PATH", File(home, "proot/lib").absolutePath)
            put("PROOT_TMP_DIR", home.absolutePath)
            put("TMPDIR", home.absolutePath)
            put("HOME", "/root")
            put("PATH", "/bin:/usr/bin:/sbin:/usr/sbin")
        }
        return try {
            val process = builder.start()
            val text = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                Run(false, "timed out after ${timeoutSeconds}s", System.currentTimeMillis() - started)
            } else {
                Run(process.exitValue() == 0, text, System.currentTimeMillis() - started)
            }
        } catch (failure: Exception) {
            Run(false, "${failure.javaClass.simpleName}: ${failure.message}", System.currentTimeMillis() - started)
        }
    }

    @Test
    fun the_rootfs_is_installed() {
        assertTrue("proot is missing", proot.exists())
        assertTrue("the guest has no busybox", File(alpine, "bin/busybox").exists())
        assertTrue("the guest has no /etc", File(alpine, "etc").isDirectory)
        Log.i(TAG, "proot=${proot.length()} bytes")
    }

    /**
     * **PRoot itself is fine. The guest is what cannot run.**
     *
     * Risk R4 said Android 15's seccomp changes had broken PRoot, and that is
     * not what happens here: PRoot starts, ptraces its child and reports its
     * own diagnostics, so the tracing machinery works on Android 16.
     *
     * What fails is the `execve` of the guest binary. Alpine is musl-linked and
     * its ELF interpreter is `/lib/ld-musl-aarch64.so.1`; an app targeting a
     * modern SDK may not `execve` anything out of its own storage, and the only
     * route it has -- `/system/bin/linker64` -- is Bionic's loader, which
     * cannot host a musl program. PRoot rewrites paths; it does not grant
     * permission to execute, and it cannot supply a second libc.
     *
     * Asserted as a failure on purpose. If this ever starts passing, the
     * platform has changed in a way that reopens the whole rootfs route, and
     * that is worth being told about rather than discovering by chance.
     */
    @Test
    fun proot_runs_but_the_guest_binary_cannot_be_executed() {
        val run = proot("/bin/busybox", "echo", "aide-os")

        Log.i(TAG, "echo in ${run.millis} ms: ok=${run.ok} ${run.output.take(300)}")
        assertTrue(
            "a musl guest executed under PRoot, which the exec restriction " +
                "should forbid -- the rootfs route may be viable again: ${run.output}",
            !run.ok,
        )
        // PRoot's own message, which is the evidence that PRoot ran at all.
        assertTrue(
            "the failure did not come from PRoot, so it may be something else: ${run.output}",
            "proot" in run.output.lowercase() && "execve" in run.output,
        )
    }

    /**
     * The reason the guest cannot start, isolated from PRoot entirely.
     *
     * Run the guest binary directly and the loader is what fails: Bionic's
     * linker loads the musl executable, then cannot find `libc.musl-*.so.1`
     * because that file *is* musl's loader and is not something Bionic can
     * use. Pointing it at the file anyway segfaults. Two libcs cannot be mixed,
     * which is a property of the binaries rather than of Android's policy --
     * and it is why "ship a Linux distribution" and "run it without root" are
     * not compatible for an app that cannot `execve` its own files.
     */
    @Test
    fun a_musl_binary_cannot_be_loaded_by_androids_linker() {
        val busybox = File(alpine, "bin/busybox")
        assumeTrue("no guest binary staged", busybox.exists())

        val output = StringBuilder()
        val process = ProcessBuilder(LINKER, busybox.absolutePath, "echo", "hello")
            .redirectErrorStream(true)
            .start()
        output.append(process.inputStream.bufferedReader().readText())
        process.waitFor(30, TimeUnit.SECONDS)

        Log.i(TAG, "musl via bionic: exit=${process.exitValue()} ${output.toString().take(200)}")
        assertTrue(
            "Android's linker ran a musl binary, which would reopen the rootfs " +
                "route: $output",
            process.exitValue() != 0,
        )
    }

    private companion object {
        const val TAG = "ProotSpike"
        const val ARCHIVE = "rootfs.tar"
        const val LINKER = "/system/bin/linker64"
    }
}
