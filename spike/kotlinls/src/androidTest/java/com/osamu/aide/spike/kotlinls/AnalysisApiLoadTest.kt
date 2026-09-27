package com.osamu.aide.spike.kotlinls

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.PathClassLoader
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Spike R12: does the Kotlin Analysis API load on ART?
 *
 * `tools/analysisapi/FINDINGS.md` establishes that the closure resolves and
 * that the relocated set dexes cleanly at `--min-api 30`. Neither is evidence
 * that a single class loads: the compiler dexed cleanly too, and then needed
 * seven fixes before it would start (`tools/kotlinc/FINDINGS.md`). This is the
 * cheapest question that can tell the two apart.
 *
 * **The classloader arrangement is the thing under test as much as the code.**
 * The Analysis API is compiled against the compiler's shaded platform, so it
 * has to see exactly those classes and must not get a second copy:
 *
 * ```
 * boot classloader
 *   └── kotlinc archive        (the compiler, and the shaded IntelliJ platform)
 *         └── analysis-api archive
 * ```
 *
 * Parented to the *compiler's* loader rather than the app's, for the reason
 * `tools/kotlinc/FINDINGS.md` gives about parenting the compiler to the boot
 * loader: the app ships its own kotlin-stdlib and d8 synthesises helpers
 * independently for each, so parent-first delegation to the app hands the
 * archive the app's synthetics and linking fails deep inside IntelliJ's
 * message bus.
 *
 * Both archives are staged out of band -- 56 MB and 2 MB -- and every test
 * skips without them, because a checkout with no archives is the normal state.
 */
@RunWith(AndroidJUnit4::class)
class AnalysisApiLoadTest {

    private lateinit var loader: ClassLoader

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staging = context.getExternalFilesDir(null)

        val kotlinc = File(staging, KOTLINC_ARCHIVE)
        val analysisApi = File(staging, ANALYSIS_API_ARCHIVE)
        assumeTrue("no $KOTLINC_ARCHIVE staged", kotlinc.isFile)
        assumeTrue("no $ANALYSIS_API_ARCHIVE staged", analysisApi.isFile)

        // Copied into app storage: PathClassLoader needs a path it can open and
        // write an optimised copy beside, and external storage is neither
        // reliably readable that way nor somewhere to leave a .oat file.
        val local = File(context.filesDir, "kotlinls").apply { mkdirs() }

        // **The published compiler component is a zip of two jars, not a dex
        // archive.** `kotlinc.jar` is the loadable one, carrying six dex files;
        // `kotlin-stdlib.jar` beside it is ordinary JVM bytecode that the
        // compiler reads as its `kotlin-home` and that `PathClassLoader` cannot
        // open at all. Handing the outer zip to the loader fails with
        // "Entry not found", which names neither.
        val compilerJar = unpack(kotlinc, File(local, "kotlinc.jar"), "kotlinc.jar")
        val analysisLocal = analysisApi.copyToIfNewer(File(local, ANALYSIS_API_ARCHIVE))

        val compilerLoader = PathClassLoader(compilerJar.absolutePath, null)
        loader = PathClassLoader(analysisLocal.absolutePath, compilerLoader)
    }

    /** Lifts one entry out of a component archive, read-only for the reason below. */
    private fun unpack(archive: File, target: File, entry: String): File {
        if (!target.isFile) {
            java.util.zip.ZipFile(archive).use { zip ->
                val found = requireNonNull(zip.getEntry(entry), "no $entry in ${archive.name}")
                zip.getInputStream(found).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }
        target.setWritable(false, false)
        return target
    }

    private fun <T> requireNonNull(value: T?, message: String): T = value ?: error(message)

    /**
     * Copies the archive into app storage and **makes it read-only**.
     *
     * The read-only part is not tidiness. Since API 29 the runtime refuses to
     * open a dex file the app can write to:
     *
     * ```
     * java.lang.SecurityException: Writable dex file
     *   '/data/user/0/…/kotlin-compiler-2.2.10.zip' is not allowed.
     * ```
     *
     * Which is thrown by the `PathClassLoader` *constructor*, not by the first
     * `loadClass` — so it presents as the archive being unreadable rather than
     * as a permission on it, and the same trap waits for anything the app
     * downloads and then loads.
     */
    private fun File.copyToIfNewer(target: File): File {
        if (!target.isFile || target.length() != length()) {
            target.delete()
            copyTo(target, overwrite = true)
        }
        target.setWritable(false, false)
        return target
    }

    /**
     * The compiler still loads on its own, which is the control.
     *
     * If this fails the staging is wrong and nothing below means anything.
     */
    @Test
    fun the_compiler_archive_loads() {
        val compiler = loader.parent!!.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")

        assertNotNull(compiler)
        Log.i(TAG, "compiler loaded: $compiler")
    }

    /**
     * The relocated platform is where the Analysis API will look for it.
     *
     * Asserted through the *compiler's* loader under its shaded name, because
     * that is the whole premise of the relocation: if this class is not here
     * under this name, every rewritten reference in the Analysis API is wrong.
     */
    @Test
    fun the_shaded_platform_is_present_under_its_relocated_name() {
        val psi = loader.loadClass("org.jetbrains.kotlin.com.intellij.psi.PsiElement")

        assertNotNull(psi)
        Log.i(TAG, "PsiElement loaded: $psi")
    }

    /**
     * The Analysis API itself, and the class that would answer completion.
     *
     * `KaSession` is the API surface and `KaCompletionCandidateChecker` is the
     * component a completion request goes through, so between them they are the
     * feature this spike exists to price.
     */
    @Test
    fun the_analysis_api_loads() {
        for (name in listOf(
            "org.jetbrains.kotlin.analysis.api.KaSession",
            "org.jetbrains.kotlin.analysis.api.components.KaCompletionCandidateChecker",
            "org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISessionBuilder",
        )) {
            val loaded = loader.loadClass(name)
            assertNotNull(loaded)
            Log.i(TAG, "loaded $name")
        }
    }

    /**
     * A relocated reference *resolves*, rather than merely being spelled right.
     *
     * Loading a class only reads its own bytes; the references in its constant
     * pool are resolved lazily. Reading a method signature forces the parameter
     * and return types to be resolved, which is what would fail if the
     * relocation had pointed at a class that is not there — the failure the
     * whole rewrite exists to avoid, and one that would otherwise not appear
     * until a query ran.
     */
    @Test
    fun a_relocated_reference_resolves_rather_than_only_looking_right() {
        val session = loader.loadClass("org.jetbrains.kotlin.analysis.api.KaSession")

        val signatures = session.methods.flatMap { method ->
            method.parameterTypes.map { it.name } + method.returnType.name
        }
        Log.i(TAG, "resolved ${signatures.size} types across ${session.methods.size} methods")

        assertTrue("KaSession exposes no methods, which cannot be right", session.methods.isNotEmpty())
        // Nothing may resolve to the unshaded name: that would mean a copy of
        // the platform arrived from somewhere other than the compiler archive.
        val unshaded = signatures.filter { it.startsWith("com.intellij.") }
        assertTrue("resolved to unshaded platform classes: $unshaded", unshaded.isEmpty())
    }

    /**
     * The service descriptors ship, and name relocated classes.
     *
     * The archive registers itself through these; one that carried the dex
     * without them would load every class here and provide nothing. Read as a
     * resource through the loader, which is how the API reads it too.
     */
    @Test
    fun the_registration_descriptors_are_readable_and_relocated() {
        val descriptor = loader.getResourceAsStream("META-INF/analysis-api/low-level-api-fir.xml")
        assertNotNull("the descriptor is not in the archive", descriptor)

        val xml = descriptor!!.bufferedReader().use { it.readText() }
        assertTrue(
            "the descriptor still names the unshaded PsiModificationTracker",
            "\"com.intellij.psi.util.PsiModificationTracker\"" !in xml,
        )
        assertTrue(
            "the descriptor lost its relocated class name",
            "org.jetbrains.kotlin.com.intellij.psi.util.PsiModificationTracker" in xml,
        )
        // The extension namespace is a string the platform matches on, not a
        // type, and relocating it would unregister everything in the file.
        assertTrue(
            "the extension namespace was relocated and must not be",
            "defaultExtensionNs=\"com.intellij\"" in xml,
        )
    }

    private companion object {
        const val TAG = "AnalysisApiLoadTest"
        const val KOTLINC_ARCHIVE = "kotlin-compiler-2.2.10.zip"
        const val ANALYSIS_API_ARCHIVE = "analysis-api-2.2.10.zip"
    }
}
