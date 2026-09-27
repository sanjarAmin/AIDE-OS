package com.osamu.aide.spike.clang

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
 * Spike R10: clang, on the device, producing something that runs.
 *
 * Layered so a failure names the layer: (1) the toolchain is present, (2) clang
 * starts at all through the linker, (3) it finds its own resource directory,
 * (4) it compiles to an object, (5) it links a shared library, and (6) **that
 * library loads and its code executes**.
 *
 * The last one is the acceptance question and the only one that cannot be
 * faked. `JNI_OnLoad` in the compiled library writes a marker file; the test
 * asserts the marker, so a pass means code clang generated on this device
 * actually ran in this process.
 *
 * The toolchain is expected at [TOOLCHAIN], installed out of band — see
 * `tools/clang/FINDINGS.md`. Every test is skipped when it is absent rather
 * than failing, because "not installed" is not a defect and this suite runs on
 * machines that never installed it.
 *
 * Numbers land in logcat under `ClangSpike`.
 */
@RunWith(AndroidJUnit4::class)
class ClangOnDeviceTest {

    private lateinit var work: File
    private lateinit var marker: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        installToolchainIfNeeded(context)
        assumeTrue(
            "no toolchain. Build one with tools/clang/fetch-toolchain.sh, then " +
                "`adb push toolchain.tar ${context.getExternalFilesDir(null)}/$ARCHIVE` " +
                "and run this suite with `am instrument` rather than Gradle — " +
                "see tools/clang/FINDINGS.md",
            File(CLANG).isFile,
        )
        work = File(context.filesDir, "clang-spike").apply {
            deleteRecursively()
            mkdirs()
        }
        marker = File(work, "the-compiled-code-ran.txt")
    }

    /**
     * Unpacks the toolchain **from inside the app**, which is the only way it
     * ends up usable.
     *
     * Extracting it as root and correcting the owner afterwards is the obvious
     * shortcut and it does not work: Android labels app-private files with
     * per-app SELinux categories, and files created by another domain do not
     * get them. `ls` as root shows a perfectly good tree; the app then gets
     * `Permission denied` on individual files, and the dynamic linker reports
     * it as `library "libz.so.1" not found` — which reads like a missing file
     * rather than an unreadable one.
     *
     * A child process of the app inherits the app's domain, so `tar` run this
     * way produces a tree the app can actually use. That is also what
     * `:toolchain:manager` will do: download, then unpack in-process.
     */
    private fun installToolchainIfNeeded(context: android.content.Context) {
        if (File(CLANG).isFile) return
        val archive = File(context.getExternalFilesDir(null), ARCHIVE)
        if (!archive.isFile) return

        val destination = File(context.filesDir, "toolchain").apply { mkdirs() }
        val started = System.currentTimeMillis()
        val process = ProcessBuilder("/system/bin/tar", "-xf", archive.absolutePath, "-C", destination.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(10, TimeUnit.MINUTES)
        Log.i(
            TAG,
            "unpacked in ${System.currentTimeMillis() - started} ms, exit=${process.exitValue()} $output",
        )
    }

    private data class Run(val ok: Boolean, val output: String, val millis: Long)

    /**
     * Runs the toolchain through the dynamic linker.
     *
     * `LD_LIBRARY_PATH` is set because the binaries' `RUNPATH` points at
     * Termux's own prefix, which this app cannot write to and which does not
     * exist here. That one environment variable is the whole of the
     * relocation — see finding 2.
     */
    private fun toolchain(vararg command: String, timeoutSeconds: Long = 180): Run {
        val started = System.currentTimeMillis()
        val builder = ProcessBuilder(LINKER, *command).redirectErrorStream(true)
        builder.directory(work)
        builder.environment()["LD_LIBRARY_PATH"] = "$TOOLCHAIN/lib"
        builder.environment()["HOME"] = work.absolutePath
        builder.environment()["TMPDIR"] = work.absolutePath
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

    /** Question 1: is the toolchain where it is supposed to be? */
    @Test
    fun the_toolchain_is_installed() {
        val clang = File(CLANG)
        Log.i(TAG, "clang=${clang.absolutePath} size=${clang.length()}")
        assertTrue("clang is missing", clang.isFile)
        assertTrue("the linker is missing", File("$TOOLCHAIN/bin/ld.lld").isFile)
        assertTrue("the sysroot headers are missing", File("$TOOLCHAIN/include/stdio.h").isFile)
    }

    /**
     * Question 2: does clang start, through the linker, from app data?
     *
     * Spike R9 proved the route with toybox. clang is a different proposition:
     * it links against a 134 MB `libLLVM.so` whose own `RUNPATH` points
     * somewhere that does not exist here.
     */
    @Test
    fun clang_starts_and_reports_its_version() {
        val run = toolchain(CLANG, "--version", timeoutSeconds = 60)

        Log.i(TAG, "clang --version in ${run.millis} ms: ${run.output.take(200)}")
        assertTrue("clang did not start: ${run.output}", run.ok)
        assertTrue("unexpected version output: ${run.output}", "clang version" in run.output)
    }

    /**
     * Question 3: **clang cannot locate itself when launched through the
     * linker, and this is the fact everything else depends on.**
     *
     * clang finds its resource directory — its builtin headers, and where it
     * looks for `ld.lld` — relative to its own executable, which it discovers
     * through `/proc/self/exe`. Launched as `linker64 clang-21`, that path is
     * **the linker's**, so clang believes it is installed in
     * `/apex/com.android.runtime/bin` and looks for everything under
     * `/apex/com.android.runtime/lib/clang/21`, which does not exist.
     *
     * `tools/nativeexec/FINDINGS.md` named this as an open question when R9
     * established the launch route: *"anything that re-execs itself, or
     * inspects `/proc/self/exe`, may behave differently. clang drivers do
     * both."* This is that, confirmed.
     *
     * The symptom is misleading. A trivial file still compiles, because it
     * needs no builtin headers; anything including `stddef.h` fails with a
     * missing-header error that reads like a broken sysroot rather than a
     * broken launch. So this is asserted directly rather than left to be
     * inferred from a compile failure later.
     */
    @Test
    fun clang_cannot_locate_itself_when_launched_through_the_linker() {
        val reported = toolchain(CLANG, "-print-resource-dir", timeoutSeconds = 60)
        assertTrue("clang did not run: ${reported.output}", reported.ok)

        Log.i(TAG, "clang believes its resource dir is '${reported.output}'")
        assertEquals(
            "clang located itself correctly, which means the launch no longer " +
                "confuses it — the explicit -resource-dir below may be unnecessary now",
            "/apex/com.android.runtime/lib/clang/21",
            reported.output.trim(),
        )
        assertTrue(
            "the path clang reports does not exist, which is the whole problem",
            !File(reported.output.trim()).isDirectory,
        )

        // And the real one, which every invocation has to be told about.
        assertTrue(
            "the toolchain's own resource dir is missing",
            File("$RESOURCE_DIR/include/stddef.h").isFile,
        )
    }

    /** Question 4: does it compile C to an object for this device? */
    @Test
    fun it_compiles_an_object_file() {
        // Includes a header on purpose. Without one, a compile succeeds even
        // when clang is looking for its builtins in the wrong place, so a test
        // of `int f(void){return 42;}` would pass against a broken toolchain.
        val source = File(work, "unit.c").apply {
            writeText(
                """
                #include <stddef.h>
                #include <stdio.h>
                size_t aide_os_answer(void) { return sizeof(int) + 38; }
                """.trimIndent(),
            )
        }
        val objectFile = File(work, "unit.o")

        val run = toolchain(
            CLANG, *SELF_LOCATION_FLAGS,
            "-c", source.absolutePath, "-o", objectFile.absolutePath,
            "-fPIC",
        )

        Log.i(TAG, "compile in ${run.millis} ms: ${run.output.take(300)}")
        assertTrue("compile failed: ${run.output}", run.ok)
        assertTrue("no object file was produced", objectFile.isFile)
        assertTrue("the object file is empty", objectFile.length() > 0)
    }

    /**
     * **clang cannot compile and link in one invocation here, and this is the
     * rule M7 has to build around.**
     *
     * A single `clang -shared foo.c -o foo.so` is two jobs. clang runs the
     * compile in-process when it is the only job, but with a link to follow it
     * spawns a separate `cc1` — through `/proc/self/exe`, which is the linker:
     *
     * ```
     * "/apex/com.android.runtime/bin/linker64" -cc1 -triple x86_64-…
     * error: expected absolute path: "-cc1"
     * ```
     *
     * `-fintegrated-cc1` does not help; it was tried. The fix is to give clang
     * one job at a time, which is what
     * [a_library_it_built_loads_and_its_code_runs] does and what
     * `:engine:fast`'s native stage will have to do.
     */
    @Test
    fun compiling_and_linking_in_one_invocation_fails() {
        val source = File(work, "combined.c").apply {
            writeText("int aide_os_combined(void) { return 1; }\n")
        }

        val run = toolchain(
            CLANG, *SELF_LOCATION_FLAGS,
            "-shared", "-fPIC", source.absolutePath,
            "-o", File(work, "combined.so").absolutePath,
        )

        assertTrue(
            "one invocation compiled and linked, which means clang can now " +
                "re-exec itself correctly and the two-step split below is no " +
                "longer required: ${run.output}",
            !run.ok,
        )
        assertTrue(
            "it failed for some other reason than the re-exec: ${run.output}",
            "-cc1" in run.output,
        )
    }

    /**
     * **clang cannot run the linker either, and this is the second rule M7 has
     * to build around.**
     *
     * Even reduced to a single job — link an existing object, no compiling —
     * the driver still fails, and for a different reason than
     * [compiling_and_linking_in_one_invocation_fails]. The link is not
     * something clang does in-process: it always `execve`s `ld.lld`, and
     * `ld.lld` lives in app-private storage, which spike R9 established is
     * never executable. The driver reports it as:
     *
     * ```
     * clang-21: error: unable to execute command: Program could not be executed
     * clang-21: error: linker command failed due to signal
     * ```
     *
     * which names neither the file nor the permission and reads like a crash.
     *
     * There is no flag that fixes this. `-B` already points at the directory —
     * clang finds `ld.lld` perfectly well, it just cannot start it. The way
     * through is [a_library_it_built_loads_and_its_code_runs]: have clang
     * *plan* the link and execute the linker ourselves, through the same route
     * that starts clang.
     */
    @Test
    fun clang_cannot_run_the_linker_itself() {
        val objectFile = compile("solo.c", "int aide_os_solo(void) { return 7; }\n")

        val run = toolchain(
            CLANG, *SELF_LOCATION_FLAGS,
            "-shared", objectFile.absolutePath,
            "-o", File(work, "solo.so").absolutePath,
        )

        Log.i(TAG, "driver-run link in ${run.millis} ms: ${run.output.take(300)}")
        assertTrue(
            "the driver linked successfully, which means it can now execute " +
                "ld.lld from app storage and the planned link below is no " +
                "longer required: ${run.output}",
            !run.ok,
        )
        assertTrue(
            "it failed for some other reason than being unable to exec: ${run.output}",
            "unable to execute command" in run.output,
        )
    }

    /**
     * Questions 5 and 6: **link a shared library, load it, and prove its code
     * ran.**
     *
     * The acceptance question for M7, in one test. `JNI_OnLoad` is called by
     * `System.load`, so a marker written from there can only appear if code
     * clang generated on this device executed in this process. Asserting that
     * the file merely exists on disk, or that `System.load` did not throw,
     * would both pass against a library that does nothing.
     *
     * Compiled one job at a time per [compiling_and_linking_in_one_invocation_fails],
     * and linked by [plannedLink] rather than by the driver per
     * [clang_cannot_run_the_linker_itself]. Both detours are load-bearing:
     * remove either and this test fails.
     */
    @Test
    fun a_library_it_built_loads_and_its_code_runs() {
        val objectFile = compile(
            "jni.c",
            """
            #include <jni.h>
            #include <stdio.h>

            JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
                FILE *f = fopen("${marker.absolutePath}", "w");
                if (f) {
                    fprintf(f, "$MARKER\n");
                    fclose(f);
                }
                return JNI_VERSION_1_6;
            }
            """.trimIndent(),
        )
        val library = File(work, "libaideclangspike.so")

        val link = plannedLink(objectFile, library)
        Log.i(TAG, "planned link in ${link.millis} ms: ${link.output.take(400)}")
        assertTrue("link failed: ${link.output}", link.ok)
        assertTrue("no library was produced", library.isFile)
        Log.i(TAG, "library is ${library.length()} bytes")

        // The whole point. If this throws, the library is not loadable on the
        // platform that just built it.
        System.load(library.absolutePath)

        assertTrue(
            "the library loaded but JNI_OnLoad never ran, so nothing clang " +
                "generated actually executed",
            marker.isFile,
        )
        assertEquals(MARKER, marker.readText().trim())
    }

    /**
     * **C++ works too, and it brings a file the built APK has to carry.**
     *
     * Most NDK code is C++, so a native stage that only handled C would not be
     * worth shipping. The compile needs nothing extra — the libc++ headers are
     * in the sysroot and `clang++` finds them — but the link adds
     * `libc++_shared.so` as a runtime dependency, and **that library is part of
     * the toolchain, not part of Android**. Nothing resolves it for free.
     *
     * Here it is loaded explicitly first. A real build cannot do that: it has
     * to copy `libc++_shared.so` out of the toolchain and into the APK it is
     * building, next to the library that needs it, which is exactly what the
     * NDK's own Gradle plugin does. That copy is a `:engine:fast` job and it
     * has no other natural owner.
     *
     * `std::string` is used on purpose. A C++ file that touches nothing from
     * the standard library links with no dependency at all and would pass this
     * test while proving none of it.
     */
    @Test
    fun it_builds_c_plus_plus_and_the_result_needs_the_toolchain_s_libcxx() {
        val marker = File(work, "cpp-ran.txt")
        val objectFile = compile(
            "jni.cpp",
            """
            #include <jni.h>
            #include <string>
            #include <fstream>

            extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
                std::string text = std::string("$MARKER") + "/C++";
                std::ofstream out("${marker.absolutePath}");
                out << text << std::endl;
                return JNI_VERSION_1_6;
            }
            """.trimIndent(),
            driver = CLANGXX,
        )
        val library = File(work, "libaideclangspikecpp.so")

        val link = plannedLink(objectFile, library, driver = CLANGXX)
        Log.i(TAG, "c++ link in ${link.millis} ms: ${link.output.take(400)}")
        assertTrue("link failed: ${link.output}", link.ok)

        // The dependency the toolchain adds and the platform does not provide.
        // Loading it by hand here is what a built APK does by packaging it.
        assertTrue("libc++_shared.so is not in the toolchain", File(LIBCXX).isFile)
        System.load(LIBCXX)
        System.load(library.absolutePath)

        assertTrue("JNI_OnLoad never ran", marker.isFile)
        assertEquals("$MARKER/C++", marker.readText().trim())
    }

    /**
     * The flags for one driver.
     *
     * C++ needs a fifth path on top of [SELF_LOCATION_FLAGS], for the same
     * reason the fourth exists: the driver looks for the libc++ headers under
     * `<sysroot>/usr/include/c++/v1`, and Termux's prefix has no `usr/` level,
     * so `#include <string>` fails with `'string' file not found` while the
     * headers sit unread two directories away.
     *
     * `-cxx-isystem` rather than `-I`, so the standard library is a system
     * header directory: warnings from inside libc++ are not the user's
     * problem, and `-I` would report them as if they were.
     */
    private fun flagsFor(driver: String): Array<String> =
        if (driver == CLANGXX) SELF_LOCATION_FLAGS + CXX_FLAGS else SELF_LOCATION_FLAGS

    /** One source, one object, one job. Fails the calling test on error. */
    private fun compile(name: String, source: String, driver: String = CLANG): File {
        val file = File(work, name).apply { writeText(source) }
        val objectFile = File(work, name.substringBeforeLast('.') + ".o")
        val run = toolchain(
            driver, *flagsFor(driver),
            "-c", "-fPIC", file.absolutePath, "-o", objectFile.absolutePath,
        )
        Log.i(TAG, "compile $name in ${run.millis} ms: ${run.output.take(300)}")
        assertTrue("compile failed: ${run.output}", run.ok)
        assertTrue("no object file was produced", objectFile.isFile)
        return objectFile
    }

    /**
     * Links by asking clang what it *would* run, then running it ourselves.
     *
     * `-###` prints the driver's plan and executes nothing, so it survives the
     * exec restriction that [clang_cannot_run_the_linker_itself] hits. The last
     * line is the full `ld.lld` invocation — every `crtbegin_so.o`,
     * `libclang_rt.builtins`, `-l:libunwind.a` and search path the platform
     * needs, worked out by the driver rather than guessed at here. Hand-writing
     * that command is the alternative, and it would be a copy of clang's
     * per-target logic that goes stale the first time the toolchain is updated.
     *
     * Then the command runs through [toolchain], the same `linker64` route that
     * starts clang, which is exactly what the driver could not do for itself.
     *
     * This is the shape `:engine:fast`'s native stage should take: **plan with
     * the driver, execute with the linker.** It generalises past linking — any
     * tool clang wants to spawn has the same problem and the same answer.
     */
    private fun plannedLink(objectFile: File, output: File, driver: String = CLANG): Run {
        val plan = toolchain(
            driver, *flagsFor(driver),
            "-shared", objectFile.absolutePath, "-o", output.absolutePath,
            "-###",
        )
        assertTrue("clang would not print a plan: ${plan.output}", plan.ok)

        // Tokens are quoted by the driver precisely so they can be read back;
        // paths here contain no quotes, so splitting on them is enough.
        val command = plan.output.trim().lines().last()
        val arguments = QUOTED.findAll(command).map { it.groupValues[1] }.toList()
        assertTrue(
            "the last line of the plan is not a linker invocation: $command",
            arguments.firstOrNull()?.endsWith("ld.lld") == true,
        )
        Log.i(TAG, "planned linker command has ${arguments.size} arguments")

        return toolchain(*arguments.toTypedArray())
    }

    private companion object {
        const val TAG = "ClangSpike"

        /**
         * Installed out of band, in **this app's own internal storage**.
         *
         * Not `/data/local/tmp`, which would be a different directory with
         * different mount and SELinux properties and would prove nothing about
         * where a real toolchain would live. Spike R9's finding is specifically
         * about app-internal storage, and external storage is ruled out there
         * because it refuses executable mappings outright — so a toolchain
         * cannot live beside the projects.
         */
        const val TOOLCHAIN =
            "/data/data/com.osamu.aide.spike.clang.test/files/toolchain/usr"

        /** Placed in the app's external files dir by hand; see the findings. */
        const val ARCHIVE = "toolchain.tar"

        const val CLANG = "$TOOLCHAIN/bin/clang-21"

        /**
         * The C++ driver — **the same binary, chosen by `argv[0]`**.
         *
         * `clang++-21` is a symlink to `clang-21`, and the basename it is
         * invoked under is the whole of what puts the driver in C++ mode: the
         * libc++ header path, `-lc++_shared`, the C++ standard. There is no
         * flag that does this.
         *
         * That makes the symlink load-bearing, which is a second reason the
         * toolchain is transferred as a tar. The ~900 symlinks a zip or an
         * `adb push` silently flattens are not all redundant copies; this one
         * decides what language gets compiled.
         *
         * It survives the `linker64` launch because `argv[0]` is the path we
         * pass, not something the linker rewrites — unlike `/proc/self/exe`,
         * which it does.
         */
        const val CLANGXX = "$TOOLCHAIN/bin/clang++-21"

        /** Shipped by the toolchain, not by Android. */
        const val LIBCXX = "$TOOLCHAIN/lib/libc++_shared.so"
        const val LINKER = "/system/bin/linker64"

        const val MARKER = "AIDE-OS-CLANG-BUILT-THIS"

        /** One `"…"` token of a `-###` plan. */
        val QUOTED = Regex("\"([^\"]*)\"")

        /** Where the builtin headers really are. */
        const val RESOURCE_DIR = "$TOOLCHAIN/lib/clang/21"

        /** The device's own triple. A real build would pick this per project. */
        val TRIPLE: String =
            if (android.os.Build.SUPPORTED_ABIS.first().startsWith("arm64")) {
                "aarch64-linux-android"
            } else {
                "x86_64-linux-android"
            }

        /**
         * The flags that undo what launching through the linker breaks.
         *
         * `-resource-dir` replaces what clang computes from `/proc/self/exe`;
         * `-B` tells it where its own tools live, without which `-fuse-ld=lld`
         * fails with `invalid linker name` because `ld.lld` is not beside the
         * linker either; `--sysroot` points at the headers and libraries.
         *
         * `-I <sysroot>/include/<triple>` is the fourth, and it is not about
         * the linker launch at all: the NDK sysroot keeps architecture-specific
         * headers — `asm/` above all — in a per-triple directory, and clang
         * derives that only from a sysroot laid out as `<root>/usr/include`.
         * This one is `<root>/include`, so it has to be told. Without it,
         * `#include <stdio.h>` fails three headers deep on `asm/types.h`.
         *
         * **Every invocation needs all of them.** `:toolchain:native` should
         * add them centrally rather than leave each caller to remember, because
         * forgetting produces a missing-header error that looks like a
         * different problem entirely.
         */
        /** Added for [CLANGXX] only; see `flagsFor`. */
        val CXX_FLAGS: Array<String> = arrayOf(
            "-cxx-isystem", "$TOOLCHAIN/include/c++/v1",
        )

        val SELF_LOCATION_FLAGS: Array<String> = arrayOf(
            "-resource-dir", RESOURCE_DIR,
            "-B", "$TOOLCHAIN/bin",
            "--sysroot=$TOOLCHAIN",
            "-I", "$TOOLCHAIN/include/$TRIPLE",
        )
    }
}
