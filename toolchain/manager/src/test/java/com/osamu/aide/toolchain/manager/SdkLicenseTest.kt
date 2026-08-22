package com.osamu.aide.toolchain.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SdkLicenseTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `starts unaccepted`() {
        assertFalse(SdkLicense(temp.root).isAccepted())
        assertNull(SdkLicense(temp.root).acceptedAt())
    }

    @Test
    fun `acceptance survives a new instance`() {
        // It has to outlive the process, not just the object -- the user agrees
        // once, not once per app launch.
        SdkLicense(temp.root).accept(at = 1_700_000_000_000)

        val reloaded = SdkLicense(temp.root)
        assertTrue(reloaded.isAccepted())
        assertEquals(1_700_000_000_000, reloaded.acceptedAt())
    }

    @Test
    fun `acceptance can be withdrawn`() {
        val license = SdkLicense(temp.root)
        license.accept()
        license.revoke()

        assertFalse(license.isAccepted())
    }

    @Test
    fun `a directory that does not exist yet is created`() {
        // The licence is normally accepted before anything has been downloaded,
        // so its directory does not exist at that point.
        val root = File(temp.root, "not/created/yet")

        SdkLicense(root).accept()

        assertTrue(SdkLicense(root).isAccepted())
    }

    @Test
    fun `an unreadable record reads as accepted with an unknown date`() {
        // The marker existing is the agreement; its contents are only there to
        // report. A hand-edited file should not un-accept a licence.
        val license = SdkLicense(temp.root)
        license.accept()
        File(temp.root, "android-sdk-license.accepted").writeText("nonsense")

        assertTrue(license.isAccepted())
        assertNull(license.acceptedAt())
    }
}
