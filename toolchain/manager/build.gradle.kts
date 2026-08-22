plugins {
    alias(libs.plugins.android.library)
}

/**
 * Components too large to ship inside the APK: downloaded, checksum-verified,
 * and installed into app storage.
 *
 * `android.jar` is the first and the one M2 needs -- the fast build engine
 * cannot compile a line without it. The NDK sysroot and the Kotlin compiler
 * archive arrive the same way later; see docs/PLAN.md.
 */
android {
    namespace = "com.osamu.aide.toolchain.manager"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":core:common"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
