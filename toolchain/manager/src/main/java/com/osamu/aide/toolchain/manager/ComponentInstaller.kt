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
import java.util.zip.ZipFile

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
        // and the file extracted from it are both on disk at once, briefly.
        val needed = component.archiveBytes * 2
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
     * Pulls the one entry out of the archive.
     *
     * Written to a temporary name and renamed into place, because
     * [ToolchainStorage.isInstalled] is a file existence check: a component
     * half-extracted when the process died would otherwise read as installed
     * for the rest of the app's life.
     */
    private fun extract(component: ToolchainComponent, archive: File): File {
        val target = storage.fileFor(component)
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "${target.name}.partial")

        ZipFile(archive).use { zip ->
            val entry = zip.getEntry(component.entry)
                ?: throw IOException(
                    "${component.displayName} does not contain ${component.entry}.",
                )
            zip.getInputStream(entry).use { input ->
                partial.outputStream().buffered().use { input.copyTo(it) }
            }
        }

        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("${component.displayName} could not be installed.")
        }
        return target
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_BYTES = 512L * 1024
        const val MEGABYTE = 1024 * 1024
        const val CONNECT_TIMEOUT_MILLIS = 30_000
        const val READ_TIMEOUT_MILLIS = 60_000
        const val HTTP_OK = 200
        const val HTTP_PARTIAL_CONTENT = 206
    }
}
