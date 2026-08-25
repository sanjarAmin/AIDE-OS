package com.osamu.aide.engine.fast

import java.io.File

/**
 * Where the Kotlin compiler lives, and whether it is here at all.
 *
 * The compiler is a **54 MB dex archive**, far too large to sit in the APK, so
 * it arrives the way `android.jar` does: downloaded once and installed by
 * `:toolchain:manager`. Everything Kotlin therefore has to degrade gracefully
 * when it is absent -- a project with `.kt` files in it is still worth opening
 * and editing on a device that has never downloaded the compiler.
 *
 * [archive] must be named `.jar` and nothing else. Plugin discovery matches on
 * the file extension and returns an empty list for anything else without
 * complaint, so the same bytes named `.zip` compile cleanly and transform
 * nothing. `tools/kotlinc/FINDINGS.md` section 7.
 */
data class KotlinToolchain(
    /** `kotlinc.jar`: the compiler and the Compose plugin, dexed together. */
    val archive: File,
    /**
     * `kotlin-stdlib.jar`, needed twice over: on the compile classpath, and
     * inside a staged `kotlin-home/lib` so the compiler stops trying to locate
     * its own installation by reading `PathUtil.class` as a resource -- which a
     * dex cannot answer, because it holds no `.class` entries at all.
     */
    val stdlib: File,
) {

    /** Null when everything needed is present; otherwise why it is not. */
    fun validate(): String? = when {
        !archive.isFile ->
            "The Kotlin compiler is not installed."
        !archive.name.endsWith(".jar") ->
            // Worth failing loudly on rather than letting it through: the
            // symptom is a clean compile that silently skips every plugin.
            "The Kotlin compiler archive must be named .jar, not ${archive.name}."
        !stdlib.isFile ->
            "The Kotlin standard library is not installed."
        else -> null
    }
}
