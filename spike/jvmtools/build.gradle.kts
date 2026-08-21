plugins {
    alias(libs.plugins.android.library)
}

/**
 * Spike R2b: do ECJ, D8 and apksig actually run on ART?
 *
 * Numbered against R2 rather than given a new number: it is the same risk --
 * a JVM toolchain meeting a runtime that is not a JVM -- for the three tools
 * R2 did not cover. (And "R8" as a risk number would collide with R8 the
 * dexer, which is one of the things under test.)
 *
 * The plan asserts they do -- "pure JVM, therefore fine on ART" -- and builds
 * the entire fast path on that assertion. The same assertion about kotlinc was
 * true in principle and cost a full session in practice, because a JVM library
 * can depend on far more of a JDK than its bytecode suggests. This module tests
 * the three remaining ones before `:build:fast` is designed around them.
 *
 * Unlike `:spike:kotlinc`, these are ordinary Gradle dependencies: AGP dexes
 * them into the test APK the same way it will dex them into the app, so this
 * spike exercises the real mechanism rather than a stand-in for it.
 *
 * Delete this module once `:build:fast` runs the same three stages under its
 * own tests.
 */
android {
    namespace = "com.osamu.aide.spike.jvmtools"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Matches the app. The toolchain's own floor is API 30 (see
        // docs/PLAN.md), but nothing here should need it, and finding out
        // otherwise is part of the point.
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
        noCompress += listOf("jar", "p12")
    }
}

dependencies {
    androidTestImplementation(libs.ecj)
    androidTestImplementation(libs.r8)
    androidTestImplementation(libs.apksig)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
