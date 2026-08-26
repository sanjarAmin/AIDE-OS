package com.osamu.aide.di

import android.content.Context
import com.osamu.aide.ai.core.ApiKeyStore
import com.osamu.aide.ai.core.Assistant
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.core.fs.FileProjectRepository
import com.osamu.aide.core.fs.ProjectImporter
import com.osamu.aide.core.fs.ProjectRepository
import com.osamu.aide.editor.DocumentStore
import com.osamu.aide.editor.EditorLanguages
import com.osamu.aide.engine.fast.AndroidPlatformProvider
import com.osamu.aide.engine.deps.DependencyResolver
import com.osamu.aide.engine.fast.ApkInstaller
import com.osamu.aide.engine.fast.KotlinCompiler
import com.osamu.aide.engine.fast.KotlinToolchainProvider
import com.osamu.aide.toolchain.manager.ToolchainManager
import com.osamu.aide.toolchain.nativetools.NativeToolRunner
import com.osamu.aide.toolchain.nativetools.NativeToolchain
import com.osamu.aide.ui.projects.ProjectsViewModel
import com.osamu.aide.ui.workspace.LanguageServices
import com.osamu.aide.ui.workspace.ProjectBuilder
import com.osamu.aide.ui.workspace.ProjectDependencies
import com.osamu.aide.ui.workspace.WorkspaceViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File

/**
 * Koin is used instead of an annotation-processor DI framework so the module
 * graph stays free of codegen -- with the number of modules this project is
 * heading towards, KSP rounds would dominate incremental build times.
 */
private const val BUILD_OUTPUT_ROOT = "buildOutputRoot"

val appModule = module {

    single<DispatcherProvider> { DefaultDispatcherProvider() }

    single { workspaceRoot(get<Context>()) }

    // One definition of where intermediates go. The build writes R.java under
    // it and language intelligence reads it back, so the two must agree; a
    // second literal here is how they would quietly stop agreeing.
    single(named(BUILD_OUTPUT_ROOT)) { File(get<Context>().cacheDir, "builds") }

    // The assistant's credential and the seam that builds sessions from it.
    // ApiKeyStore is a singleton because it holds a handle to a Keystore entry,
    // not the key itself; Assistant reads through it on every session so a key
    // changed in settings takes effect on the next message.
    single { ApiKeyStore(get()) }
    single { Assistant(get(), get()) }

    single<ProjectRepository> { FileProjectRepository(get(), get()) }
    single { ProjectImporter(get(), get(), get()) }

    // Holds the tree-sitter query sources, so opening a second Java file does
    // not go back to assets for them.
    single { EditorLanguages(get()) }
    single { DocumentStore(get()) }

    single { NativeToolchain.from(get()) }
    single { NativeToolRunner(get(), get()) }
    single { ToolchainManager(get(), get()) }
    single { AndroidPlatformProvider(get(), get()) }
    single { ApkInstaller(get(), get()) }
    // The local Maven repository. Cache storage: every file in it can be
    // fetched again, so the system is welcome to reclaim it.
    single { DependencyResolver(File(get<Context>().cacheDir, "maven"), get()) }
    single { ProjectDependencies(get()) }

    // Nullable on purpose: the Kotlin compiler is a 54 MB archive that is not
    // published anywhere yet, so most devices will not have one and every
    // Kotlin feature has to cope with that. See KotlinToolchainProvider.
    single { KotlinToolchainProvider(get()) }
    single<KotlinCompiler?> {
        get<KotlinToolchainProvider>().toolchain()?.let { toolchain ->
            KotlinCompiler(toolchain, File(get<Context>().cacheDir, "kotlin-host"))
        }
    }

    single {
        ProjectBuilder(
            toolchain = get(),
            platforms = get(),
            runner = get(),
            dependencies = get(),
            kotlin = get(),
            dispatchers = get(),
            outputRoot = get(named(BUILD_OUTPUT_ROOT)),
        )
    }

    // One per app, so the warm compiler behind it survives tab switches and
    // rotations. Rebuilding it per screen would undo the whole point.
    single {
        LanguageServices(
            toolchain = get(),
            dispatchers = get(),
            buildOutputRoot = get(named(BUILD_OUTPUT_ROOT)),
        )
    }

    viewModel { ProjectsViewModel(get(), get()) }
    viewModel { WorkspaceViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}

/**
 * Projects live in external app-specific storage: it survives app updates, needs
 * no runtime permission, and is reachable over MTP so a desktop can pick up the
 * same tree. Falls back to internal storage when no external volume is mounted.
 */
private fun workspaceRoot(context: Context): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "projects")
