plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.osamu.aide.spike.kotlinls"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // The archives are dexed at --min-api 30, the same floor as the Kotlin
        // compiler and aapt2. Below it they will not load at all, so this spike
        // has nothing to say about older devices.
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
