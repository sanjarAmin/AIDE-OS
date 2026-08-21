package com.osamu.aide.engine.fast

import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.ProjectTemplate
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.toolchain.nativetools.NativeToolRunner
import com.osamu.aide.toolchain.nativetools.NativeToolchain
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Shared setup for the on-device engine tests.
 *
 * The platform files are staged out of assets because there is no
 * `:toolchain:manager` yet to put them on a real device; when there is, this is
 * the part that changes.
 */
class EngineTestFixture(private val name: String) {

    val context: Context = InstrumentationRegistry.getInstrumentation().context
    val workDir: File = File(context.cacheDir, name).apply {
        deleteRecursively()
        mkdirs()
    }

    val runner = NativeToolRunner(NativeToolchain.from(context), DefaultDispatcherProvider())

    val platform: AndroidPlatform by lazy {
        AndroidPlatform(
            androidJar = stageAsset("android.jar"),
            platformStubs = stageAsset("platform-stubs.jar"),
        )
    }

    /** aapt2 needs API 30; the rest of the pipeline does not. */
    fun assumeAapt2Supported() {
        assumeTrue(
            "aapt2 requires API 30; this device is ${Build.VERSION.SDK_INT}",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
        )
    }

    fun project(
        applicationId: String = "com.example.demo",
        language: SourceLanguage = SourceLanguage.JAVA,
    ): Project {
        val root = File(workDir, "project").apply { mkdirs() }
        val project = Project(
            name = "Demo",
            rootDir = root,
            applicationId = applicationId,
            language = language,
            engine = BuildEngine.FAST,
            lastOpenedAt = 0L,
        )
        ProjectTemplate.write(project)
        return project
    }

    fun workspace(): BuildWorkspace =
        BuildWorkspace(File(workDir, "out")).apply { prepare() }

    private fun stageAsset(assetName: String): File {
        val target = File(workDir, assetName)
        if (!target.isFile) {
            context.assets.open(assetName).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return target
    }
}
