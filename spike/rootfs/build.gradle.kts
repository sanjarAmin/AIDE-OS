plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.osamu.aide.spike.rootfs"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    // The launcher is an executable named lib*.so; it has to be extracted to
    // nativeLibraryDir at install or it has no path on disk to run from.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

// The launcher and jspawnhelper come from :toolchain:native now, which ships
// them for the app. This module used to build its own; keeping a second copy
// would mean the spike testing something the product does not have.

dependencies {
    // Brings libaapt2.so into this APK's jniLibs. AGP has to exec aapt2, and
    // nativeLibraryDir is the only place an app may exec from -- the aapt2 AGP
    // fetches from Maven is a Linux x86_64 binary and cannot run here at all.
    implementation(project(":toolchain:native"))

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
