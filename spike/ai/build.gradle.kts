plugins {
    alias(libs.plugins.android.library)
}

/**
 * Spike R5: does the Anthropic SDK run on ART?
 *
 * `docs/PLAN.md` designs M5 around `com.anthropic:anthropic-java` and settles
 * the question in a parenthesis -- "(OkHttp works fine on Android)". That is
 * the same shape of claim as "pure JVM, therefore fine on ART", which this
 * project has now been wrong about three times: kotlinc needed seven startup
 * fixes, ECJ a hand-written `javax.lang.model` shim plus a stubs jar, and
 * maven-resolver four workarounds, one of which ruled out half that library's
 * released versions.
 *
 * OkHttp genuinely is fine on Android. The SDK around it is the question:
 *
 * 1. **Does it dex and load?** It is a large JVM library. Jackson is the usual
 *    offender -- reflection and `java.beans` shapes that ART does not have.
 * 2. **`java.time`.** Used throughout modern JVM SDKs and only available from
 *    API 26 without desugaring. `:app` enables core library desugaring; a
 *    library module does not inherit it.
 * 3. **`java.net.http`.** Absent from Android at every API level. It is what
 *    ruled out maven-resolver 2.x, and a JVM SDK may reach for it.
 * 4. **Streaming.** The plan wants token-by-token output. Whether the SDK's
 *    streaming helper works on a device is a separate question from whether a
 *    request completes.
 * 5. **Prompt caching.** The plan calls it "the cost lever". Verifiable only by
 *    reading `usage.cache_read_input_tokens` back on a second turn.
 *
 * Needs a real API key, so every test skips without one rather than failing:
 *
 *     ./gradlew :spike:ai:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.anthropicApiKey=sk-ant-...
 *
 * The key is passed as an instrumentation argument and never written to disk,
 * never logged, and never committed. Delete this module once `:ai:core`
 * answers the same questions under its own tests.
 */
android {
    namespace = "com.osamu.aide.spike.ai"
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
        // Question 2. A JVM SDK will use java.time and streams freely; without
        // this the failure is a NoClassDefFoundError at run time on anything
        // below the API level that added them.
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    androidTestImplementation(libs.anthropic.java)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
