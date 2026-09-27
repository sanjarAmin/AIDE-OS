package com.osamu.aide.engine.fast

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * How a debug build's code is cut into pieces that can be dexed alone and kept.
 *
 * **Dexing was most of every build.** Measured on the emulator, 2026-09-14: a
 * 3,000-class project built in 52 s warm, 42 s of it D8 converting classes that
 * had not changed since the last build; 480 classes, 13 s with 9 s of dexing.
 * `BuildWorkspace.prepare()` empties the workspace every time, deliberately,
 * so nothing survived to reuse.
 *
 * So a debug build is dexed a shard at a time -- **one shard per package of the
 * project's classes, and one per dependency jar** -- and each shard's dex is
 * kept outside the workspace under a key made from the *contents* of its input
 * and everything else that shapes its output. A shard whose key matches is not
 * dexed again. `engine/fast/FINDINGS.md` section 9 is why the key is content
 * and never a timestamp: `R` changes whenever resources do, so a timestamp rule
 * ships stale constants, while a content rule simply sees `R$string.class`
 * differ and re-dexes that package.
 *
 * **The shards are packaged as they are, as `classes.dex`, `classes2.dex`, ...,
 * with no merge.** Merging per-class intermediates is what Gradle does, and
 * measured on a desktop it cost 8 s of a 21 s whole-program dex for 3,000
 * classes -- a merge on every build would give back most of the saving. Every
 * device this engine builds for loads multiple dex files natively.
 *
 * Why a package is a safe unit: what D8 generates for a class -- a lambda's
 * synthetic class, a nest-mate accessor -- is named after that class and needs
 * only its own nest, and nest-mates share a package. What is *not* safe is
 * interface desugaring, which reads across the hierarchy; it only happens
 * below API 24, so [applies] requires 24. And should D8 ever emit the same
 * shared synthetic into two shards, [duplicateClasses] catches it and the
 * build falls back to one whole-program dex rather than ship two definitions.
 */
internal object DexShards {

    /** Below this D8 desugars default and static interface methods, which needs the whole hierarchy. */
    const val MIN_SDK = 24

    fun applies(debuggable: Boolean, minSdk: Int): Boolean = debuggable && minSdk >= MIN_SDK

    /** One unit of dexing: a name that is stable across builds, and its inputs. */
    data class Shard(val name: String, val inputs: List<File>)

    /**
     * The project's classes by package, then each dependency jar on its own.
     *
     * Named by package path or by the jar's path, so the same code lands in the
     * same shard next build and its cache entry is found.
     */
    fun plan(classesDir: File, classFiles: List<File>, dependencyJars: List<File>): List<Shard> {
        val byPackage = classFiles
            .groupBy { it.parentFile!!.relativeTo(classesDir).invariantSeparatorsPath }
            .toSortedMap()
            .map { (packagePath, files) ->
                Shard("classes:" + packagePath.ifEmpty { "<default>" }, files.sortedBy { it.invariantSeparatorsPath })
            }
        val jars = dependencyJars.map { Shard("jar:" + it.absolutePath, listOf(it)) }
        return byPackage + jars
    }

    /**
     * What decides a shard's dex: its inputs' paths and bytes, and every option
     * that changes the output. A D8 upgrade changes its output, so its version
     * is in the key and an app update invalidates the cache by itself.
     */
    fun key(shard: Shard, root: File, minSdk: Int, d8Version: String, platform: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(text: String) = digest.update((text + "\u0000").toByteArray())
        add("v1")
        add(d8Version)
        add("min=$minSdk")
        add("platform=${platform.name}:${platform.length()}")
        shard.inputs.forEach { file ->
            add(if (file.startsWith(root)) file.relativeTo(root).invariantSeparatorsPath else file.absolutePath)
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            add("")
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** A directory name for a shard: its name, hashed, so paths and colons are no concern. */
    fun directoryName(shard: Shard): String =
        MessageDigest.getInstance("SHA-1").digest(shard.name.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * Class descriptors defined in more than one of [dexFiles], or empty.
     *
     * The runtime would load whichever it met first and say nothing, so this is
     * checked rather than assumed. Read straight from the dex header -- the
     * class_defs table, each entry's type, each type's descriptor string -- as
     * D8 has no public reader, and this is thirty lines.
     */
    fun duplicateClasses(dexFiles: List<File>): Set<String> {
        val seen = HashSet<String>()
        val duplicates = HashSet<String>()
        dexFiles.forEach { dex ->
            classDescriptors(dex)
                .filterNot { it.startsWith(D8_SUPPORT) }
                .forEach { if (!seen.add(it)) duplicates += it }
        }
        return duplicates
    }

    /**
     * D8's own support classes, which every shard may carry a copy of.
     *
     * **Found by the first test with a lambda in it:** a debug dex of any class
     * with a lambda also defines `Lcom/android/tools/r8/annotations/LambdaMethod;`,
     * so two packages with lambdas -- which is every real app -- each got one,
     * the duplicate check fired, and the cache fell back to a whole-program
     * dex on every build while appearing to work. These are fixed definitions
     * D8 writes the same way each time, so which copy the runtime loads does
     * not matter; `DexStageTest` installs and runs such an APK.
     */
    private const val D8_SUPPORT = "Lcom/android/tools/r8/"

    internal fun classDescriptors(dex: File): List<String> = RandomAccessFile(dex, "r").use { file ->
        fun u4(offset: Long): Long {
            file.seek(offset)
            val b = ByteArray(4)
            file.readFully(b)
            return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
                ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
        }
        val stringIdsOff = u4(0x3C)
        val typeIdsOff = u4(0x44)
        val classDefsSize = u4(0x60)
        val classDefsOff = u4(0x64)
        (0 until classDefsSize).map { index ->
            val typeIndex = u4(classDefsOff + index * 32)
            val stringIndex = u4(typeIdsOff + typeIndex * 4)
            val dataOff = u4(stringIdsOff + stringIndex * 4)
            file.seek(dataOff)
            // uleb128 length in UTF-16 units, then MUTF-8 bytes ending in 0.
            while (file.readUnsignedByte() and 0x80 != 0) Unit
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val b = file.readUnsignedByte()
                if (b == 0) break
                bytes.write(b)
            }
            // Descriptors are ASCII in practice; MUTF-8 only differs for
            // characters no class name here uses, and a mismatch would at worst
            // miss a duplicate between two such names.
            bytes.toString(Charsets.UTF_8.name())
        }
    }
}
