pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AIDE-OS"

include(":app")

// Core infrastructure shared by every feature module.
include(":core:common")
include(":core:fs")
include(":core:ui")

// The build engine. Named :engine rather than the plan's :build because a
// source directory called build/ is the root project's Gradle output directory:
// gradlew clean would delete it and .gitignore would hide it.
include(":engine:api")

// Bundled native executables (aapt2 today, clang later) and the exec harness.
include(":toolchain:native")

// Spike R2 -- Kotlin compiler + Compose plugin on ART. Not part of the app.
include(":spike:kotlinc")

// Spike R2b -- ECJ, D8 and apksig on ART. Not part of the app.
include(":spike:jvmtools")
