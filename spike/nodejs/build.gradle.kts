plugins {
    alias(libs.plugins.android.library)
}

/**
 * Spike R13: can a JavaScript runtime run on the device?
 *
 * The first question M10 asks. The milestone is written as "QuickJS + Node;
 * .NET SDK (experimental)", and before any of it is designed the same thing has
 * to be settled that R9 settled for a downloaded binary and R11 for a JVM:
 * **whether a real Node.js starts at all from app-private storage**, and if so
 * what it costs.
 *
 * There is good reason to expect yes and good reason to check anyway. Termux
 * builds Node against Bionic, so `bin/node` is an ordinary Android ELF with
 * `/system/bin/linker64` as its interpreter -- the one shape spike R9
 * established this app can start, and the shape the JDK and clang both have.
 * What is *not* transferable is everything those two needed on top: the JDK
 * re-execs itself unless `LD_LIBRARY_PATH` is right, and clang cannot spawn a
 * child at all. Node is a single 100 MB binary that spawns nothing for
 * `node script.js`, but it does open sockets, use `libuv`'s threads, and want a
 * writable `HOME` and `TMPDIR`. Which of those the app's SELinux domain permits
 * is not knowable from the other two spikes.
 *
 * **nodejs-lts, from Termux, assembled by `tools/node/fetch-node.sh`.** The
 * builds from nodejs.org are glibc and cannot run here at all, which is the
 * same reason `tools/clang` uses Termux's clang rather than the NDK's.
 *
 * The C# half of M10 is not asked here. Termux publishes `mono` (~9 MB) and no
 * `dotnet`, so that half is a separate question with a separate answer.
 *
 * Delete this module once `:toolchain:manager` installs a JS runtime by
 * whichever route wins.
 */
android {
    namespace = "com.osamu.aide.spike.nodejs"
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

// node.tar is 101 MB and is not in git; without it these tests skip, and a skip
// reports as OK.
extra["deviceTestPackage"] = "com.osamu.aide.spike.nodejs.test"
extra["deviceArchives"] = listOf("node.tar")
apply(from = rootProject.file("gradle/stage-device-archives.gradle.kts"))
