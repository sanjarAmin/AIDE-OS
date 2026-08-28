plugins {
    alias(libs.plugins.android.library)
}

/**
 * A pseudoterminal, and a shell running in it.
 *
 * Spike R7 (`tools/pty/FINDINGS.md`) answered the platform questions: an
 * unprivileged Android app may call `forkpty`, exec `/system/bin/sh`, and get
 * real job control, and a shell survives the app being backgrounded. Nothing
 * here is a workaround for any of that.
 *
 * **This is the process half only.** There is no terminal emulation: no escape
 * parsing, no screen model, no scrollback. `TerminalSession` moves bytes and
 * manages a lifetime, which is what every emulator needs underneath it and what
 * is the same whichever emulator lands. `docs/PLAN.md` records that choice as
 * still open.
 *
 * `ndkBuild` rather than CMake: the NDK ships `ndk-build`, and no CMake is
 * installed in this SDK. One file of C does not justify making that a
 * prerequisite.
 */
android {
    namespace = "com.osamu.aide.terminal"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // forkpty is declared from API 23, and nothing here needs the API 30
        // floor the build engine has: a terminal is useful wherever the editor
        // runs.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core:common"))

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // Pressing HOME from a test, for BackgroundSurvivalTest.
    androidTestImplementation(libs.androidx.uiautomator)
}
