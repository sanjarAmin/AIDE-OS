package com.osamu.aide.ui.workspace

import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.engine.deps.DependencyResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * That everything the engine needs actually reaches it.
 *
 * This is a seam test, and it exists because the seam leaked. `libraryPackages`
 * and `libraryManifests` were added to `DependencyInputs` during M6 and wired
 * only into `:engine:fast`'s own tests — so the engine was proven correct while
 * a project built through the app got neither.
 *
 * **Both failures happen after a successful build**, which is what makes them
 * expensive: without `libraryPackages` the app dies on launch with
 * `NoClassDefFoundError` on a library's own `R` class, and without
 * `libraryManifests` a library's `<provider>` is silently absent and nothing
 * reports it ever.
 *
 * So this asserts the *shape* of what the app hands the engine, rather than
 * trusting a call site that compiles either way.
 */
class ProjectDependenciesTest {

    private lateinit var dependencies: ProjectDependencies
    private lateinit var project: Project

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dependencies = ProjectDependencies(
            DependencyResolver(File(context.cacheDir, "maven"), DefaultDispatcherProvider()),
        )
        project = Project(
            name = "seam",
            rootDir = File(context.cacheDir, "seam-test").apply { mkdirs() },
            applicationId = "com.example.seam",
            language = SourceLanguage.JAVA,
            engine = BuildEngine.FAST,
            lastOpenedAt = 0L,
            // An AAR with resources, its own R class and a manifest — the three
            // things the fields under test carry.
            dependencies = listOf("androidx.appcompat:appcompat:1.7.0"),
        )
    }

    @Test
    fun every_input_the_engine_needs_is_carried() = runTest(timeout = 5.minutes) {
        val inputs = dependencies.inputsFor(project)

        assertTrue("no classpath", inputs.classpath.isNotEmpty())
        assertTrue("no resource directories", inputs.resourceDirectories.isNotEmpty())
        assertTrue(
            "no library packages, so no per-library R class and a crash on launch",
            inputs.libraryPackages.isNotEmpty(),
        )
        assertTrue(
            "no library manifests, so no library <provider> in the built APK",
            inputs.libraryManifests.isNotEmpty(),
        )

        // The packages are real ones, not empty strings that would pass the
        // check above while telling aapt2 nothing.
        assertTrue(
            "appcompat's own package is missing: ${inputs.libraryPackages}",
            "androidx.appcompat" in inputs.libraryPackages,
        )
        assertTrue(
            "a manifest path does not exist: ${inputs.libraryManifests}",
            inputs.libraryManifests.all { it.isFile },
        )
    }
}
