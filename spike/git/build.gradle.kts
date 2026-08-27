plugins {
    alias(libs.plugins.android.library)
}

/**
 * Spike R6: does JGit run on ART?
 *
 * `docs/PLAN.md` lists JGit as "Pure JVM", which is the phrase that has cost
 * this project three times already -- kotlinc needed seven fixes before it
 * would start, ECJ needed a hand-written `javax.lang.model` shim, and
 * maven-resolver needed four workarounds and ruled out its own 2.x line. M8 is
 * designed around this assumption, so it gets a spike before the design.
 *
 * The specific doubts, in the order they are likely to bite:
 *
 * 1. **`FS.detect()`.** JGit's filesystem abstraction probes its environment on
 *    first use: it looks for a `git` executable on `PATH` and runs a shell to
 *    read the umask. Android has no `/bin/sh` -- it is `/system/bin/sh` -- and
 *    no `git` anywhere. Whether that degrades or throws decides whether any of
 *    the rest is reachable.
 * 2. **`java.nio.file`.** JGit 6+ uses it throughout, including POSIX
 *    attributes and symbolic links. Android has the API from 26 but not every
 *    provider operation behind it, and a `FileStore` question that answers
 *    wrongly shows up as corrupt index state rather than as an exception.
 * 3. **Java 17 bytecode.** The artifact targets 17. D8 desugars, so the
 *    question is not whether it dexes but whether anything JGit calls at
 *    runtime is a JDK 9+ API with no Android equivalent -- `ProcessHandle` is
 *    the one to watch, since it is what a modern JVM uses to reap the
 *    subprocesses of point 1.
 * 4. **Transport.** JGit's HTTP transport is built on `HttpURLConnection`,
 *    which Android does have -- unlike maven-resolver 2.x's `java.net.http`.
 *    That is the one point of optimism here. SSH is a separate artifact and is
 *    deliberately out of scope: the acceptance test says "clone from GitHub",
 *    and HTTPS with a token is the path a phone can actually take.
 *
 * Answering "does it work" is not the point. The point is the list of things
 * that have to be worked around, and their cost, before `:vcs:git` is designed
 * around them.
 *
 * Delete this module once `:vcs:git` answers the same questions under its own
 * tests.
 */
android {
    namespace = "com.osamu.aide.spike.git"
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
    androidTestImplementation(libs.jgit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
