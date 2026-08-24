package com.osamu.aide.ui.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.editor.EditorCompletionKind
import com.osamu.aide.toolchain.manager.ToolchainManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The completion path as the editor actually calls it, inside the app's APK.
 *
 * `:editor` proves the plumbing with a fake source and `:lsp:java` proves the
 * proposals are right; neither proves the two work *here*. That distinction is
 * not academic -- the app is the only APK that contains both nb-javac and ECJ,
 * and the first version of this wiring returned an empty list for every request
 * because the two disagreed about `javax.lang.model.SourceVersion`. See
 * `engine/fast/FINDINGS.md` section 10.
 *
 * Calling the blocking [CompletionSource] method rather than the suspending
 * service is deliberate: that is the entry point sora uses.
 */
@RunWith(AndroidJUnit4::class)
class JavaCompletionSourceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var projectRoot: File
    private lateinit var services: LanguageServices

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

        projectRoot = File(context.cacheDir, "completion-project").apply {
            deleteRecursively()
            mkdirs()
        }
        services = LanguageServices(
            toolchain = ToolchainManager(context, DefaultDispatcherProvider()),
            dispatchers = DefaultDispatcherProvider(),
            buildOutputRoot = File(context.cacheDir, "builds-test"),
        )
    }

    private fun sourceFile() =
        File(projectRoot, "src/main/java/com/example/MainActivity.java")
            .apply { parentFile?.mkdirs() }

    @Test
    fun the_editor_gets_real_proposals_from_the_real_service() {
        val service = requireNotNull(services.forProject(projectRoot)) {
            "no language service; the platform is installed, so this is a wiring failure"
        }
        val source = JavaCompletionSource(service)

        val text = ACTIVITY.replace(CURSOR, "")
        val proposals = source.completionsAt(sourceFile(), text, ACTIVITY.indexOf(CURSOR))

        val labels = proposals.map { it.label }
        assertTrue("no proposals reached the editor: $labels", labels.isNotEmpty())
        assertTrue("expected an inherited platform member, got ${labels.take(20)}",
            "getSystemService" in labels)
        assertEquals(
            EditorCompletionKind.METHOD,
            proposals.first { it.label == "getSystemService" }.kind,
        )
    }

    /** The service must be the same instance, or every request pays a cold start. */
    @Test
    fun the_warm_service_is_reused_for_the_same_project() {
        val first = services.forProject(projectRoot)
        val second = services.forProject(projectRoot)
        assertTrue("a second service was built for the same project", first === second)
    }

    private companion object {
        const val CURSOR = "/*^*/"

        val ACTIVITY = """
            package com.example;

            import android.app.Activity;
            import android.os.Bundle;

            public class MainActivity extends Activity {
                @Override
                protected void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    this./*^*/
                }
            }
        """.trimIndent()
    }
}
