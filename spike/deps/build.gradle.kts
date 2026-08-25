plugins {
    alias(libs.plugins.android.library)
}

/**
 * Spike R4: does Maven resolution run on ART?
 *
 * `docs/PLAN.md` lists `maven-resolver` among the "pure JVM, therefore fine on
 * ART" dependencies. That phrase has cost this project twice already -- kotlinc
 * needed seven separate fixes before it would start (`tools/kotlinc/FINDINGS.md`)
 * and ECJ needed a hand-written `javax.lang.model` shim plus a stubs jar
 * (`tools/ecj/FINDINGS.md`). M4 is designed around this assumption, so it gets
 * a spike before the design, not after.
 *
 * The specific doubts, in the order they are likely to bite:
 *
 * 1. **Sisu/Guice.** Maven's normal wiring builds its object graph by
 *    generating bytecode at runtime, which ART cannot do. `maven-resolver-supplier`
 *    exists to avoid that; whether it is enough here is the first question.
 * 2. **POM reading.** The resolver cannot parse a POM by itself --
 *    `maven-resolver-provider` supplies that, and it drags in maven-model-builder,
 *    plexus and a StAX parser. `tools/kotlinc/FINDINGS.md` records that StAX had
 *    to be shimmed for kotlinc; the same gap may apply.
 * 3. **Transport.** The 1.9 line uses Apache HttpClient 4, which works on
 *    Android. The 2.x line uses `java.net.http.HttpClient`, which does not exist
 *    on Android at any API level -- that alone rules 2.x out.
 * 4. **AAR.** AndroidX ships `.aar`, not `.jar`. Maven's model has no notion of
 *    that packaging, so whether resolution even produces a file is part of the
 *    question, never mind extracting `classes.jar` from it.
 *
 * Delete this module once `:engine:deps` answers the same questions under its
 * own tests.
 */
android {
    namespace = "com.osamu.aide.spike.deps"
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
            // Maven's jars each carry their own copies of these, and the merger
            // refuses to pick one for you.
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
    androidTestImplementation(libs.maven.resolver.supplier)
    androidTestImplementation(libs.maven.resolver.connector.basic)
    // Still on the classpath for its ChecksumExtractor type, which the
    // supplier's own signature names; its HttpTransporter is never constructed.
    androidTestImplementation(libs.maven.resolver.transport.http)
    androidTestImplementation(libs.maven.resolver.transport.file)
    androidTestImplementation(libs.maven.resolver.provider)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
