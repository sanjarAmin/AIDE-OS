plugins {
    alias(libs.plugins.android.library)
}

/**
 * Spike R7: can an unprivileged Android app open a PTY and run a shell in it?
 *
 * M8's other half. Every previous risk in this project was "does this JVM
 * library work on ART"; this one is not. **There is no PTY API in Java at
 * all**, so the terminal is the first thing here that cannot be attempted
 * without native code, and the questions are about the platform rather than
 * about a library.
 *
 * The specific doubts, in the order they are likely to bite:
 *
 * 1. **`forkpty` on Bionic.** It is declared from API 23, comfortably under
 *    this project's `minSdk` of 26. Declared is not the same as permitted: a
 *    PTY is allocated through `/dev/ptmx`, and whether an `untrusted_app`
 *    SELinux domain may open it is a policy question, not a libc one.
 * 2. **`exec` under W^X.** `tools/aapt2/FINDINGS.md` records that Android
 *    refuses to exec a binary out of the app's data directory, which is why
 *    aapt2 ships in `jniLibs`. A shell is different -- `/system/bin/sh` is
 *    already executable and already labelled -- but whether an app may exec it
 *    *as a session leader on a new controlling terminal* is the actual question.
 * 3. **Job control.** A terminal that cannot deliver `SIGINT` to a foreground
 *    process group is a log viewer, not a terminal. That needs `setsid`, a
 *    controlling TTY, and `TIOCSPGRP` to all work.
 * 4. **Reaping and lifetime.** Android kills process groups when an app is
 *    backgrounded. What happens to a shell mid-command decides whether the
 *    terminal can be a tab you leave open.
 *
 * `ndkBuild` rather than CMake: the NDK ships `ndk-build`, and no CMake is
 * installed in this SDK. One file of C does not justify making that a
 * prerequisite.
 *
 * Delete this module once `:terminal` answers the same questions under its own
 * tests.
 */
android {
    namespace = "com.osamu.aide.spike.pty"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // The two this project's devices and its emulator actually are.
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
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Pressing HOME from a test: the only way to put the app in the state whose
    // effect on a running shell is the question.
    androidTestImplementation(libs.androidx.uiautomator)
}
