plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.osamu.aide.lsp.api"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Diagnostic is shared with the build engine on purpose: a compiler error
    // and a language-server error are the same thing to the gutter that draws
    // them, and two near-identical types would mean the editor holding both.
    api(project(":engine:api"))

    testImplementation(libs.junit)
}
