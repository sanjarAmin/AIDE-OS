plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.osamu.aide.engine.gradle"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // The JDK and Gradle are large downloads and the JVM launch route needs
        // API 30's platform behaviour; the module is gated at runtime by
        // JvmToolchain.isInstalled rather than refusing to install here.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The launcher, jspawnhelper and aapt2 have to be extracted to
    // nativeLibraryDir at install: that is the only directory an app may
    // execute from, and a library left compressed inside the APK has no path on
    // disk at all. Without this the binaries ship and none of them can run.
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

dependencies {
    api(project(":engine:api"))
    implementation(project(":core:common"))
    implementation(project(":core:fs"))
    implementation(project(":toolchain:native"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// The instrumented tests here need real toolchain archives on the device, and
// without them they skip -- which reports as OK. See the script for why that
// mattered enough to automate.
extra["deviceTestPackage"] = "com.osamu.aide.engine.gradle.test"
// `sdk-extra-med.tar` under the name the test reads. **Not the 19 MB
// `sdk-extra.tar`**: build-tools has to be *complete*, not merely useful, and
// trimmed to only the tools AGP runs it fails with "Installed Build Tools
// revision 36.0.0 is corrupted". The medium trim drops lib64/, lld-bin/ and
// renderscript/ and passes. engine/gradle/FINDINGS.md, "build-tools must exist
// and be complete".
extra["deviceArchives"] = listOf(
    "jvm.tar",
    "gradle.tar",
    "sdk36.tar",
    "sdk-extra-med.tar=sdk-extra.tar",
)
apply(from = rootProject.file("gradle/stage-device-archives.gradle.kts"))
