package com.osamu.aide.analysisapi.backend

import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipFile

/**
 * The top-level callables in a set of jars, read from `@kotlin.Metadata`.
 *
 * **Why the annotation and not the class file's method table.** A jar's
 * bytecode is the *lowering* of Kotlin, not Kotlin, and the two disagree in
 * ways that matter here:
 *
 * - `println`, `let`, `also`, `require` are `@kotlin.internal.InlineOnly`, so
 *   the compiler emits them **private** to stop Java calling them. A scan
 *   filtering on `ACC_PUBLIC` drops them, and nothing says so -- the first
 *   version of this index was missing a third of the standard library that way.
 * - Nothing in a method's descriptor says it is an **extension**. An extension
 *   is a static method whose first parameter happens to be the receiver, which
 *   is indistinguishable from an ordinary two-argument function.
 *
 * `@kotlin.Metadata` carries the protobuf the compiler wrote, so it answers
 * both directly and needs no special cases. `kotlin-compiler-embeddable`
 * already ships the reader (`JvmProtoBufUtil`) and a shaded ASM to get the
 * annotation out, so this costs no new dependency.
 *
 * Read at runtime rather than shipped: the jars that matter include the
 * project's own dependencies, which the build cannot know. It also removes the
 * version coupling a prebuilt index has with the standard library it describes.
 *
 * The approach is CodeOnTheGo's, an actively maintained AndroidIDE fork whose
 * `lsp/jvm-symbol-index` reads metadata for the same reason. See
 * `tools/analysisapi/FINDINGS.md` sections 19 and 20.
 */
object MetadataIndex {

    /** One top-level callable: its name, and whether it extends something. */
    data class Callable(val name: String, val isExtension: Boolean)

    /**
     * Package name -> its top-level callables, across [jars].
     *
     * A jar with no Kotlin in it contributes nothing and is skipped whole:
     * `android.jar` is 43 MB of Java, and reading forty thousand class files to
     * discover it has no metadata would be the dominant cost of opening a
     * session. Every jar the Kotlin compiler produces carries a
     * a `.kotlin_module` file under `META-INF`, so its absence is a reliable
     * "no Kotlin here". (Written without a glob on purpose: Kotlin block
     * comments *nest*, so a slash-star inside this KDoc opens a comment that
     * never closes, and the error lands at the end of the file.)
     */
    fun scan(jars: List<File>): Map<String, List<Callable>> {
        val byPackage = HashMap<String, MutableSet<Callable>>()
        for (jar in jars) {
            if (!jar.isFile) continue
            runCatching { scanInto(jar, byPackage) }
        }
        return byPackage.mapValues { (_, callables) -> callables.toList() }
    }

    private fun scanInto(jar: File, byPackage: MutableMap<String, MutableSet<Callable>>) {
        ZipFile(jar).use { zip ->
            val hasKotlin = zip.entries().asSequence().any {
                it.name.startsWith("META-INF/") && it.name.endsWith(".kotlin_module")
            }
            if (!hasKotlin) return

            for (entry in zip.entries()) {
                if (!entry.name.endsWith(".class")) continue
                val packageName = entry.name.substringBeforeLast('/', "").replace('/', '.')
                val metadata = runCatching {
                    zip.getInputStream(entry).use { readMetadata(it.readBytes()) }
                }.getOrNull() ?: continue

                // Kind 2 is a file facade, kind 5 one part of a multifile
                // facade. Those are the only two that carry *top-level*
                // callables; a class's own members are reached through its type
                // and do not belong in this index.
                if (metadata.kind != KIND_FILE_FACADE && metadata.kind != KIND_MULTIFILE_PART) {
                    continue
                }
                if (metadata.d1.isEmpty()) continue

                val (resolver, proto) = runCatching {
                    JvmProtoBufUtil.readPackageDataFrom(metadata.d1, metadata.d2)
                }.getOrNull() ?: continue

                val into = byPackage.getOrPut(packageName) { LinkedHashSet() }
                for (function in proto.functionList) {
                    into += Callable(
                        name = resolver.getString(function.name),
                        isExtension = function.hasReceiverType(),
                    )
                }
                // Extension *properties* are completions too -- `lastIndex`,
                // `indices`, `size` on the receiver types that define them.
                for (property in proto.propertyList) {
                    into += Callable(
                        name = resolver.getString(property.name),
                        isExtension = property.hasReceiverType(),
                    )
                }
            }
        }
    }

    private class Metadata(val kind: Int, val d1: Array<String>, val d2: Array<String>)

    private fun readMetadata(bytes: ByteArray): Metadata? {
        var kind = 1
        val d1 = ArrayList<String>()
        val d2 = ArrayList<String>()
        var found = false

        val visitor = object : ClassVisitor(API) {
            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                if (descriptor != METADATA) return null
                found = true
                return object : AnnotationVisitor(API) {
                    override fun visit(name: String?, value: Any?) {
                        if (name == "k" && value is Int) kind = value
                    }

                    override fun visitArray(name: String?): AnnotationVisitor? {
                        val into = when (name) {
                            "d1" -> d1
                            "d2" -> d2
                            else -> return null
                        }
                        return object : AnnotationVisitor(API) {
                            override fun visit(name: String?, value: Any?) {
                                if (value is String) into += value
                            }
                        }
                    }
                }
            }
        }

        // SKIP_CODE is most of the win: the annotation is in the class header,
        // and parsing method bodies for every class in a jar is the difference
        // between a scan that fits in a session build and one that does not.
        ClassReader(bytes).accept(
            visitor,
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return if (found) Metadata(kind, d1.toTypedArray(), d2.toTypedArray()) else null
    }

    private const val API = Opcodes.ASM9
    private const val METADATA = "Lkotlin/Metadata;"
    private const val KIND_FILE_FACADE = 2
    private const val KIND_MULTIFILE_PART = 5

    @Suppress("unused")
    private fun unused(proto: ProtoBuf.Package) = proto
}
