package com.osamu.aide.engine.deps

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The `.module` parser, against the shapes AndroidX actually publishes.
 *
 * Worth its own test rather than being left to the end-to-end build, because
 * **a parser that quietly returns nothing is indistinguishable from a graph
 * that needed nothing** — same empty redirect, same empty constraint list, same
 * successful resolve, and the duplicate class only appears at D8 in a different
 * module's test. `AarExtractor`'s regex shipped broken for exactly that reason
 * (`FINDINGS.md` section 8), and this is the same failure mode one layer up.
 *
 * The fixtures are trimmed from real files. Trimmed, not invented: the field
 * nesting is the part that is easy to get wrong, and a hand-written shape would
 * test the test.
 */
class ModuleMetadataTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "module-metadata-test",
        ).apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(name: String, content: String): File =
        File(dir, name).apply { writeText(content) }

    /**
     * A KMP root, which is the shape that makes `collection` and
     * `compose.runtime:runtime` resolve to two artifacts under Maven and one
     * under Gradle.
     */
    @Test
    fun a_root_redirects_to_its_android_variant() {
        val metadata = ModuleMetadata.parse(
            file(
                "runtime.module",
                """
                {
                  "formatVersion": "1.1",
                  "component": {
                    "group": "androidx.compose.runtime",
                    "module": "runtime",
                    "version": "1.12.0"
                  },
                  "variants": [
                    {
                      "name": "jsApiElements-published",
                      "available-at": {
                        "url": "../../runtime-js/1.12.0/runtime-js-1.12.0.module",
                        "group": "androidx.compose.runtime",
                        "module": "runtime-js",
                        "version": "1.12.0"
                      }
                    },
                    {
                      "name": "releaseApiElements-published",
                      "available-at": {
                        "url": "../../runtime-android/1.12.0/runtime-android-1.12.0.module",
                        "group": "androidx.compose.runtime",
                        "module": "runtime-android",
                        "version": "1.12.0"
                      }
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(
            Coordinate("androidx.compose.runtime", "runtime-android", "1.12.0"),
            requireNotNull(metadata).redirect,
        )
    }

    /**
     * `-android` wins over `-jvm` when a root offers both.
     *
     * The preference is the whole content of [ModuleMetadata.redirect] for a
     * module like this, and it was **not** pinned by any other test here: the
     * other fixtures offer only one candidate each, so reversing the order left
     * them all passing. Found by mutation, which is the only way that kind of
     * gap shows up.
     */
    @Test
    fun android_wins_over_jvm_when_a_root_offers_both() {
        val metadata = ModuleMetadata.parse(
            file(
                "both.module",
                """
                {
                  "formatVersion": "1.1",
                  "variants": [
                    {
                      "name": "jvmApiElements-published",
                      "available-at": {
                        "group": "androidx.collection",
                        "module": "collection-jvm",
                        "version": "1.5.0"
                      }
                    },
                    {
                      "name": "releaseApiElements-published",
                      "available-at": {
                        "group": "androidx.collection",
                        "module": "collection-android",
                        "version": "1.5.0"
                      }
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(
            Coordinate("androidx.collection", "collection-android", "1.5.0"),
            requireNotNull(metadata).redirect,
        )
    }

    /** `-jvm` when there is no Android target, which is how `collection` publishes. */
    @Test
    fun a_root_with_no_android_variant_falls_back_to_jvm() {
        val metadata = ModuleMetadata.parse(
            file(
                "common.module",
                """
                {
                  "formatVersion": "1.1",
                  "variants": [
                    {
                      "name": "jvmApiElements-published",
                      "available-at": {
                        "group": "androidx.lifecycle",
                        "module": "lifecycle-common-jvm",
                        "version": "2.9.4"
                      }
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(
            Coordinate("androidx.lifecycle", "lifecycle-common-jvm", "2.9.4"),
            requireNotNull(metadata).redirect,
        )
    }

    /**
     * A root offering only targets an Android build cannot use is not a
     * redirect *for us*, and saying otherwise would drop the root in favour of
     * an artifact that will not resolve.
     */
    @Test
    fun a_root_offering_only_foreign_targets_is_not_a_redirect() {
        val metadata = ModuleMetadata.parse(
            file(
                "ios.module",
                """
                {
                  "formatVersion": "1.1",
                  "variants": [
                    {
                      "name": "iosArm64ApiElements-published",
                      "available-at": {
                        "group": "androidx.lifecycle",
                        "module": "lifecycle-common-iosarm64",
                        "version": "2.9.4"
                      }
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertNull(requireNotNull(metadata).redirect)
    }

    /**
     * The constraints that align a group, and the reason the absorption table
     * could be deleted.
     */
    @Test
    fun constraints_are_read_with_their_required_versions() {
        val metadata = ModuleMetadata.parse(
            file(
                "activity.module",
                """
                {
                  "formatVersion": "1.1",
                  "variants": [
                    {
                      "name": "releaseApiElements",
                      "dependencyConstraints": [
                        {
                          "group": "androidx.activity",
                          "module": "activity-ktx",
                          "version": { "requires": "1.13.0" }
                        },
                        {
                          "group": "androidx.activity",
                          "module": "activity-compose",
                          "version": { "requires": "1.13.0" }
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(
                Coordinate("androidx.activity", "activity-ktx", "1.13.0"),
                Coordinate("androidx.activity", "activity-compose", "1.13.0"),
            ),
            requireNotNull(metadata).constraints,
        )
    }

    /** Every variant repeats the same block; the module constrains each module once. */
    @Test
    fun a_constraint_repeated_across_variants_is_read_once() {
        val variant = """
            {
              "name": "%s",
              "dependencyConstraints": [
                {
                  "group": "androidx.lifecycle",
                  "module": "lifecycle-common-java8",
                  "version": { "requires": "2.9.4" }
                }
              ]
            }
        """.trimIndent()
        val metadata = ModuleMetadata.parse(
            file(
                "repeated.module",
                """
                {
                  "formatVersion": "1.1",
                  "variants": [
                    ${variant.format("jvmApiElements-published")},
                    ${variant.format("jvmRuntimeElements-published")},
                    ${variant.format("jvmSourcesElements-published")}
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(1, requireNotNull(metadata).constraints.size)
    }

    /**
     * Only `requires` is read.
     *
     * `prefers` and `strictly` express a resolution model this module does not
     * implement, and treating a preference as a floor would raise versions
     * Gradle itself would not.
     */
    @Test
    fun a_constraint_with_no_required_version_is_ignored() {
        val metadata = ModuleMetadata.parse(
            file(
                "prefers.module",
                """
                {
                  "formatVersion": "1.1",
                  "variants": [
                    {
                      "name": "apiElements",
                      "dependencyConstraints": [
                        {
                          "group": "com.example",
                          "module": "preferred",
                          "version": { "prefers": "1.2.3" }
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(emptyList<Coordinate>(), requireNotNull(metadata).constraints)
    }

    /** A module with variants but nothing this reads is still valid metadata. */
    @Test
    fun a_module_with_neither_redirects_nor_constraints_parses_empty() {
        val metadata = ModuleMetadata.parse(
            file(
                "plain.module",
                """
                {
                  "formatVersion": "1.1",
                  "variants": [ { "name": "apiElements" } ]
                }
                """.trimIndent(),
            ),
        )

        assertNull(requireNotNull(metadata).redirect)
        assertTrue(metadata.constraints.isEmpty())
    }

    /**
     * Malformed metadata is null, not a crash.
     *
     * This runs over files fetched from a repository the user named. A
     * truncated download is a bad artifact, not a reason to take the whole
     * resolution down -- and the caller already works without an entry, because
     * most of a graph publishes none.
     */
    @Test
    fun something_that_is_not_module_metadata_is_null() {
        assertNull(ModuleMetadata.parse(file("truncated.module", """{"formatVersion": "1.1",""")))
        assertNull(ModuleMetadata.parse(file("empty.module", "")))
        assertNull(ModuleMetadata.parse(file("html.module", "<html>404</html>")))
        // Valid JSON, no variants: nothing to read, and nothing to report.
        assertNull(ModuleMetadata.parse(file("novariants.module", """{"formatVersion":"1.1"}""")))
    }
}
