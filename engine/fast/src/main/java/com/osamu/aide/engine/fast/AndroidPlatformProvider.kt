package com.osamu.aide.engine.fast

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Assembles an [AndroidPlatform] from the two very different places its halves
 * come from.
 *
 * `android.jar` is 63 MB of download, installed by `:toolchain:manager`, and is
 * passed in. `platform-stubs.jar` is two kilobytes shipped inside this module's
 * assets -- but assets have no path on disk, and the Java compiler takes a
 * classpath of files, so it has to be staged out before it can be used.
 */
class AndroidPlatformProvider(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) {

    suspend fun platformFor(androidJar: File): AndroidPlatform =
        AndroidPlatform(androidJar, stagedStubs())

    /**
     * Copies the stubs out of assets, once per installed version of the app.
     *
     * The version is in the file name rather than checked against a marker
     * because the failure it guards against is silent: an app update that
     * changes the stubs would otherwise keep using the old copy for ever, and
     * the symptom would be a compile error about `java.lang.invoke` that makes
     * no sense against the source in front of the user.
     */
    private suspend fun stagedStubs(): File = withContext(dispatchers.io) {
        val target = File(context.filesDir, "engine/platform-stubs-${appVersion()}.jar")
        if (target.isFile) return@withContext target

        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "${target.name}.partial")
        context.assets.open(STUBS_ASSET).use { input ->
            partial.outputStream().use { input.copyTo(it) }
        }
        // Renamed into place so a copy interrupted half way is not picked up as
        // a complete one next time.
        partial.renameTo(target)
        target
    }

    private fun appVersion(): Long {
        val info: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private companion object {
        const val STUBS_ASSET = "platform-stubs.jar"
    }
}
