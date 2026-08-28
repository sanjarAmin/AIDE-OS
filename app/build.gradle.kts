plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.osamu.aide"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.osamu.aide"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // aapt2 and the tree-sitter grammars are native, and the app is only
        // useful on a device it has both for. Left unset, the APK would carry
        // tree-sitter for armeabi-v7a and x86 -- ABIs with no aapt2 beside them,
        // where the editor would open and every build would fail at exec time.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11

        // Required by the tree-sitter bindings :editor pulls in; their AAR
        // metadata refuses to build without it. See editor/build.gradle.kts.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
    }

    // The build engine executes aapt2 (and later clang) from the app's native
    // library directory, the only location Android's W^X policy permits since
    // API 29. That requires the binaries to be extracted at install time rather
    // than loaded straight from the APK.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }

        resources {
            // Maven's own jars, which :engine:deps brings in, each carry a copy
            // of these and the merger will not choose between them -- seventeen
            // files named META-INF/DEPENDENCIES stop the build outright. The
            // same excludes exist in :engine:deps, and library packaging rules
            // do not propagate to a consumer, so they have to be repeated here.
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/sisu/**",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":ai:core"))
    implementation(project(":ai:ui"))
    implementation(project(":core:common"))
    implementation(project(":core:fs"))
    implementation(project(":core:ui"))
    implementation(project(":editor"))
    implementation(project(":engine:api"))
    implementation(project(":engine:deps"))
    implementation(project(":engine:fast"))
    implementation(project(":lsp:java"))
    implementation(project(":toolchain:native"))
    implementation(project(":toolchain:manager"))
    implementation(project(":vcs:git"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    testImplementation(libs.junit)
    // The tool handlers are suspending -- a build is not a synchronous call.
    testImplementation(libs.kotlinx.coroutines.test)
    // JGit is `implementation` in :vcs:git, so it does not reach here on its
    // own. These tests read commits back out of the object database, which is
    // the only way to assert a commit was really written.
    androidTestImplementation(libs.jgit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
