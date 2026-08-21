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

// Bundled native executables (aapt2 today, clang later) and the exec harness.
include(":toolchain:native")

// Spike R2 -- Kotlin compiler + Compose plugin on ART. Not part of the app.
include(":spike:kotlinc")
