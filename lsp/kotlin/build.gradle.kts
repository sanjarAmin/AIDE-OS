import java.util.zip.ZipFile
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.osamu.aide.lsp.kotlin"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // **26, not 30, and the 30 is real.** The archives are dexed at
        // --min-api 30 and will not load below it. Declaring that here looks
        // right and is wrong: a library minSdk above the app's fails the
        // manifest merge outright, and forcing it past with
        // tools:overrideLibrary would trade a build error for a runtime one.
        //
        // The app's floor stays 26 because the editor works there, so this
        // gates at runtime instead -- KotlinArchives.isSupported, checked by
        // LanguageServices before it ever builds a service. The same rule the
        // build features follow: gate at runtime, never fail at load.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":lsp:api"))
    implementation(project(":core:common"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

/**
 * Pushes the Kotlin analysis archives to the device before the instrumented
 * tests run.
 *
 * **Without this the suite reports `OK` and tests nothing.** Its tests
 * `assumeTrue` on the archives being present, and a skip rolls up into a green
 * `connectedDebugAndroidTest` -- the exact shape of failure this project has
 * lost a week to before. Staging by hand works and is what every targeted run
 * did, but a sweep on a machine that has not had it done is quietly hollow.
 *
 * Safe to run before the install: an `install -r` leaves
 * `/sdcard/Android/data/<pkg>/files` alone, which was checked rather than
 * assumed -- the belief that it wiped them is what sent an earlier fix down the
 * wrong path entirely.
 *
 * The archives are built out of tree by `tools/analysisapi/build-dex.sh` and
 * `build-component.sh`; they are ~58 MB and not in git. When they are missing
 * this warns with the command that produces them rather than failing the build,
 * because a fresh clone must still be able to run every other module's tests.
 */
val analysisArchiveHome = providers.environmentVariable("ANALYSIS_API_JARS")
    .orElse("${System.getProperty("user.home")}/aide-os-spikes/analysisapi")
val androidSdkRoot = providers.environmentVariable("ANDROID_SDK_ROOT")
    .orElse("${System.getProperty("user.home")}/Android/Sdk")

val stageKotlinAnalysisArchives by tasks.registering {
    description = "Pushes the Kotlin compiler and Analysis API archives to the device."
    group = "verification"

    // Deliberately not input/output tracked: the state it changes lives on a
    // device, which Gradle cannot see, so an up-to-date check would be a lie.
    outputs.upToDateWhen { false }

    // Read at configuration time and captured as plain values. The
    // configuration cache refuses to serialize references back into the build
    // script, so nothing here may touch `project` or `providers` inside doLast.
    val home = analysisArchiveHome.get()
    val adb = "${androidSdkRoot.get()}/platform-tools/adb"

    doLast {
        val archives = mapOf(
            "kotlinc-archive.zip" to "kotlin-compiler-2.2.10.zip",
            "kotlin-analysis-2.2.10.zip" to "kotlin-analysis-2.2.10.zip",
        )
        val missing = archives.keys.filterNot { File(home, it).isFile }
        if (!File(adb).isFile || missing.isNotEmpty()) {
            logger.warn(
                "Kotlin analysis archives not staged -- :lsp:kotlin's tests will SKIP, " +
                    "and a skip reports as OK. Missing: ${missing.joinToString()}. " +
                    "Build them with tools/analysisapi/build-dex.sh then build-component.sh, " +
                    "or set ANALYSIS_API_JARS to where they are.",
            )
            return@doLast
        }

        val target = "/sdcard/Android/data/com.osamu.aide.lsp.kotlin.test/files"
        fun run(vararg command: String) {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "${command.joinToString(" ")} failed: $output" }
        }
        run(adb, "shell", "mkdir", "-p", target)
        for ((source, name) in archives) {
            run(adb, "push", File(home, source).absolutePath, "$target/$name")
        }
        logger.lifecycle("Staged ${archives.size} Kotlin analysis archives to the device.")
    }
}

tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(stageKotlinAnalysisArchives)
}

/**
 * A real AAR, for the tests that ask whether an AAR works.
 *
 * `tools/analysisapi/FINDINGS.md` §24 recorded that the `@kotlin.Metadata` scan
 * had only ever run against `kotlin-stdlib`, and that a project's AARs were
 * covered "by construction" -- the claim, not the evidence. `core-ktx` is the
 * right subject: a small Kotlin library whose whole content is extension
 * functions on platform types, carrying under `META-INF` the `.kotlin_module`
 * the scan requires. (Written without the glob: a literal slash-star inside a
 * KDoc opens a *nested* block comment, and Kotlin nests them -- the closing
 * marker then ends the inner one and the rest of the file is silently comment.
 * It cost an hour here, and `tools/analysisapi/FINDINGS.md` had already
 * recorded it once.)
 *
 * Resolved through Gradle rather than read out of a developer's cache, so it
 * reproduces on a clean machine, and `isTransitive = false` because only this
 * one artifact is wanted -- not the graph beneath it.
 */
val aarFixture: Configuration by configurations.creating { isTransitive = false }

dependencies {
    aarFixture("androidx.core:core-ktx:1.16.0@aar")
}

val aarFixtureJar = layout.buildDirectory.file("aar-fixture/core-ktx.jar")

val extractAarFixture by tasks.registering {
    description = "Unpacks classes.jar out of the core-ktx AAR for the device tests."
    // A `Configuration` itself cannot be serialized into the configuration
    // cache; a FileCollection built from it can, so that is what doLast sees.
    val aar = files(aarFixture)
    val output = aarFixtureJar
    inputs.files(aar)
    outputs.file(output)
    doLast {
        val target = output.get().asFile
        target.parentFile.mkdirs()
        val archive = aar.singleFile
        // `java.util.zip...` fully qualified does not resolve here: `java` is the
        // Java plugin extension in a Kotlin DSL script. Hence the import.
        ZipFile(archive).use { zip ->
            val entry = requireNotNull(zip.getEntry("classes.jar")) {
                "no classes.jar in $archive"
            }
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
    }
}

// The instrumented tests here need real toolchain archives on the device, and
// without them they skip -- which reports as OK.
extra["deviceTestPackage"] = "com.osamu.aide.lsp.kotlin.test"
/**
 * `android.jar` goes with it, because that is the classpath the app builds.
 *
 * `core-ktx`'s extensions hang off `android.view.View` and `android.content
 * .Context`, so without the platform there is no receiver for them to apply to
 * and the test would prove nothing about the AAR. `LanguageServices` puts the
 * platform first for exactly this reason.
 */
val platformJar: String = File("${System.getenv("ANDROID_SDK_ROOT") ?: "${System.getProperty("user.home")}/Android/Sdk"}/platforms")
    .listFiles()
    ?.filter { File(it, "android.jar").isFile }
    ?.maxByOrNull { it.name }
    ?.let { File(it, "android.jar").absolutePath }
    .orEmpty()

extra["deviceArchives"] = listOf(
    "${aarFixtureJar.get().asFile}=core-ktx.jar",
    "$platformJar=android.jar",
)
apply(from = rootProject.file("gradle/stage-device-archives.gradle.kts"))

tasks.named("stageDeviceArchives") { dependsOn(extractAarFixture) }
