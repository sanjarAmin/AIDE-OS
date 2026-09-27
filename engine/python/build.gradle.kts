plugins {
    alias(libs.plugins.android.library)
}

/**
 * The engine that runs a Python project.
 *
 * A sibling to `:engine:node` and `:engine:mono`, and the same shape: it starts
 * a program and streams what it says, where `:engine:fast` and `:engine:gradle`
 * turn sources into an APK. Everything about how CPython starts on Android
 * lives in `PythonToolchain`, which this drives. `tools/python/FINDINGS.md`,
 * spike R17.
 */
android {
    namespace = "com.osamu.aide.engine.python"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Python is started through the linker in nativeLibraryDir's stead, so this
    // module ships no binaries of its own.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":engine:api"))
    implementation(project(":core:common"))
    implementation(project(":core:fs"))
    implementation(project(":toolchain:native"))

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// The tests here run a real CPython, which is 40 MB and not in git; without it
// they skip, and a skip reports as OK.
extra["deviceTestPackage"] = "com.osamu.aide.engine.python.test"
extra["deviceArchives"] = listOf("python.tar")
apply(from = rootProject.file("gradle/stage-device-archives.gradle.kts"))
