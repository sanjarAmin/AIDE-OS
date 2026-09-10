plugins {
    alias(libs.plugins.android.application)
}

/**
 * The other half of spike R15: an app that *is* debugged.
 *
 * `PlatformJdwpAgentTest` showed a process can attach the platform's own
 * `libjdwp.so` to itself and get a JDWP server on a TCP port. That is the
 * debuggee half of a debugger and it needs no native code of ours -- but it was
 * proved in one process talking to itself, which proves nothing about the shape
 * `:debugger` needs: **the IDE is a different app.**
 *
 * So this is a second app, deliberately: a separate package, a separate UID, a
 * separate process, doing exactly what a debug build produced by AIDE-OS would
 * do. `JdwpAcrossAppsTest` in `:spike:jdwp` connects to it. Whether one app may
 * reach another's loopback port is the question that decides whether the
 * debugger works at all, and it cannot be asked from inside a single process.
 *
 * Installed by hand, not by Gradle -- `connectedAndroidTest` would uninstall it
 * between runs, and the test that needs it assumes and skips rather than fails.
 */
android {
    namespace = "com.osamu.aide.spike.jdwpdebuggee"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.osamu.aide.spike.jdwpdebuggee"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        // debuggable is what lets the agent attach at all, and that is the
        // point rather than an accident: the platform refuses attachJvmtiAgent
        // in a release build, which is exactly the gate a debugger should have.
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
