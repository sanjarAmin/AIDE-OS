plugins {
    alias(libs.plugins.android.library)
}

android {
    // `native` is a reserved word in Java, so the namespace cannot mirror the
    // module path -- the same reason :toolchain:native uses `nativetools`.
    namespace = "com.osamu.aide.lsp.nativelsp"
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
    // For LinkerLaunch: clangd is a downloaded binary and cannot be executed
    // directly any more than clang can.
    implementation(project(":toolchain:native"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
