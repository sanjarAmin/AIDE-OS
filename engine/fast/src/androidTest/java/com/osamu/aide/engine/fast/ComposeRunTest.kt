package com.osamu.aide.engine.fast

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.fs.ProjectLayout
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.BuildRequest
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.DependencyInputs
import com.osamu.aide.engine.deps.Coordinate
import com.osamu.aide.engine.deps.DependencyResolver
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * **M6's second half: a Compose app the engine built actually runs.**
 *
 * `ComposeBuildTest` proves the compiler half — that `@Composable` really was
 * transformed, asserted on bytecode because a build with the plugin inert still
 * succeeds. This is the other word in the acceptance criterion, and it is a
 * different claim entirely: an APK can compile, dex, sign, install and launch
 * and still show nothing.
 *
 * **The assertion is made from outside the app.** The text is read back out of
 * the launched process's accessibility tree, which is where Compose publishes
 * its semantics — so a match means the composition ran, laid out and drew.
 * Asserting that the process was alive, or that the Activity resumed, would
 * pass on an app whose window is empty.
 *
 * Worth knowing what this exercises that no earlier test does: the engine
 * performs **no manifest merging**. `ResolvedDependency.manifest` is extracted
 * by `AarExtractor` and never used; `ResourceStage` hands aapt2 the project's
 * own manifest alone. Every `<provider>` an AndroidX AAR contributes — the
 * `androidx.startup` initializers among them — is therefore absent from the
 * built APK. Whether that is fatal is not answerable by reading the code, and
 * a build-only test cannot see it at all. See `FINDINGS.md` section 12.
 */
@RunWith(AndroidJUnit4::class)
class ComposeRunTest {

    private lateinit var fixture: EngineTestFixture
    private lateinit var kotlin: KotlinCompiler
    private lateinit var resolver: DependencyResolver

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun setUp() {
        fixture = EngineTestFixture("compose-run-test")
        fixture.assumeAapt2Supported()

        val archive = stage("kotlinc.jar")
        val stdlib = stage("kotlin-stdlib.jar")
        assumeTrue("kotlinc.jar is not staged; see tools/kotlinc/FINDINGS.md", archive != null)
        assumeTrue("kotlin-stdlib.jar is not staged", stdlib != null)

        kotlin = KotlinCompiler(
            KotlinToolchain(archive!!, stdlib!!),
            File(fixture.context.cacheDir, "kotlin-host"),
        )
        // Shared cache with the other suites; re-downloading Compose per class
        // would make this unrunnable.
        resolver = DependencyResolver(
            File(fixture.context.cacheDir, "maven"),
            DefaultDispatcherProvider(),
        )
        shell("pm uninstall $PACKAGE")
    }

    @After
    fun tearDown() {
        shell("am force-stop $PACKAGE")
        shell("pm uninstall $PACKAGE")
        shell("rm -f $STAGED")
    }

    private fun stage(name: String): File? {
        val target = File(fixture.workDir, name)
        if (target.isFile) return target
        return runCatching {
            fixture.context.assets.open(name).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            // API 29+ refuses to load a dex the app can still write to.
            target.setReadOnly()
            target
        }.getOrNull()
    }

    @Test
    fun a_compose_app_builds_installs_and_draws_on_screen() = runTest(timeout = 20.minutes) {
        val resolved = when (
            val result = resolver.resolve(
                listOf(
                    // activity-compose brings ComponentActivity and setContent;
                    // foundation brings BasicText. Deliberately not material3 --
                    // a theme's worth of extra resources would make an aapt2
                    // failure and a Compose failure hard to tell apart.
                    //
                    // **Contemporaneous versions, and that is a requirement
                    // rather than tidiness.** Resolution here is Maven's, which
                    // has no equivalent of the Gradle platform constraints the
                    // AndroidX BOM applies -- so a graph mixing eras keeps
                    // whatever each POM happened to name and lands two copies
                    // of a class that moved between modules.
                    // `engine/deps/FINDINGS.md` section 1.
                    requireNotNull(Coordinate.parse("androidx.activity:activity-compose:1.13.0")),
                    requireNotNull(
                        Coordinate.parse("androidx.compose.foundation:foundation-android:1.12.0"),
                    ),
                ),
            )
        ) {
            is AppResult.Success -> result.value
            is AppResult.Failure ->
                throw AssertionError("resolution failed: ${result.error.message}")
        }
        assumeTrue("dependencies did not resolve; no network?", resolved.dependencies.isNotEmpty())
        // Kept as a string rather than only logged, because the test clears
        // logcat before launching the app: by the time anything below fails,
        // the log line describing the graph that produced it is gone.
        val graph = resolved.dependencies.joinToString("\n") { "  ${it.coordinate}" }
        Log.i(TAG, "resolved ${resolved.dependencies.size} artifacts:\n$graph")

        // An artifact the resolver could not fetch is reported, not thrown:
        // one missing POM should not lose the other sixty. That is right for
        // the app and wrong for this test, where a hole in the classpath
        // becomes a NoClassDefFoundError twenty minutes later with nothing
        // connecting the two.
        assertTrue(
            "some dependencies did not resolve: ${resolved.unresolved}",
            resolved.unresolved.isEmpty(),
        )

        // Asserted here rather than left to the launch, because an empty list
        // is not a build failure: the APK links, installs and starts, and only
        // dies when a library first touches its own R class. The parse behind
        // this list is a regex over a manifest, and a regex that matches
        // nothing looks exactly like a graph with no Android libraries in it.
        assertTrue(
            "no library packages were parsed out of the resolved AARs: " +
                resolved.dependencies.joinToString { it.coordinate.toString() },
            "androidx.customview.poolingcontainer" in resolved.libraryPackages,
        )

        val project = fixture.project(applicationId = PACKAGE, language = SourceLanguage.KOTLIN)
        val layout = ProjectLayout.of(project)

        // Replaces the template's framework-only Activity. The marker is read
        // back off the screen, so it has to be something no system UI would
        // ever show.
        File(layout.javaDir, "com/example/composerun/MainActivity.kt").apply {
            parentFile?.mkdirs()
            writeText(
                """
                package $PACKAGE

                import android.os.Bundle
                import androidx.activity.ComponentActivity
                import androidx.activity.compose.setContent
                import androidx.compose.foundation.text.BasicText

                class MainActivity : ComponentActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContent { BasicText("$MARKER") }
                    }
                }
                """.trimIndent(),
            )
        }

        val outputDir = File(fixture.workDir, "build")
        val events = FastBuildSystem(
            fixture.runner,
            fixture.platform,
            DefaultDispatcherProvider(),
            kotlin,
        ).build(
            BuildRequest(
                project = project,
                outputDir = outputDir,
                dependencies = DependencyInputs(
                    classpath = resolved.compileClasspath,
                    resourceDirectories = resolved.resourceDirectories,
                    libraryPackages = resolved.libraryPackages,
                ),
            ),
        ).toList()

        val result = (events.last() as BuildEvent.Finished).result
        assertTrue(
            "build failed: ${(result as? BuildResult.Failure)?.message}\n" +
                result.diagnostics.joinToString("\n") { it.describe() },
            result is BuildResult.Success,
        )
        val apk = (result as BuildResult.Success).apk
        Log.i(TAG, "built ${apk.name}: ${apk.length()} bytes")

        install(apk)

        // Cleared immediately before launch so anything reported below is this
        // run's, not a previous one's.
        shell("logcat -c")
        val launch = shell("am start -W -n $PACKAGE/.MainActivity")
        assertTrue("am start refused: $launch", "Error" !in launch)

        val drew = device.wait(Until.hasObject(By.textContains(MARKER)), DRAW_TIMEOUT_MS)

        assertTrue(
            "the Compose app installed and launched but never drew.\n" +
                "am start said:\n$launch\n\n" +
                "logcat for $PACKAGE:\n${crashLog()}\n\n" +
                "resolved graph:\n$graph",
            drew,
        )
    }

    /**
     * Two hops, and both are forced.
     *
     * The app cannot write `/data/local/tmp`, and the installer cannot read the
     * app's own storage -- `/storage` is FUSE-backed and SELinux denies
     * system_server any read of a fuse file. `FINDINGS.md` section 7.
     */
    private fun install(apk: File) {
        val staged = File(
            requireNotNull(fixture.context.getExternalFilesDir(null)) { "no external files dir" },
            "compose-run.apk",
        )
        apk.copyTo(staged, overwrite = true)
        shell("cp ${staged.absolutePath} $STAGED")

        val output = shell("pm install -t -r $STAGED")
        assertTrue("pm install refused the APK: $output", "Success" in output)
    }

    /**
     * Whatever the launched app said before it stopped saying anything.
     *
     * The failure this test exists to catch is a runtime one -- a missing class,
     * an absent `<provider>`, a resource that did not link -- and all of those
     * are invisible from the outside except here. Without it the assertion
     * would report only "it did not draw", which names no cause at all.
     */
    private fun crashLog(): String =
        shell("logcat -d -t 400 *:E").lines()
            .filter { PACKAGE in it || "AndroidRuntime" in it || "Compose" in it }
            .joinToString("\n")
            .ifBlank { "(nothing in logcat; the app may have drawn nothing at all)" }

    /**
     * Runs [command] as shell and returns everything it printed.
     *
     * `executeShellCommandRwe`, not `executeShellCommand`: the latter returns
     * stdout only and `pm install` reports refusals on stderr, so a failure
     * arrives as an empty string. Not wrapped in `sh -c` either -- UiAutomation
     * hands the string to `Runtime.exec`, which splits on whitespace and does
     * not honour quotes. `FINDINGS.md` section 7.
     */
    private fun shell(command: String): String {
        val streams = instrumentation.uiAutomation.executeShellCommandRwe(command)
        val (stdout, stdin, stderr) = streams
        stdin.close()
        return listOf(stdout, stderr).joinToString("") { descriptor ->
            descriptor.use {
                java.io.FileInputStream(it.fileDescriptor).use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                }
            }
        }.trim()
    }

    private operator fun <T> Array<T>.component3(): T = this[2]

    private companion object {
        const val TAG = "ComposeRun"
        const val PACKAGE = "com.example.composerun"
        const val STAGED = "/data/local/tmp/aide-compose-run.apk"

        /** Read back off the screen, so it must be text nothing else shows. */
        const val MARKER = "AIDE-OS-COMPOSE-DREW-THIS"

        /** Generous: a cold start of a freshly installed app on an emulator. */
        const val DRAW_TIMEOUT_MS = 30_000L
    }
}
