package com.osamu.aide.engine.gradle

import com.osamu.aide.engine.api.DebugAgentSource
import com.osamu.aide.engine.api.DebuggerRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The files a Gradle debug build is handed.
 *
 * Whether AGP accepts them is a question for a real build, and one was run
 * against AGP 9.3.2 on a desktop before this engine used them -- see
 * `debugger/FINDINGS.md`. This pins what that build was given: written to the
 * module's own `build/` so the same files can be pointed at by hand.
 */
class GradleDebugAgentTest {

    private val workDir = File("build/tmp/gradle-debug-agent")
    private val request = DebuggerRequest(port = 38123, handshakeAuthority = "com.osamu.aide.debug-handshake")

    @Test
    fun the_init_script_names_the_agent_it_wrote() {
        val script = GradleDebugAgent.prepare(workDir, request)

        val source = File(workDir, "java/${DebugAgentSource.relativePath}")
        val manifest = File(workDir, "AndroidManifest.xml")
        assertTrue("no agent source", source.isFile)
        assertTrue("the agent does not listen on the requested port", "127.0.0.1:38123" in source.readText())
        assertTrue("no manifest", manifest.isFile)

        val text = script.readText()
        assertTrue(text, "'${File(workDir, "java").absolutePath}'" in text)
        assertTrue(text, "'${manifest.absolutePath}'" in text)
        // Debug variants only: a release built in the same run must not listen.
        assertTrue(text, "withBuildType('debug')" in text)
    }

    @Test
    fun the_manifest_lets_agp_name_the_package() {
        val manifest = DebugAgentSource.manifestOverlay(request)
        assertTrue(manifest, """android:authorities="${'$'}{applicationId}.aide-debug-agent"""" in manifest)
        assertTrue(manifest, """android:name="aide.debug.AideDebugAgent"""" in manifest)
        assertTrue(manifest, "android.permission.INTERNET" in manifest)
        assertTrue(manifest, "com.osamu.aide.debug-handshake" in manifest)
        assertTrue(manifest, """android:exported="true"""" in manifest)
    }

    @Test
    fun without_a_handshake_nothing_is_queried() {
        assertFalse(DebugAgentSource.manifestOverlay(DebuggerRequest(port = 1)).contains("<queries>"))
    }

    @Test
    fun a_path_with_a_quote_stays_one_groovy_string() {
        val script = GradleDebugAgent.initScript(File("/data/it's here/java"), File("/data/m.xml"))
        assertTrue(script, """def agentSources = '/data/it\'s here/java'""" in script)
    }

    @Test
    fun an_apk_is_read_for_the_agent_not_trusted() {
        val with = apk("with.apk", "Laide/debug/AideDebugAgent;")
        val without = apk("without.apk", "Lcom/example/MainActivity;")
        assertTrue(GradleDebugAgent.carriesAgent(with))
        assertFalse(GradleDebugAgent.carriesAgent(without))
        assertEquals(false, GradleDebugAgent.carriesAgent(File(workDir, "missing.apk")))
    }

    private fun apk(name: String, dexContent: String): File {
        workDir.mkdirs()
        return File(workDir, name).apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(dexContent.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
