plugins {
    alias(libs.plugins.android.library)
}

/**
 * The contract between the UI and whichever engine builds a project: the fast
 * bundled pipeline, or Gradle in the rootfs. It deliberately knows nothing about
 * either -- no compiler, no aapt2, no toolchain types -- so that a screen can
 * render a build without depending on how it happens.
 */
android {
    namespace = "com.osamu.aide.engine.api"
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
    // Both are part of this module's public surface: BuildRequest exposes
    // Project, and build() returns a Flow.
    api(project(":core:common"))
    api(project(":core:fs"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
