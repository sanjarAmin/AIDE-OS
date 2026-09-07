plugins {
    alias(libs.plugins.android.library)
}

/**
 * Syntax diagnostics for JavaScript, from `node --check`.
 *
 * The thinnest of the four language services and deliberately so: Node ships no
 * language server, and writing one is not what M10 asked for. What it does ship
 * is a syntax check that is exactly right about what it covers -- every early
 * error V8 raises, including redeclaration -- and silent about everything else.
 * That is a smaller promise than `:lsp:java` makes and an honest one.
 */
android {
    namespace = "com.osamu.aide.lsp.node"
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
    api(project(":lsp:api"))
    implementation(project(":core:common"))
    // For NodeToolchain: node is a downloaded binary and starts only through
    // the linker, exactly as clangd does for :lsp:native.
    implementation(project(":toolchain:native"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// The instrumented tests here need a real Node on the device, and without one
// they skip -- which reports as OK.
extra["deviceTestPackage"] = "com.osamu.aide.lsp.node.test"
extra["deviceArchives"] = listOf("node.tar")
apply(from = rootProject.file("gradle/stage-device-archives.gradle.kts"))
