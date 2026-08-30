package com.osamu.aide.toolchain.manager

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Installs the two runtimes the Gradle engine needs, from their real URLs.
 *
 * The unit tests cover extraction against fixtures; this covers it at the size
 * it happens — 142 MiB unpacking to 303 MB for the JDK, 145 MiB to 175 MB for
 * Gradle — and against the archives users will actually get. A pinned URL and
 * checksum that nothing ever fetches is a pin that can rot unnoticed, which is
 * why the Android platform component is tested the same way.
 *
 * Slow by nature: it downloads.
 */
@RunWith(AndroidJUnit4::class)
class GradleRuntimeInstallTest {

    private lateinit var storage: ToolchainStorage
    private lateinit var installer: ComponentInstaller

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "runtimes-under-test").apply { mkdirs() }
        storage = ToolchainStorage(root)
        installer = ComponentInstaller(storage, SdkLicense(root), DefaultDispatcherProvider())
    }

    private fun install(component: ToolchainComponent): InstallProgress =
        runBlocking { installer.install(component).toList().last() }

    /**
     * The JDK, and the three files that have to be there for it to be usable.
     *
     * `libjvm.so` is what the launcher loads; `bin/java` and `lib/jspawnhelper`
     * are the ones `JvmToolchain.prepare()` replaces. A JDK missing any of them
     * installs and then fails somewhere that names none of this.
     */
    @Test
    fun the_jdk_installs_and_is_complete() {
        val component = ToolchainComponent.openJdk(Build.SUPPORTED_ABIS.first())
        assumeNotNull("no JDK for ${Build.SUPPORTED_ABIS.first()}", component)
        storage.remove(component!!)

        val last = install(component)

        assertTrue("install did not finish: $last", last is InstallProgress.Installed)
        val jdk = File(storage.directoryFor(component), "lib/jvm/java-21-openjdk")
        assertTrue("no server VM", File(jdk, "lib/server/libjvm.so").isFile)
        assertTrue("no java launcher to replace", File(jdk, "bin/java").isFile)
        assertTrue("no spawn helper to replace", File(jdk, "lib/jspawnhelper").isFile)
        // libjvm is linked against Termux's own libraries, two levels up.
        assertTrue(
            "the support libraries are missing",
            File(storage.directoryFor(component), "lib/libandroid-shmem.so").isFile,
        )
    }

    /**
     * Gradle, from Gradle's own servers rather than a copy of ours.
     *
     * It runs unmodified on any JVM and its publisher checksums it, so
     * re-hosting would add a second thing to keep current and nothing else.
     */
    @Test
    fun gradle_installs_from_its_publisher() {
        val component = ToolchainComponent.GRADLE
        storage.remove(component)

        val last = install(component)

        assertTrue("install did not finish: $last", last is InstallProgress.Installed)
        val launcher = File(
            storage.directoryFor(component),
            "gradle-9.7.1/lib/gradle-launcher-9.7.1.jar",
        )
        assertTrue("no launcher jar, so the engine has nothing to run", launcher.isFile)
        assertTrue("the URL is not Gradle's", component.archiveUrl.startsWith("https://services.gradle.org/"))
    }

    /** Neither asks for Google's SDK terms; neither is Google's. */
    @Test
    fun neither_runtime_needs_the_android_sdk_licence() {
        assertEquals(false, ToolchainComponent.GRADLE.requiresSdkLicense)
        ToolchainComponent.openJdk(Build.SUPPORTED_ABIS.first())?.let {
            assertEquals(false, it.requiresSdkLicense)
        }
    }

    /** A second install is a no-op, not a second 142 MiB download. */
    @Test
    fun installing_twice_downloads_once() {
        val component = ToolchainComponent.GRADLE
        install(component)

        val progress = runBlocking { installer.install(component).toList() }

        assertEquals(1, progress.size)
        assertTrue(progress.single() is InstallProgress.Installed)
    }
}
