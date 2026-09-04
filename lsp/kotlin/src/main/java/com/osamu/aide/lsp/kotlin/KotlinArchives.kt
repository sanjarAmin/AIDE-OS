package com.osamu.aide.lsp.kotlin

import android.os.Build
import java.io.File

/**
 * The jars Kotlin intelligence loads, and where to stage them.
 *
 * Four files from two components. They must end up on **one flat classloader**:
 * `tools/analysisapi/FINDINGS.md` section 9 is why chaining them does not work
 * -- IntelliJ resolves plugin classes with `Class.forName`, which uses its own
 * loader, and a parent cannot see a child, so the error is a
 * `ClassNotFoundException` for a class plainly present in the dex.
 *
 * A value type rather than four parameters because they are meaningless apart
 * and because [prepare] has to run over all of them before any is loadable.
 */
data class KotlinArchives(
    /** `kotlinc.jar`: the Kotlin compiler, dexed. Carries the shaded IntelliJ. */
    val compilerJar: File,
    /**
     * `kotlin-stdlib.jar`: plain bytecode, and **not on the classloader**.
     *
     * A different job from the others. They are dex the archive runs on; this
     * is the *library module* the session resolves against, read as class files
     * the way a desktop would. Without it a session still answers for `String`
     * -- those are builtins the front end carries -- so its absence looks like
     * success and shows up only as thin completion. FINDINGS.md section 16.
     */
    val stdlibJar: File,
    /** `analysis-api.jar`: the Analysis API, relocated and dexed. */
    val analysisApiJar: File,
    /** `analysis-backend.jar`: our code, compiled against the relocated jars. */
    val backendJar: File,
    /**
     * `top-level-callables.index`: the names the Analysis API will not list.
     *
     * Not a jar and not on any classloader -- a text file the backend reads.
     * Completion can resolve a top-level callable by name and cannot enumerate
     * them, so without this there are no extension proposals at all.
     * `tools/analysisapi/FINDINGS.md` section 20.
     */
    val nameIndex: File,
    /** App-private storage for the read-only copies [prepare] makes. */
    val workingDir: File,
) {

    /** All four present. Not the same as loadable -- see [prepare]. */
    val isComplete: Boolean
        get() = compilerJar.isFile && stdlibJar.isFile &&
            analysisApiJar.isFile && backendJar.isFile && nameIndex.isFile

    /**
     * Stages the jars read-only and returns what to load.
     *
     * **A dex file the app can write to will not load at all.** The
     * `PathClassLoader` *constructor* throws `SecurityException: Writable dex
     * file ... is not allowed` -- before any class is looked up, so the message
     * points at the loader rather than at the file, and it reads like a
     * corrupted archive.
     *
     * Copies rather than marking the originals: the originals belong to
     * `:toolchain:manager`, which reinstalls and verifies them, and a component
     * that cannot overwrite its own files is a component that cannot be
     * repaired. Copied once -- the length check makes a second call cheap, and
     * catches a copy that a killed process left half-written.
     */
    fun prepare(): Prepared {
        workingDir.mkdirs()
        val dexPath = listOf(compilerJar, analysisApiJar, backendJar)
            .map { readOnlyCopy(it) }
            .joinToString(File.pathSeparator) { it.absolutePath }
        // The stdlib is read as class files, not loaded as dex, so it does not
        // need the read-only treatment -- but it is copied for the same reason
        // the others are: the session holds it open for its whole life.
        return Prepared(
            dexPath = dexPath,
            stdlib = readOnlyCopy(stdlibJar),
            nameIndex = readOnlyCopy(nameIndex),
        )
    }

    /** The three paths [prepare] produces. Different jobs, see above. */
    data class Prepared(val dexPath: String, val stdlib: File, val nameIndex: File)

    private fun readOnlyCopy(source: File): File {
        val target = File(workingDir, source.name)
        if (!target.isFile || target.length() != source.length()) {
            target.delete()
            source.copyTo(target, overwrite = true)
        }
        target.setWritable(false, false)
        return target
    }

    companion object {
        /**
         * Whether this device can load the archives at all.
         *
         * They are dexed at `--min-api 30`, the same floor as the Kotlin
         * compiler and aapt2. The app supports 26 because the editor does, so
         * this is a runtime gate rather than a manifest one -- the same shape
         * the build features use, and for the same reason: a feature that is
         * merely absent beats one that fails at load time.
         */
        val isSupported: Boolean
            get() = Build.VERSION.SDK_INT >= 30
    }
}
