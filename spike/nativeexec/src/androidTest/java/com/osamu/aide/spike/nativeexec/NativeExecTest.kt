package com.osamu.aide.spike.nativeexec

import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Spike R9: by what route can a binary that was not in the APK be run?
 *
 * Each test is one route, and the **failures matter as much as the successes**:
 * this exists to produce a table of what the platform allows, not a pass. The
 * exact error is logged in every case, because "permission denied" and "no such
 * file" send a reader to completely different places.
 *
 * Payload is `/system/bin/toybox`, a real dynamically linked executable already
 * on the device. The question is where a binary may be run *from*, not which
 * binary, so nothing here needs a toolchain download.
 *
 * Answers land in logcat under `NativeExec`.
 */
@RunWith(AndroidJUnit4::class)
class NativeExecTest {

    private lateinit var payload: File
    private lateinit var filesDir: File
    private lateinit var externalDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        payload = File(SYSTEM_PAYLOAD)
        filesDir = context.filesDir
        externalDir = context.getExternalFilesDir(null) ?: context.filesDir
        Log.i(TAG, "abi=${android.os.Build.SUPPORTED_ABIS.joinToString()} sdk=${android.os.Build.VERSION.SDK_INT}")
    }

    /** Result of one attempt: what came back, or what stopped it. */
    private data class Attempt(val ok: Boolean, val detail: String)

    private fun run(vararg command: String): Attempt = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        val finished = process.waitFor(20, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            Attempt(false, "timed out")
        } else {
            Attempt(process.exitValue() == 0, "exit=${process.exitValue()} output='${text.take(200)}'")
        }
    } catch (failure: Exception) {
        Attempt(false, "${failure.javaClass.simpleName}: ${failure.message}")
    }

    /** Copies the payload to [target] and makes it executable. */
    private fun place(target: File): File {
        target.parentFile?.mkdirs()
        payload.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        Os.chmod(target.absolutePath, EXECUTABLE_MODE)
        val mode = Os.stat(target.absolutePath).st_mode and 0xFFF
        Log.i(TAG, "placed ${target.absolutePath} mode=${Integer.toOctalString(mode)} size=${target.length()}")
        return target
    }

    /**
     * The control. If this fails, nothing below means anything.
     *
     * `/system/bin` is executable and always has been; the payload runs from
     * where it already lives.
     */
    @Test
    fun the_payload_runs_from_where_the_system_keeps_it() {
        val attempt = run(SYSTEM_PAYLOAD, "echo", MARKER)

        Log.i(TAG, "system path: ${attempt.detail}")
        assertTrue("the control failed, so no other result here is meaningful", attempt.ok)
        assertTrue(MARKER in attempt.detail)
    }

    /**
     * **The route M7's download plan assumes, and the one W^X forbids.**
     *
     * A copy in `filesDir`, marked executable, run directly. `docs/PLAN.md`
     * line 122 says this is refused for anything targeting API 29+; this
     * records what the refusal actually looks like.
     */
    @Test
    fun a_copy_in_app_data_reports_what_the_platform_does() {
        val copy = place(File(filesDir, "toybox-direct"))

        val attempt = run(copy.absolutePath, "echo", MARKER)

        Log.i(TAG, "app data, direct: ok=${attempt.ok} ${attempt.detail}")
        // Deliberately not asserted either way. The finding is the answer, and
        // asserting the expected failure would turn a platform that quietly
        // relaxed this into a red test rather than good news.
        assertTrue("the file was not placed", copy.isFile)
    }

    /**
     * **The route `docs/PLAN.md` R4 names and nothing had tried.**
     *
     * The kernel execs `/system/bin/linker64`, which is in an executable
     * directory; the payload is passed as an argument and only ever mapped. If
     * this works, a downloaded toolchain is viable and M7 needs no redesign.
     */
    @Test
    fun the_dynamic_linker_may_be_able_to_run_it() {
        val copy = place(File(filesDir, "toybox-linker"))

        val attempt = run(LINKER, copy.absolutePath, "echo", MARKER)

        Log.i(TAG, "app data, via linker64: ok=${attempt.ok} ${attempt.detail}")
        assertTrue("the file was not placed", copy.isFile)
    }

    /** The same, through the linker's real path rather than the `/system/bin` symlink. */
    @Test
    fun the_apex_linker_may_be_able_to_run_it() {
        val copy = place(File(filesDir, "toybox-apex"))

        val attempt = run(APEX_LINKER, copy.absolutePath, "echo", MARKER)

        Log.i(TAG, "app data, via apex linker: ok=${attempt.ok} ${attempt.detail}")
        assertTrue("the file was not placed", copy.isFile)
    }

    /**
     * External storage, which is where this project already keeps projects.
     *
     * Worth asking separately: it is a different filesystem with its own mount
     * options, and a reader who knows `filesDir` is refused will wonder.
     */
    @Test
    fun external_storage_reports_what_the_platform_does() {
        val copy = place(File(externalDir, "toybox-external"))

        val direct = run(copy.absolutePath, "echo", MARKER)
        val viaLinker = run(LINKER, copy.absolutePath, "echo", MARKER)

        Log.i(TAG, "external, direct: ok=${direct.ok} ${direct.detail}")
        Log.i(TAG, "external, via linker64: ok=${viaLinker.ok} ${viaLinker.detail}")
        assertTrue("the file was not placed", copy.isFile)
    }

    /**
     * The one place the platform allows it — and it is not always there.
     *
     * `nativeLibraryDir` is a **path, not a guarantee**: Android only creates
     * it when the APK actually ships a `.so`. This spike ships none, so the
     * directory does not exist, and a first version of this test asserted it
     * did and failed for the right reason.
     *
     * That is worth knowing rather than working around, because it constrains
     * M7: a design that execs a toolchain from `nativeLibraryDir` has to put
     * something in `jniLibs` for the directory to exist at all. `aapt2` already
     * does (`tools/aapt2/FINDINGS.md`), so `:app` is fine — but a separate
     * toolchain APK carrying nothing else would not be.
     *
     * Never writable at runtime either way, which is the whole reason a
     * downloaded payload cannot simply live there.
     */
    @Test
    fun the_native_library_directory_is_never_writable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)

        Log.i(
            TAG,
            "nativeLibraryDir=$nativeDir exists=${nativeDir.isDirectory} " +
                "writable=${nativeDir.canWrite()}",
        )
        assertEquals(
            "if this ever became writable, a downloaded toolchain could live here " +
                "and the linker route below would be unnecessary",
            false,
            nativeDir.canWrite(),
        )
    }

    private companion object {
        const val TAG = "NativeExec"
        const val SYSTEM_PAYLOAD = "/system/bin/toybox"
        const val LINKER = "/system/bin/linker64"
        const val APEX_LINKER = "/apex/com.android.runtime/bin/linker64"
        const val MARKER = "AIDE-OS-EXEC-WORKS"

            /** 0700: rwx for the owner only, which is all an app can grant itself. */
        /** 0700 — rwx for the owner, which is all an app can grant itself. */
        const val EXECUTABLE_MODE = 448
    }
}
