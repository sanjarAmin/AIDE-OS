package com.osamu.aide.engine.fast

import java.io.File

/**
 * The platform a project is compiled against.
 *
 * [androidJar] is the same file aapt2 links against and the Java compiler
 * resolves `android.*` from. It is ~43 MB and is not bundled in the APK --
 * getting it onto the device is `:toolchain:manager`'s job -- so it is passed in
 * rather than located here.
 *
 * [platformStubs] is the small jar of `java.lang.invoke` bootstrap classes that
 * android.jar does not contain. Without it no lambda compiles at all; with it
 * nothing changes at run time, because D8 desugars the invokedynamic away. See
 * tools/ecj/FINDINGS.md. It ships as an asset of this module.
 */
data class AndroidPlatform(
    val androidJar: File,
    val platformStubs: File,
) {
    /**
     * Order matters only in that both must be present. Never put this on a
     * runtime classpath: the stubs' method bodies throw.
     */
    val compileClasspath: List<File> get() = listOf(androidJar, platformStubs)

    fun validate(): String? = when {
        !androidJar.isFile -> "The Android platform (android.jar) is not installed."
        !platformStubs.isFile -> "platform-stubs.jar is missing from the app's assets."
        else -> null
    }
}
