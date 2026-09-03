package com.osamu.aide.toolchain.manager

import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Downloads a component, checks it, and installs the one file that matters.
 *
 * The order is not negotiable: verify the whole archive against its published
 * checksum *before* opening it. A truncated android.jar is not an obvious
 * failure -- it opens as a valid zip missing classes, and surfaces as compile
 * errors against the user's own code with no hint that the platform is at
 * fault.
 */
class ComponentInstaller(
    private val storage: ToolchainStorage,
    private val license: SdkLicense,
    private val dispatchers: DispatcherProvider,
) {

    fun install(component: ToolchainComponent): Flow<InstallProgress> = flow {
        if (storage.isInstalled(component)) {
            emit(InstallProgress.Installed(storage.fileFor(component)))
            return@flow
        }

        if (component.requiresSdkLicense && !license.isAccepted()) {
            emit(
                InstallProgress.Failed(
                    "The Android SDK Terms and Conditions have not been accepted.",
                    licenseRequired = true,
                ),
            )
            return@flow
        }

        val archive = storage.downloadFor(component)

        // Checked before starting rather than discovered 60 MB in. The archive
        // and what comes out of it are both on disk at once, briefly -- so the
        // requirement is the sum, not twice either. For the C/C++ toolchain
        // those two numbers differ by a factor of three and a half, and
        // doubling the download would have let a 710 MB install begin on a
        // device with 320 MB free.
        val needed = component.archiveBytes + component.installedBytes
        val available = archive.parentFile?.usableSpace ?: Long.MAX_VALUE
        if (available < needed) {
            emit(
                InstallProgress.Failed(
                    "${component.displayName} needs ${needed / MEGABYTE} MB free and there " +
                        "is ${available / MEGABYTE} MB.",
                ),
            )
            return@flow
        }

        try {
            download(component, archive)

            emit(InstallProgress.Verifying)
            val actual = sha1(archive)
            if (!actual.equals(component.archiveSha1, ignoreCase = true)) {
                // Delete it. Keeping a corrupt archive would make every later
                // attempt resume onto the same bad bytes and fail identically,
                // which reads as the download being permanently broken.
                archive.delete()
                emit(
                    InstallProgress.Failed(
                        "The download of ${component.displayName} was corrupt. Try again.",
                    ),
                )
                return@flow
            }

            emit(InstallProgress.Extracting)
            val installed = extract(component, archive)
            archive.delete()
            emit(InstallProgress.Installed(installed))
        } catch (failure: IOException) {
            // The partial download is deliberately left in place: it is what
            // makes the next attempt resume rather than start over, and a
            // dropped connection on a phone is the common case, not the
            // exceptional one.
            emit(InstallProgress.Failed(failure.message ?: "The download failed."))
        }
    }.flowOn(dispatchers.io)

    /**
     * Fetches [component]'s archive into [target], resuming a partial one.
     *
     * Resume matters more here than it looks: this is 63 MB over a phone
     * connection, and losing it at 90% and starting from zero is the difference
     * between a feature that works on a train and one that does not. The server
     * has to agree, though -- a 200 where a 206 was asked for means it ignored
     * the range and is sending the whole file, so the partial file is discarded
     * rather than appended to.
     */
    private suspend fun FlowCollector<InstallProgress>.download(
        component: ToolchainComponent,
        target: File,
    ) {
        val existing = if (target.isFile) target.length() else 0L
        if (existing >= component.archiveBytes) return

        val connection = (URL(component.archiveUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            val resumed = connection.responseCode == HTTP_PARTIAL_CONTENT
            if (connection.responseCode != HTTP_OK && !resumed) {
                throw IOException(
                    "${component.displayName} could not be downloaded " +
                        "(HTTP ${connection.responseCode}).",
                )
            }

            val from = if (resumed) existing else 0L
            val total = component.archiveBytes

            connection.inputStream.use { input ->
                RandomAccessFile(target, "rw").use { output ->
                    output.setLength(from)
                    output.seek(from)
                    copy(input, output, from, total)
                }
            }

            // A connection that drops mid-body does not always raise: a fixed
            // length response read short can simply report end of stream. Left
            // unchecked the short file goes on to fail its checksum and be
            // deleted as corrupt, which throws away the very bytes resuming
            // exists to keep.
            if (target.length() < total) {
                throw IOException(
                    "The connection was lost while downloading ${component.displayName}.",
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Copies the body, reporting progress.
     *
     * Progress is emitted per [PROGRESS_INTERVAL_BYTES] rather than per read.
     * A 64 KB buffer over 63 MB is a thousand reads; emitting each one floods
     * the collector with updates finer than a progress bar can show.
     */
    private suspend fun FlowCollector<InstallProgress>.copy(
        input: InputStream,
        output: RandomAccessFile,
        from: Long,
        total: Long,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var written = from
        var sinceReport = 0L

        emit(InstallProgress.Downloading(written, total))
        while (true) {
            // Cancelling the collection has to stop the transfer, not just stop
            // the reporting of it.
            currentCoroutineContext().ensureActive()

            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            written += read
            sinceReport += read

            if (sinceReport >= PROGRESS_INTERVAL_BYTES) {
                emit(InstallProgress.Downloading(written, total))
                sinceReport = 0
            }
        }
        emit(InstallProgress.Downloading(written, total))
    }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Pulls the wanted entries out of the archive.
     *
     * Each is written to a temporary name and renamed into place, and the
     * *primary* entry is renamed last. [ToolchainStorage.isInstalled] is a file
     * existence check on that one file, so finishing it last means a component
     * interrupted part way through never reads as installed -- which would
     * otherwise persist for the life of the app and surface as a compiler that
     * is present and cannot start.
     */
    private fun extract(component: ToolchainComponent, archive: File): File =
        when (component.archive) {
            is ComponentArchive.ZipEntries -> extractEntries(component, archive)
            is ComponentArchive.GzippedTar -> extractTree(component, archive)
            is ComponentArchive.ZipTree -> extractZipTree(component, archive)
        }

    /**
     * Unpacks a whole zip, into a sibling directory moved into place at the end.
     *
     * The same staging discipline as [extractTree] and for the same reason: a
     * 145 MB extraction interrupted by the user backgrounding the app must not
     * leave a half-tree that answers "installed".
     *
     * No permissions are restored, because `java.util.zip` does not read them
     * and nothing in a Gradle distribution is executed by us — the engine runs
     * `GradleMain` on our own JVM rather than the `bin/gradle` script.
     */
    /**
     * Whether an entry stays inside the directory being extracted into.
     *
     * An archive can name `../` and write anywhere; ours do not, and one that
     * did would be an attack.
     *
     * **The archive's own root is not an escape.** A tar packed as
     * `tar -C prefix .` begins with an entry `./`, which resolves to exactly
     * the staging directory — the first version of this rejected it, so a JDK
     * packed that way failed to install with "contains an unsafe path: ./"
     * while a toolchain packed as `usr/` installed fine.
     */
    private fun isInside(destination: File, staging: File): Boolean {
        val root = staging.canonicalPath
        val path = destination.canonicalPath
        return path == root || path.startsWith(root + File.separator)
    }

    private fun extractZipTree(component: ToolchainComponent, archive: File): File {
        val target = storage.directoryFor(component)
        val staging = File(target.parentFile, "${target.name}.partial")
        staging.deleteRecursively()
        staging.mkdirs()

        val shape = component.archive as ComponentArchive.ZipTree

        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                val name = shape.rewrite(entry.name) ?: continue
                val destination = File(staging, name)
                // A zip can name `../` and write outside the directory it is
                // extracted into. Ours does not; one that did would be an
                // attack, and the check is cheap.
                if (!isInside(destination, staging)) {
                    throw IOException("${component.displayName} contains an unsafe path: $name")
                }
                if (entry.isDirectory) {
                    destination.mkdirs()
                } else {
                    destination.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        destination.outputStream().buffered().use { input.copyTo(it) }
                    }
                }
            }
        }

        if (!File(staging, component.archive.installedMarker).exists()) {
            staging.deleteRecursively()
            throw IOException(
                "${component.displayName} does not contain ${component.archive.installedMarker}.",
            )
        }

        target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.deleteRecursively()
            throw IOException("${component.displayName} could not be installed.")
        }
        return storage.fileFor(component)
    }

    /**
     * Unpacks a whole tree, preserving what makes it a toolchain.
     *
     * **Symlinks are recreated as symlinks.** Following them instead would
     * triple the install and, worse, lose the thing they encode: a clang driver
     * decides which language it compiles from the name it was invoked under, so
     * `clang++` being a link to `clang` is not a space optimisation, it is the
     * mechanism. The executable bit is carried across for the same class of
     * reason -- a compiler that is not executable is a confusing silence.
     *
     * Unpacked into a sibling directory and moved into place at the end. A
     * 551 MB extraction interrupted by the user backgrounding the app is the
     * common case, not the rare one, and a half-tree that answered "installed"
     * would fail later inside a header with nothing pointing back here.
     */
    private fun extractTree(component: ToolchainComponent, archive: File): File {
        val target = storage.directoryFor(component)
        val staging = File(target.parentFile, "${target.name}.partial")
        staging.deleteRecursively()
        staging.mkdirs()

        archive.inputStream().buffered().use { raw ->
            TarArchiveInputStream(GZIPInputStream(raw)).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val destination = File(staging, entry.name)
                    // A tar can name `../` and write outside the directory it
                    // was extracted into. Ours does not; an archive that did
                    // would be an attack, and this is cheap.
                    if (!isInside(destination, staging)) {
                        throw IOException("${component.displayName} contains an unsafe path: ${entry.name}")
                    }
                    when {
                        entry.isDirectory -> destination.mkdirs()
                        entry.isSymbolicLink -> {
                            destination.parentFile?.mkdirs()
                            Files.createSymbolicLink(
                                destination.toPath(),
                                File(entry.linkName).toPath(),
                            )
                        }
                        else -> {
                            destination.parentFile?.mkdirs()
                            destination.outputStream().buffered().use { tar.copyTo(it) }
                            if (entry.mode and OWNER_EXECUTE != 0) destination.setExecutable(true)
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }

        // The zip path fails loudly when a named entry is absent; a tree needs
        // the same guarantee, and its marker is the only thing that expresses
        // it. Without this an archive of the wrong shape installs "successfully"
        // and every later use reports the toolchain as not installed, with
        // nothing pointing at the download that was actually wrong.
        if (!File(staging, component.archive.installedMarker).exists()) {
            staging.deleteRecursively()
            throw IOException(
                "${component.displayName} does not contain ${component.archive.installedMarker}.",
            )
        }

        target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.deleteRecursively()
            throw IOException("${component.displayName} could not be installed.")
        }
        return storage.fileFor(component)
    }

    private fun extractEntries(component: ToolchainComponent, archive: File): File {
        val entries = (component.archive as ComponentArchive.ZipEntries).entries
        val directory = storage.directoryFor(component).apply { mkdirs() }
        val primary = storage.fileFor(component)
        val staged = mutableMapOf<File, File>()

        ZipFile(archive).use { zip ->
            entries.forEach { (name, installedName) ->
                val entry = zip.getEntry(name)
                    ?: throw IOException("${component.displayName} does not contain $name.")
                val partial = File(directory, "$installedName.partial")
                zip.getInputStream(entry).use { input ->
                    partial.outputStream().buffered().use { input.copyTo(it) }
                }
                staged[partial] = File(directory, installedName)
            }
        }

        // Everything but the primary first, so the existence check flips last.
        staged.entries.sortedBy { it.value == primary }.forEach { (partial, target) ->
            if (!partial.renameTo(target)) {
                staged.keys.forEach(File::delete)
                throw IOException("${component.displayName} could not be installed.")
            }
        }
        return primary
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_BYTES = 512L * 1024
        const val OWNER_EXECUTE = 0b001_000_000
        const val MEGABYTE = 1024 * 1024
        const val CONNECT_TIMEOUT_MILLIS = 30_000
        const val READ_TIMEOUT_MILLIS = 60_000
        const val HTTP_OK = 200
        const val HTTP_PARTIAL_CONTENT = 206
    }
}
