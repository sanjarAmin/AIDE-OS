package com.osamu.aide.toolchain.nativetools

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [ClangToolchain] against a real toolchain, on a real device.
 *
 * The acceptance question for M7's compiler half, and the only test that can
 * answer it: every workaround this class encodes exists because of platform
 * behaviour, so a fake would confirm nothing. The library it builds is loaded
 * into this process and its code is run -- asserting that a `.so` appeared on
 * disk would pass against one that does nothing.
 *
 * Skipped when no toolchain is installed, because "not installed" is not a
 * defect and this suite runs on machines that never installed one. Build it
 * with `tools/clang/fetch-toolchain.sh` and stage it as described below.
 */
@RunWith(AndroidJUnit4::class)
class ClangToolchainOnDeviceTest {

    private lateinit var context: Context
    private lateinit var work: File
    private lateinit var clang: ClangToolchain

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        stageToolchainIfNeeded()

        clang = ClangToolchain(
            root = File(context.filesDir, "toolchain/usr"),
            abi = Build.SUPPORTED_ABIS.first(),
            launch = LinkerLaunch.forThisProcess(),
            runner = NativeToolRunner(NativeToolchain.from(context), DefaultDispatcherProvider()),
        )
        // **Skipped only when nothing was staged.** If an archive is present,
        // an unusable install is a failure and has to say so: the first version
        // of this assumed on `isInstalled` instead, and when a real bug made a
        // complete toolchain look uninstalled, all three tests skipped -- which
        // the runner prints as `OK (3 tests)`. A skip that can be caused by a
        // defect is indistinguishable from a pass.
        assumeTrue(
            "no C/C++ toolchain: build one with tools/clang/fetch-toolchain.sh and " +
                "`adb push toolchain.tar ${context.getExternalFilesDir(null)}/$ARCHIVE`",
            File(context.getExternalFilesDir(null), ARCHIVE).isFile ||
                File(context.filesDir, "toolchain/usr/bin/clang").exists(),
        )
        assertTrue(
            "a toolchain was staged but is not usable; the unpack may have been " +
                "interrupted, or its layout has changed",
            clang.isInstalled,
        )
        work = File(context.filesDir, "clang-work").apply { deleteRecursively(); mkdirs() }
    }

    /**
     * Unpacks a staged archive, **from inside the app**.
     *
     * A fixture, not the product: `:toolchain:manager` will download and unpack
     * in-process. What is not negotiable is *which process does it*. Android
     * labels app-private files with per-app SELinux categories, and a tree
     * written by any other domain lacks them -- the app then gets permission
     * denied on individual files, which the dynamic linker reports as
     * `library "libz.so.1" not found`. `tools/clang/FINDINGS.md` §4.
     */
    private fun stageToolchainIfNeeded() {
        val destination = File(context.filesDir, "toolchain")
        if (File(destination, "usr/bin/clang").exists()) return
        val archive = File(context.getExternalFilesDir(null), ARCHIVE)
        if (!archive.isFile) return

        destination.mkdirs()
        ProcessBuilder("/system/bin/tar", "-xf", archive.absolutePath, "-C", destination.absolutePath)
            .redirectErrorStream(true)
            .start()
            .apply { inputStream.readBytes(); waitFor(10, TimeUnit.MINUTES) }
    }

    private fun source(name: String, text: String) =
        File(work, name).apply { writeText(text) }

    /**
     * The whole path: compile, link, load, run.
     *
     * `JNI_OnLoad` runs when `System.load` succeeds, so the marker can only
     * exist if code this device compiled a moment ago executed in this process.
     */
    @Test
    fun it_builds_a_library_that_loads_and_runs() = runTest {
        val marker = File(work, "c-ran.txt")
        val objectFile = File(work, "jni.o")
        val library = File(work, "libaideclangc.so")
        val output = StringBuilder()

        val compiled = clang.compile(
            source = source(
                "jni.c",
                """
                #include <jni.h>
                #include <stdio.h>
                JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
                    FILE *f = fopen("${marker.absolutePath}", "w");
                    if (f) { fprintf(f, "%s\n", "$MARKER"); fclose(f); }
                    return JNI_VERSION_1_6;
                }
                """.trimIndent(),
            ),
            output = objectFile,
            arguments = listOf("-fPIC"),
            workingDir = work,
        ) { output.appendLine(it.text) }

        assertSucceeded("compile", compiled, output)
        assertTrue("no object file", objectFile.isFile)

        val linked = clang.link(listOf(objectFile), library, workingDir = work) {
            output.appendLine(it.text)
        }
        assertSucceeded("link", linked, output)
        assertTrue("no library", library.isFile)

        System.load(library.absolutePath)
        assertTrue("the library loaded but its code never ran", marker.isFile)
        assertEquals(MARKER, marker.readText().trim())
    }

    /**
     * The same for C++, which is what most native Android code actually is.
     *
     * `libc++_shared.so` comes from the toolchain, not from the platform, and
     * the driver plans `-lc++_shared` into every C++ link. Loading it by hand
     * here stands in for what a built APK has to do: package it beside the
     * library that needs it.
     */
    @Test
    fun it_builds_c_plus_plus_too() = runTest {
        val marker = File(work, "cpp-ran.txt")
        val objectFile = File(work, "jni_cpp.o")
        val library = File(work, "libaideclangcpp.so")
        val output = StringBuilder()

        val compiled = clang.compile(
            source = source(
                "jni.cpp",
                """
                #include <jni.h>
                #include <string>
                #include <fstream>
                extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
                    std::ofstream out("${marker.absolutePath}");
                    out << std::string("$MARKER") + "/C++" << std::endl;
                    return JNI_VERSION_1_6;
                }
                """.trimIndent(),
            ),
            output = objectFile,
            language = NativeLanguage.CXX,
            arguments = listOf("-fPIC"),
            workingDir = work,
        ) { output.appendLine(it.text) }
        assertSucceeded("c++ compile", compiled, output)

        val linked = clang.link(
            objects = listOf(objectFile),
            output = library,
            language = NativeLanguage.CXX,
            workingDir = work,
        ) { output.appendLine(it.text) }
        assertSucceeded("c++ link", linked, output)

        System.load(File(context.filesDir, "toolchain/usr/lib/libc++_shared.so").absolutePath)
        System.load(library.absolutePath)

        assertTrue("the C++ library loaded but its code never ran", marker.isFile)
        assertEquals("$MARKER/C++", marker.readText().trim())
    }

    /**
     * A compile error reaches the caller as output, not as a thrown exception
     * or a bare exit code. The editor shows these; a build that failed silently
     * would be worse than one that failed loudly.
     */
    @Test
    fun a_broken_source_reports_what_clang_said() = runTest {
        val output = StringBuilder()

        val result = clang.compile(
            source = source("broken.c", "int missing_semicolon(void) { return 1 }\n"),
            output = File(work, "broken.o"),
            workingDir = work,
        ) { output.appendLine(it.text) }

        assertTrue("expected a failed compile", result is AppResult.Success)
        assertTrue("expected a non-zero exit", !(result as AppResult.Success).value.isSuccess)
        assertTrue("clang's diagnostic did not reach the caller: $output", "error" in output.toString())
    }

    private fun assertSucceeded(what: String, result: AppResult<ToolResult>, output: StringBuilder) {
        assertTrue("$what could not start: $result\n$output", result is AppResult.Success)
        assertTrue(
            "$what failed:\n$output",
            (result as AppResult.Success).value.isSuccess,
        )
    }

    private companion object {
        const val ARCHIVE = "toolchain.tar"
        const val MARKER = "AIDE-OS-NATIVE-BUILT-THIS"
    }
}
