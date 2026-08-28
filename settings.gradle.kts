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

// Maven resolution and AAR extraction. Spike R4 (tools/deps/FINDINGS.md) is
// the design input; the resolver needs four workarounds to run on ART.
include(":engine:deps")

// Bundled native executables (aapt2 today, clang later) and the exec harness.
include(":toolchain:native")

// Components too large to bundle: downloaded, verified, installed on device.
include(":toolchain:manager")

// The AI layer: Anthropic client, session state, tool definitions, context
// assembly. Bring-your-own-key; see tools/ai/FINDINGS.md for spike R5.
include(":ai:core")

// The AI layer's Compose surface. Thin on purpose -- the decisions live in
// :ai:core so they can be tested without a composition.
include(":ai:ui")

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

// Spike R5 -- the Anthropic SDK on ART, which M5 is designed around. Not part
// of the app.
include(":spike:ai")

// Spike R10 -- clang on a device. It runs, and the .so it builds loads: the
// tests here are what says so, and they are also where the two rules M7 has
// to obey are pinned. Kept rather than retired, because a toolchain update
// is exactly when they need re-checking. Not part of the app.
include(":spike:clang")

// Spike R9 -- executing a downloaded native binary, which M7 is designed
// around and which docs/PLAN.md is currently inconsistent about. Not part of
// the app.
include(":spike:nativeexec")

// A pseudoterminal and the shell in it. Spike R7 answered the platform
// questions; the terminal *emulator* is a separate decision and is not here.
include(":terminal")

// Version control. JGit, which spike R6 found needs no workarounds on ART --
// but which gets no global config or credential helper there, so identity and
// tokens are this module's to store.
include(":vcs:git")

// Spike R6 -- JGit on ART, which M8 is designed around. Not part of the app.
include(":spike:git")

