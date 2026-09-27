plugins {
    alias(libs.plugins.android.library)
}

/**
 * Git, through JGit.
 *
 * Spike R6 (`tools/git/FINDINGS.md`) established that JGit runs on ART
 * unmodified -- the first "pure JVM, therefore fine" claim in this project that
 * held. So unlike `:engine:deps`, nothing here is a workaround, and the module
 * is small on purpose: it is a coroutine-and-`AppResult` skin over JGit's
 * porcelain, plus the two things Android does not give it.
 *
 * Those two are the whole reason this is a module rather than a few calls at
 * the call site. `FS.detect()` reports **no system config and no user home** on
 * a device, which means:
 *
 * - there is no `user.name`/`user.email` to fall back on, so every commit must
 *   carry an identity this module stores ([GitIdentityStore]);
 * - there is no credential helper and no `~/.gitconfig`, so a token is ours to
 *   keep ([GitCredentialStore], Keystore-backed like `ApiKeyStore`).
 *
 * Read `tools/git/FINDINGS.md` before assuming a git behaviour holds here.
 */
android {
    namespace = "com.osamu.aide.vcs.git"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Nothing here execs a binary, so there is no API 30 floor: version
        // control is useful anywhere the editor runs.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.jgit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
