package com.osamu.aide.ui.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.engine.fast.NativeToolchainProvider
import com.osamu.aide.lsp.java.JavaLanguageService
import com.osamu.aide.lsp.nativelsp.ClangdService
import com.osamu.aide.toolchain.manager.ComponentInstaller
import com.osamu.aide.toolchain.manager.InstallProgress
import com.osamu.aide.toolchain.manager.SdkLicense
import com.osamu.aide.toolchain.manager.ToolchainComponent
import com.osamu.aide.toolchain.manager.ToolchainManager
import com.osamu.aide.toolchain.manager.ToolchainStorage
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * That the editor is actually pointed at the right service.
 *
 * `:lsp:native`'s own tests prove clangd answers; they would pass just as well
 * with nothing in the app calling it. This is the seam that decides whether a
 * `.cpp` tab gets C++ intelligence or silence -- the same class of gap as
 * `engine/fast/FINDINGS.md` section 14, where an input wired only into a test
 * left the real path broken for a milestone.
 */
@RunWith(AndroidJUnit4::class)
class LanguageServiceRoutingTest {

    private lateinit var services: LanguageServices
    private lateinit var project: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dispatchers = DefaultDispatcherProvider()
        services = LanguageServices(
            native = NativeToolchainProvider(context, dispatchers),
            toolchain = ToolchainManager(context, dispatchers),
            dispatchers = dispatchers,
            buildOutputRoot = File(context.cacheDir, "builds-routing-test"),
        )
        project = File(context.cacheDir, "routing-project").apply {
            deleteRecursively()
            File(this, "src/main/java").mkdirs()
            File(this, "src/main/cpp").mkdirs()
        }
    }

    @After
    fun tearDown() {
        services.release()
    }

    /**
     * Java still routes to javac. The point of adding a second service is that
     * the first keeps working, and a routing bug that sent `.java` to clangd
     * would not throw -- it would just stop answering.
     */
    @Test
    fun java_goes_to_the_java_service() {
        val java = File(project, "src/main/java/Main.java")
        assumeTrue(
            "no android.jar installed; the Java service cannot be built",
            ToolchainManager(
                InstrumentationRegistry.getInstrumentation().targetContext,
                DefaultDispatcherProvider(),
            ).canBuild(),
        )

        val service = services.serviceFor(java, project)

        assertTrue("Java was not routed to the Java service: $service", service is JavaLanguageService)
    }

    /**
     * C++ routes to clangd when a toolchain is installed, and to nothing when
     * it is not. Both are correct; what would be wrong is a `.cpp` file
     * silently receiving javac's opinion.
     */
    @Test
    fun cpp_goes_to_clangd_when_a_toolchain_is_installed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = NativeToolchainProvider(context, DefaultDispatcherProvider()).toolchain() != null
        val cpp = File(project, "src/main/cpp/hello.cpp")

        val service = services.serviceFor(cpp, project)

        if (installed) {
            assertTrue("C++ was not routed to clangd: $service", service is ClangdService)
        } else {
            // No toolchain is the ordinary state. Silence is the right answer;
            // handing the file to javac would not be.
            assertNull("C++ was routed somewhere without a toolchain: $service", service)
        }
    }

    /**
     * The positive path, made deterministic by installing the toolchain first.
     *
     * The test above asserts whichever branch this device happens to be in,
     * which is honest but leaves the case that matters untested on a machine
     * that has never downloaded clang. This one installs it through the same
     * component installer the app uses, then asks the same question -- so a
     * wiring break cannot hide behind an absent toolchain.
     *
     * Slow: it downloads 152 MiB the first time and is a no-op after.
     */
    @Test
    fun installing_the_toolchain_makes_cpp_route_to_clangd() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dispatchers = DefaultDispatcherProvider()
        val component = ToolchainComponent.nativeToolchain(Build.SUPPORTED_ABIS.first())
        assumeNotNull("no C/C++ toolchain for this ABI", component)

        // **Its own root, not the app's.** Installing into the directory the
        // app really reads would leave 600 MB behind and make every sibling
        // test that asserts "no toolchain installed" fail, for a reason
        // written down in neither of them.
        val isolated = File(context.cacheDir, "routing-toolchains")
        val installed = runBlocking {
            ComponentInstaller(
                ToolchainStorage(isolated),
                SdkLicense(context.cacheDir),
                dispatchers,
            ).install(component!!).toList().last()
        }
        assumeTrue("the toolchain could not be installed: $installed", installed is InstallProgress.Installed)

        val isolatedServices = LanguageServices(
            native = NativeToolchainProvider(context, dispatchers, installRoot = isolated),
            toolchain = ToolchainManager(context, dispatchers),
            dispatchers = dispatchers,
            buildOutputRoot = File(context.cacheDir, "builds-routing-test"),
        )
        val service = try {
            isolatedServices.serviceFor(File(project, "src/main/cpp/hello.cpp"), project)
        } finally {
            isolatedServices.release()
        }

        assertTrue("C++ was not routed to clangd after installing it: $service", service is ClangdService)
    }

    /** A file no service claims gets none, rather than the nearest one. */
    @Test
    fun an_unclaimed_file_gets_no_service() {
        assertNull(services.serviceFor(File(project, "README.md"), project))
    }

    /**
     * Kotlin routes to nothing until its archives are installed.
     *
     * **Not the same claim as the test above, and it used to be.**
     * `build.gradle.kts` was listed there as a file nothing handles; now
     * `:lsp:kotlin` handles `.kt` and `.kts`, and the only reason this returns
     * null is that the Analysis API component is a separate download this
     * device has not got. Kept as its own test so that when it starts failing,
     * the reason is legible: the archives arrived, and Kotlin is being routed.
     *
     * The absence has to stay silent. A device below API 30 can never load
     * them, and most projects never need them; an error there would be a
     * feature reporting itself broken for working as designed.
     */
    @Test
    fun kotlin_gets_no_service_while_its_archives_are_not_installed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "the Kotlin analysis archives are installed, so this no longer holds",
            ToolchainManager(context, DefaultDispatcherProvider())
                .kotlinAnalysisArchives() == null,
        )

        assertNull(services.serviceFor(File(project, "src/main/kotlin/Main.kt"), project))
        assertNull(services.serviceFor(File(project, "build.gradle.kts"), project))
    }
}
