package com.osamu.aide.engine.fast

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The manifest merge, at the level where each rule can be named.
 *
 * `ComposeRunTest` asserts the end of the story -- that `androidx.startup`'s
 * provider reaches the installed package, asked of Android's own
 * `PackageManager` -- which is the claim that matters and a poor place to find
 * out *which* rule is wrong. These are the rules.
 *
 * Fixtures are written the way real manifests are, `tools:` namespace and all.
 * The first version of the merger dropped every element carrying a `tools:`
 * attribute, which threw away the exact `<provider>` it existed to merge, and a
 * fixture without one would not have noticed.
 */
class ManifestMergerTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "manifest-merger-test",
        ).apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /**
     * Assembled by concatenation rather than one interpolated raw string.
     *
     * `trimIndent` runs *after* interpolation, so a multi-line body with no
     * indentation of its own drops the common indent to zero and leaves the
     * XML declaration indented -- which is not a well-formed document. Every
     * library manifest then failed to parse, the merger skipped them all
     * exactly as it is designed to, and four tests failed while one passed
     * vacuously. The helper was wrong, not the code under test.
     */
    private fun manifest(name: String, body: String): File = File(dir, name).apply {
        writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                "    xmlns:tools=\"http://schemas.android.com/tools\">\n" +
                body + "\n" +
                "</manifest>\n",
        )
    }

    private fun merge(project: File, vararg libraries: File): String {
        val output = File(dir, "merged.xml")
        val result = ManifestMerger.merge(project, libraries.toList(), APPLICATION_ID, output)
        return result.readText()
    }

    /**
     * The case the whole thing exists for.
     *
     * `androidx.startup` ships this exact shape: a provider whose authority is
     * a placeholder, carrying `tools:node="merge"`.
     */
    @Test
    fun a_library_provider_is_merged_and_its_placeholder_substituted() {
        val project = manifest("app.xml", "<application />")
        val library = manifest(
            "startup.xml",
            """
            <application>
                <provider
                    android:name="androidx.startup.InitializationProvider"
                    android:authorities="${'$'}{applicationId}.androidx-startup"
                    android:exported="false"
                    tools:node="merge" />
            </application>
            """.trimIndent(),
        )

        val merged = merge(project, library)

        assertTrue("the provider was not merged:\n$merged", "InitializationProvider" in merged)
        assertTrue(
            "the applicationId placeholder survived:\n$merged",
            "$APPLICATION_ID.androidx-startup" in merged,
        )
        assertFalse("an unsubstituted placeholder reached aapt2", "\${applicationId}" in merged)
        // aapt2 refuses an unbound prefix, and a marker this does not implement
        // must not look as though it had been honoured.
        assertFalse("a tools: attribute reached the output:\n$merged", "tools:node" in merged)
    }

    @Test
    fun permissions_are_unioned_without_duplicates() {
        val project = manifest(
            "app.xml",
            """
            <uses-permission android:name="android.permission.INTERNET" />
            <application />
            """.trimIndent(),
        )
        val library = manifest(
            "lib.xml",
            """
            <uses-permission android:name="android.permission.INTERNET" />
            <uses-permission android:name="android.permission.WAKE_LOCK" />
            <application />
            """.trimIndent(),
        )

        val merged = merge(project, library)

        assertEquals(
            "INTERNET should appear exactly once",
            1,
            Regex("android.permission.INTERNET").findAll(merged).count(),
        )
        assertTrue("WAKE_LOCK was not merged", "android.permission.WAKE_LOCK" in merged)
    }

    /**
     * The one merge rule a user can reason about without reading a
     * specification: what they wrote stays.
     */
    @Test
    fun the_project_wins_over_a_library_declaring_the_same_component() {
        val project = manifest(
            "app.xml",
            """
            <application>
                <provider android:name="com.example.Shared" android:authorities="mine" />
            </application>
            """.trimIndent(),
        )
        val library = manifest(
            "lib.xml",
            """
            <application>
                <provider android:name="com.example.Shared" android:authorities="theirs" />
            </application>
            """.trimIndent(),
        )

        val merged = merge(project, library)

        assertTrue("the project's own declaration was lost", "mine" in merged)
        assertFalse("the library replaced the project's component", "theirs" in merged)
    }

    /** The only `tools:node` value honoured, and it has to be. */
    @Test
    fun an_element_marked_for_removal_is_not_merged() {
        val project = manifest("app.xml", "<application />")
        val library = manifest(
            "lib.xml",
            """
            <application>
                <provider android:name="com.example.Unwanted" tools:node="remove" />
                <service android:name="com.example.Wanted" />
            </application>
            """.trimIndent(),
        )

        val merged = merge(project, library)

        assertFalse("an element marked remove was merged anyway", "Unwanted" in merged)
        assertTrue("removal took the rest of the library with it", "Wanted" in merged)
    }

    /**
     * A malformed library manifest costs that library, not the build.
     *
     * These files come out of archives fetched over the network from a
     * coordinate the user typed. Failing a build over one is a worse outcome
     * than linking without it, which is what this project did until the merger
     * existed and which still produces a working APK.
     */
    @Test
    fun a_malformed_library_manifest_is_skipped_rather_than_fatal() {
        val project = manifest("app.xml", "<application />")
        val broken = File(dir, "broken.xml").apply { writeText("<manifest") }
        val good = manifest(
            "good.xml",
            """
            <application>
                <service android:name="com.example.Survivor" />
            </application>
            """.trimIndent(),
        )

        val merged = merge(project, broken, good)

        assertTrue("one bad manifest lost a good one", "Survivor" in merged)
    }

    /**
     * With nothing to merge, the project's own file is used unchanged.
     *
     * Not a micro-optimisation: it means a project with no dependencies links
     * exactly the bytes the user wrote, so a merge bug cannot affect a build
     * that has no libraries in it.
     */
    @Test
    fun a_project_with_no_libraries_links_its_own_manifest() {
        val project = manifest("app.xml", "<application />")
        val output = File(dir, "merged.xml")

        val result = ManifestMerger.merge(project, emptyList(), APPLICATION_ID, output)

        assertEquals(project, result)
        assertFalse("an output file was written for nothing", output.exists())
    }

    /**
     * An element with no `android:name` is never merged.
     *
     * Components are keyed by name, so an element without one cannot be
     * compared to anything and is left where it is. That property is what
     * actually protects `<uses-sdk>` in the test below, and it needs its own:
     * adding `uses-sdk` to the unioned set changes nothing, because it has no
     * name either way, so that test passes with or without it. Mutation found
     * the gap; review would not have.
     */
    @Test
    fun an_element_with_no_android_name_is_not_merged() {
        val project = manifest("app.xml", "<application />")
        val library = manifest(
            "lib.xml",
            """
            <uses-feature android:glEsVersion="0x00020000" android:required="true" />
            <application />
            """.trimIndent(),
        )

        val merged = merge(project, library)

        assertFalse("a nameless element was merged:\n$merged", "glEsVersion" in merged)
    }

    /**
     * A library's `<uses-sdk>` is *not* merged.
     *
     * True for two independent reasons, which is what makes it safe: it is not
     * in the unioned set, and it has no `android:name` to key on. It has a
     * merge rule of its own -- the highest `minSdkVersion` wins, and a library
     * above the app's is an error rather than a silent raise -- so quietly
     * unioning it would give an app whose declared floor came from whichever
     * library happened to be first. `androidx.startup` declares
     * `minSdkVersion="14"`, so getting this wrong lowers a real app's floor.
     */
    @Test
    fun a_library_uses_sdk_is_left_alone() {
        val project = manifest(
            "app.xml",
            """
            <uses-sdk android:minSdkVersion="26" />
            <application />
            """.trimIndent(),
        )
        val library = manifest(
            "lib.xml",
            """
            <uses-sdk android:minSdkVersion="14" />
            <application />
            """.trimIndent(),
        )

        val merged = merge(project, library)

        assertFalse("the library's minSdk reached the app", "14" in merged)
        assertTrue("the project's own minSdk was lost", "26" in merged)
    }

    private companion object {
        const val APPLICATION_ID = "com.example.merged"
    }
}
