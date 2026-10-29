plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * One line of git's output, or null when git is missing or fails.
 *
 * Through `providers.exec` so the configuration cache records the answer and
 * re-asks it each build, rather than baking in whatever HEAD was when the cache
 * was written.
 */
fun git(vararg args: String): String? = runCatching {
    val run = providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }
    if (run.result.get().exitValue != 0) null else run.standardOutput.asText.get().trim().ifEmpty { null }
}.getOrNull()

/**
 * **The version comes from git.** It was `1` / `"1.0"` for nine published
 * previews, so About said "Version 1.0" on every one of them and a bug report
 * could not name the build it came from. The commit count only rises along
 * `main`, which is the one line previews are cut from, so it serves as the
 * versionCode Android needs to accept an update; the commit itself goes into
 * BuildConfig, where the update check compares it with what GitHub publishes.
 *
 * A source tree without git -- an archive download -- builds as version 1 with
 * no commit, and the update check then says it cannot tell rather than guess.
 * A shallow clone would count too few commits, which is why `fetch-depth` has
 * to be full anywhere a release is built.
 */
val commitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull()
val headCommit = git("rev-parse", "HEAD").orEmpty()
// Tracked files only: an untracked scratch file is not part of what was built.
val hasLocalChanges = git("status", "--porcelain", "--untracked-files=no") != null

/**
 * `-Paide.pretendBuiltFrom=<sha>` builds an APK that believes it is the
 * pre-versioning preview at <sha>: that commit, versionCode 1. It is the only
 * way to drive the update path against real releases before a release that
 * has it exists -- install this, and the app finds the previews published
 * after <sha> and installs one over itself. Never for anything published.
 */
val pretendBuiltFrom = providers.gradleProperty("aide.pretendBuiltFrom").orNull

android {
    namespace = "com.osamu.aide"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.osamu.aide"
        minSdk = 26
        targetSdk = 37
        versionCode = if (pretendBuiltFrom != null) 1 else commitCount ?: 1
        // "0." because these are previews; M13's first real release is 1.0.
        versionName = if (pretendBuiltFrom != null) "1.0" else commitCount?.let { "0.$it" } ?: "0.0"
        buildConfigField("String", "GIT_COMMIT", "\"${pretendBuiltFrom ?: headCommit}\"")
        buildConfigField("boolean", "GIT_LOCAL_CHANGES", "${pretendBuiltFrom == null && hasLocalChanges}")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // aapt2 and the tree-sitter grammars are native, and the app is only
        // useful on a device it has both for. Left unset, the APK would carry
        // tree-sitter for armeabi-v7a and x86 -- ABIs with no aapt2 beside them,
        // where the editor would open and every build would fail at exec time.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11

        // Required by the tree-sitter bindings :editor pulls in; their AAR
        // metadata refuses to build without it. See editor/build.gradle.kts.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The build engine executes aapt2 (and later clang) from the app's native
    // library directory, the only location Android's W^X policy permits since
    // API 29. That requires the binaries to be extracted at install time rather
    // than loaded straight from the APK.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }

        resources {
            // Maven's own jars, which :engine:deps brings in, each carry a copy
            // of these and the merger will not choose between them -- seventeen
            // files named META-INF/DEPENDENCIES stop the build outright. The
            // same excludes exist in :engine:deps, and library packaging rules
            // do not propagate to a consumer, so they have to be repeated here.
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/sisu/**",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":ai:core"))
    implementation(project(":ai:ui"))
    implementation(project(":core:common"))
    implementation(project(":core:fs"))
    implementation(project(":core:ui"))
    implementation(project(":debugger"))
    implementation(project(":editor"))
    implementation(project(":engine:api"))
    implementation(project(":engine:deps"))
    implementation(project(":engine:fast"))
    implementation(project(":engine:gradle"))
    implementation(project(":engine:node"))
    implementation(project(":engine:mono"))
    implementation(project(":engine:python"))
    implementation(project(":lsp:java"))
    implementation(project(":lsp:native"))
    implementation(project(":lsp:node"))
    implementation(project(":lsp:kotlin"))
    implementation(project(":toolchain:native"))
    implementation(project(":terminal"))
    implementation(project(":toolchain:manager"))
    implementation(project(":vcs:git"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)

    // Declared rather than borrowed from :ai:core's `api`: the update check
    // talks to GitHub itself, and should not stop compiling if the AI layer
    // ever narrows what it exports.
    implementation(libs.okhttp)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    testImplementation(libs.junit)
    // The tool handlers are suspending -- a build is not a synchronous call.
    testImplementation(libs.kotlinx.coroutines.test)
    // The real org.json, as :ai:core's tests use: android.jar's is a stub that
    // throws in a JVM test, and the benchmark's reply classifier parses JSON.
    testImplementation(libs.json)
    // The update check is tested against a GitHub of our own, so the JSON it
    // reads and the HTTP statuses it handles are exercised without a network.
    testImplementation(libs.okhttp.mockwebserver)
    // JGit is `implementation` in :vcs:git, so it does not reach here on its
    // own. These tests read commits back out of the object database, which is
    // the only way to assert a commit was really written.
    androidTestImplementation(libs.jgit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// KotlinAndroidXCompletionTest drives the whole Kotlin path -- Maven to AAR to
// session to completion -- and needs the two Kotlin components on the device to
// do it. Without them it skips, and a skip reports as OK.
// The **app** package, not `.test`. For a library module the two are the same
// process and either works, but here the instrumentation's own context returns
// null from getExternalFilesDir, so the only directory the test can read is the
// one belonging to the app under test.
extra["deviceTestPackage"] = "com.osamu.aide"
extra["deviceArchives"] = listOf(
    "kotlinc-archive.zip=kotlin-compiler-2.2.10.zip",
    "kotlin-analysis-2.2.10.zip",
    // 109 MB, and without it the JavaScript run test can only prove that the
    // download is offered -- which is the half of the feature that does not
    // start a program.
    "node.tar",
    "mono.tar",
)
apply(from = rootProject.file("gradle/stage-device-archives.gradle.kts"))
