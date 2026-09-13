package com.osamu.aide.engine.fast

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.engine.api.BuildRequest
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.awaitResult
import com.osamu.aide.toolchain.nativetools.NativeTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * The debugger, built into an app.
 *
 * Spike R15 found that a debuggable process may attach ART's own `libjdwp.so`
 * to itself and listen on a socket, which makes the debuggee half of a debugger
 * something the *build* provides rather than a permission the IDE asks for.
 * [DebugAgent] is that; this is what says it reaches the APK.
 *
 * **Asserted against the built APK, not the workspace.** The generated provider
 * is written into a merged manifest that aapt2 then reads, links and rewrites
 * into binary XML; every one of those steps can drop an element, and a test
 * that reads the intermediate file would pass while the app shipped without it.
 * So the manifest here is read back out of the APK with aapt2's own dumper, and
 * the class is looked for in the dex that was actually packaged.
 *
 * **The negative case is the important one.** Carrying a debugger means adding
 * `INTERNET` to someone's app and opening a port on it; an ordinary debug build
 * must contain neither, and that is the assertion most worth keeping honest.
 */
@RunWith(AndroidJUnit4::class)
class DebugAgentTest {

    private lateinit var fixture: EngineTestFixture

    @Before
    fun setUp() {
        fixture = EngineTestFixture("debug-agent-test")
        fixture.assumeAapt2Supported()
    }

    private fun build(
        debuggable: Boolean = true,
        debugPort: Int? = null,
        extraSource: Pair<String, String>? = null,
    ): BuildResult {
        val project = fixture.project()
        extraSource?.let { (path, text) ->
            File(project.rootDir, path).apply { parentFile?.mkdirs() }.writeText(text)
        }
        val engine = FastBuildSystem(
            runner = fixture.runner,
            platform = fixture.platform,
            dispatchers = DefaultDispatcherProvider(),
        )
        return runBlocking {
            engine.build(
                BuildRequest(
                    project = project,
                    outputDir = File(fixture.workDir, "out-$debuggable-$debugPort"),
                    debuggable = debuggable,
                    debugPort = debugPort,
                ),
            ).awaitResult()
        }
    }

    /** A class with a distinctively named local, so the dex can be searched. */
    private fun namedLocalSource() = "src/main/java/com/example/demo/Named.java" to
        """
        package com.example.demo;
        public final class Named {
            public int compute(int startingPoint) {
                int accumulatedTotal = startingPoint * 2;
                return accumulatedTotal;
            }
        }
        """.trimIndent()

    /** The APK's manifest, as text, straight out of aapt2. */
    private fun manifestOf(apk: File): String = runBlocking {
        val output = StringBuilder()
        val result = fixture.runner.run(
            tool = NativeTool.AAPT2,
            args = listOf("dump", "xmltree", "--file", "AndroidManifest.xml", apk.absolutePath),
            onLine = { output.append(it.text).append('\n') },
        )
        assertTrue("aapt2 could not read the built APK: $result", output.isNotEmpty())
        output.toString()
    }

    /**
     * Whether the packaged dex mentions a class.
     *
     * A string search rather than a dex parse: every class name appears in the
     * string table verbatim, and the question here is only whether the
     * generated source was compiled and shipped.
     */
    private fun dexContains(apk: File, descriptor: String): Boolean = ZipFile(apk).use { zip ->
        zip.entries().asSequence()
            .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
            .any { entry ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                String(bytes, Charsets.ISO_8859_1).contains(descriptor)
            }
    }

    @Test
    fun a_build_asked_for_a_debugger_ships_one() {
        val result = build(debugPort = PORT)
        assertTrue("build failed: $result", result is BuildResult.Success)
        val apk = (result as BuildResult.Success).apk

        val manifest = manifestOf(apk)
        assertTrue(
            "the debugger's provider is not in the built manifest:\n$manifest",
            manifest.contains(PROVIDER_CLASS),
        )
        assertTrue(
            "the built app cannot open a socket, so the agent will exit(2):\n$manifest",
            manifest.contains("android.permission.INTERNET"),
        )
        assertTrue(
            "the generated provider was not compiled into the APK",
            dexContains(apk, PROVIDER_DESCRIPTOR),
        )
        assertTrue(
            "the agent is listening on the wrong port, or on none",
            dexContains(apk, "127.0.0.1:$PORT"),
        )
    }

    /**
     * An ordinary debug build carries neither, which is the whole of the
     * opt-in.
     */
    @Test
    fun a_build_that_did_not_ask_for_one_carries_nothing() {
        val result = build()
        assertTrue("build failed: $result", result is BuildResult.Success)
        val apk = (result as BuildResult.Success).apk

        val manifest = manifestOf(apk)
        assertFalse(
            "a debugger was built into an app that did not ask for one",
            manifest.contains(PROVIDER_CLASS),
        )
        assertFalse(
            "INTERNET was added to an app that did not ask for it:\n$manifest",
            manifest.contains("android.permission.INTERNET"),
        )
        assertFalse(
            "the debugger's class was compiled into an app that did not ask for it",
            dexContains(apk, PROVIDER_DESCRIPTOR),
        )
    }

    /**
     * A release build is refused rather than quietly built without one.
     *
     * The platform would not let the agent attach anyway -- a release build is
     * not debuggable -- so the failure this prevents is an app shipped with a
     * permission and a provider that can never do anything.
     */
    @Test
    fun a_release_build_will_not_carry_a_debugger() {
        val result = build(debuggable = false, debugPort = PORT)
        assertTrue("expected a refusal, got: $result", result is BuildResult.Failure)
        assertTrue(
            "the refusal does not say why: ${(result as BuildResult.Failure).message}",
            result.message.contains("release", ignoreCase = true),
        )
    }

    /**
     * The user's own manifest is not written to.
     *
     * `ManifestMerger.merge` returns the *project's own* file when there are no
     * libraries to merge, and the template has none -- so the naive injection
     * edits a file in the user's source tree and leaves the debugger in their
     * project for good.
     */
    @Test
    fun the_projects_own_manifest_is_left_alone() {
        val project = fixture.project()
        val manifest = File(project.rootDir, "src/main/AndroidManifest.xml")
        val before = manifest.readText()

        runBlocking {
            FastBuildSystem(
                runner = fixture.runner,
                platform = fixture.platform,
                dispatchers = DefaultDispatcherProvider(),
            ).build(
                BuildRequest(
                    project = project,
                    outputDir = File(fixture.workDir, "out-untouched"),
                    debugPort = PORT,
                ),
            ).awaitResult()
        }

        assertTrue(
            "the build wrote the debugger into the user's own manifest",
            manifest.readText() == before,
        )
    }

    /**
     * A debug build names its locals, which is what a debugger reads.
     *
     * **ECJ emits no local variable table unless told.** Its default is
     * `-g:lines,source`, which is enough to stop on a line and show a stack --
     * so a debugger looks like it works, right up to the point where every
     * variable comes back with an empty name. Caught by attaching `:debugger`
     * to an app this pipeline built; asserted here against the dex, which
     * covers both halves at once, since D8 could drop what ECJ emitted.
     */
    @Test
    fun a_debug_build_keeps_the_names_of_locals() {
        val result = build(debugPort = PORT, extraSource = namedLocalSource())
        assertTrue("build failed: $result", result is BuildResult.Success)
        val apk = (result as BuildResult.Success).apk

        assertTrue(
            "the parameter's name did not survive into the dex",
            dexContains(apk, "startingPoint"),
        )
        assertTrue(
            "the local's name did not survive into the dex",
            dexContains(apk, "accumulatedTotal"),
        )
    }

    /**
     * A release build keeps line numbers and drops the names.
     *
     * Line numbers are what makes a crash report readable and are worth their
     * bytes in a shipped app; variable names are not, and are a small
     * disclosure. This is also the assertion that would catch `-g` being
     * applied unconditionally the next time this stage is edited.
     */
    @Test
    fun a_release_build_drops_the_names_of_locals() {
        val keys = ReleaseKeystoreStore(fixture.context)
        keys.generate("release-secret".toCharArray(), commonName = "Debug Info Test", remember = true)
        try {
            val result = FastBuildSystem(
                runner = fixture.runner,
                platform = fixture.platform,
                dispatchers = DefaultDispatcherProvider(),
                releaseKeys = keys,
            ).let { engine ->
                val project = fixture.project()
                namedLocalSource().let { (path, text) ->
                    File(project.rootDir, path).apply { parentFile?.mkdirs() }.writeText(text)
                }
                runBlocking {
                    engine.build(
                        BuildRequest(
                            project = project,
                            outputDir = File(fixture.workDir, "out-release-names"),
                            debuggable = false,
                        ),
                    ).awaitResult()
                }
            }
            assertTrue("build failed: $result", result is BuildResult.Success)
            val apk = (result as BuildResult.Success).apk
            assertFalse(
                "a release build shipped its local variable names",
                dexContains(apk, "accumulatedTotal"),
            )
        } finally {
            keys.remove()
        }
    }

    private companion object {
        const val PORT = 8721
        const val PROVIDER_CLASS = "aide.debug.AideDebugAgent"
        const val PROVIDER_DESCRIPTOR = "Laide/debug/AideDebugAgent;"
    }
}
