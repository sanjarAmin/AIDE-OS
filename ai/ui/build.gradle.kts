plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The AI layer's Compose surface: chat panel, approval prompt, error states.
 *
 * Deliberately thin. Everything with a decision in it -- the tool loop, the
 * approval handshake, what counts as a failed call -- lives in `:ai:core`,
 * where it can be tested against a local Messages API instead of through a
 * composition. If a rule starts to appear in here, it is in the wrong module.
 */
android {
    namespace = "com.osamu.aide.ai.ui"
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
        // Inherited from :ai:core rather than chosen here: the Anthropic SDK
        // uses java.time freely, and AGP refuses to consume a library that
        // needs desugaring from one that has it off.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    api(project(":ai:core"))
    implementation(project(":core:ui"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
