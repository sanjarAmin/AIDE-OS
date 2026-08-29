import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

android {
    // The module path is :toolchain:native, but the namespace cannot mirror it:
    // `native` is a reserved word in Java, so the generated BuildConfig would
    // not compile.
    namespace = "com.osamu.aide.toolchain.nativetools"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // arm64 is what real devices run; x86_64 exists so the pipeline can be
        // tested on an emulator without emulating a foreign architecture.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Bundled tools must be extracted to nativeLibraryDir at install time.
    // Without this they stay compressed inside the APK, where they have no path
    // on disk and therefore cannot be executed.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

/**
 * Builds the JVM launcher with the NDK's clang, into `jniLibs`.
 *
 * Not `ndkBuild`, and the reason is worth stating because the obvious routes
 * both fail. Only files named `lib….so` are packaged into `jniLibs` and
 * extracted to `nativeLibraryDir` -- the one directory an app may execute from
 * -- but `ndk-build` refuses an extension in an executable's module name, and
 * building a *shared library* with `-Wl,-e,main` instead produces a file that
 * starts and then crashes before reaching `main`: a shared object is linked
 * without the C runtime startup, so libc and TLS are never initialised.
 *
 * So it is compiled as a real executable and named `lib….so`, exactly as
 * aapt2 is shipped. `tools/rootfs/FINDINGS.md` sections 4 and 7.
 */
val buildJvmLauncher by tasks.registering {
    val ndk = Properties().apply {
        rootProject.file("local.properties").inputStream().use { load(it) }
    }.getProperty("sdk.dir")
        ?.let { sdk -> File(sdk, "ndk").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name } }
        ?: error("no NDK beside the SDK named in local.properties")

    // Captured at configuration time; a task that reaches for `project` while
    // it runs cannot be cached.
    val clang = File(ndk, "toolchains/llvm/prebuilt/linux-x86_64/bin/clang")
    val source = file("src/main/jni/launch_jvm.c")
    val outputDir = file("src/main/jniLibs")
    val targets = mapOf(
        "arm64-v8a" to "aarch64-linux-android26",
        "x86_64" to "x86_64-linux-android26",
    )

    inputs.file(source)
    outputs.files(targets.keys.map { File(outputDir, "$it/libjvmlauncher.so") })

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
            if (process.waitFor() != 0) error("building the JVM launcher for $abi failed:\n$output")
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }
    .configureEach { dependsOn(buildJvmLauncher) }

dependencies {
    api(project(":core:common"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
