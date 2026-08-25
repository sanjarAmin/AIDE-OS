package com.osamu.aide.engine.deps

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.minutes

/**
 * The milestone question: does `androidx.appcompat` resolve on a device.
 *
 * Reaches the real Google and Central repositories on purpose. Spike R4 found
 * four separate ways this breaks on ART -- an ICU-hostile regex, a shadowed
 * HTTP stack, AndroidX's hard version ranges, and Maven's ignorance of AAR
 * packaging -- and not one of them is visible against a mocked transport. A
 * hermetic version of this test would pass on the day the module stopped
 * working. See `tools/deps/FINDINGS.md`.
 *
 * The first run downloads the graph and takes about a minute; later runs read
 * it back in well under a second.
 */
@RunWith(AndroidJUnit4::class)
class DependencyResolverTest {

    private lateinit var resolver: DependencyResolver
    private lateinit var localRepository: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Shared across runs on purpose: re-downloading AndroidX for every test
        // would make this suite unusable, and the cache is what production does.
        localRepository = File(context.cacheDir, "maven").apply { mkdirs() }
        resolver = DependencyResolver(localRepository, DefaultDispatcherProvider())
    }

    private suspend fun resolve(vararg notation: String): ResolvedDependencies {
        val coordinates = notation.map { requireNotNull(Coordinate.parse(it)) { "bad: $it" } }
        return when (val result = resolver.resolve(coordinates)) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> throw AssertionError("resolution failed: ${result.error.message}")
        }
    }

    @Test
    fun a_coordinate_parses_or_is_refused() {
        assertEquals(
            Coordinate("androidx.appcompat", "appcompat", "1.7.0"),
            Coordinate.parse("androidx.appcompat:appcompat:1.7.0"),
        )
        assertNull("two parts is not a coordinate", Coordinate.parse("androidx.appcompat:appcompat"))
        assertNull("a blank segment is not a coordinate", Coordinate.parse("a::1.0"))
    }

    @Test
    fun nothing_declared_resolves_to_nothing() = runTest {
        val resolved = resolve()
        assertTrue(resolved.isEmpty)
        assertTrue(resolved.compileClasspath.isEmpty())
    }

    /** The milestone's own acceptance, minus the Kotlin half. */
    @Test
    fun appcompat_resolves_to_a_usable_compile_classpath() = runTest(timeout = NETWORK_TIMEOUT) {
        lateinit var resolved: ResolvedDependencies
        val millis = measureTimeMillis {
            resolved = resolve("androidx.appcompat:appcompat:1.7.0")
        }

        Log.i(TAG, "appcompat -> ${resolved.dependencies.size} deps in $millis ms")
        Log.i(TAG, "unresolved: ${resolved.unresolved}")

        assertTrue("nothing resolved", resolved.dependencies.isNotEmpty())

        // Everything on a compile classpath has to be a file that exists, or
        // ECJ reports it as hundreds of unresolved types in the user's code.
        assertTrue(
            "a classpath entry does not exist: ${resolved.compileClasspath.filterNot { it.isFile }}",
            resolved.compileClasspath.all { it.isFile },
        )

        val names = resolved.dependencies.map { it.coordinate.artifact }
        assertTrue("appcompat itself is missing from $names", "appcompat" in names)
        // Pulled in transitively, and the artifact that needed the jar-to-aar
        // fallback in spike R4 -- so its presence is the fallback still working.
        assertTrue("lifecycle-runtime is missing from $names", "lifecycle-runtime" in names)
    }

    /** AARs have to arrive unpacked, or aapt2 and ECJ have nothing to read. */
    @Test
    fun android_libraries_arrive_as_classes_and_resources() = runTest(timeout = NETWORK_TIMEOUT) {
        val resolved = resolve("androidx.appcompat:appcompat:1.7.0")

        val appcompat = resolved.dependencies.first { it.coordinate.artifact == "appcompat" }
        assertEquals("classes.jar", appcompat.classes.name)
        assertTrue("appcompat is an Android library and should say so", appcompat.isAndroidLibrary)
        assertTrue("its R.txt should have been extracted", appcompat.rTxt?.isFile == true)
        assertTrue("its res/ should have been extracted", appcompat.resources?.isDirectory == true)

        // And a plain jar dependency should not pretend to be one.
        val plainJar = resolved.dependencies.firstOrNull { !it.isAndroidLibrary }
        assertTrue(
            "expected at least one plain jar in the graph",
            plainJar != null && plainJar.classes.extension == "jar",
        )
    }

    /**
     * One version of each module, never two.
     *
     * Conflict resolution does not remove the versions it rejects: they stay in
     * the graph carrying a marker that names the winner. A collector that takes
     * every node it is shown therefore returns both, and the failure lands much
     * later and much less clearly -- D8 refusing the build with "Type
     * androidx.lifecycle.LifecycleRegistry$Companion is defined multiple times",
     * which says nothing about resolution at all.
     *
     * appcompat's graph reaches lifecycle-runtime by two paths at 2.6.1 and
     * 2.6.2, so it exercises this without contriving anything.
     */
    @Test
    fun a_module_appears_at_exactly_one_version() = runTest(timeout = NETWORK_TIMEOUT) {
        val resolved = resolve("androidx.appcompat:appcompat:1.7.0")

        val byModule = resolved.dependencies.groupBy {
            "${it.coordinate.group}:${it.coordinate.artifact}"
        }
        val duplicated = byModule.filterValues { it.size > 1 }

        assertTrue(
            "these modules resolved to more than one version, which D8 will " +
                "reject: " + duplicated.map { (module, entries) ->
                    "$module -> ${entries.map { it.coordinate.version }}"
                },
            duplicated.isEmpty(),
        )

        // And the classpath must not carry the same jar name twice either.
        val names = resolved.compileClasspath.map { it.parentFile?.name to it.name }
        assertEquals("duplicate classpath entries", names.size, names.distinct().size)
    }

    /**
     * The number that decides whether this is usable day to day.
     *
     * Reported rather than asserted. The first resolve is network-bound and
     * varies with the connection; what matters is that a graph already in the
     * local repository comes back fast enough to sit in a build.
     */
    @Test
    fun a_cached_graph_resolves_quickly() = runTest(timeout = NETWORK_TIMEOUT) {
        resolve("androidx.appcompat:appcompat:1.7.0")

        val warm = measureTimeMillis { resolve("androidx.appcompat:appcompat:1.7.0") }
        Log.i(TAG, "warm resolve: $warm ms")

        assertTrue("a cached resolve took $warm ms, which is not a cache", warm < 10_000)
    }

    private companion object {
        const val TAG = "DepsResolver"

        /**
         * runTest defaults to a minute, and a cold resolve of AndroidX does not
         * fit in one: 46 artifacts is nearer 90 requests once Maven has asked
         * for a checksum beside each. Measured at ~92 s on this emulator, so
         * the default fails the suite on a machine with an empty cache while
         * saying nothing about the code.
         */
        val NETWORK_TIMEOUT = 5.minutes
    }
}
