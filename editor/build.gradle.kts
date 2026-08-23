plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The code editor: a Compose wrapper around sora-editor, plus the document
 * handling around it.
 *
 * sora-editor and the tree-sitter bindings are LGPL-2.1 and are used
 * **unmodified**, as published Maven artifacts. Nothing in this module may fork
 * or vendor them: R6 in docs/PLAN.md records why, and what the obligation would
 * become if that changed.
 */
android {
    namespace = "com.osamu.aide.editor"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The tree-sitter grammars are native libraries, so this module carries
        // the same ABI set as the rest of the app.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11

        // The tree-sitter bindings use java.util.concurrent.Flow and friends,
        // which exist only from API 24 upward and not at all on some of the
        // range this module supports. Their AAR metadata refuses to build
        // without this rather than failing at run time, which is the right call.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":core:common"))
    implementation(project(":core:fs"))
    implementation(project(":core:ui"))
    // For Diagnostic: what the gutter shows. :engine:api is a contract with no
    // toolchain in it, so this does not drag the build engine into the editor.
    api(project(":engine:api"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // api, not implementation: sora-editor is exposed to consumers by the api
    // line below, and a versionless api dependency whose constraint stayed in
    // this module resolves to nothing at all in :app.
    api(platform(libs.sora.bom))
    api(libs.sora.editor)
    implementation(libs.sora.language.treesitter)
    implementation(libs.tree.sitter)
    implementation(libs.tree.sitter.java)
    implementation(libs.tree.sitter.kotlin)
    implementation(libs.tree.sitter.xml)
    implementation(libs.tree.sitter.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
