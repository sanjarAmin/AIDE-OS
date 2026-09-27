package com.osamu.aide.toolchain.manager

import com.sun.net.httpserver.HttpServer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * Serves a small gzipped tar shaped like the real toolchain.
 *
 * Separate from [ArchiveServer] on purpose. That one exists to exercise HTTP --
 * range requests, and servers that ignore them -- and those are properties of
 * the download, which the tree install shares and does not change. What is new
 * here is what comes *out* of the archive, so this serves plainly and puts its
 * detail in the payload: a nested directory, an executable, a symlink, and a
 * symlink to a symlink, which is the shape that broke `ClangToolchain`.
 */
class TarballServer(private val escaping: Boolean = false) {

    val archive: ByteArray = ByteArrayOutputStream().also { bytes ->
        TarArchiveOutputStream(GZIPOutputStream(bytes)).use { tar ->
            // The archive's own root, which `tar -C prefix .` writes and the
            // JDK archive really has. It resolves to the extraction directory
            // itself, which an over-eager traversal check reads as an escape.
            tar.putArchiveEntry(TarArchiveEntry("./").also { it.mode = DIRECTORY_MODE })
            tar.closeArchiveEntry()

            tar.putArchiveEntry(TarArchiveEntry("usr/bin/").also { it.mode = DIRECTORY_MODE })
            tar.closeArchiveEntry()

            file(tar, "usr/bin/clang-21", "a compiler, allegedly", executable = true)
            file(tar, "usr/README", "not executable")
            symlink(tar, "usr/bin/clang", "clang-21")
            // clang++ -> clang -> clang-21. The driver picks its language from
            // the name it is invoked under, so this chain is the C++ path.
            symlink(tar, "usr/bin/clang++", "clang")

            // Not something our archives contain; something an archive could.
            if (escaping) file(tar, "../escaped", "should never be written")
        }
    }.toByteArray()

    val sha1: String = MessageDigest.getInstance("SHA-1")
        .digest(archive)
        .joinToString("") { "%02x".format(it) }

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/toolchain.tar.gz") { exchange ->
                exchange.sendResponseHeaders(200, archive.size.toLong())
                exchange.responseBody.use { it.write(archive) }
            }
            start()
        }

    val url: String get() = "http://127.0.0.1:${server.address.port}/toolchain.tar.gz"

    fun component(marker: String = "usr/bin/clang") = ToolchainComponent(
        id = "clang-test",
        displayName = "Test toolchain",
        archiveUrl = url,
        archiveSha1 = sha1,
        archiveBytes = archive.size.toLong(),
        archive = ComponentArchive.GzippedTar(marker),
        installedBytes = 4096L,
        requiresSdkLicense = false,
    )

    fun stop() = server.stop(0)

    private fun file(tar: TarArchiveOutputStream, name: String, body: String, executable: Boolean = false) {
        val bytes = body.toByteArray()
        tar.putArchiveEntry(
            TarArchiveEntry(name).also {
                it.size = bytes.size.toLong()
                it.mode = if (executable) EXECUTABLE_MODE else REGULAR_MODE
            },
        )
        tar.write(bytes)
        tar.closeArchiveEntry()
    }

    private fun symlink(tar: TarArchiveOutputStream, name: String, target: String) {
        tar.putArchiveEntry(
            TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK).also { it.linkName = target },
        )
        tar.closeArchiveEntry()
    }

    private companion object {
        const val DIRECTORY_MODE = 0b111_101_101 or 0b100_000_000_000_000
        const val EXECUTABLE_MODE = 0b111_101_101
        const val REGULAR_MODE = 0b110_100_100
    }
}
