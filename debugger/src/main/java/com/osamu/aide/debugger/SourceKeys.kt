package com.osamu.aide.debugger

/**
 * One line of one source file, named the way the VM can recognise it.
 *
 * [key] is the package path joined to the file name -- `com/example/Main.kt` --
 * because that is the only identity an editor and a VM share. The editor knows
 * a file; the VM knows classes, each carrying a package and a `SourceFile`
 * attribute with a bare file name. A project path (`app/src/main/java/...`)
 * means nothing to the VM, and a class name means nothing for Kotlin, where one
 * file compiles to `MainKt`, every class it declares, and a synthetic class per
 * lambda.
 */
data class SourceLine(val key: String, val line: Int)

object SourceKeys {

    /**
     * The key for a source file, from its name and its text.
     *
     * The package comes from the file's own `package` declaration and not from
     * its directory: Kotlin does not require the two to match, and a file whose
     * directory disagrees with its declaration is still compiled into the
     * declared package. Comments are stripped first, because a license header
     * that mentions the word "package" is common and a match inside it would
     * put every breakpoint in the file under the wrong key.
     */
    fun forSource(fileName: String, text: String): String {
        val code = text
            .replace(BLOCK_COMMENT, " ")
            .replace(LINE_COMMENT, "")
        val packageName = PACKAGE.find(code)?.groupValues?.get(1).orEmpty()
        return join(packageName.replace('.', '/'), fileName)
    }

    /**
     * The key for a loaded class, from its signature and `SourceFile`.
     *
     * Inner, anonymous and lambda classes all resolve to the file that
     * declared them: `Lcom/example/Main$onCreate$1;` shares the package of
     * `Main` and carries `Main.kt` as its source file.
     */
    fun forClass(signature: String, sourceFile: String): String {
        val binaryName = signature.removePrefix("L").removeSuffix(";")
        return join(binaryName.substringBeforeLast('/', missingDelimiterValue = ""), sourceFile)
    }

    /** The dotted package a key belongs to, for a `ClassMatch` pattern. */
    fun packageOf(key: String): String = key.substringBeforeLast('/', missingDelimiterValue = "").replace('/', '.')

    /** The JVM package path of a class signature, e.g. `com/example`. */
    fun packagePathOf(signature: String): String =
        signature.removePrefix("L").removeSuffix(";").substringBeforeLast('/', missingDelimiterValue = "")

    private fun join(packagePath: String, fileName: String) =
        if (packagePath.isEmpty()) fileName else "$packagePath/$fileName"

    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val LINE_COMMENT = Regex("""//[^\n]*""")
    private val PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
}

/** How a JVM type signature reads to a person: `I` is `int`, `[Ljava/lang/String;` is `String[]`. */
fun readableType(signature: String): String = when {
    signature.startsWith("[") -> readableType(signature.substring(1)) + "[]"
    signature.startsWith("L") ->
        signature.removePrefix("L").removeSuffix(";").substringAfterLast('/').substringAfterLast('$')
    else -> when (signature.firstOrNull()) {
        'Z' -> "boolean"
        'B' -> "byte"
        'C' -> "char"
        'S' -> "short"
        'I' -> "int"
        'J' -> "long"
        'F' -> "float"
        'D' -> "double"
        'V' -> "void"
        else -> signature
    }
}
