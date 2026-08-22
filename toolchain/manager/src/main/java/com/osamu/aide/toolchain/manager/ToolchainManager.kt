package com.osamu.aide.toolchain.manager

import android.content.Context
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.toolchain.manager.R
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * The app's way in: what is installed, what it would take to install it, and
 * the licence that has to be agreed to first.
 *
 * Everything underneath is deliberately Context-free so it can be unit-tested
 * against a local server; this is the thin layer that knows about Android.
 */
class ToolchainManager(
    private val context: Context,
    dispatchers: DispatcherProvider,
) {

    // filesDir, not cacheDir. The system may clear a cache whenever it likes,
    // and a 63 MB download vanishing between two builds is not a cache miss a
    // user would forgive.
    val storage = ToolchainStorage(File(context.filesDir, "toolchains"))

    val license = SdkLicense(File(context.filesDir, "toolchains"))

    private val installer = ComponentInstaller(storage, license, dispatchers)

    fun install(component: ToolchainComponent): Flow<InstallProgress> =
        installer.install(component)

    /** Google's agreement, verbatim, for the screen that asks the user to accept it. */
    fun licenseText(): String = context.resources
        .openRawResource(R.raw.android_sdk_license)
        .bufferedReader()
        .use { it.readText() }

    /**
     * The platform the build engine compiles against, or null if it is not
     * installed yet.
     *
     * Null rather than an exception: not having it is the state every install
     * starts in, and the app's job then is to offer the download, not to report
     * a failure.
     */
    fun androidJar(): File? = storage
        .fileFor(ToolchainComponent.ANDROID_PLATFORM)
        .takeIf { it.isFile }

    fun canBuild(): Boolean = androidJar() != null
}
