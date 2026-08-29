package com.osamu.aide.ui.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.awaitResult
import com.osamu.aide.engine.deps.DependencyResolver
import com.osamu.aide.engine.fast.AndroidPlatformProvider
import com.osamu.aide.engine.fast.NativeToolchainProvider
import com.osamu.aide.engine.gradle.GradleToolchainProvider
import com.osamu.aide.toolchain.manager.ToolchainManager
import com.osamu.aide.toolchain.nativetools.NativeToolRunner
import com.osamu.aide.toolchain.nativetools.NativeToolchain
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * That a project's declared engine is the one that builds it.
 *
 * `:engine:gradle`'s own tests prove it builds; they would pass with nothing in
 * the app routing to it. This is the seam, and it is the same class of gap
 * `engine/fast/FINDINGS.md` section 14 describes -- an input wired only into a
 * test left the real path broken for a milestone.
 *
 * A device without a JDK is the ordinary case and the interesting one: the
 * build has to be refused with a sentence naming what is missing, not fail
 * somewhere inside Gradle or silently fall back to the fast engine, which would
 * build the project the wrong way and look like it worked.
 */
@RunWith(AndroidJUnit4::class)
class EngineSelectionTest {

    private lateinit var builder: ProjectBuilder
    private lateinit var workspace: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dispatchers = DefaultDispatcherProvider()
        workspace = File(context.cacheDir, "engine-selection").apply { deleteRecursively(); mkdirs() }

        builder = ProjectBuilder(
            toolchain = ToolchainManager(context, dispatchers),
            platforms = AndroidPlatformProvider(context, dispatchers),
            runner = NativeToolRunner(NativeToolchain.from(context), dispatchers),
            dependencies = ProjectDependencies(DependencyResolver(File(context.cacheDir, "deps-engine-test"), dispatchers)),
            kotlin = KotlinCompilerSource(
                com.osamu.aide.engine.fast.KotlinToolchainProvider(context),
                File(context.cacheDir, "kotlin-host-engine-test"),
            ),
            native = NativeToolchainProvider(context, dispatchers),
            // Its own root, so this test cannot be changed by whatever another
            // test installed -- and cannot change what they see.
            gradle = GradleToolchainProvider(
                context,
                dispatchers,
                installRoot = File(workspace, "toolchains"),
            ),
            dispatchers = dispatchers,
            outputRoot = File(workspace, "out"),
        )
    }

    private fun project(engine: BuildEngine): Project {
        val root = File(workspace, "project-${engine.name.lowercase()}").apply { mkdirs() }
        File(root, "src/main").mkdirs()
        File(root, "src/main/AndroidManifest.xml").writeText(
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" />",
        )
        return Project(
            name = "demo",
            rootDir = root,
            applicationId = "demo.app",
            language = SourceLanguage.JAVA,
            engine = engine,
            lastOpenedAt = 0L,
        )
    }

    /**
     * With no JDK installed, a Gradle project is refused by name.
     *
     * The wrong behaviours this rules out are both quiet: building it with the
     * fast engine, which would ignore the project's own build script, or
     * failing with whatever Gradle says when it cannot start.
     */
    @Test
    fun a_gradle_project_without_a_runtime_is_refused_and_names_what_is_missing() {
        val result = runBlocking { builder.build(project(BuildEngine.GRADLE)).awaitResult() }

        assertTrue("expected a refusal, got: $result", result is BuildResult.Failure)
        val message = (result as BuildResult.Failure).message
        assertTrue("the message does not mention Gradle: $message", message.contains("Gradle"))
        assertTrue(
            "the message does not say a runtime is missing: $message",
            message.contains("Java runtime") || message.contains("not installed"),
        )
    }

    /**
     * And a fast-engine project does not go anywhere near it.
     *
     * Asserted through the message: the fast path refuses for its own reasons —
     * a missing platform, no sources — never for a missing Java runtime.
     */
    @Test
    fun a_fast_project_is_not_sent_to_gradle() {
        val events = runBlocking { builder.build(project(BuildEngine.FAST)).toListOfFinished() }

        val result = events.result
        val message = (result as? BuildResult.Failure)?.message.orEmpty()
        assertTrue(
            "a FAST project was refused for Gradle's reasons: $message",
            !message.contains("Gradle distribution"),
        )
    }

    private suspend fun kotlinx.coroutines.flow.Flow<BuildEvent>.toListOfFinished(): BuildEvent.Finished {
        var finished: BuildEvent.Finished? = null
        collect { if (it is BuildEvent.Finished) finished = it }
        return checkNotNull(finished) { "the build never finished" }
    }
}
