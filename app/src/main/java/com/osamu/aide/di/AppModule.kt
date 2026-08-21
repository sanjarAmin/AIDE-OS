package com.osamu.aide.di

import android.content.Context
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.core.fs.FileProjectRepository
import com.osamu.aide.core.fs.ProjectRepository
import com.osamu.aide.ui.projects.ProjectsViewModel
import com.osamu.aide.ui.workspace.WorkspaceViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.io.File

/**
 * Koin is used instead of an annotation-processor DI framework so the module
 * graph stays free of codegen -- with the number of modules this project is
 * heading towards, KSP rounds would dominate incremental build times.
 */
val appModule = module {

    single<DispatcherProvider> { DefaultDispatcherProvider() }

    single { workspaceRoot(get<Context>()) }

    single<ProjectRepository> { FileProjectRepository(get(), get()) }

    viewModel { ProjectsViewModel(get()) }
    viewModel { WorkspaceViewModel(get()) }
}

/**
 * Projects live in external app-specific storage: it survives app updates, needs
 * no runtime permission, and is reachable over MTP so a desktop can pick up the
 * same tree. Falls back to internal storage when no external volume is mounted.
 */
private fun workspaceRoot(context: Context): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "projects")
