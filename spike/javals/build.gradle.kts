plugins {
    alias(libs.plugins.android.library)
}

/**
 * Spike R3: does a Java language-intelligence core run on ART?
 *
 * M3 needs completion, live diagnostics and go-to-definition, which means a
 * compiler front end resident in the app, answering queries between
 * keystrokes -- a different duty cycle from `:engine:fast`'s batch ECJ, which
 * parses, emits and forgets. The plan asserts "javac/JDT-based, in-process"
 * without evidence; R2 taught that "pure JVM, therefore fine on ART" can cost
 * a session per library, so the assertion gets a spike before `:lsp:java` is
 * designed around it.
 *
 * Candidate under test: nb-javac-android -- NetBeans' error-tolerant javac,
 * patched for Android by the AndroidIDE project, which shipped it as the core
 * of the most widely used open on-device IDE. The fallback candidate, JDT's
 * CompletionEngine, stays untested unless this one fails; findings either way
 * land in `tools/javals/FINDINGS.md`.
 *
 * Like `:spike:jvmtools` before it (deleted; see `tools/ecj/FINDINGS.md`),
 * the dependency is dexed by AGP exactly the way the app would dex it, so the
 * spike exercises the real mechanism.
 *
 * Delete this module once `:lsp:java` answers the same queries under its own
 * tests.
 */
android {
    namespace = "com.osamu.aide.spike.javals"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Matches the app. Language intelligence should work wherever the
        // editor does (minSdk 26); finding a higher floor is part of the point.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    androidResources {
        // android.jar is read as a zip at run time; compressing it into the APK
        // only costs install time and CPU.
        noCompress += listOf("jar")
    }
}

dependencies {
    androidTestImplementation(libs.nb.javac.android)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
