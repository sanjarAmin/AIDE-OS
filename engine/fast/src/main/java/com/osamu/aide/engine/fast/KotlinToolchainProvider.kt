package com.osamu.aide.engine.fast

import android.content.Context
import java.io.File

/**
 * Finds the Kotlin compiler, if this device has one.
 *
 * Downloaded and installed by `:toolchain:manager` like the Android platform,
 * from this project's own releases -- nothing upstream ships a Kotlin compiler
 * that runs on ART, so the archive is built by
 * `tools/kotlinc/build-kotlinc-dex.py` and published alongside the source.
 *
 * This class only *locates* it. Absence is an ordinary state, not a
 * misconfiguration: a Java project never needs the compiler and a user may
 * simply not have downloaded it, so everything Kotlin has to degrade to a
 * refusal that names what is missing.
 */
class KotlinToolchainProvider(private val context: Context) {

    /**
     * Null when the compiler is not installed, which is the normal state.
     *
     * The caller turns that into a refusal naming the missing component; it
     * must never become a build that starts and fails in the middle.
     */
    fun toolchain(): KotlinToolchain? {
        val root = installDirectory()
        val toolchain = KotlinToolchain(
            archive = File(root, ARCHIVE_NAME),
            stdlib = File(root, STDLIB_NAME),
        )
        return toolchain.takeIf { it.validate() == null }
    }

    /**
     * Where `:toolchain:manager` installs it.
     *
     * Derived the same way `ToolchainStorage` derives it rather than shared
     * through a type, because `:engine:fast` does not depend on the manager and
     * should not: the engine's job is to compile with what it is given, not to
     * know how components arrive. The cost is this one convention, and the
     * component id is asserted against the manager's in a test.
     */
    fun installDirectory(): File =
        File(context.filesDir, "toolchains/${COMPONENT_ID.replace(';', '-')}")

    companion object {
        /** Must match `ToolchainComponent.KOTLIN_COMPILER.id`; a test checks it. */
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
