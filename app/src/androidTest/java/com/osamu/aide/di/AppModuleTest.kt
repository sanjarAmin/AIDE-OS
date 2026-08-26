package com.osamu.aide.di

import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.ai.core.ApiKeyStore
import com.osamu.aide.ai.core.Assistant
import com.osamu.aide.engine.fast.KotlinToolchainProvider
import com.osamu.aide.ui.workspace.KotlinCompilerSource
import com.osamu.aide.ui.workspace.LanguageServices
import com.osamu.aide.ui.workspace.ProjectBuilder
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File

/**
 * That the object graph resolves on a device with nothing installed.
 *
 * This is the test the app was missing. `single<KotlinCompiler?>` looked
 * reasonable and every unit test built its objects by hand, so nobody
 * discovered that **Koin cannot hold null in a singleton** until a project was
 * opened on a device without the Kotlin toolchain -- which is the ordinary
 * state, since the compiler is a 54 MB download. The whole graph came down with
 * `IllegalStateException: Single instance created couldn't return value`, three
 * frames below a Koin stack trace that named `ProjectBuilder`.
 *
 * So the assertion is not about Kotlin. It is that every definition a workspace
 * needs can actually be constructed on a bare device, which is the only
 * configuration a new user has.
 */
class AppModuleTest {

    @After
    fun tearDown() = stopKoin()

    private fun koin() = startKoin {
        modules(
            module { single { InstrumentationRegistry.getInstrumentation().targetContext } },
            appModule,
        )
    }.koin

    @Test
    fun the_graph_a_workspace_needs_resolves_with_no_toolchain_installed() {
        val koin = koin()

        assertNotNull(koin.get<ProjectBuilder>())
        assertNotNull(koin.get<LanguageServices>())
        assertNotNull(koin.get<Assistant>())
        assertNotNull(koin.get<ApiKeyStore>())
    }

    /**
     * And the absence itself is still expressible.
     *
     * The fix would be worthless if it papered over "not installed" with a
     * broken compiler: a build would start and fail in the middle rather than
     * refusing up front and naming the missing component.
     *
     * Built against an empty directory rather than resolved from Koin, because
     * whether *this* device has a toolchain is not the question -- and asking
     * Koin would make the test pass or fail on whatever an earlier run happened
     * to download.
     */
    @Test
    fun a_missing_kotlin_compiler_is_reported_as_absent_rather_than_fabricated() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val empty = File(context.cacheDir, "no-toolchain-${System.nanoTime()}")

        val source = KotlinCompilerSource(KotlinToolchainProvider(emptyContext(empty)), empty)

        assertNull(source.compiler())
    }

    /** A Context whose filesDir is [root], so the provider finds nothing. */
    private fun emptyContext(root: File): android.content.Context =
        object : android.content.ContextWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
        ) {
            override fun getFilesDir(): File = root.apply { mkdirs() }
        }
}
