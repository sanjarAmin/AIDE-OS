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

// The code editor: sora-editor wrapped for Compose, plus tree-sitter grammars.
include(":editor")

// The build engine. Named :engine rather than the plan's :build because a
// source directory called build/ is the root project's Gradle output directory:
// gradlew clean would delete it and .gitignore would hide it.
include(":engine:api")
include(":engine:fast")

// Bundled native executables (aapt2 today, clang later) and the exec harness.
include(":toolchain:native")

// Components too large to bundle: downloaded, verified, installed on device.
include(":toolchain:manager")

// Java language intelligence: completion, diagnostics, go-to-definition. In
// process, not over a socket -- see :lsp:client for the transports the C++ and
// Kotlin servers will need. Spike R3 in tools/javals/FINDINGS.md is the design
// input; the short version is that a compiler has to stay warm between
// keystrokes or nothing here meets its latency budget.
include(":lsp:java")

// Spike R3 -- a Java language-intelligence core on ART. Not part of the app.
include(":spike:javals")

// Spike R4 -- Maven resolution on ART, which M4 is designed around. Not part
// of the app.
include(":spike:deps")
