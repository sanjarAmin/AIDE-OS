plugins {
    alias(libs.plugins.android.library)
}

/**
 * The engine that runs a Node project.
 *
 * A sibling to `:engine:fast` and `:engine:gradle` rather than part of either:
 * those turn sources into an APK, and this starts a program and streams what it
 * says. `:engine:api` holds both contracts and knows no toolchain; the
 * knowledge of how Node starts on Android lives in `NodeToolchain`, which this
 * drives. `tools/node/FINDINGS.md`, spike R13.
 */
android {
    namespace = "com.osamu.aide.engine.node"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Node is started through the linker in nativeLibraryDir's stead, so this
    // module ships no binaries of its own; the packaging block :engine:gradle
    // needs is deliberately absent.
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

// The tests here run a real Node, which is 104 MB and not in git; without it
// they skip, and a skip reports as OK.
extra["deviceTestPackage"] = "com.osamu.aide.engine.node.test"
extra["deviceArchives"] = listOf("node.tar")
apply(from = rootProject.file("gradle/stage-device-archives.gradle.kts"))
