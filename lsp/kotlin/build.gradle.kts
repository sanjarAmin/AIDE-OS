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
