package com.osamu.aide.ui.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.ai.core.ProjectFiles
import com.osamu.aide.ai.core.ProjectToolset
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.engine.fast.NativeToolchainProvider
import com.osamu.aide.toolchain.manager.ToolchainManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Kotlin tools over a **real** `LanguageServices`, on a device.
 *
 * `KotlinToolsTest` covers the tool layer against a fake service: refusal
 * semantics, ordering, path handling. What it cannot cover is the seam — that
 * the real `LanguageServices` this is wired to behaves the way the fake
 * pretended. Twice this week "wired" and "works" came apart in exactly that
 * gap: a component that was defined, pinned and published but never installed,
 * and eighteen tests passing against a backend three commits old.
 *
 * **The case asserted here is the one most devices are in.** Kotlin
 * intelligence is two downloads; without them `serviceFor` returns null. A
 * `check_kotlin` that answered "No problems" in that state would tell the model
 * its broken code is fine — and to a model, that is indistinguishable from a
 * clean file. So the assertion is not that a refusal happens, it is that a
 * *false clean bill of health* never does.
 *
 * The happy path — real diagnostics from a real session — is
 * `:lsp:kotlin`'s eighteen instrumented tests. This is about what happens when
 * the thing they test is absent.
 */
@RunWith(AndroidJUnit4::class)
class KotlinToolsOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dispatchers = DefaultDispatcherProvider()

    private fun realServices() = LanguageServices(
        native = NativeToolchainProvider(context, dispatchers),
        toolchain = ToolchainManager(context, dispatchers),
        dispatchers = dispatchers,
        buildOutputRoot = File(context.cacheDir, "builds-kotlin-tools-test"),
    )

    @Test
    fun without_its_components_check_kotlin_refuses_instead_of_reporting_a_clean_file() =
        runBlocking {
            val services = realServices()
            val root = File(context.cacheDir, "kt-tools-${System.nanoTime()}").apply { mkdirs() }
            File(root, "Main.kt").writeText("fun broken(): String = 1")

            val project = Project(
                name = "Sample",
                rootDir = root,
                applicationId = "com.example.sample",
                language = SourceLanguage.KOTLIN,
                engine = BuildEngine.FAST,
                lastOpenedAt = 0L,
            )
            val toolset = ProjectToolset(
                ProjectFiles(root),
                kotlinTools(
                    serviceFor = { file -> services.serviceFor(file, root) },
                    project = { project },
                ),
            )

            val outcome = try {
                toolset.execute("check_kotlin", mapOf("path" to "Main.kt"))
            } finally {
                services.release()
            }

            // `Main.kt` does not type-check. Either a real service says so, or
            // no service exists and the tool refuses. What must never happen is
            // the third answer.
            val clean = outcome is ProjectFiles.Outcome.Ok &&
                outcome.content.startsWith("No problems")
            assertTrue(
                "check_kotlin called code that does not type-check clean: $outcome",
                !clean,
            )

            if (outcome is ProjectFiles.Outcome.Refused) {
                assertTrue(
                    "a refusal has to tell the model what to do instead: ${outcome.reason}",
                    "run_build" in outcome.reason,
                )
            }
        }

    /**
     * A non-Kotlin file never reaches the service.
     *
     * Asking for one would build a session — seconds, and the front end's whole
     * object graph — to answer about a file it could not handle. Asserted
     * against the real `LanguageServices` because the fake could not have told
     * us whether the order was right.
     */
    @Test
    fun a_non_kotlin_file_is_refused_without_building_a_session() = runBlocking {
        val services = realServices()
        val root = File(context.cacheDir, "kt-tools-${System.nanoTime()}").apply { mkdirs() }
        File(root, "notes.txt").writeText("not kotlin")

        val project = Project(
            name = "Sample",
            rootDir = root,
            applicationId = "com.example.sample",
            language = SourceLanguage.KOTLIN,
            engine = BuildEngine.FAST,
            lastOpenedAt = 0L,
        )
        val toolset = ProjectToolset(
            ProjectFiles(root),
            kotlinTools(
                serviceFor = { error("a non-Kotlin path must be refused before this is called") },
                project = { project },
            ),
        )

        val outcome = try {
            toolset.execute("check_kotlin", mapOf("path" to "notes.txt"))
        } finally {
            services.release()
        }
        assertTrue("$outcome", outcome is ProjectFiles.Outcome.Refused)
    }
}
