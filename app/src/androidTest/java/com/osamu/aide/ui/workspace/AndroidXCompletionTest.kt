package com.osamu.aide.ui.workspace

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.engine.fast.NativeToolchainProvider
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.engine.deps.DependencyResolver
import com.osamu.aide.toolchain.manager.ToolchainManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * M3's acceptance test, in the words the plan actually uses.
 *
 * > Completion on AndroidX types < 200ms
 *
 * Every earlier measurement was against `android.jar` alone, because until
 * `:engine:deps` existed nothing could put an AndroidX artifact on a classpath.
 * This is the first time the sentence can be answered as written.
 *
 * Reaches the network on a cold cache, and is skipped rather than failed when
 * the platform is not installed -- there is nothing to complete against without
 * it, and a red test would say the wrong thing.
 */
@RunWith(AndroidJUnit4::class)
class AndroidXCompletionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dispatchers = DefaultDispatcherProvider()

    private lateinit var projectRoot: File
    private lateinit var services: LanguageServices
    private lateinit var dependencies: ProjectDependencies

    @Before
    fun setUp() {
        val platform = File(context.filesDir, "toolchains/platforms-android-36/android.jar")
        if (!platform.isFile) {
            platform.parentFile?.mkdirs()
            runCatching {
                InstrumentationRegistry.getInstrumentation().context.assets.open("android.jar")
                    .use { input -> platform.outputStream().use { input.copyTo(it) } }
            }
        }
        assumeTrue("no android.jar staged", platform.isFile)

        projectRoot = File(context.cacheDir, "androidx-project").apply {
            deleteRecursively()
            File(this, "src/main/java/com/example").mkdirs()
        }
        services = LanguageServices(
            native = NativeToolchainProvider(context, DefaultDispatcherProvider()),
            toolchain = ToolchainManager(context, dispatchers),
            dispatchers = dispatchers,
            buildOutputRoot = File(context.cacheDir, "builds-test"),
        )
        dependencies = ProjectDependencies(
            DependencyResolver(File(context.cacheDir, "maven"), dispatchers),
        )
    }

    private fun project() = Project(
        name = "AndroidX Demo",
        rootDir = projectRoot,
        applicationId = "com.example.androidx",
        language = SourceLanguage.JAVA,
        engine = BuildEngine.FAST,
        lastOpenedAt = 0L,
        dependencies = listOf("androidx.appcompat:appcompat:1.7.0"),
    )

    private fun sourceFile() =
        File(projectRoot, "src/main/java/com/example/Main.java").apply { parentFile?.mkdirs() }

    @Test
    fun completion_on_an_androidx_type_answers_inside_the_budget() = runBlocking {
        val classpath = dependencies.classpathFor(project())
        assumeTrue("dependencies did not resolve; no network?", classpath.isNotEmpty())
        Log.i(TAG, "classpath: ${classpath.size} jars")

        val service = requireNotNull(services.forProject(projectRoot, classpath)) {
            "no language service despite an installed platform"
        }

        val text = SOURCE.replace(CURSOR, "")
        val offset = SOURCE.indexOf(CURSOR)

        // The AndroidX type has to resolve at all before its latency means
        // anything: an unresolved receiver completes to an empty list quickly.
        val proposals = service.complete(sourceFile(), text, offset).map { it.label }
        Log.i(TAG, "proposals: ${proposals.take(12)}")
        assertTrue(
            "AppCompatActivity did not resolve from the dependency classpath",
            "getSupportActionBar" in proposals,
        )
        assertTrue("expected inherited platform members too", "getSystemService" in proposals)

        val timings = (0 until 6).map {
            measureTimeMillis { service.complete(sourceFile(), text, offset) }
        }
        val warmRuns = timings.drop(1).sorted()
        val warm = warmRuns[warmRuns.size / 2]
        Log.i(TAG, "AndroidX completion latency: $timings (warm median $warm ms)")

        assertTrue(
            "warm completion on an AndroidX type was $warm ms against a 200 ms budget; $timings",
            warm < 200,
        )
    }

    private companion object {
        const val TAG = "AndroidXCompletion"
        const val CURSOR = "/*^*/"

        val SOURCE = """
            package com.example;

            import androidx.appcompat.app.AppCompatActivity;
            import android.os.Bundle;

            public class Main extends AppCompatActivity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    this./*^*/
                }
            }
        """.trimIndent()
    }
}
