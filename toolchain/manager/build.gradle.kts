plugins {
    alias(libs.plugins.android.library)
}

/**
 * Components too large to ship inside the APK: downloaded, checksum-verified,
 * and installed into app storage.
 *
 * `android.jar` is the first and the one M2 needs -- the fast build engine
 * cannot compile a line without it. The NDK sysroot and the Kotlin compiler
 * archive arrive the same way later; see docs/PLAN.md.
 */
android {
    namespace = "com.osamu.aide.toolchain.manager"
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

/**
 * `-Ppins=true` reaches the test JVM.
 *
 * It did not. `PinnedReleaseTest` gates itself on `System.getProperty("pins")`,
 * a Gradle *project* property is not a system property, and an
 * `AssumptionViolatedException` is not a failure -- so the documented command
 * printed BUILD SUCCESSFUL in three seconds having checked nothing, which is
 * the worst possible way for a network-gated test to behave. The pins are the
 * one number a user's install depends on, and this is the only thing that
 * checks them.
 */
tasks.withType<Test>().configureEach {
    systemProperty("pins", providers.gradleProperty("pins").getOrElse("false"))
    // Rerun when the flag changes: the task is otherwise up to date from the
    // last run and skips again, silently, which is the same failure by another
    // route.
    inputs.property("pins", providers.gradleProperty("pins").getOrElse("false"))
}

dependencies {
    api(project(":core:common"))
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
    testImplementation(libs.commons.compress)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
