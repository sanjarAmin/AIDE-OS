package com.osamu.aide.engine.fast

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.apksig.ApkVerifier
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.fs.ComposeApp
import com.osamu.aide.core.fs.ProjectTemplate
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.engine.api.BuildRequest
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.DependencyInputs
import com.osamu.aide.engine.api.awaitResult
import com.osamu.aide.engine.deps.Coordinate
import com.osamu.aide.engine.deps.DependencyResolver
import com.osamu.aide.toolchain.nativetools.ClangToolchain
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.minutes

/**
 * **Every template in the picker builds into an APK the platform accepts.**
 *
 * This is the test that makes `ProjectTemplate`'s central claim true rather
 * than aspirational. The catalog's contract is that everything in `ALL` builds
 * or runs on a device today, and until this existed that was a sentence in a
 * KDoc: `ProjectTemplateCatalogTest` checks what a template *writes*, which
 * catches a missing manifest and cannot catch a Java file that does not
 * compile, a JNI symbol the linker rejects, or a resource aapt2 refuses.
 *
 * The failure mode it guards against is the expensive one. A bad template does
 * not crash -- the project is created successfully, and the build fails minutes
 * later with an error about code the user never wrote and cannot be expected to
 * recognise as ours.
 *
 * **Driven by the catalog, not by a list.** Adding a twelfth template adds a
 * case here with no edit, which is the point: a list would go stale exactly when
 * someone is busy adding something.
 *
 * [ComposeApp] is excluded and is the only exclusion. It is the one template
 * with dependencies, so building it means resolving Maven over the network --
 * `ComposeRunTest` does that, with the same pinned pair, and builds, installs
 * and reads the result off the screen.
 */
@RunWith(AndroidJUnit4::class)
class ProjectTemplateBuildTest {

    private lateinit var fixture: EngineTestFixture
    private var kotlin: KotlinCompiler? = null
    private var clang: ClangToolchain? = null

    @Before
    fun setUp() {
        fixture = EngineTestFixture("template-build-test")
        fixture.assumeAapt2Supported()
        kotlin = stageKotlin()
        clang = stageClang()
    }

    private fun engine() = FastBuildSystem(
        fixture.runner,
        fixture.platform,
        DefaultDispatcherProvider(),
        kotlin,
        clang,
    )

    /**
     * The whole catalog, minus what cannot be built without the network.
     *
     * One test rather than one per template, because the setup is a staged
     * toolchain and repeating it per case would multiply the slowest part of
     * the run. Every template is attempted even after one fails, and they are
     * reported together -- a run that stopped at the first would hide the other
     * ten behind whichever happens to be first in `ALL`.
     */
    @Test
    fun every_android_template_builds_into_a_verifiable_apk() = runTest(timeout = 20.minutes) {
        val buildable = ProjectTemplate.ALL.filter { it.language !in RUN_ONLY && it != ComposeApp }
        assumeTrue("nothing to build", buildable.isNotEmpty())

        val failures = mutableListOf<String>()
        val built = mutableListOf<String>()

        for (template in buildable) {
            // Each in its own directory: a shared one would let the previous
            // template's sources compile into this one's APK, and the test
            // would pass for the wrong reason.
            val project = fixture.project(
                applicationId = "com.example.${template.id.replace("-", "")}",
                language = template.language,
                template = template,
                directory = template.id,
            )

            val skip = unmetToolchain(template.language)
            if (skip != null) {
                Log.i(TAG, "${template.id}: skipped, $skip")
                continue
            }

            val result = engine()
                .build(
                    BuildRequest(
                        project = project,
                        outputDir = File(fixture.workDir, "${template.id}-out"),
                    ),
                )
                .awaitResult()

            when (result) {
                is BuildResult.Success -> {
                    // apksig's own verifier, which is the code the platform
                    // runs. A file that exists is not an APK that installs.
                    val verified = ApkVerifier.Builder(result.apk).build().verify()
                    if (verified.isVerified) {
                        built += template.id
                        Log.i(TAG, "${template.id}: built in ${result.durationMillis} ms")
                    } else {
                        failures += "${template.id}: APK did not verify: ${verified.errors}"
                    }
                }
                is BuildResult.Failure -> failures += "${template.id}: " +
                    "${result.stage} ${result.message} ${result.diagnostics.map { it.message }}"
            }
        }

        Log.i(TAG, "built ${built.size} of ${buildable.size}: $built")
        assertTrue("templates that did not build:\n" + failures.joinToString("\n"), failures.isEmpty())
        // A run where every template skipped would otherwise pass having built
        // nothing, which is the failure mode this repo's skip floor exists for.
        assumeTrue("no toolchain was staged, so nothing was built", built.isNotEmpty())
    }

    /**
     * And the one template the loop above leaves out.
     *
     * Separate because it is the only template with [ProjectTemplate.dependencies],
     * so it needs a Maven resolve and therefore the network -- a suite that
     * fails on an aeroplane is a suite people stop running, so this skips when
     * resolution comes back empty rather than failing.
     *
     * **It resolves the template's own coordinates**, not a list copied from
     * here. That is most of the value: the strings in `ComposeApp.dependencies`
     * are parsed by `:engine:deps` at runtime and a typo in one of them is not
     * a compile error anywhere -- it is an empty classpath and a Kotlin file
     * that fails on its first `import androidx`.
     *
     * It also proves the Compose *plugin* engaged, which a successful build
     * does not: plugin discovery reads `META-INF/services` out of the file
     * named on the command line, so an unregistered plugin produces no error,
     * no warning, and valid bytecode that was simply not transformed. A
     * `@Composable` compiled with the plugin gains a `Composer` parameter, so
     * the dex references `androidx/compose/runtime/Composer` although the
     * source never names it. `ComposeBuildTest` and spike R2 finding 7.
     */
    @Test
    fun the_compose_template_resolves_its_own_dependencies_and_builds() = runTest(timeout = 20.minutes) {
        assumeTrue("kotlinc.jar is not staged; see tools/kotlinc/FINDINGS.md", kotlin != null)

        val coordinates = ComposeApp.dependencies.map { coordinate ->
            requireNotNull(Coordinate.parse(coordinate)) {
                "ComposeApp declares $coordinate, which :engine:deps cannot parse"
            }
        }
        val resolver = DependencyResolver(
            File(fixture.context.cacheDir, "template-maven"),
            DefaultDispatcherProvider(),
        )
        val resolved = when (val result = resolver.resolve(coordinates)) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> throw AssertionError("resolution failed: ${result.error.message}")
        }
        assumeTrue("dependencies did not resolve; no network?", resolved.dependencies.isNotEmpty())
        // An artifact the resolver could not fetch is reported rather than
        // thrown, which is right for the app and wrong here: a hole in the
        // classpath becomes a compile error about the user's own import.
        assertTrue("some dependencies did not resolve: ${resolved.unresolved}", resolved.unresolved.isEmpty())
        Log.i(TAG, "compose: resolved ${resolved.dependencies.size} artifacts")

        val project = fixture.project(
            applicationId = "com.example.composetemplate",
            language = ComposeApp.language,
            template = ComposeApp,
            directory = ComposeApp.id,
        )

        val result = engine()
            .build(
                BuildRequest(
                    project = project,
                    outputDir = File(fixture.workDir, "${ComposeApp.id}-out"),
                    dependencies = DependencyInputs(
                        classpath = resolved.compileClasspath,
                        resourceDirectories = resolved.resourceDirectories,
                        libraryPackages = resolved.libraryPackages,
                        libraryManifests = resolved.libraryManifests,
                    ),
                ),
            )
            .awaitResult()

        assertTrue(
            "the Compose template did not build: " +
                "${(result as? BuildResult.Failure)?.message}\n" +
                result.diagnostics.joinToString("\n") { it.message },
            result is BuildResult.Success,
        )
        val apk = (result as BuildResult.Success).apk
        assertTrue("the APK did not verify", ApkVerifier.Builder(apk).build().verify().isVerified)

        // The plugin engaged, not merely the build succeeded.
        val transformed = ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".dex") }.any { entry ->
                zip.getInputStream(entry).readBytes()
                    .toString(Charsets.ISO_8859_1)
                    .contains("androidx/compose/runtime/Composer")
            }
        }
        assertTrue(
            "the APK has no reference to Composer, so the Compose plugin never ran and " +
                "every composable in the template is inert",
            transformed,
        )
        Log.i(TAG, "compose: built and transformed in ${result.durationMillis} ms")
    }

    /**
     * Why [language] cannot be built here, or null when it can.
     *
     * A string rather than a boolean so the log says which archive is missing.
     * Skipping is right -- the Kotlin and clang archives are hundreds of
     * megabytes and are not in git -- but a silent skip is how five modules
     * once ran none of their tests while the sweep said BUILD SUCCESSFUL.
     */
    private fun unmetToolchain(language: SourceLanguage): String? = when (language) {
        SourceLanguage.KOTLIN ->
            if (kotlin == null) "kotlinc.jar is not staged; see tools/kotlinc/FINDINGS.md" else null
        SourceLanguage.C, SourceLanguage.CPP ->
            if (clang == null) "no C/C++ toolchain; see tools/clang/fetch-toolchain.sh" else null
        else -> null
    }

    /** Null when the archives are absent, so the Kotlin templates skip. */
    private fun stageKotlin(): KotlinCompiler? {
        val archive = asset("kotlinc.jar") ?: return null
        val stdlib = asset("kotlin-stdlib.jar") ?: return null
        return KotlinCompiler(
            KotlinToolchain(archive, stdlib),
            File(fixture.context.cacheDir, "template-kotlin-host"),
        )
    }

    /**
     * Left writable, as a download leaves it.
     *
     * The platform refuses to load a dex the app can write to, and staging it
     * read-only here would make this test pass while every build with a
     * compiler installed through the app killed the build process.
     * `KotlinBuildTest` records the same thing.
     */
    private fun asset(name: String): File? {
        val target = File(fixture.workDir, name)
        return runCatching {
            if (!target.isFile) {
                fixture.context.assets.open(name).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
            target.setWritable(true, true)
            target
        }.getOrNull()
    }

    /** Null when no clang is staged, so the native templates skip. */
    private fun stageClang(): ClangToolchain? {
        val abi = Build.SUPPORTED_ABIS.first()
        val archive = File(fixture.context.getExternalFilesDir(null), "toolchain.tar")
        val installed = File(fixture.context.filesDir, "toolchains/clang-21.1.8-$abi")
        if (!File(installed, "usr/bin/clang").exists()) {
            if (!archive.isFile) return null
            installed.mkdirs()
            // Unpacked by this process; `tools/clang/FINDINGS.md` §4 for why.
            ProcessBuilder("/system/bin/tar", "-xf", archive.absolutePath, "-C", installed.absolutePath)
                .redirectErrorStream(true)
                .start()
                .apply { inputStream.readBytes(); waitFor(10, TimeUnit.MINUTES) }
        }
        return NativeToolchainProvider(fixture.context, DefaultDispatcherProvider(), abi).toolchain()
    }

    private companion object {
        const val TAG = "TemplateBuildTest"

        /** Languages that run rather than build; they have no APK to produce. */
        val RUN_ONLY = setOf(
            SourceLanguage.JAVASCRIPT,
            SourceLanguage.CSHARP,
            SourceLanguage.PYTHON,
        )
    }
}
