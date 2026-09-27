plugins {
    alias(libs.plugins.android.library)
}

/**
 * Spike R9: can a **downloaded** native binary be executed?
 *
 * `docs/PLAN.md` contains an unresolved tension with itself, and M7 is built on
 * the wrong half of it. Line 122 records that Android 10+ enforces W^X and
 * refuses to execute anything under the app data directory, which is why
 * `aapt2` ships inside `jniLibs` -- `nativeLibraryDir` is the one exempt place.
 * Line 229 then plans clang, lld and an NDK sysroot as "an optional ~400 MB
 * download", which lands in app data.
 *
 * Both cannot be true. A 400 MB payload cannot go in `jniLibs`: that directory
 * is fixed at install time, and no store or sideloaded APK wants those
 * megabytes for a feature most users never open.
 *
 * So the question this settles, before any of M7 is designed, is **by what
 * route a binary that was not in the APK can be run**, if any:
 *
 * 1. Directly, from `filesDir`. Expected to fail; the point is to record the
 *    exact error, since "permission denied" and "not executable" send a reader
 *    to different places.
 * 2. Through `/system/bin/linker64`, which `docs/PLAN.md` R4 already names as a
 *    fallback exec strategy but which nothing here has tried. The kernel execs
 *    the linker, which is in `/system/bin` and executable; the payload is only
 *    mapped. If that works, M7's download plan survives intact.
 * 3. From external storage, which some projects reach for and which has its own
 *    mount options.
 *
 * Uses `/system/bin/toybox` as the payload rather than clang: it is a real
 * dynamically linked executable already on the device, and the question is
 * about *where a binary may be run from*, not about which binary. Answering it
 * does not need a 400 MB download.
 *
 * Delete this module once `:toolchain:manager` installs a real toolchain by
 * whichever route wins.
 */
android {
    namespace = "com.osamu.aide.spike.nativeexec"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // No `targetSdk` here: a library module has none, and the test APK inherits
    // `compileSdk`. That matters, because **W^X is enforced by target SDK** --
    // a spike that ended up targeting 28 would answer a question nobody is
    // asking, since exec from app data was legal then.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
