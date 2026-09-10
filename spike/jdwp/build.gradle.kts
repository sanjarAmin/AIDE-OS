plugins {
    alias(libs.plugins.android.library)
}

/**
 * Spike R15: can an unprivileged app debug another app on the same phone?
 *
 * `docs/PLAN.md` lists `:debugger` -- "JDWP client for on-device Java/Kotlin
 * debugging" -- as a module, and every other milestone that looked like a
 * library problem turned out to be a platform-permission problem first: clang
 * could not spawn, Node's `execPath` was the linker, the log showed only our
 * own process. This asks the permission question before any protocol code is
 * written, because the answer decides whether `:debugger` is a JDWP client at
 * all.
 *
 * The routes, in the order they are worth trying:
 *
 * 1. **The `@jdwp-control` abstract socket.** It exists and is listening. If an
 *    app may connect to it and speak the control protocol, everything else is
 *    ordinary JDWP over a socket. SELinux is the doubt: `untrusted_app` is not
 *    normally allowed to talk to `adbd`'s sockets, and `run-as` cannot answer
 *    this -- it runs in `runas_app`, a different domain, which
 *    `tools/clang/FINDINGS.md` section 7 already recorded getting wrong once.
 * 2. **adbd over TCP**, the way wireless debugging works. Not listening by
 *    default; the question is whether an app can reach it once the user turns
 *    it on, which would make the debugger's prerequisite a settings toggle
 *    rather than a root prompt.
 * 3. **A JVMTI agent inside the debuggee.** `Debug.attachJvmtiAgent` attaches
 *    to the *calling* process only -- useless for attaching to someone else,
 *    but AIDE-OS controls what it builds, so a stub the template links in
 *    could open a channel from the inside. That trades "debug any app" for
 *    "debug apps built here", which for this product may be the whole
 *    requirement.
 *
 * The deliverable is `FINDINGS.md`: which routes are open, what each costs the
 * user, and therefore what `:debugger` is. Delete this module once that module
 * answers the same questions under its own tests.
 */
android {
    namespace = "com.osamu.aide.spike.jdwp"
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
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
