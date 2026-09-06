package com.osamu.aide.toolchain.manager

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Every pinned component, against what is actually published.
 *
 * **Nothing exercised the pins**, and one of them was wrong for long enough to
 * ship: `kotlin-analysis-2.2.10` was pinned at 1,991,075 bytes against a
 * release holding 1,988,723, which made the component impossible to install and
 * impossible to retry -- `FINDINGS.md` has the mechanism. A sweep of 560 tests
 * was green throughout, because every test that uses a component stages its
 * archive by hand. That is right for speed and for working offline, and it
 * leaves the one number a user depends on unchecked.
 *
 * This is the cheap thing that would have caught it: a `HEAD` per component,
 * comparing `Content-Length` with the constant, and a `GET` to check the digest.
 *
 * **Network, so it is opt in.** It is the only test here that reaches the
 * internet, and a suite that fails on an aeroplane is a suite people stop
 * running:
 *
 * ```
 * ./gradlew :toolchain:manager:test -Ppins=true
 * ```
 */
class PinnedReleaseTest {

    private fun enabled() = assumeTrue(
        "set -Ppins=true (or PINS=true) to check the pins against the network",
        System.getProperty("pins") == "true" || System.getenv("PINS") == "true",
    )

    @Test
    fun `every pinned component matches the published artifact`() {
        enabled()

        val failures = ToolchainComponent.ALL.mapNotNull { component ->
            val url = URL(component.archiveUrl)
            val bytes = runCatching { url.readBytes() }.getOrElse {
                return@mapNotNull "${component.id}: could not be fetched (${it.message})"
            }
            val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes)
                .joinToString("") { "%02x".format(it) }

            when {
                bytes.size.toLong() != component.archiveBytes ->
                    "${component.id}: published ${bytes.size} bytes, pinned ${component.archiveBytes}"
                !sha1.equals(component.archiveSha1, ignoreCase = true) ->
                    "${component.id}: published sha1 $sha1, pinned ${component.archiveSha1}"
                else -> null
            }
        }

        assertEquals(
            "a pin disagrees with what is published, which makes that component " +
                "impossible to install; see toolchain/manager/FINDINGS.md",
            emptyList<String>(),
            failures,
        )
    }

    /**
     * The cheaper half, worth having on its own: a `HEAD` catches a size drift
     * without downloading a hundred megabytes, and size drift is what actually
     * broke the install.
     */
    @Test
    fun `every pinned size matches, by HEAD alone`() {
        enabled()

        val failures = ToolchainComponent.ALL.mapNotNull { component ->
            val connection = (URL(component.archiveUrl).openConnection() as HttpURLConnection)
                .apply { requestMethod = "HEAD"; instanceFollowRedirects = true }
            val length = runCatching {
                connection.connect()
                connection.contentLengthLong
            }.getOrElse {
                return@mapNotNull "${component.id}: HEAD failed (${it.message})"
            }.also { connection.disconnect() }

            "${component.id}: published $length bytes, pinned ${component.archiveBytes}"
                .takeIf { length > 0 && length != component.archiveBytes }
        }

        assertEquals("a pinned size disagrees with the published artifact", emptyList<String>(), failures)
    }
}
