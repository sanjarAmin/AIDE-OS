package com.osamu.aide.engine.deps

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Unpacking an AAR into the three things a build reads from it.
 *
 * Built from hand-made archives rather than resolved ones: this is about zip
 * handling, and a real AAR would make the test slow, network-dependent and
 * worse at saying what broke. Resolution against the real thing is
 * [DependencyResolverTest]'s job.
 */
@RunWith(AndroidJUnit4::class)
class AarExtractorTest {

    private lateinit var workDir: File

    private val coordinate = Coordinate("androidx.example", "widget", "1.0.0")

    @Before
    fun setUp() {
        workDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "aar-test-${System.nanoTime()}",
        ).apply { mkdirs() }
    }

    private fun aar(name: String = "widget-1.0.0.aar", entries: Map<String, String>): File {
        val file = File(workDir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun an_aar_yields_its_classes_resources_and_symbols() {
        val archive = aar(entries = mapOf(
            "classes.jar" to "not really a jar, but a file",
            "AndroidManifest.xml" to "<manifest/>",
            "R.txt" to "int string app_name 0x7f010001",
            "res/values/strings.xml" to "<resources/>",
        ))

        val resolved = requireNotNull(AarExtractor.extract(coordinate, archive))

        assertTrue("classes.jar was not extracted", resolved.classes.isFile)
        assertEquals("R.txt", resolved.rTxt?.name)
        assertEquals("AndroidManifest.xml", resolved.manifest?.name)
        assertTrue("res/ should be a directory", resolved.resources?.isDirectory == true)
        assertTrue("it is an Android library", resolved.isAndroidLibrary)
    }

    /**
     * A resource-only library is legal and ships without code.
     *
     * Null rather than an empty classpath entry: there is nothing to compile
     * against, and handing back a file that does not exist would fail later in
     * ECJ, where the cause is much harder to see.
     */
    @Test
    fun an_aar_with_no_code_resolves_to_nothing() {
        val archive = aar(entries = mapOf("res/values/colors.xml" to "<resources/>"))

        assertNull(AarExtractor.extract(coordinate, archive))
    }

    @Test
    fun an_aar_with_no_resources_is_still_a_classpath_entry() {
        val archive = aar(entries = mapOf("classes.jar" to "code"))

        val resolved = requireNotNull(AarExtractor.extract(coordinate, archive))
        assertTrue(resolved.classes.isFile)
        assertNull("there were no resources to find", resolved.resources)
        assertFalse("nothing here needs aapt2", resolved.isAndroidLibrary)
    }

    /**
     * Zip Slip: an entry named `../` writing outside the target directory.
     *
     * Worth a test rather than a comment because this module unpacks archives
     * fetched over the network from a coordinate the user typed, which is the
     * exact program the attack is aimed at.
     *
     * On Android the platform gets there first -- `java.util.zip` refuses a
     * restricted entry name on read as well as write -- so what this actually
     * pins is that a forged archive is *refused rather than fatal*: the
     * ZipException stays inside the extractor and the artifact resolves to
     * nothing, instead of taking the whole resolution down.
     */
    @Test
    fun an_entry_that_escapes_the_target_directory_is_refused() {
        val escapee = File(workDir, "escaped.txt")

        // The archive has to be forged byte by byte, because Android's own
        // ZipOutputStream will not write an entry named `../escaped.txt` --
        // "restricted zip entry name". That platform check is welcome but it is
        // not the guard under test: it protects archives this app *writes*, and
        // nothing stops a server returning one it did not. So the entry is
        // created under a same-length benign name and renamed in the bytes
        // afterwards, in both the local header and the central directory.
        val archive = aar(entries = mapOf(
            "classes.jar" to "code",
            "zz/escaped.txt" to "should never be written",
        ))
        val forged = archive.readBytes()
        val benign = "zz/escaped.txt".toByteArray()
        val hostile = "../escaped.txt".toByteArray()
        check(benign.size == hostile.size) { "the rename must not change the entry length" }
        for (i in 0..forged.size - benign.size) {
            if (forged.copyOfRange(i, i + benign.size).contentEquals(benign)) {
                hostile.copyInto(forged, i)
            }
        }
        archive.writeBytes(forged)

        val resolved = AarExtractor.extract(coordinate, archive)

        assertNull("a forged archive must not resolve to a dependency", resolved)
        assertFalse("a zip entry wrote outside its directory", escapee.exists())
        assertFalse(
            "a refused archive should leave no half-unpacked directory behind",
            File(workDir, "widget-1.0.0").exists(),
        )
    }

    /** Re-extraction is skipped, because an artifact at a version is immutable. */
    @Test
    fun a_second_extraction_reuses_the_first() {
        val archive = aar(entries = mapOf("classes.jar" to "code"))

        val first = requireNotNull(AarExtractor.extract(coordinate, archive))
        first.classes.writeText("touched, to prove this file survives")

        val second = requireNotNull(AarExtractor.extract(coordinate, archive))

        assertEquals(first.classes, second.classes)
        assertEquals("touched, to prove this file survives", second.classes.readText())
    }

    /** Unless the archive is newer, which means the cache is stale. */
    @Test
    fun a_newer_archive_forces_re_extraction() {
        val archive = aar(entries = mapOf("classes.jar" to "code"))
        val first = requireNotNull(AarExtractor.extract(coordinate, archive))
        first.classes.writeText("stale")

        val replacement = aar("replacement.aar", mapOf("classes.jar" to "fresh code"))
        replacement.copyTo(archive, overwrite = true)
        archive.setLastModified(System.currentTimeMillis() + 10_000)

        val second = requireNotNull(AarExtractor.extract(coordinate, archive))
        assertEquals("fresh code", second.classes.readText())
    }
}
