plugins {
    alias(libs.plugins.android.library)
}

/**
 * Spike R16: can a phone run a coding model locally, and is it worth offering?
 *
 * The design being tested is small on purpose. llama.cpp is published for
 * Android by Termux, so `llama-server` arrives the way clang and Node did --
 * an optional download, started through the dynamic linker from app storage --
 * and it serves the OpenAI chat API the assistant's Custom provider already
 * speaks. If that holds, a local model is a download and a process, and no new
 * AI code.
 *
 * The questions, each answered by a test rather than assumed:
 *
 * 1. Does `llama-server` start from app storage at all, and how long until it
 *    answers?
 * 2. How fast does it generate, and how fast does it read a prompt the size the
 *    assistant actually sends -- which on a phone may matter more?
 * 3. Does a small model produce a well-formed tool call, which is how the
 *    assistant reads and edits files? Explaining code is not enough on its own.
 * 4. Would the app be allowed to talk to it: plain HTTP to 127.0.0.1 is subject
 *    to the platform's cleartext policy, and `parseEndpoint` accepts only https.
 *
 * Emulator numbers are the laptop's CPU and say nothing about a phone's speed;
 * they establish that the route works. `tools/localai/FINDINGS.md` has the
 * account, including what is still to be measured on hardware.
 */
android {
    namespace = "com.osamu.aide.spike.localai"
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

dependencies {
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// llama.tar is 30 MB and the model is 470 MB; neither is in git, and without
// them these tests skip -- which reports as OK. tools/localai/fetch-llama.sh
// and fetch-model.sh build them.
extra["deviceTestPackage"] = "com.osamu.aide.spike.localai.test"
extra["deviceArchives"] = listOf(
    "llama.tar",
    "qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
    "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
)
apply(from = rootProject.file("gradle/stage-device-archives.gradle.kts"))
