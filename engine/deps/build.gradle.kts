plugins {
    alias(libs.plugins.android.library)
}

/**
 * Maven resolution and AAR extraction, on the device.
 *
 * Spike R4 established that `maven-resolver` runs on ART but not as shipped:
 * four separate workarounds stand between the library and a resolved AndroidX
 * graph, and one of them rules out the 2.x line entirely. **Read
 * `tools/deps/FINDINGS.md` before changing anything about how the repository
 * system is constructed** -- `AndroidRepositorySystem` looks like gratuitous
 * subclassing and every override in it is load-bearing.
 */
android {
    namespace = "com.osamu.aide.engine.deps"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Nothing here execs a binary, so unlike :engine:fast there is no API 30
        // floor. Resolution is useful anywhere the editor runs.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            // Maven's jars each carry their own copy of these and the merger
            // will not choose between them.
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/sisu/**",
            )
        }
    }
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.maven.resolver.supplier)
    implementation(libs.maven.resolver.connector.basic)
    // Named in RepositorySystemSupplier.getTransporterFactories's own signature
    // via ChecksumExtractor, so it stays on the classpath even though its
    // HttpTransporter is never constructed -- it cannot work on Android.
    implementation(libs.maven.resolver.transport.http)
    implementation(libs.maven.resolver.transport.file)
    implementation(libs.maven.resolver.provider)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
