plugins {
    alias(libs.plugins.android.library)
}

/**
 * Spike R10: does clang run on a device, and is what it produces usable?
 *
 * Spike R9 (`tools/nativeexec/FINDINGS.md`) established the *route* — a
 * downloaded binary runs from internal storage when launched through
 * `/system/bin/linker64`. It deliberately used `/system/bin/toybox` as the
 * payload, because the question was where a binary may run from rather than
 * which binary.
 *
 * This asks the second question, and it is not a formality. Three times now this
 * project has assumed a binary or a library would simply work and been wrong:
 * kotlinc needed seven fixes before it would start, ECJ needed a shim and a
 * stubs jar, maven-resolver needed four workarounds. clang is a compiler driver
 * that re-execs itself, reads `/proc/self/exe` to locate its own resource
 * directory, and expects a sysroot at a prefix this app cannot write to.
 *
 * The acceptance question is not "does clang print a version". It is whether a
 * `.so` it produced **loads and runs**, which the test asserts by having the
 * compiled code write a marker from `JNI_OnLoad`.
 *
 * The toolchain is Termux's build of LLVM, which `docs/PLAN.md` already names.
 * The spike does not download it: `:toolchain:manager` is where that belongs,
 * and mixing the two would make a failure ambiguous.
 *
 * Delete this module once `:toolchain:native` runs a real toolchain under its
 * own tests.
 */
android {
    namespace = "com.osamu.aide.spike.clang"
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
