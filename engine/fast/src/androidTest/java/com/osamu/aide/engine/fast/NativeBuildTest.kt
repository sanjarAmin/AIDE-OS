package com.osamu.aide.engine.fast

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.fs.ProjectLayout
import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.BuildRequest
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.BuildStage
import com.osamu.aide.toolchain.nativetools.ClangToolchain
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.minutes

/**
 * **M7's acceptance test: a JNI project with a native `.so` builds.**
 *
 * The APK containing an entry named `lib/<abi>/libdemo.so` is not enough to
 * believe, so the library is extracted from the built APK and loaded into this
 * process, and the marker its `JNI_OnLoad` writes is what the test asserts. A
 * `.so` that is corrupt, built for the wrong architecture, or linked against
 * something the device does not have would satisfy every weaker check and fail
 * on a user's phone instead.
 *
 * Skipped when the toolchain is not staged. Build one with
 * `tools/clang/fetch-toolchain.sh` and push it as `toolchain.tar` to this test
 * package's external files directory.
 */
@RunWith(AndroidJUnit4::class)
class NativeBuildTest {

    private lateinit var fixture: EngineTestFixture
    private lateinit var clang: ClangToolchain
    private val abi = Build.SUPPORTED_ABIS.first()

    @Before
    fun setUp() {
        fixture = EngineTestFixture("native-build-test")
        fixture.assumeAapt2Supported()

        val archive = File(fixture.context.getExternalFilesDir(null), ARCHIVE)
        val installed = File(fixture.context.filesDir, "toolchains/clang-21.1.8-$abi")
        assumeTrue(
            "no C/C++ toolchain: build one with tools/clang/fetch-toolchain.sh and " +
                "`adb push toolchain.tar ${fixture.context.getExternalFilesDir(null)}/$ARCHIVE`",
            archive.isFile || File(installed, "usr/bin/clang").exists(),
        )
        stage(archive, installed)

        // Through the provider, not by hand: this is also what pins the
        // install path convention the engine and `:toolchain:manager` have to
        // agree on without sharing a type.
        clang = requireNotNull(
            NativeToolchainProvider(fixture.context, DefaultDispatcherProvider(), abi).toolchain(),
        ) { "a toolchain was staged but is not usable" }
    }

    /** Unpacked by this process; see `tools/clang/FINDINGS.md` §4 for why. */
    private fun stage(archive: File, destination: File) {
        if (File(destination, "usr/bin/clang").exists()) return
        destination.mkdirs()
        ProcessBuilder("/system/bin/tar", "-xf", archive.absolutePath, "-C", destination.absolutePath)
            .redirectErrorStream(true)
            .start()
            .apply { inputStream.readBytes(); waitFor(10, TimeUnit.MINUTES) }
    }

    private fun engine(withClang: Boolean = true) = FastBuildSystem(
        fixture.runner,
        fixture.platform,
        DefaultDispatcherProvider(),
        null,
        if (withClang) clang else null,
    )

    /** Adds C sources to the template project, and Java that would load them. */
    private fun withNativeSources(project: com.osamu.aide.core.fs.Project, cpp: Boolean = false) {
        val layout = ProjectLayout.of(project)
        layout.nativeDir.mkdirs()
        val marker = File(fixture.workDir, "jni-ran.txt")
        if (cpp) {
            File(layout.nativeDir, "hello.cpp").writeText(
                """
                #include <jni.h>
                #include <string>
                #include <fstream>
                extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
                    std::ofstream out("${marker.absolutePath}");
                    out << std::string("$MARKER") << std::endl;
                    return JNI_VERSION_1_6;
                }
                """.trimIndent(),
            )
        } else {
            File(layout.nativeDir, "hello.c").writeText(
                """
                #include <jni.h>
                #include <stdio.h>
                JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
                    FILE *f = fopen("${marker.absolutePath}", "w");
                    if (f) { fprintf(f, "%s\n", "$MARKER"); fclose(f); }
                    return JNI_VERSION_1_6;
                }
                """.trimIndent(),
            )
        }
        marker.delete()
    }

    private fun markerFile() = File(fixture.workDir, "jni-ran.txt")

    private suspend fun build(project: com.osamu.aide.core.fs.Project, withClang: Boolean = true) =
        engine(withClang).build(
            BuildRequest(project = project, outputDir = File(fixture.workDir, "out")),
        ).toList()

    private fun apkOf(events: List<BuildEvent>): File {
        val finished = events.filterIsInstance<BuildEvent.Finished>().single()
        val result = finished.result
        assertTrue("the build failed: $result", result is BuildResult.Success)
        return (result as BuildResult.Success).apk
    }

    /**
     * Extracts one entry from the APK into app storage so it can be loaded.
     * External storage would not do: it refuses executable mappings.
     */
    private fun extract(apk: File, entryName: String, into: File): File {
        ZipFile(apk).use { zip ->
            val entry = requireNotNull(zip.getEntry(entryName)) { "$entryName is not in the APK" }
            zip.getInputStream(entry).use { input ->
                into.outputStream().use { input.copyTo(it) }
            }
        }
        return into
    }

    @Test
    fun a_jni_project_builds_and_its_library_runs() = runTest(timeout = 10.minutes) {
        val project = fixture.project()
        withNativeSources(project)

        val events = build(project)
        val apk = apkOf(events)

        // The other half of what FastBuildSystemTest pins. That test asserts a
        // Java-only build reports exactly six stages and no C/C++ one; without
        // this, making it pass by never reporting the stage at all would also
        // pass, and the user would watch a native build with no sign that the
        // longest part of it was running.
        assertTrue(
            "the build never reported a C/C++ stage",
            events.filterIsInstance<BuildEvent.StageStarted>()
                .any { it.stage == BuildStage.COMPILE_NATIVE },
        )

        val entry = "lib/$abi/libdemo.so"
        val names = ZipFile(apk).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue("the APK has no $entry, only: ${names.filter { it.startsWith("lib/") }}", entry in names)

        val library = extract(apk, entry, File(fixture.context.filesDir, "libdemo-from-apk.so"))
        Log.i(TAG, "built ${library.length()} bytes for $abi")

        System.load(library.absolutePath)

        assertTrue("the library was packaged but its code never ran", markerFile().isFile)
        assertEquals(MARKER, markerFile().readText().trim())
    }

    /**
     * C++ brings `libc++_shared.so` with it, and the APK has to carry it:
     * the driver links against it and Android does not provide it. Without
     * this the APK installs and dies at `System.loadLibrary`.
     */
    @Test
    fun a_cpp_project_packages_the_runtime_it_needs() = runTest(timeout = 10.minutes) {
        val project = fixture.project()
        withNativeSources(project, cpp = true)

        val apk = apkOf(build(project))

        val names = ZipFile(apk).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue("libc++_shared.so was not packaged: ${names.filter { it.startsWith("lib/") }}",
            "lib/$abi/libc++_shared.so" in names)

        val runtime = extract(apk, "lib/$abi/libc++_shared.so", File(fixture.context.filesDir, "libc++_shared.so"))
        val library = extract(apk, "lib/$abi/libdemo.so", File(fixture.context.filesDir, "libdemo-cpp.so"))
        System.load(runtime.absolutePath)
        System.load(library.absolutePath)

        assertEquals(MARKER, markerFile().readText().trim())
    }

    /**
     * Refused before anything runs, and by name.
     *
     * The alternative is worse than a slow failure: without the toolchain the
     * build would otherwise succeed and produce an APK with no library in it,
     * which installs and then dies at `System.loadLibrary` on the user's
     * device, with nothing pointing back at a missing download.
     */
    @Test
    fun a_native_project_without_the_toolchain_is_refused_by_name() = runTest(timeout = 5.minutes) {
        val project = fixture.project()
        withNativeSources(project)

        val events = build(project, withClang = false)

        val result = events.filterIsInstance<BuildEvent.Finished>().single().result
        assertTrue("expected a refusal, got: $result", result is BuildResult.Failure)
        val message = (result as BuildResult.Failure).message
        assertTrue("the refusal does not name the toolchain: $message", "C/C++ toolchain" in message)
    }

    private companion object {
        const val TAG = "NativeBuild"
        const val ARCHIVE = "toolchain.tar"
        const val MARKER = "AIDE-OS-JNI-BUILT-THIS"
    }
}
