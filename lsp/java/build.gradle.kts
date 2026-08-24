plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.osamu.aide.lsp.java"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // The editor works from 26, and so should its intelligence. Nothing
        // here execs a binary, so unlike the build engine there is no API 30
        // floor to gate at runtime.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    androidResources {
        // The tests read android.jar as a zip at run time; compressing it into
        // the test APK only costs install time and CPU.
        noCompress += listOf("jar")
    }
}

dependencies {
    implementation(project(":core:common"))

    // For Diagnostic. The editor's gutter already renders that type for build
    // output, and a diagnostic from javac is the same thing to a reader: a
    // severity, a message, and a project-relative place to jump to. A second
    // near-identical type would buy nothing and would have to be converted at
    // every boundary.
    api(project(":engine:api"))

    // NetBeans' error-tolerant javac. Spike R3 measured it on ART; see
    // tools/javals/FINDINGS.md, including the note that this artifact is the
    // last standalone release and upstream has moved on.
    implementation(libs.nb.javac.android)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
