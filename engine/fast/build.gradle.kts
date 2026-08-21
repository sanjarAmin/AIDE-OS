plugins {
    alias(libs.plugins.android.library)
}

/**
 * The bundled build engine: aapt2 -> ECJ -> D8 -> apksig, running entirely on
 * the device with one native binary and no Linux userland.
 *
 * Everything here was proved to work on ART by `:spike:kotlinc` and
 * `:spike:jvmtools` first. Read `tools/ecj/FINDINGS.md` before changing how the
 * compiler is invoked -- several of the arguments look arbitrary and are not.
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
    implementation(libs.r8)
    implementation(libs.apksig)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
