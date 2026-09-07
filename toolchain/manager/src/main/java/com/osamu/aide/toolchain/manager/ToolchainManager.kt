package com.osamu.aide.toolchain.manager

import android.content.Context
import android.os.Build
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

    /**
     * Where Node is installed, or null when it is not.
     *
     * Null rather than an exception, for the reason [androidJar] gives: not
     * having it is the state every install starts in, and the permanent state
     * of anyone who never opens a JavaScript project.
     *
     * The marker is `bin/node` rather than the directory, because an install
     * interrupted between creating the directory and finishing the unpack
     * leaves the first and not the second.
     */
    fun nodeRoot(): File? = ToolchainComponent.node(Build.SUPPORTED_ABIS.first())
        ?.let { storage.directoryFor(it) }
        ?.takeIf { File(it, "bin/node").isFile }

    /** The Node component this device needs, or null when it is installed. */
    fun missingNodeComponent(): ToolchainComponent? =
        if (nodeRoot() != null) null else ToolchainComponent.node(Build.SUPPORTED_ABIS.first())

    /**
     * Where mono is installed, or null when it is not.
     *
     * The marker is `bin/mono-sgen` and not `bin/mono`, which is a **symlink**
     * to it: an unpack that stopped between the two leaves a link pointing at
     * nothing, and a link that resolves nowhere is `isFile == false` anyway --
     * so checking the real file is both the earlier and the honest test.
     */
    fun monoRoot(): File? = ToolchainComponent.mono(Build.SUPPORTED_ABIS.first())
        ?.let { storage.directoryFor(it) }
        ?.takeIf { File(it, "bin/mono-sgen").isFile }

    /** The mono component this device needs, or null when it is installed. */
    fun missingMonoComponent(): ToolchainComponent? =
        if (monoRoot() != null) null else ToolchainComponent.mono(Build.SUPPORTED_ABIS.first())

    /**
     * The archives Kotlin intelligence needs, or null if either is missing.
     *
     * Two components, not one: the Analysis API is built against the Kotlin
     * compiler's shaded IntelliJ and cannot load without it, and the compiler
     * is a 56 MB download a Java-only project has no reason to have.
     *
     * Null rather than an exception, for the reason [androidJar] gives -- not
     * having them is the state every install starts in, and it is also the
     * permanent state of a device whose owner never opens a Kotlin file.
     * Returns the three paths `KotlinArchives` takes, in its order.
     */
    fun kotlinAnalysisArchives(): KotlinAnalysisFiles? {
        val files = KotlinAnalysisFiles(
            compilerJar = storage.fileFor(ToolchainComponent.KOTLIN_COMPILER, "kotlinc.jar"),
            stdlibJar = storage.fileFor(ToolchainComponent.KOTLIN_COMPILER, "kotlin-stdlib.jar"),
            analysisApiJar = storage
                .fileFor(ToolchainComponent.KOTLIN_ANALYSIS_API, "analysis-api.jar"),
            backendJar = storage
                .fileFor(ToolchainComponent.KOTLIN_ANALYSIS_API, "analysis-backend.jar"),
        )
        return files.takeIf { it.allPresent }
    }

    /**
     * The component Kotlin intelligence is still waiting on, or null if none.
     *
     * **Two components, and the compiler comes first**: the Analysis API is
     * built against that compiler's shaded IntelliJ and cannot load without it,
     * and its `kotlin-stdlib.jar` is the library module the session resolves
     * against. Offering the smaller one to somebody who has neither would
     * download 2 MB and change nothing.
     *
     * Null when both are installed, which is also what a caller reads as "stop
     * asking".
     */
    fun missingKotlinAnalysisComponent(): ToolchainComponent? = when {
        !storage.fileFor(ToolchainComponent.KOTLIN_COMPILER, "kotlinc.jar").isFile ->
            ToolchainComponent.KOTLIN_COMPILER
        !storage.fileFor(ToolchainComponent.KOTLIN_ANALYSIS_API, "analysis-api.jar").isFile ->
            ToolchainComponent.KOTLIN_ANALYSIS_API
        else -> null
    }

    /**
     * The four files Kotlin intelligence needs, from two components.
     *
     * Named here rather than returned as a tuple because the four are not
     * interchangeable and three of them are jars of the same shape: swapping
     * the API and the backend produces a `ClassNotFoundException` several
     * layers down instead of a compile error.
     *
     * Deliberately not `:lsp:kotlin`'s own `KotlinArchives` -- that type also
     * carries where to stage them, which is the caller's business, and this
     * module has no reason to depend on a language service.
     */
    data class KotlinAnalysisFiles(
        val compilerJar: File,
        val stdlibJar: File,
        val analysisApiJar: File,
        val backendJar: File,
    ) {
        val allPresent: Boolean
            get() = compilerJar.isFile && stdlibJar.isFile &&
                analysisApiJar.isFile && backendJar.isFile
    }
}
