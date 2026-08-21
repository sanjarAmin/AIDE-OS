plugins {
    alias(libs.plugins.android.library)
}

/**
 * Spike R2: does the Kotlin compiler -- and the Compose compiler plugin -- run
 * on ART? This module exists only to answer that. It is deliberately not wired
 * into :app; if the spike succeeds its findings move into :build:fast, and if
 * it fails the module is deleted.
 */
android {
    namespace = "com.osamu.aide.spike.kotlinc"
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

    androidResources {
        // The dex archive is read back as a zip; re-compressing it in the APK
        // only costs install time.
        noCompress += listOf("zip", "jar")
    }
}

dependencies {
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
