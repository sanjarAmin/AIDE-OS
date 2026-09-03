package com.osamu.aide.di

import android.content.Context
import com.osamu.aide.ai.core.ApiKeyStore
import com.osamu.aide.ai.core.Assistant
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.core.fs.FileProjectRepository
import com.osamu.aide.core.fs.ProjectAdoption
import com.osamu.aide.core.fs.ProjectImporter
import com.osamu.aide.core.fs.ProjectRepository
import com.osamu.aide.editor.DocumentStore
import com.osamu.aide.editor.EditorLanguages
import com.osamu.aide.engine.fast.AndroidPlatformProvider
import com.osamu.aide.engine.deps.DependencyResolver
import com.osamu.aide.engine.fast.ApkInstaller
import com.osamu.aide.engine.fast.KotlinToolchainProvider
import com.osamu.aide.engine.fast.NativeToolchainProvider
import com.osamu.aide.engine.gradle.GradleToolchainProvider
import com.osamu.aide.toolchain.manager.ToolchainManager
import com.osamu.aide.toolchain.nativetools.NativeToolRunner
import com.osamu.aide.toolchain.nativetools.NativeToolchain
import com.osamu.aide.ui.projects.ProjectsViewModel
import com.osamu.aide.vcs.git.GitCredentialStore
import com.osamu.aide.vcs.git.GitIdentityStore
import com.osamu.aide.vcs.git.GitWorkspace
import com.osamu.aide.ui.workspace.AssistantViewModel
import com.osamu.aide.ui.workspace.KotlinCompilerSource
import com.osamu.aide.ui.workspace.GitViewModel
import com.osamu.aide.ui.workspace.LanguageServices
import com.osamu.aide.ui.workspace.ProjectBuilder
import com.osamu.aide.ui.workspace.TerminalViewModel
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

    // Version control. The two stores are singletons for the same reason
    // ApiKeyStore is: each holds a handle to preferences and a Keystore entry
    // rather than the secret itself, and GitWorkspace reads through them on
    // every operation so a token added in settings works on the next push.
    single { GitIdentityStore(get()) }
    single { GitCredentialStore(get()) }
    single { GitWorkspace(get(), get(), get(), get()) }

    single<ProjectRepository> { FileProjectRepository(get(), get()) }
    single { ProjectImporter(get(), get(), get()) }
    single { ProjectAdoption(get()) }

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

    // The Kotlin compiler is a 54 MB archive downloaded on demand, so most
    // devices will not have one. That absence is carried by
    // KotlinCompilerSource rather than by a nullable definition, because Koin
    // cannot hold null in a singleton -- see the class for what that cost.
    single { KotlinToolchainProvider(get()) }
    single { KotlinCompilerSource(get(), File(get<Context>().cacheDir, "kotlin-host")) }

    // The C/C++ toolchain is absent even more often than the Kotlin compiler,
    // and for the same reason cannot be a nullable singleton. The provider is
    // registered; what it finds is asked for per build.
    single { NativeToolchainProvider(get(), get()) }

    // The Gradle engine's runtimes are the largest downloads in the app and the
    // ones fewest projects need. Absent is the normal state, so this is a
    // provider rather than a nullable definition -- Koin cannot hold null.
    single { GradleToolchainProvider(get(), get()) }

    single {
        ProjectBuilder(
            toolchain = get(),
            platforms = get(),
            runner = get(),
            dependencies = get(),
            kotlin = get(),
            native = get(),
            gradle = get(),
            dispatchers = get(),
            outputRoot = get(named(BUILD_OUTPUT_ROOT)),
        )
    }

    // One per app, so the warm compiler behind it survives tab switches and
    // rotations. Rebuilding it per screen would undo the whole point.
    single {
        LanguageServices(
            native = get(),
            toolchain = get(),
            dispatchers = get(),
            buildOutputRoot = get(named(BUILD_OUTPUT_ROOT)),
        )
    }

    viewModel { AssistantViewModel(get(), get(), get(), get()) }
    viewModel { ProjectsViewModel(get(), get(), get(), get(), get()) }
    viewModel { GitViewModel(get(), get(), get()) }
    viewModel { TerminalViewModel(get()) }
    viewModel { WorkspaceViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}

/**
 * Projects live in external app-specific storage: it survives app updates, needs
 * no runtime permission, and is reachable over MTP so a desktop can pick up the
 * same tree. Falls back to internal storage when no external volume is mounted.
 */
private fun workspaceRoot(context: Context): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "projects")
