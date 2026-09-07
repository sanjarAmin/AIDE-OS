plugins {
    alias(libs.plugins.android.library)
}

/**
 * Spike R14: can C# be compiled and run on the device?
 *
 * M10's second half. The roadmap words it as ".NET SDK (experimental)", and
 * that phrasing does not survive contact with the platform: **Termux publishes
 * no `dotnet`**, and Microsoft's builds are glibc, so they cannot start here at
 * all -- the same wall `tools/node` hits with the builds from nodejs.org. What
 * Termux does publish is `mono`, built against Bionic, which carries both
 * halves the milestone asks for: `mcs` compiles and `mono` runs.
 *
 * Two things are already known to be awkward before a line of it runs, both
 * from unpacking the archive on the host:
 *
 *  - `bin/mono` is a **symlink** to `mono-sgen`. Symlinks survive because the
 *    archive is transferred as a tar and unpacked on the device; `adb push` of
 *    a tree drops all of them (`tools/clang/FINDINGS.md` §4).
 *  - `bin/mcs` is **not a binary**. It is a shell script that hardcodes
 *    `/data/data/com.termux/files/usr` three times over, a prefix this app
 *    neither has nor can create. So the compiler has to be invoked as what it
 *    actually is -- an assembly run by the runtime -- exactly as npm has to be
 *    invoked as `npm-cli.js` rather than through `bin/npm`.
 *
 * The open question is whether mono can be told where it lives. It resolves its
 * class library relative to a prefix fixed at build time, and if that cannot be
 * moved then this half of M10 is closed by the same kind of wall that closed
 * the rootfs route in R4.
 *
 * Delete this module once M10 has an answer either way.
 */
android {
    namespace = "com.osamu.aide.spike.mono"
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

// mono.tar is 222 MB and is not in git; without it these tests skip, and a skip
// reports as OK.
extra["deviceTestPackage"] = "com.osamu.aide.spike.mono.test"
extra["deviceArchives"] = listOf("mono.tar")
apply(from = rootProject.file("gradle/stage-device-archives.gradle.kts"))
