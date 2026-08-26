plugins {
    alias(libs.plugins.android.library)
}

/**
 * The AI layer's engine: client, session state, tools, context assembly.
 *
 * Spike R5 established that the Anthropic SDK runs on ART unmodified, and what
 * around it does not — `tools/ai/FINDINGS.md`. Read it before changing how the
 * client is built or how requests are shaped; the prompt layout in particular
 * is dictated by prompt caching rather than by taste.
 *
 * Bring-your-own-key by design: no backend, no shared credential, no per-user
 * liability. The key belongs to the user and never leaves the device.
 */
android {
    namespace = "com.osamu.aide.ai.core"
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
        // A JVM SDK uses java.time and streams freely, and a library module
        // does not inherit :app's setting. Spike R5 ran with this on from the
        // start, so it is precaution rather than a proven requirement.
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    api(project(":core:common"))
    api(libs.anthropic.java)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // The tool loop is tested against a local Messages API rather than the real
    // one -- see FakeAnthropic. com.sun.net.httpserver does not exist on
    // Android, so this is not optional; tools/ai/FINDINGS.md section 1.
    androidTestImplementation(libs.okhttp.mockwebserver)
}
