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
 * Extraction is cached by *a completion marker plus mtime*, which is sound here
 * in a way it would not be for a build: these files come out of the local Maven
 * repository, where an artifact at a fixed version is immutable by contract. A
 * snapshot would break that assumption, which is one more reason `:engine:deps`
 * does not support snapshots yet.
 *
 * **Unpacked beside the target and renamed into place.** It used to be written
 * straight into its final directory and trusted whenever `classes.jar` existed
 * -- so a process killed part way, with `classes.jar` written and `res/`,
 * `R.txt` and the manifest not, left a directory that was reused on every build
 * after. The library's resources, R class and components disappeared, or ECJ
 * choked on a truncated jar, until someone cleared the cache.
 */
internal object AarExtractor {

    private val PACKAGE = Regex("""\bpackage\s*=\s*"([^"]+)"""")

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

        if (!File(target, COMPLETE).isFile || target.lastModified() < aar.lastModified()) {
            val staging = File(aar.parentFile, "${aar.nameWithoutExtension}.partial")
            staging.deleteRecursively()
            staging.mkdirs()
            // A hostile or truncated archive is a bad dependency, not a crash.
            // It arrives over the network from a coordinate the user typed, so
            // failing the one artifact and letting the caller report it beats
            // taking the whole resolution down with a ZipException.
            val unpacked = runCatching {
                unpack(aar, staging)
                // Last, so its presence means everything before it was written.
                File(staging, COMPLETE).writeText("")
            }.isSuccess
            target.deleteRecursively()
            if (!unpacked || !staging.renameTo(target)) {
                staging.deleteRecursively()
                return null
            }
        }
        if (!classes.isFile) return null

        val resources = File(target, "res").takeIf { it.isDirectory && it.listFiles()?.isNotEmpty() == true }
        val rTxt = File(target, "R.txt").takeIf { it.isFile }
        val manifest = File(target, "AndroidManifest.xml").takeIf { it.isFile }

        return ResolvedDependency(
            coordinate,
            classes,
            resources,
            rTxt,
            manifest,
            packageName = manifest?.let(::packageOf),
            // **The rest of what an AAR can carry.** Only the four above used
            // to be taken, so a library built with a local jar dependency --
            // shipped under `libs/` -- compiled, and then threw
            // NoClassDefFoundError the first time it touched that jar; one with
            // native code failed `System.loadLibrary`; one with assets found
            // none. The format is fixed by AGP: `libs/*.jar`, `jni/<abi>/*.so`,
            // `assets/`.
            extraJars = File(target, "libs").listFiles { file -> file.isFile && file.extension == "jar" }
                ?.sortedBy { it.name }
                .orEmpty(),
            nativeLibraries = File(target, "jni").takeIf { it.isDirectory },
            assets = File(target, "assets").takeIf { it.isDirectory && it.listFiles()?.isNotEmpty() == true },
        )
    }

    /** Written last into a finished extraction; see the class comment. */
    private const val COMPLETE = ".extracted"

    /**
     * The `package` an AAR's manifest declares, which is the package its
     * compiled code expects to find its own `R` class in.
     *
     * Read with a regex rather than a DOM parse. This runs once per artifact
     * on a graph that can be seventy of them, the attribute is on the root
     * element of a file the build system wrote, and the failure mode of a
     * miss is one library's R class going missing -- which
     * `ComposeRunTest` catches, because the app crashes on launch.
     */
    private fun packageOf(manifest: File): String? = runCatching {
        PACKAGE.find(manifest.readText())?.groupValues?.get(1)
    }.getOrNull()

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
