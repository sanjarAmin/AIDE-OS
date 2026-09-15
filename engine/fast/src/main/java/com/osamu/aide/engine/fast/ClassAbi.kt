package com.osamu.aide.engine.fast

import java.io.DataInputStream
import java.io.File

/**
 * What other classes can see of a compiled class: its name, the source file it
 * came from, and a text rendering of its ABI.
 *
 * The ABI is everything a *different* source file could have compiled against:
 * the class's access, supertypes and generic signature, and every non-private
 * field and method with its descriptor, generic signature, thrown types -- and,
 * for a field, its **constant value**. That last one is the trap: `javac` and
 * ECJ copy a `static final` constant into every class that reads it, so the
 * reader's class file no longer mentions the class it came from. A change to a
 * constant has to count as a change to the ABI, or the classes that inlined the
 * old value are never recompiled. `R.java` is exactly that, a class of
 * constants that changes whenever a resource does.
 *
 * Method bodies, private members, synthetic members and the classes a source
 * declares anonymously are left out: nothing outside the source file can refer
 * to them, so a change to them needs only that file recompiled.
 *
 * **For Kotlin, `@kotlin.Metadata` is ABI too.** Nullability, `internal`,
 * default arguments, whether a function is `inline` -- a Kotlin caller compiles
 * against all of these, and none of them is in a JVM descriptor: `String` and
 * `String?` are both `Ljava/lang/String;`. The annotation's declarations are
 * therefore part of the rendering. A body edit leaves them alone; an edit that
 * adds a private function does not, which only costs a full compile.
 *
 * Read by hand because the format is small and stable, and the alternatives are
 * internal: ECJ's reader is `org.eclipse.jdt.internal`, and D8's copy of ASM is
 * shaded under a package name that changes with its version.
 */
internal data class ClassAbi(
    /** Internal name, `com/example/Main$Inner`. */
    val name: String,
    /** The `SourceFile` attribute, `Main.java`, or null when compiled without it. */
    val sourceFile: String?,
    val abi: String,
) {
    /** The package path this class is in, `com/example`. */
    val packagePath: String get() = name.substringBeforeLast('/', missingDelimiterValue = "")

    /** Anonymous and local classes, `Main$1` and `Main$1Helper`: visible only to their own source. */
    val isLocal: Boolean
        get() = name.substringAfterLast('/').split('$').drop(1).any { it.firstOrNull()?.isDigit() == true }

    companion object {
        private const val ACC_PRIVATE = 0x0002
        private const val ACC_SUPER = 0x0020
        private const val ACC_SYNTHETIC = 0x1000

        fun read(file: File): ClassAbi = file.inputStream().buffered().use { read(DataInputStream(it)) }

        fun read(input: DataInputStream): ClassAbi {
            check(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
            input.readUnsignedShort()
            input.readUnsignedShort()

            val count = input.readUnsignedShort()
            val utf8 = arrayOfNulls<String>(count)
            val classIndex = IntArray(count)
            val constant = arrayOfNulls<String>(count)
            var i = 1
            while (i < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> utf8[i] = input.readUTF()
                    3 -> constant[i] = "I" + input.readInt()
                    4 -> constant[i] = "F" + input.readFloat().toRawBits()
                    5 -> { constant[i] = "J" + input.readLong(); i++ }
                    6 -> { constant[i] = "D" + input.readDouble().toRawBits(); i++ }
                    7 -> classIndex[i] = input.readUnsignedShort()
                    8 -> constant[i] = "S#" + input.readUnsignedShort()
                    9, 10, 11, 12, 17, 18 -> { input.readUnsignedShort(); input.readUnsignedShort() }
                    15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                    16, 19, 20 -> input.readUnsignedShort()
                    else -> error("unknown constant pool tag $tag")
                }
                i++
            }
            fun className(index: Int) = if (index == 0) "" else utf8[classIndex[index]].orEmpty()
            fun constantValue(index: Int): String {
                val value = constant[index].orEmpty()
                // A string constant is an index to its UTF-8 entry; render the text.
                return if (value.startsWith("S#")) "S" + utf8[value.removePrefix("S#").toInt()] else value
            }

            val access = input.readUnsignedShort()
            val name = className(input.readUnsignedShort())
            val superName = className(input.readUnsignedShort())
            val interfaces = (0 until input.readUnsignedShort()).map { className(input.readUnsignedShort()) }

            fun members(kind: Char): List<String> =
                (0 until input.readUnsignedShort()).mapNotNull {
                    val memberAccess = input.readUnsignedShort()
                    val memberName = utf8[input.readUnsignedShort()]
                    val descriptor = utf8[input.readUnsignedShort()]
                    val details = StringBuilder()
                    repeat(input.readUnsignedShort()) {
                        val attribute = utf8[input.readUnsignedShort()]
                        val length = input.readInt()
                        when (attribute) {
                            "ConstantValue" -> details.append(" =").append(constantValue(input.readUnsignedShort()))
                            "Signature" -> details.append(" sig=").append(utf8[input.readUnsignedShort()])
                            "Exceptions" -> {
                                val thrown = (0 until input.readUnsignedShort()).map { className(input.readUnsignedShort()) }
                                details.append(" throws=").append(thrown.sorted())
                            }
                            else -> input.skipFully(length)
                        }
                    }
                    if (memberAccess and (ACC_PRIVATE or ACC_SYNTHETIC) != 0) {
                        null
                    } else {
                        "$kind $memberAccess $memberName $descriptor$details"
                    }
                }.sorted()

            val fields = members('F')
            val methods = members('M')

            var sourceFile: String? = null
            var signature = ""
            var kotlinMetadata = ""
            repeat(input.readUnsignedShort()) {
                val attribute = utf8[input.readUnsignedShort()]
                val length = input.readInt()
                when (attribute) {
                    "SourceFile" -> sourceFile = utf8[input.readUnsignedShort()]
                    "Signature" -> signature = utf8[input.readUnsignedShort()].orEmpty()
                    "RuntimeVisibleAnnotations" -> {
                        repeat(input.readUnsignedShort()) {
                            val type = utf8[input.readUnsignedShort()]
                            val rendered = StringBuilder()
                            repeat(input.readUnsignedShort()) {
                                rendered.append(utf8[input.readUnsignedShort()]).append('=')
                                readElementValue(input, rendered, utf8, ::constantValue)
                                rendered.append(';')
                            }
                            if (type == KOTLIN_METADATA) kotlinMetadata = rendered.toString()
                        }
                    }
                    else -> input.skipFully(length)
                }
            }

            val abi = buildString {
                append("C ").append(access and ACC_SUPER.inv()).append(' ').append(name)
                append(" super=").append(superName)
                append(" implements=").append(interfaces.sorted())
                append(" sig=").append(signature).append('\n')
                if (kotlinMetadata.isNotEmpty()) append("K ").append(kotlinMetadata).append('\n')
                (fields + methods).forEach { append(it).append('\n') }
            }
            return ClassAbi(name, sourceFile, abi)
        }

        private const val KOTLIN_METADATA = "Lkotlin/Metadata;"

        /** One annotation element value, rendered; the format is the JVM spec's, section 4.7.16.1. */
        private fun readElementValue(
            input: DataInputStream,
            out: StringBuilder,
            utf8: Array<String?>,
            constant: (Int) -> String,
        ) {
            when (val tag = input.readUnsignedByte().toChar()) {
                's' -> out.append('"').append(utf8[input.readUnsignedShort()]).append('"')
                'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> out.append(constant(input.readUnsignedShort()))
                'e' -> out.append(utf8[input.readUnsignedShort()]).append('.').append(utf8[input.readUnsignedShort()])
                'c' -> out.append(utf8[input.readUnsignedShort()])
                '@' -> {
                    out.append('@').append(utf8[input.readUnsignedShort()]).append('(')
                    repeat(input.readUnsignedShort()) {
                        out.append(utf8[input.readUnsignedShort()]).append('=')
                        readElementValue(input, out, utf8, constant)
                        out.append(',')
                    }
                    out.append(')')
                }
                '[' -> {
                    out.append('[')
                    repeat(input.readUnsignedShort()) {
                        readElementValue(input, out, utf8, constant)
                        out.append(',')
                    }
                    out.append(']')
                }
                else -> error("unknown annotation element tag $tag")
            }
        }

        private fun DataInputStream.skipFully(bytes: Int) {
            var remaining = bytes
            while (remaining > 0) {
                val skipped = skipBytes(remaining)
                if (skipped <= 0) { readByte(); remaining-- } else remaining -= skipped
            }
        }
    }
}
