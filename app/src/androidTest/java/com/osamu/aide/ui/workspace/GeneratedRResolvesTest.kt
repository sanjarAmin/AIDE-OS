package com.osamu.aide.ui.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.engine.fast.NativeToolchainProvider
import com.osamu.aide.toolchain.manager.ToolchainManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `R` resolves once aapt2 has generated it.
 *
 * A freshly created project references `R.string.greeting`, and until a build
 * has run there is no `R.java` -- so "package R does not exist" is correct and
 * every test that asserts on diagnostics has to tolerate it.
 * `LanguageServices` puts the build's `generated/java` on javac's source path
 * precisely so the error goes away after one build, and `AppModule` keeps one
 * definition of that root with a comment saying the two must agree.
 *
 * **Nothing checked that they do.** Driven on a device: build a template
 * project, watch `R.java` appear under `cache/builds/<name>/generated/java`,
 * and the red underline on `R.string.greeting` stays -- through a re-analysis,
 * and through a restart of the app that builds a fresh compiler.
 */
@RunWith(AndroidJUnit4::class)
class GeneratedRResolvesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dispatchers = DefaultDispatcherProvider()

    private lateinit var services: LanguageServices
    private lateinit var buildOutput: File
    private lateinit var externalProjects: File
    private lateinit var project: File
    private lateinit var source: File

    @Before
    fun setUp() {
        buildOutput = File(context.cacheDir, "builds-generated-r").apply { deleteRecursively() }
        externalProjects = File(context.getExternalFilesDir(null), "r-test-projects")
            .apply { deleteRecursively() }
        services = LanguageServices(
            native = NativeToolchainProvider(context, dispatchers),
            toolchain = ToolchainManager(context, dispatchers),
            dispatchers = dispatchers,
            buildOutputRoot = buildOutput,
        )
        project = File(externalProjects, "Rtwo").apply {
            deleteRecursively()
            File(this, "src/main/java/com/example/rtwo").mkdirs()
        }
        source = File(project, "src/main/java/com/example/rtwo/MainActivity.java")
        source.writeText(
            """
            package com.example.rtwo;

            import android.app.Activity;
            import android.os.Bundle;
            import android.widget.TextView;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    TextView text = new TextView(this);
                    text.setText(R.string.greeting);
                    setContentView(text);
                }
            }
            """.trimIndent(),
        )

        // What aapt2 writes, where ProjectBuilder writes it.
        val generated = File(buildOutput, "${project.name}/generated/java/com/example/rtwo")
        generated.mkdirs()
        File(generated, "R.java").writeText(
            """
            package com.example.rtwo;

            public final class R {
                public static final class string {
                    public static final int greeting=0x7f010000;
                }
            }
            """.trimIndent(),
        )
    }

    @After
    fun tearDown() {
        services.release()
        externalProjects.deleteRecursively()
        buildOutput.deleteRecursively()
    }

    /**
     * The control: with no generated `R.java`, javac must say so.
     *
     * Without this the passing half proves nothing -- a service that returned
     * an empty list for everything would satisfy it.
     */
    @Test
    fun R_is_unresolved_until_something_generates_it() {
        assumeTrue(
            "no android.jar installed; javac cannot run at all",
            ToolchainManager(context, dispatchers).androidJar() != null,
        )
        File(buildOutput, "${project.name}/generated/java").deleteRecursively()

        val diagnostics = runBlocking {
            services.serviceFor(source, project)!!.diagnostics(source, source.readText())
        }

        assertTrue(
            "an unbuilt project reported no problem with R at all: " +
                diagnostics.joinToString("\n") { it.describe() },
            diagnostics.any { "R" in it.message },
        )
    }

    @Test
    fun a_generated_R_on_the_source_path_resolves() {
        assumeTrue(
            "no android.jar installed; javac cannot run at all",
            ToolchainManager(context, dispatchers).androidJar() != null,
        )

        val diagnostics = runBlocking {
            services.serviceFor(source, project)!!.diagnostics(source, source.readText())
        }

        assertTrue(
            "R did not resolve from the generated source path: " +
                diagnostics.joinToString("\n") { it.describe() },
            diagnostics.none { "R" in it.message && "does not exist" in it.message },
        )
    }
}
