package com.osamu.aide.engine.fast

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectManifestTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun manifest(body: String): File =
        temp.newFile("AndroidManifest.xml").apply { writeText(body) }

    @Test
    fun `reads the declared minSdkVersion`() {
        val file = manifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.demo">
                <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="34" />
                <application />
            </manifest>
            """.trimIndent(),
        )

        assertEquals(21, ProjectManifest.minSdk(file))
    }

    @Test
    fun `a manifest without uses-sdk falls back to the default`() {
        val file = manifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.demo">
                <application />
            </manifest>
            """.trimIndent(),
        )

        assertEquals(ProjectManifest.DEFAULT_MIN_SDK, ProjectManifest.minSdk(file))
    }

    @Test
    fun `a codename is not guessed at`() {
        // Mapping "TIRAMISU" to 33 needs a table that goes stale every release,
        // and guessing too high leaves language features in the dex that the
        // device cannot run. Falling back is the safe direction.
        val file = manifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-sdk android:minSdkVersion="TIRAMISU" />
            </manifest>
            """.trimIndent(),
        )

        assertEquals(ProjectManifest.DEFAULT_MIN_SDK, ProjectManifest.minSdk(file))
    }

    @Test
    fun `a malformed manifest does not throw`() {
        // aapt2 will fail on this and say so far better than a stack trace here.
        val file = manifest("<manifest><uses-sdk")

        assertEquals(ProjectManifest.DEFAULT_MIN_SDK, ProjectManifest.minSdk(file))
    }

    @Test
    fun `a missing manifest does not throw`() {
        assertEquals(
            ProjectManifest.DEFAULT_MIN_SDK,
            ProjectManifest.minSdk(File(temp.root, "nope.xml")),
        )
    }

    @Test
    fun `an attribute in the wrong namespace is ignored`() {
        // minSdkVersion without the android: prefix is a common hand-edit, and
        // aapt2 ignores it too. Honouring it here would make the dex disagree
        // with the manifest the APK actually ships.
        val file = manifest(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-sdk minSdkVersion="21" />
            </manifest>
            """.trimIndent(),
        )

        assertEquals(ProjectManifest.DEFAULT_MIN_SDK, ProjectManifest.minSdk(file))
    }
}
