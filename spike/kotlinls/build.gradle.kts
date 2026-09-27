plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.osamu.aide.spike.kotlinls"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // The archives are dexed at --min-api 30, the same floor as the Kotlin
        // compiler and aapt2. Below it they will not load at all, so this spike
        // has nothing to say about older devices.
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

/**
 * Pushes the archives R12's probes load, before the instrumented tests run.
 *
 * **Without this the suite reports `OK` and tests nothing.** Its tests
 * `assumeTrue` on the archives being present, and a skip rolls up into a green
 * `connectedDebugAndroidTest` -- the last full sweep showed this module as 18
 * tests, 18 skipped, which reads as passing at a glance.
 *
 * A near-copy of `:lsp:kotlin`'s task rather than something shared, because the
 * two stage *different sets* to *different packages*: this one also needs the
 * probe jar, which only the spike loads, and the analysis archive here is the
 * bare dex rather than the packaged component. Factoring them together would
 * mean a parameterised task whose two call sites have nothing in common but the
 * verb.
 *
 * Safe before the install: `install -r` leaves
 * `/sdcard/Android/data/<pkg>/files` untouched.
 */
val stageAnalysisProbeArchives by tasks.registering {
    description = "Pushes the Kotlin compiler, Analysis API and probe archives to the device."
    group = "verification"

    // Not input/output tracked: the state it changes is on a device, which
    // Gradle cannot see, so an up-to-date check would be a lie.
    outputs.upToDateWhen { false }

    // Read at configuration time and captured as plain values: the
    // configuration cache refuses to serialize references back into the script.
    val home = providers.environmentVariable("ANALYSIS_API_JARS")
        .orElse("${System.getProperty("user.home")}/aide-os-spikes/analysisapi").get()
    val adb = providers.environmentVariable("ANDROID_SDK_ROOT")
        .orElse("${System.getProperty("user.home")}/Android/Sdk").get() + "/platform-tools/adb"

    doLast {
        val archives = mapOf(
            "kotlinc-archive.zip" to "kotlin-compiler-2.2.10.zip",
            "analysis-api-2.2.10.zip" to "analysis-api-2.2.10.zip",
            "analysis-probe.jar" to "analysis-probe.jar",
        )
        val missing = archives.keys.filterNot { File(home, it).isFile }
        if (!File(adb).isFile || missing.isNotEmpty()) {
            logger.warn(
                "R12 archives not staged -- :spike:kotlinls' tests will SKIP, and a skip " +
                    "reports as OK. Missing: ${missing.joinToString()}. Build them with " +
                    "tools/analysisapi/build-dex.sh and build-probe.sh, or set " +
                    "ANALYSIS_API_JARS to where they are.",
            )
            return@doLast
        }

        val target = "/sdcard/Android/data/com.osamu.aide.spike.kotlinls.test/files"
        fun run(vararg command: String) {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "${command.joinToString(" ")} failed: $output" }
        }
        run(adb, "shell", "mkdir", "-p", target)
        for ((source, name) in archives) {
            run(adb, "push", File(home, source).absolutePath, "$target/$name")
        }
        logger.lifecycle("Staged ${archives.size} R12 archives to the device.")
    }
}

tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(stageAnalysisProbeArchives)
}
