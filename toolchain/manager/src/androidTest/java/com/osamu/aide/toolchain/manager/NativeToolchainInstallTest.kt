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
import java.nio.file.Files

/**
 * Installs the real C/C++ toolchain, from the real release, onto a real device.
 *
 * The unit tests cover extraction against a handful of entries; this is the
 * only thing that covers it at the scale it actually runs at -- a 152 MiB
 * download unpacking to 551 MB and nearly 10,000 entries -- and against the
 * archive users will get rather than one built in the test.
 *
 * It is deliberately not a fixture. The Android platform component is tested
 * the same way, for the same reason: a pinned URL and checksum that nothing
 * ever fetches is a pin that can rot without anyone noticing.
 *
 * Slow by nature. It downloads.
 */
@RunWith(AndroidJUnit4::class)
class NativeToolchainInstallTest {

    private lateinit var storage: ToolchainStorage
    private lateinit var installer: ComponentInstaller
    private var component: ToolchainComponent? = null

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "toolchains-under-test").apply { mkdirs() }
        storage = ToolchainStorage(root)
        installer = ComponentInstaller(storage, SdkLicense(root), DefaultDispatcherProvider())

        component = ToolchainComponent.nativeToolchain(Build.SUPPORTED_ABIS.first())
        // A 32-bit device is out of scope rather than broken: the toolchain is
        // not built for those ABIs, and `nativeToolchain` says so with null.
        assumeNotNull(
            "no C/C++ toolchain for ${Build.SUPPORTED_ABIS.first()}",
            component,
        )
    }

    @Test
    fun it_installs_and_the_tree_is_usable() {
        val component = requireNotNull(component)
        storage.remove(component)

        val progress = runBlocking { installer.install(component).toList() }

        val last = progress.last()
        assertTrue("install did not finish: $last", last is InstallProgress.Installed)

        val root = storage.directoryFor(component)
        // The three properties a toolchain stops being one without.
        assertTrue("the compiler is missing", File(root, "usr/bin/clang-21").canExecute())
        assertTrue(
            "clang++ was flattened into a copy, which silently compiles C",
            Files.isSymbolicLink(File(root, "usr/bin/clang++").toPath()),
        )
        assertTrue(
            "the sysroot headers are missing",
            File(root, "usr/include/stdio.h").isFile,
        )
    }

    /**
     * That no licence prompt stands in front of it.
     *
     * The Android platform requires accepting Google's terms; LLVM is
     * Apache-2.0 and requiring the same acceptance would be asking the user to
     * agree to something irrelevant in order to compile C.
     */
    @Test
    fun it_needs_no_android_sdk_licence() {
        assertEquals(false, requireNotNull(component).requiresSdkLicense)
    }

    /**
     * Installing twice does not download twice. The second call has to see the
     * marker and return immediately -- at 152 MiB a repeat is not a minor
     * inefficiency.
     */
    @Test
    fun a_second_install_is_a_no_op() {
        val component = requireNotNull(component)
        runBlocking { installer.install(component).toList() }

        val progress = runBlocking { installer.install(component).toList() }

        assertEquals(1, progress.size)
        assertTrue(progress.single() is InstallProgress.Installed)
    }
}
