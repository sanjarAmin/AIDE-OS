plugins {
    alias(libs.plugins.android.library)
}

android {
    // The module path is :toolchain:native, but the namespace cannot mirror it:
    // `native` is a reserved word in Java, so the generated BuildConfig would
    // not compile.
    namespace = "com.osamu.aide.toolchain.nativetools"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // arm64 is what real devices run; x86_64 exists so the pipeline can be
        // tested on an emulator without emulating a foreign architecture.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Bundled tools must be extracted to nativeLibraryDir at install time.
    // Without this they stay compressed inside the APK, where they have no path
    // on disk and therefore cannot be executed.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
