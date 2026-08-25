package com.osamu.aide.engine.fast

import android.content.Context
import java.io.File

/**
 * Finds the Kotlin compiler, if this device has one.
 *
 * Deliberately not part of `:toolchain:manager` yet. That module downloads a
 * component from a pinned URL and verifies it against a checksum, and the
 * Kotlin archive **is not published anywhere**: it is produced by
 * `tools/kotlinc/build-kotlinc-dex.py` from a jar set the repository can now
 * mostly rebuild, but nothing hosts the 54 MB result. Inventing a URL for it
 * would be worse than admitting the gap.
 *
 * So the archive is looked for where a component would be installed, and
 * everything Kotlin degrades cleanly when it is absent. Once there is somewhere
 * to download it from, this becomes a `ToolchainComponent` and this class goes
 * away.
 */
class KotlinToolchainProvider(private val context: Context) {

    /**
     * Null when the compiler is not installed, which is the normal state.
     *
     * The caller turns that into a refusal naming the missing component; it
     * must never become a build that starts and fails in the middle.
     */
    fun toolchain(): KotlinToolchain? {
        val root = File(context.filesDir, "toolchains/$COMPONENT_ID")
        val toolchain = KotlinToolchain(
            archive = File(root, ARCHIVE_NAME),
            stdlib = File(root, STDLIB_NAME),
        )
        return toolchain.takeIf { it.validate() == null }
    }

    /** Where an installer should put it. Public so a UI can say so. */
    fun installDirectory(): File = File(context.filesDir, "toolchains/$COMPONENT_ID")

    private companion object {
        const val COMPONENT_ID = "kotlin-compiler"

        /**
         * Must be `.jar`. Plugin discovery matches on the extension and returns
         * an empty list for anything else without complaint, so the same bytes
         * named `.zip` compile cleanly and transform nothing.
         */
        const val ARCHIVE_NAME = "kotlinc.jar"
        const val STDLIB_NAME = "kotlin-stdlib.jar"
    }
}
