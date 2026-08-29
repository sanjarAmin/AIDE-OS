import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.osamu.aide.spike.rootfs"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    // The launcher is an executable named lib*.so; it has to be extracted to
    // nativeLibraryDir at install or it has no path on disk to run from.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

/**
 * Builds the launcher with the NDK's clang, straight into `jniLibs`.
 *
 * Not `ndkBuild`, and the reason is worth stating because the obvious routes
 * both fail. Only files named `lib….so` are packaged into `jniLibs` and
 * extracted to `nativeLibraryDir` -- the one directory an app may execute from
 * -- but `ndk-build` refuses an extension in an executable's module name, and
 * building a *shared library* with `-Wl,-e,main` instead produces a file that
 * starts and then crashes before reaching `main`: a shared object is linked
 * without the C runtime startup, so libc and TLS are never initialised and the
 * first `fprintf` segfaults.
 *
 * So it is compiled as a real executable and simply named `lib….so`, which is
 * exactly what `:toolchain:native` ships aapt2 as.
 */
val buildLauncher by tasks.registering {
    // Located from local.properties rather than an AGP accessor: this AGP does
    // not expose ndkDirectory to a library module, and the NDK sits beside the
    // SDK by construction. The newest installed one is used, since the launcher
    // depends on nothing version-specific.
    val ndk = Properties().apply {
        rootProject.file("local.properties").inputStream().use { load(it) }
    }.getProperty("sdk.dir")
        ?.let { sdk ->
            File(sdk, "ndk").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
        }
        ?: error("no NDK beside the SDK named in local.properties")

    // Everything the action needs is captured here, at configuration time. A
    // task that reaches for `project` while it runs cannot be cached.
    val clang = File(ndk, "toolchains/llvm/prebuilt/linux-x86_64/bin/clang")
    val source = file("src/main/jni/launch_jvm.c")
    val outputDir = file("src/main/jniLibs")
    val targets = mapOf(
        "arm64-v8a" to "aarch64-linux-android26",
        "x86_64" to "x86_64-linux-android26",
    )

    inputs.file(source)
    outputs.dir(outputDir)

    doLast {
        targets.forEach { (abi, triple) ->
            val out = File(outputDir, abi).apply { mkdirs() }
            val command = listOf(
                clang.absolutePath, "--target=$triple",
                "-O2", "-fPIE", "-pie",
                source.absolutePath,
                "-ldl", "-llog",
                "-o", File(out, "libjvmlauncher.so").absolutePath,
            )
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) error("building the launcher for $abi failed:\n$output")
        }
    }
}

// Built before anything packages it. AGP reads src/main/jniLibs without being
// told, but nothing there tells it the directory is generated.
tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }
    .configureEach { dependsOn(buildLauncher) }

dependencies {
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
