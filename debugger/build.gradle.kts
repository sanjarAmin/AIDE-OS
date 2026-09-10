plugins {
    alias(libs.plugins.android.library)
}

/**
 * The debugger: a JDWP client, speaking to an app AIDE-OS built.
 *
 * `docs/PLAN.md` described this as "JDWP client for on-device Java/Kotlin
 * debugging" and assumed the client could attach to any process. It cannot --
 * `@jdwp-control` is closed to apps and an app cannot even enumerate what is
 * debuggable. What it *can* do is talk to a debuggee that opened a port for it,
 * and a debug build produced by AIDE-OS is exactly that: ART ships OpenJDK's
 * `libjdwp.so`, and four lines in the template make an app listen. Spike R15,
 * `tools/jdwp/FINDINGS.md`, is the whole account.
 *
 * So this module is only the client half, and it is deliberately ignorant of
 * how the debuggee came to be listening: given a host and a port it speaks the
 * protocol. That keeps the Shizuku route (attaching to apps AIDE-OS did not
 * build) a question about who opens the port, not a rewrite of this.
 *
 * **The wire layer is unit-tested and the session is instrumented**, because
 * they fail differently. Framing and ID widths are arithmetic and belong in a
 * JVM test; whether ART's VM answers `EventRequest.Set` the way the spec reads
 * is a question only a device settles, and `:spike:jdwpdebuggee` is what it is
 * asked against.
 */
android {
    namespace = "com.osamu.aide.debugger"
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
    }
}

dependencies {
    api(project(":core:common"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
