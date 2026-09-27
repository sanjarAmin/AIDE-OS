package com.osamu.aide.debugger

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The identity a breakpoint travels under between the editor and the VM.
 *
 * Both halves have to produce the same string for the same file, and each gets
 * there by a different road -- one from text, one from a class signature --
 * so a mismatch is silent: the breakpoint is installed nowhere and simply
 * never fires.
 */
class SourceKeysTest {

    @Test
    fun a_java_file_and_its_class_agree() {
        val fromSource = SourceKeys.forSource(
            "MainActivity.java",
            "package com.example.app;\n\npublic class MainActivity {}",
        )
        val fromClass = SourceKeys.forClass("Lcom/example/app/MainActivity;", "MainActivity.java")
        assertEquals("com/example/app/MainActivity.java", fromSource)
        assertEquals(fromSource, fromClass)
    }

    /** A lambda's synthetic class belongs to the file that wrote the lambda. */
    @Test
    fun a_kotlin_lambda_class_maps_back_to_its_file() {
        assertEquals(
            SourceKeys.forSource("Main.kt", "package com.example\n\nfun main() {}"),
            SourceKeys.forClass("Lcom/example/MainKt\$main\$1;", "Main.kt"),
        )
    }

    /**
     * The declaration wins over a comment that mentions a package.
     *
     * A license header saying "this package is..." is ordinary, and a naive
     * first-match would file every breakpoint in the file under a package
     * named `is`.
     */
    @Test
    fun a_comment_mentioning_package_is_not_the_declaration() {
        val text = """
            /*
             * package this.is.not.it
             */
            // package nor.this
            package com.example.real
        """.trimIndent()
        assertEquals("com/example/real/Main.kt", SourceKeys.forSource("Main.kt", text))
    }

    @Test
    fun a_file_with_no_package_keys_on_its_name_alone() {
        assertEquals("Main.java", SourceKeys.forSource("Main.java", "class Main {}"))
        assertEquals("Main.java", SourceKeys.forClass("LMain;", "Main.java"))
    }

    @Test
    fun the_package_of_a_key_is_dotted_for_class_match() {
        assertEquals("com.example.app", SourceKeys.packageOf("com/example/app/Main.kt"))
        assertEquals("", SourceKeys.packageOf("Main.kt"))
    }

    @Test
    fun types_read_the_way_they_are_written() {
        assertEquals("int", readableType("I"))
        assertEquals("String", readableType("Ljava/lang/String;"))
        assertEquals("String[]", readableType("[Ljava/lang/String;"))
        assertEquals("int[][]", readableType("[[I"))
        assertEquals("Entry", readableType("Ljava/util/Map\$Entry;"))
    }
}
