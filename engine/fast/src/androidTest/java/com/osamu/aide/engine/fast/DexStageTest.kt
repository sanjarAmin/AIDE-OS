package com.osamu.aide.engine.fast

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.fs.ProjectLayout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * D8, on the device, dexing what the previous three stages really produced.
 *
 * Nothing here is stubbed: aapt2 links, ECJ compiles, D8 dexes. A dex built from
 * hand-written class files would prove D8 runs, which the spike already did; the
 * open question this answers is whether the *pipeline* hands D8 something it can
 * use.
 */
@RunWith(AndroidJUnit4::class)
class DexStageTest {

    private lateinit var fixture: EngineTestFixture
    private lateinit var resources: ResourceStage
    private lateinit var javac: JavaCompileStage
    private lateinit var d8: DexStage

    @Before
    fun setUp() {
        fixture = EngineTestFixture("dex-stage-test")
        fixture.assumeAapt2Supported()
        resources = ResourceStage(fixture.runner)
        javac = JavaCompileStage(DefaultDispatcherProvider())
        d8 = DexStage(DefaultDispatcherProvider())
    }

    /** Everything up to the point where D8 takes over. */
    private suspend fun compile(layout: ProjectLayout, workspace: BuildWorkspace) {
        assertTrue(resources.compile(layout, workspace).succeeded)
        assertTrue(resources.link(layout, workspace, fixture.platform, debuggable = true).succeeded)
        val sources = layout.javaSources() + workspace.generatedJavaSources()
        val result = javac.compile(sources, fixture.platform, workspace, layout.root)
        assertTrue("compile failed: ${result.failure} ${result.diagnostics}", result.succeeded)
    }

    private suspend fun dex(layout: ProjectLayout, workspace: BuildWorkspace) =
        d8.dex(
            classesDir = workspace.classes,
            platform = fixture.platform,
            workspace = workspace,
            minSdk = ProjectManifest.minSdk(layout.manifestFile),
            debuggable = true,
            projectRoot = layout.root,
        )

    /** Dex string data is plain bytes; ISO-8859-1 keeps every one of them. */
    private fun File.asText(): String = readBytes().toString(Charsets.ISO_8859_1)

    @Test
    fun dexes_the_compiled_project() = runTest {
        val layout = ProjectLayout.of(fixture.project())
        val workspace = fixture.workspace()
        compile(layout, workspace)

        val result = dex(layout, workspace)

        assertTrue("dexing failed: ${result.failure} ${result.diagnostics}", result.succeeded)
        val produced = requireNotNull(result.value) { "no dex produced" }
        assertEquals(
            "a hello-world should fit in one dex, got ${produced.map { it.name }}",
            listOf("classes.dex"),
            produced.map { it.name },
        )

        // "dex\n". A truncated or empty file would satisfy isFile, and an
        // unloadable dex is a failure the packaging stage would not notice.
        val dexFile = produced.single()
        assertArrayEquals(
            "not a dex file",
            byteArrayOf(0x64, 0x65, 0x78, 0x0a),
            dexFile.readBytes().take(4).toByteArray(),
        )

        // The class the user wrote actually made it in. Exit codes do not say
        // this: D8 succeeds happily on an empty input.
        assertTrue(
            "MainActivity is not in the dex",
            dexFile.asText().contains("Lcom/example/demo/MainActivity;"),
        )
    }

    @Test
    fun a_lambda_is_desugared_rather_than_left_for_a_runtime_that_cannot_run_it() = runTest {
        // The other half of the platform-stubs story. ECJ compiles the lambda
        // into an invokedynamic against LambdaMetafactory -- a class no Android
        // runtime has. If D8 did not rewrite it, the APK would build clean and
        // crash on the first call. See tools/ecj/FINDINGS.md.
        val layout = ProjectLayout.of(fixture.project())
        val workspace = fixture.workspace()
        File(layout.javaDir, "com/example/demo/Lambdas.java").writeText(
            """
            package com.example.demo;

            import java.util.List;
            import java.util.stream.Collectors;

            public final class Lambdas {
                public static List<String> shout(List<String> words) {
                    return words.stream().map(w -> w + "!").collect(Collectors.toList());
                }
            }
            """.trimIndent(),
        )
        compile(layout, workspace)

        val result = dex(layout, workspace)

        assertTrue("dexing failed: ${result.failure} ${result.diagnostics}", result.succeeded)
        val dexText = requireNotNull(result.value).single().asText()

        assertTrue(
            "LambdaMetafactory survived into the dex: D8 did not desugar the lambda",
            !dexText.contains("LambdaMetafactory"),
        )
        // What D8 names the classes it invents in its place.
        assertTrue(
            "no synthesized class in the dex: the lambda went somewhere unexpected",
            dexText.contains("D8${'$'}${'$'}SyntheticClass"),
        )
    }

    @Test
    fun a_project_with_no_classes_is_not_a_failure() = runTest {
        // Resources and no code is a legal APK. Failing here would make it
        // unbuildable for no reason.
        val layout = ProjectLayout.of(fixture.project())
        val workspace = fixture.workspace()

        val result = dex(layout, workspace)

        assertTrue("should have succeeded: ${result.failure}", result.succeeded)
        assertEquals(emptyList<File>(), result.value)
    }

    @Test
    fun a_class_file_D8_cannot_read_fails_the_stage() = runTest {
        val layout = ProjectLayout.of(fixture.project())
        val workspace = fixture.workspace()
        compile(layout, workspace)
        File(workspace.classes, "com/example/demo/MainActivity.class")
            .writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))

        val result = dex(layout, workspace)

        assertTrue("a truncated class file should fail the stage", !result.succeeded)
        assertTrue(
            "the failure says nothing: ${result.failure}",
            !result.failure.isNullOrBlank(),
        )
    }

    private suspend fun dexInShards(
        layout: ProjectLayout,
        workspace: BuildWorkspace,
        cache: File,
        dependencies: List<File> = emptyList(),
    ) = d8.dex(
        classesDir = workspace.classes,
        platform = fixture.platform,
        workspace = workspace,
        minSdk = ProjectManifest.minSdk(layout.manifestFile),
        debuggable = true,
        projectRoot = layout.root,
        dependencies = dependencies,
        cacheDir = cache,
    )

    private fun classesIn(dexFiles: List<File>): Set<String> =
        dexFiles.flatMap { DexShards.classDescriptors(it) }.toSet()

    private fun writeHelper(layout: ProjectLayout, text: String) {
        File(layout.javaDir, "com/example/demo/util/Helper.java").apply {
            parentFile?.mkdirs()
            writeText(
                """
                package com.example.demo.util;

                public final class Helper {
                    public static Runnable say() { return () -> System.out.println("$text"); }
                }
                """.trimIndent(),
            )
        }
    }

    /**
     * Sharding changes where classes are, never which classes there are.
     *
     * Compared with a whole-program dex of the same classes, lambdas included:
     * D8's synthetic classes are what would go missing, or appear twice, if a
     * package were not a sound unit.
     */
    @Test
    fun shards_hold_exactly_the_classes_a_whole_program_dex_does() = runTest {
        val layout = ProjectLayout.of(fixture.project())
        writeHelper(layout, "hello")
        // A lambda in the other package too: each shard then carries D8's
        // LambdaMethod annotation, which is the duplicate DexShards tolerates.
        File(layout.javaDir, "com/example/demo/Callbacks.java").writeText(
            "package com.example.demo;\npublic final class Callbacks { " +
                "public static Runnable ping() { return () -> System.out.println(\"ping\"); } }\n",
        )
        val workspace = fixture.workspace()
        compile(layout, workspace)

        val whole = dex(layout, workspace)
        assertTrue("whole-program dex failed: ${whole.failure}", whole.succeeded)
        val expected = classesIn(whole.value!!)

        workspace.dex.listFiles()?.forEach { it.delete() }
        val sharded = dexInShards(layout, workspace, File(fixture.workDir, "dex-cache"))
        assertTrue("sharded dex failed: ${sharded.failure}", sharded.succeeded)

        // D8 names a lambda's class after its context: `Helper$0`, today.
        assertTrue(
            "no lambda class to compare: $expected",
            expected.any { it.startsWith("Lcom/example/demo/util/Helper$") },
        )
        assertEquals(expected, classesIn(sharded.value!!))
        assertTrue(
            "both shards should carry D8's support class, or this is not testing the duplicate",
            sharded.value!!.all { dex -> DexShards.classDescriptors(dex).any { it.startsWith("Lcom/android/tools/r8/") } },
        )
        assertEquals(
            "one dex per package, packaged in order",
            listOf("classes.dex", "classes2.dex"),
            sharded.value!!.map { it.name },
        )
    }

    /**
     * **The point of the cache: an edit re-dexes one package, not the app.**
     *
     * Measured before this existed: a 3,000-class project spent 42 s of a 52 s
     * rebuild dexing classes that had not changed. Asserted by the cache
     * entries themselves -- the untouched package's is the same file, not
     * rewritten -- and by the new code really being in the result.
     */
    @Test
    fun a_rebuild_dexes_only_the_package_that_changed() = runTest {
        val layout = ProjectLayout.of(fixture.project())
        val cache = File(fixture.workDir, "dex-cache")
        writeHelper(layout, "first")
        val first = fixture.workspace()
        compile(layout, first)
        assertTrue(dexInShards(layout, first, cache).succeeded)
        val entries = cache.listFiles()!!.filter { it.isDirectory }.associate { it.name to File(it, "key").readText() }
        assertEquals("expected a shard per package: ${cache.list()?.toList()}", 2, entries.size)
        val stamps = cache.listFiles()!!.associate { it.name to File(it, "classes.dex").lastModified() }

        Thread.sleep(1_100) // past a filesystem's one-second mtime resolution
        writeHelper(layout, "second")
        val second = fixture.workspace()
        compile(layout, second)
        val result = dexInShards(layout, second, cache)
        assertTrue("rebuild failed: ${result.failure}", result.succeeded)

        val after = cache.listFiles()!!.filter { it.isDirectory }.associate { it.name to File(it, "key").readText() }
        val changed = after.filter { (name, key) -> entries[name] != key }.keys
        assertEquals("exactly one package should have been dexed again: $changed", 1, changed.size)
        val untouched = (after.keys - changed).single()
        assertEquals(
            "the unchanged package's dex was rewritten",
            stamps[untouched],
            File(cache, "$untouched/classes.dex").lastModified(),
        )
        assertTrue(
            "the edit is not in the rebuilt app",
            result.value!!.any { it.asText().contains("second") },
        )
        assertTrue(result.value!!.none { it.asText().contains("first") })
    }

    /**
     * The same class in two shards is not packaged twice. The runtime would
     * load whichever came first and say nothing, so the stage falls back to a
     * whole-program dex -- which, for a genuine duplicate, fails as it always
     * did.
     */
    @Test
    fun a_class_in_two_shards_falls_back_to_a_whole_program_dex() = runTest {
        val layout = ProjectLayout.of(fixture.project())
        val workspace = fixture.workspace()
        compile(layout, workspace)
        val duplicate = File(fixture.workDir, "duplicate.jar")
        java.util.zip.ZipOutputStream(duplicate.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("com/example/demo/MainActivity.class"))
            zip.write(File(workspace.classes, "com/example/demo/MainActivity.class").readBytes())
            zip.closeEntry()
        }

        val result = dexInShards(layout, workspace, File(fixture.workDir, "dex-cache"), listOf(duplicate))

        assertTrue("two definitions of MainActivity were packaged: ${result.value}", !result.succeeded)
    }
}
