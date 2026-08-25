plugins {
    alias(libs.plugins.android.library)
}

/**
 * The bundled build engine: aapt2 -> ECJ -> D8 -> apksig, running entirely on
 * the device with one native binary and no Linux userland.
 *
 * Everything here was proved to work on ART by spikes R2 and R2b first (the
 * spike modules are gone; their findings live under `tools/`). Read
 * `tools/ecj/FINDINGS.md` before changing how the compiler is invoked --
 * several of the arguments look arbitrary and are not.
 */
android {
    namespace = "com.osamu.aide.engine.fast"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // The editor works below API 30; aapt2 does not. Building is gated at
        // run time rather than by minSdk so the app still installs and edits on
        // older devices. See NativeTool.AAPT2.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // aapt2 arrives from :toolchain:native as a jniLib and must be extracted to
    // disk at install time; unextracted it has no path to execute from. Library
    // packaging options do not propagate to a consumer, so this has to be
    // repeated here for the instrumentation APK.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }

        resources {
            // :engine:deps is on the *test* classpath for M4's acceptance test,
            // and Maven's jars each carry their own copy of these -- seventeen
            // files named META-INF/DEPENDENCIES, which the merger refuses to
            // choose between. Library packaging rules do not propagate to a
            // consumer, so this is repeated in :app and here.
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/sisu/**",
                "META-INF/*.kotlin_module",
            )
        }
    }

    androidResources {
        // android.jar and platform-stubs.jar are read as zips from disk;
        // compressing them into the APK only costs install time and CPU.
        noCompress += listOf("jar")
    }
}

dependencies {
    api(project(":engine:api"))
    implementation(project(":core:common"))
    implementation(project(":core:fs"))
    implementation(project(":toolchain:native"))

    // Pinned deliberately; see tools/ecj/FINDINGS.md before moving them.
    implementation(libs.ecj)

    // Not for its compiler -- for its `javax.lang.model`, which Android does
    // not have and which ECJ's batch FileSystem touches in a static
    // initialiser. This module used to carry a twelve-line hand-written
    // SourceVersion for that; nb-javac ships the real one, and two copies of
    // the same class in one APK is a bug that only appears once both are
    // installed. See FINDINGS section 10.
    implementation(libs.nb.javac.android)
    implementation(libs.r8)
    implementation(libs.apksig)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Test-only. The engine is handed an android.jar and does not care where it
    // came from; this is here so one test can prove the two halves compose --
    // that a downloaded platform really drives a build.
    androidTestImplementation(project(":toolchain:manager"))
    // For M4's acceptance test, which resolves a real AndroidX artifact. The
    // engine itself takes dependencies as plain files and never resolves.
    androidTestImplementation(project(":engine:deps"))

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
