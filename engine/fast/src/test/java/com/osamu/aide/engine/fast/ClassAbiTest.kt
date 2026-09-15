package com.osamu.aide.engine.fast

import org.eclipse.jdt.core.compiler.batch.BatchCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * What counts as a change another source could notice.
 *
 * Compiled with ECJ, the compiler the fast engine uses, so the class files are
 * the shape the incremental compile will really read. The two assertions that
 * matter most are the pair about constants and bodies: a body edit must *not*
 * change the ABI, or nothing is ever incremental, and a constant edit *must*,
 * or a reader keeps the inlined old value.
 */
class ClassAbiTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun compile(source: String, name: String = "Subject"): Map<String, ClassAbi> {
        val src = temp.newFolder().resolve("p/$name.java").apply { parentFile.mkdirs(); writeText(source) }
        val out = temp.newFolder()
        val err = StringWriter()
        val ok = BatchCompiler.compile(
            arrayOf("-source", "11", "-target", "11", "-g", "-proc:none", "-d", out.path, src.path),
            PrintWriter(StringWriter()), PrintWriter(err), null,
        )
        assertTrue("compile failed: $err", ok)
        return out.walkTopDown().filter { it.extension == "class" }
            .map { ClassAbi.read(it) }.associateBy { it.name }
    }

    private fun abi(source: String) = IncrementalJava.abiOf(compile(source).values.toList())

    private val base = """
        package p;
        public class Subject {
            public static final int LIMIT = 10;
            public int count(String s) { return s.length() + LIMIT; }
            private int helper() { return 1; }
        }
    """.trimIndent()

    @Test
    fun a_body_edit_leaves_the_abi_alone() {
        assertEquals(abi(base), abi(base.replace("s.length() + LIMIT", "s.length() * 2 + helper()")))
    }

    @Test
    fun a_private_member_added_leaves_the_abi_alone() {
        assertEquals(abi(base), abi(base.replace("private int helper()", "private void other() {}\n    private int helper()")))
    }

    @Test
    fun a_constant_value_changes_the_abi() {
        assertNotEquals(abi(base), abi(base.replace("LIMIT = 10", "LIMIT = 11")))
    }

    @Test
    fun a_public_signature_changes_the_abi() {
        assertNotEquals(abi(base), abi(base.replace("count(String s)", "count(CharSequence s)")))
        assertNotEquals(abi(base), abi(base.replace("public int count", "int count")))
    }

    @Test
    fun a_string_constant_is_read_as_its_text() {
        val one = abi(base.replace("public static final int LIMIT = 10;", "public static final String NAME = \"a\"; public static final int LIMIT = 10;"))
        val two = abi(base.replace("public static final int LIMIT = 10;", "public static final String NAME = \"b\"; public static final int LIMIT = 10;"))
        assertNotEquals(one, two)
    }

    @Test
    fun a_class_names_its_source_and_anonymous_classes_are_local() {
        val classes = compile(
            """
            package p;
            public class Subject {
                public Runnable r = new Runnable() { public void run() {} };
                public static class Nested {}
            }
            """.trimIndent(),
        )
        assertEquals("Subject.java", classes.getValue("p/Subject").sourceFile)
        assertEquals("p/Subject.java", IncrementalJava.sourceKey(classes.getValue("p/Subject\$Nested")))
        assertTrue(classes.getValue("p/Subject\$1").isLocal)
        assertFalse(classes.getValue("p/Subject\$Nested").isLocal)
        // The anonymous class's number is an implementation detail of the body.
        assertEquals(
            IncrementalJava.abiOf(classes.values.toList()),
            IncrementalJava.abiOf(classes.values.filterNot { it.isLocal }),
        )
    }

    @Test
    fun the_state_survives_a_round_trip() {
        val dir = temp.newFolder()
        val cache = IncrementalJava(dir)
        File(dir, "classes").mkdirs()
        val state = IncrementalJava.State(
            settings = "s1",
            sources = mapOf(
                "/p/A.java" to IncrementalJava.Source(
                    hash = "h", classFiles = listOf("p/A.class", "p/A\$1.class"),
                    diagnostics = listOf(
                        com.osamu.aide.engine.api.Diagnostic(
                            com.osamu.aide.engine.api.DiagnosticSeverity.WARNING,
                            "The import\tx is never used\nreally",
                            File("src/p/A.java"),
                            line = 3,
                        ),
                    ),
                ),
            ),
        )
        cache.save(state)
        val loaded = cache.load()!!
        assertEquals(state.settings, loaded.settings)
        assertEquals(state.sources, loaded.sources)
    }

    /**
     * A kept hash is trusted only for a file whose size and time are those it
     * was taken at, and never for one touched within a moment of the state
     * being saved -- the same-second edit `engine/fast/FINDINGS.md` section 9
     * describes, which would otherwise look untouched.
     */
    @Test
    fun a_kept_hash_is_trusted_only_when_it_cannot_be_stale() {
        val source = temp.newFile("A.java").apply { writeText("class A {}") }
        val savedAt = System.currentTimeMillis()
        fun state(size: Long, modified: Long) = IncrementalJava.State(
            "s",
            mapOf(source.absolutePath to IncrementalJava.Source("h", emptyList(), emptyList(), "p/A.java", size, modified)),
            savedAt,
        )

        source.setLastModified(savedAt - 60_000)
        val old = source.lastModified()
        assertEquals("h", state(source.length(), old).trustedHash(source.absolutePath, source)?.hash)
        assertEquals(null, state(source.length() + 1, old).trustedHash(source.absolutePath, source))

        source.setLastModified(savedAt - 500)
        val racy = source.lastModified()
        assertEquals(
            "a file modified as the state was written must be read again",
            null,
            state(source.length(), racy).trustedHash(source.absolutePath, source),
        )
    }
}
