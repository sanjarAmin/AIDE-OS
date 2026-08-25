package com.osamu.aide.engine.deps

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Unpacks the parts of an AAR a build actually consumes.
 *
 * An `.aar` is a zip, and nothing downstream can read one: ECJ wants a jar on
 * the classpath, aapt2 wants a directory of resources, and the R class comes
 * from `R.txt`. So each is written out once, next to the archive, and reused.
 *
 * Extraction is cached by *existence plus mtime*, which is sound here in a way
 * it would not be for a build: these files come out of the local Maven
 * repository, where an artifact at a fixed version is immutable by contract. A
 * snapshot would break that assumption, which is one more reason `:engine:deps`
 * does not support snapshots yet.
 */
internal object AarExtractor {

    /** Where an archive's unpacked form lives: `<name>-1.2.3.aar` -> `<name>-1.2.3/`. */
    private fun unpackedDir(aar: File): File = File(aar.parentFile, aar.nameWithoutExtension)

    /**
     * Returns the parts of [aar], extracting them on first use.
     *
     * Null when the archive carries no `classes.jar`. That is not a corrupt
     * AAR -- a resource-only library is legal and ships exactly that way -- but
     * it has nothing to put on a compile classpath, so it is the caller's
     * decision what to do with it.
     */
    fun extract(coordinate: Coordinate, aar: File): ResolvedDependency? {
        val target = unpackedDir(aar)
        val classes = File(target, "classes.jar")

        if (!classes.isFile || target.lastModified() < aar.lastModified()) {
            target.deleteRecursively()
            target.mkdirs()
            // A hostile or truncated archive is a bad dependency, not a crash.
            // It arrives over the network from a coordinate the user typed, so
            // failing the one artifact and letting the caller report it beats
            // taking the whole resolution down with a ZipException.
            val unpacked = runCatching { unpack(aar, target) }.isSuccess
            if (!unpacked) {
                target.deleteRecursively()
                return null
            }
        }
        if (!classes.isFile) return null

        val resources = File(target, "res").takeIf { it.isDirectory && it.listFiles()?.isNotEmpty() == true }
        val rTxt = File(target, "R.txt").takeIf { it.isFile }
        val manifest = File(target, "AndroidManifest.xml").takeIf { it.isFile }

        return ResolvedDependency(coordinate, classes, resources, rTxt, manifest)
    }

    private fun unpack(aar: File, target: File) {
        ZipFile(aar).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                val destination = resolveSafely(target, entry) ?: return@forEach
                destination.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /**
     * Guards against an entry escaping the target directory.
     *
     * `../` in a zip entry name is the Zip Slip vulnerability, and an IDE that
     * unpacks archives fetched over the network is exactly the program it is
     * aimed at. The check is on the canonical path because `a/../../b` only
     * shows itself once resolved.
     *
     * On Android this is belt and braces: the platform's own `java.util.zip`
     * refuses a restricted entry name on **both** read and write, so a forged
     * archive throws out of [ZipFile] before reaching here. That is welcome and
     * is not a reason to drop the check -- it is the platform's guarantee, not
     * this module's, and it says nothing about the same code on a plain JVM.
     */
    private fun resolveSafely(target: File, entry: ZipEntry): File? {
        val destination = File(target, entry.name)
        val root = target.canonicalPath + File.separator
        return destination.takeIf { it.canonicalPath.startsWith(root) }
    }
}
