plugins {
    alias(libs.plugins.android.library)
}

/**
 * The engine that runs a C# project.
 *
 * A sibling to `:engine:node`, and the second implementation of `RunSystem` --
 * which is what settles that the contract was worth having. It differs from
 * the first in one way that shows in the events: running C# is *two* processes,
 * `mcs` and then the assembly, so a run emits two `Started`s. Everything about
 * how mono starts on Android lives in `MonoToolchain`; `tools/mono/FINDINGS.md`
 * and spike R14 are the evidence behind it.
 */
android {
    namespace = "com.osamu.aide.engine.mono"
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
    api(project(":engine:api"))
    implementation(project(":core:common"))
    implementation(project(":core:fs"))
    implementation(project(":toolchain:native"))

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// The tests here run a real mono, which is 118 MB and not in git; without it
// they skip, and a skip reports as OK.
extra["deviceTestPackage"] = "com.osamu.aide.engine.mono.test"
extra["deviceArchives"] = listOf("mono.tar")
apply(from = rootProject.file("gradle/stage-device-archives.gradle.kts"))
