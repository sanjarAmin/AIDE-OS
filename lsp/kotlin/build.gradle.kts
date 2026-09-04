plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.osamu.aide.lsp.kotlin"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // **26, not 30, and the 30 is real.** The archives are dexed at
        // --min-api 30 and will not load below it. Declaring that here looks
        // right and is wrong: a library minSdk above the app's fails the
        // manifest merge outright, and forcing it past with
        // tools:overrideLibrary would trade a build error for a runtime one.
        //
        // The app's floor stays 26 because the editor works there, so this
        // gates at runtime instead -- KotlinArchives.isSupported, checked by
        // LanguageServices before it ever builds a service. The same rule the
        // build features follow: gate at runtime, never fail at load.
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
