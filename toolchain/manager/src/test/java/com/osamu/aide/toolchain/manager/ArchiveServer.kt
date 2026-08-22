package com.osamu.aide.toolchain.manager

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A real HTTP server serving a real zip, on a real socket.
 *
 * The alternative -- a fake that returns bytes from an interface -- would not
 * exercise the two things most likely to be wrong: range requests, and what
 * happens when the server ignores one. Both are properties of HTTP, not of the
 * installer, and only show up against something that speaks it.
 */
class ArchiveServer(
    private val entryName: String = "android-36/android.jar",
    // Incompressible, from a fixed seed. A repeating pattern deflates to a few
    // hundred bytes, which makes the archive too small for a partial download to
    // be a meaningful fraction of it.
    entryBody: ByteArray = ByteArray(300_000).also { java.util.Random(1).nextBytes(it) },
) {

    /** Set false to make the server answer a Range request with the whole file. */
    var honoursRange: Boolean = true

    /** Set to cut every response short, as a dropped connection would. */
    var truncateAfter: Int? = null

    var requests: Int = 0
        private set

    /** Every Range header the server was sent, in order. */
    val rangeHeaders = mutableListOf<String?>()

    val archive: ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(entryBody)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("android-36/source.properties"))
            zip.write("Pkg.Revision=2".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    val sha1: String = MessageDigest.getInstance("SHA-1")
        .digest(archive)
        .joinToString("") { "%02x".format(it) }

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/archive.zip") { exchange -> serve(exchange) }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}/archive.zip"

    fun component(sha1: String = this.sha1) = ToolchainComponent(
        id = "platforms;android-36",
        displayName = "Test Platform",
        archiveUrl = url,
        archiveSha1 = sha1,
        archiveBytes = archive.size.toLong(),
        entry = entryName,
        installedName = "android.jar",
    )

    fun stop() = server.stop(0)

    private fun serve(exchange: HttpExchange) {
        requests++
        val range = exchange.requestHeaders.getFirst("Range")
        rangeHeaders += range

        val from = range
            ?.takeIf { honoursRange }
            ?.removePrefix("bytes=")
            ?.substringBefore('-')
            ?.toIntOrNull()
            ?: 0

        val body = archive.copyOfRange(from, archive.size)
        val sent = truncateAfter?.coerceAtMost(body.size) ?: body.size
        val status = if (from > 0) 206 else 200

        if (from > 0) {
            exchange.responseHeaders.add(
                "Content-Range",
                "bytes $from-${archive.size - 1}/${archive.size}",
            )
        }
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body, 0, sent) }
    }
}
