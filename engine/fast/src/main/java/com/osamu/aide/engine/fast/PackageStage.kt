package com.osamu.aide.engine.fast

import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Joins the two halves of the build into one unsigned APK.
 *
 * aapt2 produced an archive with the resources and the binary manifest but no
 * code; D8 produced the code but no archive. This copies the former and adds the
 * latter, and does nothing else -- no alignment, because apksig realigns every
 * uncompressed entry as it writes the signed copy, and doing it here would only
 * be undone.
 */
internal class PackageStage(private val dispatchers: DispatcherProvider) {

    suspend fun pack(
        workspace: BuildWorkspace,
        dexFiles: List<File>,
        nativeLibraries: List<File> = emptyList(),
        /** Dependencies' `jni/` directories, one subdirectory per ABI. */
        libraryNativeDirectories: List<File> = emptyList(),
        /** Dependencies' `assets/`, weakest first. */
        libraryAssets: List<File> = emptyList(),
        abi: String = android.os.Build.SUPPORTED_ABIS.first(),
    ): StageResult<File> = withContext(dispatchers.io) {
        if (!workspace.linkedApk.isFile) {
            return@withContext StageResult.failed("There is nothing to package.")
        }

        runCatching {
            ZipFile(workspace.linkedApk).use { linked ->
                ZipOutputStream(workspace.unsignedApk.outputStream().buffered()).use { out ->
                    // A zip may not repeat a name, and the project's own entry
                    // always comes first -- so the first writer of a name wins,
                    // and the project's asset or library beats a dependency's.
                    val written = HashSet<String>()
                    for (entry in linked.entries()) {
                        if (entry.isDirectory) continue
                        written += entry.name
                        out.putNextEntry(copyOf(entry))
                        linked.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                    for (dex in dexFiles) {
                        out.putNextEntry(entryFor(dex.name, ZipEntry.DEFLATED))
                        dex.inputStream().use { it.copyTo(out) }
                        out.closeEntry()
                    }
                    for (library in nativeLibraries) {
                        // **Deflated, so the platform extracts at install.**
                        // The alternative is storing them uncompressed and
                        // loading them in place, which is what modern AGP does
                        // -- but that requires page-aligning every entry, and
                        // apksig aligns to 4 bytes, not to a page. A library
                        // stored and misaligned installs and then fails to load
                        // on devices with larger pages. Compressed costs
                        // install-time disk and nothing else.
                        val name = "lib/$abi/${library.name}"
                        if (!written.add(name)) continue
                        out.putNextEntry(entryFor(name, ZipEntry.DEFLATED))
                        library.inputStream().use { it.copyTo(out) }
                        out.closeEntry()
                    }
                    // A dependency's native code, for this device's ABI only:
                    // an AAR with `jni/` used to reach the APK without it, and
                    // its `System.loadLibrary` failed on first use. Deflated
                    // for the reason given above.
                    for (directory in libraryNativeDirectories) {
                        File(directory, abi).listFiles { file -> file.isFile && file.extension == "so" }
                            ?.sortedBy { it.name }
                            ?.forEach { library ->
                                val name = "lib/$abi/${library.name}"
                                if (!written.add(name)) return@forEach
                                out.putNextEntry(entryFor(name, ZipEntry.DEFLATED))
                                library.inputStream().use { it.copyTo(out) }
                                out.closeEntry()
                            }
                    }
                    // Strongest first, so that under first-writer-wins the
                    // library that depends on another keeps its own copy.
                    for (directory in libraryAssets.asReversed()) {
                        directory.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { asset ->
                            val name = "assets/" + asset.relativeTo(directory).invariantSeparatorsPath
                            if (!written.add(name)) return@forEach
                            out.putNextEntry(entryFor(name, ZipEntry.DEFLATED))
                            asset.inputStream().use { it.copyTo(out) }
                            out.closeEntry()
                        }
                    }
                }
            }
        }.fold(
            onSuccess = { StageResult.ok(workspace.unsignedApk) },
            onFailure = { failure ->
                // A half-written APK left on disk is worse than none: the next
                // stage would sign it, and the user would install it.
                workspace.unsignedApk.delete()
                StageResult.failed(
                    "Packaging failed: ${failure.message ?: failure::class.java.simpleName}",
                )
            },
        )
    }

    /**
     * Re-declares an entry from the linked APK, keeping aapt2's choice of
     * compression -- it already knows which resource extensions are not worth
     * deflating -- except for `resources.arsc`.
     *
     * That one must be stored, whatever aapt2 did with it: from API 30 the
     * platform maps the resource table straight out of the APK, and refuses to
     * install a package whose table is compressed. Nothing in the pipeline
     * before this point would catch it, because the APK is otherwise perfectly
     * well-formed; the failure appears at install time as a bare INSTALL_FAILED
     * code.
     */
    private fun copyOf(entry: ZipEntry): ZipEntry {
        val stored = entry.name == RESOURCE_TABLE || entry.method == ZipEntry.STORED
        return entryFor(entry.name, if (stored) ZipEntry.STORED else ZipEntry.DEFLATED).apply {
            if (stored) {
                // A stored entry's header is written before its data, so the
                // sizes and CRC have to be known up front. They are the
                // uncompressed values, which the source entry already carries
                // whichever way it was stored.
                size = entry.size
                compressedSize = entry.size
                crc = entry.crc
            }
        }
    }

    private fun entryFor(name: String, method: Int) = ZipEntry(name).apply {
        this.method = method
        // A fixed timestamp, so that building the same sources twice produces
        // the same bytes and a diff of two APKs shows only what changed.
        time = FIXED_TIMESTAMP
    }

    private companion object {
        const val RESOURCE_TABLE = "resources.arsc"

        /**
         * Local midnight on 1980-01-01, the earliest a zip entry can claim.
         *
         * Built in the default zone on purpose. Zip stores wall-clock time with
         * no offset, so an instant fixed in UTC lands on a different DOS date
         * per device -- and in a zone behind UTC, on a date zip cannot encode at
         * all, which the writer silently clamps.
         */
        val FIXED_TIMESTAMP = java.util.GregorianCalendar(1980, 0, 1, 0, 0, 0).timeInMillis
    }
}
