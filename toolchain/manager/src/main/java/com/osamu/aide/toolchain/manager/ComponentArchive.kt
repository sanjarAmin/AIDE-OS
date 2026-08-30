package com.osamu.aide.toolchain.manager

/**
 * What is inside a component's download, and what installing it means.
 *
 * Two shapes, because two genuinely different things are downloaded. The
 * Android platform and the Kotlin compiler are zips from which a couple of
 * named files are worth keeping and the rest is discarded. The C/C++ toolchain
 * is a 551 MB tree in which nearly everything is load-bearing and the
 * *relationships* between files matter -- so it is kept whole, and it has to be
 * a tar, because a zip cannot carry the symlinks.
 */
sealed interface ComponentArchive {

    /**
     * The path, relative to the install directory, whose existence means the
     * component is installed.
     *
     * Every install writes this last, so a partial one is never mistaken for a
     * finished one.
     */
    val installedMarker: String

    /**
     * Named entries lifted out of a zip; everything else is discarded.
     *
     * The map is ordered deliberately: the first value is the marker, and for
     * Kotlin that is the compiler archive rather than the stdlib beside it.
     */
    data class ZipEntries(val entries: Map<String, String>) : ComponentArchive {
        override val installedMarker: String get() = entries.values.first()
    }

    /**
     * A gzipped tar unpacked whole, symlinks and permissions preserved.
     *
     * Gzipped because it makes the difference between a 551 MB download and a
     * 152 MB one, on a phone. The symlinks are not decoration: `clang++` is a
     * link to `clang`, and the name a driver is invoked under is the whole of
     * what selects the language it compiles.
     */
    data class GzippedTar(override val installedMarker: String) : ComponentArchive

    /**
     * A zip unpacked whole, rather than picked over.
     *
     * Gradle's own distribution is this shape: a tree of jars under one
     * directory named for the version. Unlike [GzippedTar] the format carries
     * no symlinks and none are needed — nothing in it is executed directly,
     * since the engine runs `GradleMain` on our JVM rather than `bin/gradle`.
     */
    data class ZipTree(override val installedMarker: String) : ComponentArchive
}
