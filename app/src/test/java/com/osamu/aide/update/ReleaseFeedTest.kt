package com.osamu.aide.update

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest

/**
 * The update check, against a GitHub of our own.
 *
 * The JSON is shaped from what the real API returned on 2026-10-29 for this
 * repository -- pre-releases tagged `app-preview-<sha>`, toolchain releases
 * beside them, asset digests as `sha256:<hex>` -- because a fixture written
 * from memory of an API is how a parser ends up correct against the fixture
 * and wrong against the service.
 */
class ReleaseFeedTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val compares = mutableMapOf<String, MockResponse>()
    private var releases = MockResponse().setBody("[]")
    private var apk = MockResponse().setResponseCode(404)

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/repos/sanjarAmin/AIDE-OS/releases") -> releases
                    path.startsWith("/repos/sanjarAmin/AIDE-OS/compare/") ->
                        compares[path.substringAfter("/compare/")] ?: MockResponse().setResponseCode(404)
                    path.startsWith("/download/") -> apk
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun feed() = ReleaseFeed(OkHttpClient(), apiRoot = server.url("/").toString().removeSuffix("/"))

    private fun release(
        sha: String,
        publishedAt: String,
        tag: String = "app-preview-${sha.take(7)}",
        draft: Boolean = false,
        digest: Any? = "sha256:" + "a".repeat(64),
        body: Any? = "notes",
        apk: Boolean = true,
        target: String = sha,
    ) = JSONObject()
        .put("tag_name", tag)
        .put("name", "AIDE-OS preview (${sha.take(7)}): something new")
        .put("draft", draft)
        .put("prerelease", true)
        .put("target_commitish", target)
        .put("html_url", "https://github.com/sanjarAmin/AIDE-OS/releases/tag/$tag")
        .put("published_at", if (draft) JSONObject.NULL else publishedAt)
        .put("body", body ?: JSONObject.NULL)
        .put(
            "assets",
            JSONArray().apply {
                if (apk) {
                    put(
                        JSONObject()
                            .put("name", "AIDE-OS-${sha.take(7)}-debug.apk")
                            .put("size", 56_260_128)
                            .put("digest", digest ?: JSONObject.NULL)
                            .put("browser_download_url", server.url("/download/$tag.apk").toString()),
                    )
                }
            },
        )

    private fun toolchain(tag: String, publishedAt: String) = JSONObject()
        .put("tag_name", tag)
        .put("name", tag)
        .put("draft", false)
        .put("prerelease", false)
        .put("target_commitish", "main")
        .put("published_at", publishedAt)
        .put("assets", JSONArray().put(JSONObject().put("name", "$tag.tar").put("size", 1).put("browser_download_url", "x")))

    private fun listing(vararg entries: JSONObject) {
        releases = MockResponse().setBody(JSONArray(entries.toList()).toString())
    }

    private fun compareAnswers(base: String, head: String, status: String, aheadBy: Int = 0) {
        compares["$base...$head"] = MockResponse().setBody(
            JSONObject().put("status", status).put("ahead_by", aheadBy).put("behind_by", 0).toString(),
        )
    }

    // -- Finding the latest preview ------------------------------------------

    @Test
    fun the_newest_app_preview_wins_over_newer_toolchain_releases_and_drafts() {
        val json = JSONArray()
            .put(toolchain("python-3.14.7", "2026-10-30T00:00:00Z"))
            .put(release(OLD, "2026-10-27T00:56:38Z"))
            .put(release(NEW, "2026-10-29T15:25:27Z"))
            .put(release(DRAFT, "", draft = true))
            .toString()

        val latest = latestAppPreview(json)

        assertEquals("app-preview-${NEW.take(7)}", latest?.tag)
        assertEquals(NEW, latest?.commit)
        assertEquals("something new", latest?.headline)
    }

    @Test
    fun a_preview_with_no_apk_is_passed_over_for_the_next_one() {
        val json = JSONArray()
            .put(release(NEW, "2026-10-29T15:25:27Z", apk = false))
            .put(release(OLD, "2026-10-27T00:56:38Z"))
            .toString()

        assertEquals(OLD, latestAppPreview(json)?.commit)
    }

    @Test
    fun a_release_made_from_a_branch_name_is_compared_by_its_tag() {
        val json = JSONArray().put(release(NEW, "2026-10-29T15:25:27Z", target = "main")).toString()

        assertEquals(NEW.take(7), latestAppPreview(json)?.commit)
    }

    @Test
    fun the_checksum_comes_from_the_asset_digest_and_otherwise_from_the_notes() {
        val fromDigest = latestAppPreview(JSONArray().put(release(NEW, "2026-10-29T00:00:00Z")).toString())
        assertEquals("a".repeat(64), fromDigest?.apkSha256)

        val fromNotes = latestAppPreview(
            JSONArray().put(
                release(NEW, "2026-10-29T00:00:00Z", digest = null, body = "Built from x.\n\nSHA-256: `${"B".repeat(64)}`"),
            ).toString(),
        )
        assertEquals("b".repeat(64), fromNotes?.apkSha256)
    }

    @Test
    fun a_null_digest_with_no_notes_is_no_checksum_and_not_the_text_null() {
        // The phone's org.json reads JSON null as "null"; this guards the
        // reading, not the library. ai/core/FINDINGS.md §16.
        val latest = latestAppPreview(
            JSONArray().put(release(NEW, "2026-10-29T00:00:00Z", digest = null, body = null)).toString(),
        )
        assertNull(latest?.apkSha256)
    }

    // -- Comparing with the running build --------------------------------------

    @Test
    fun the_build_that_was_published_is_up_to_date_without_asking_github_to_compare() {
        listing(release(NEW, "2026-10-29T15:25:27Z"))

        assertEquals(UpdateCheck.UpToDate, feed().check(NEW))
        assertEquals("only the listing was requested", 1, server.requestCount)
    }

    @Test
    fun a_published_commit_ahead_of_the_build_is_an_update() {
        listing(release(NEW, "2026-10-29T15:25:27Z"))
        compareAnswers(OLD, NEW, "ahead", aheadBy = 2)

        val result = feed().check(OLD)

        assertTrue("expected Available, got $result", result is UpdateCheck.Available)
        result as UpdateCheck.Available
        assertEquals(NEW, result.release.commit)
        assertEquals(2, result.commitsAhead)
    }

    @Test
    fun a_build_newer_than_every_preview_is_up_to_date() {
        listing(release(OLD, "2026-10-27T00:56:38Z"))
        compareAnswers(NEW, OLD, "behind")

        assertEquals(UpdateCheck.UpToDate, feed().check(NEW))
    }

    @Test
    fun a_build_from_a_branch_is_unknown_rather_than_offered_main() {
        listing(release(NEW, "2026-10-29T15:25:27Z"))
        compareAnswers(OLD, NEW, "diverged")

        assertUnknown(feed().check(OLD), "branch")
    }

    @Test
    fun a_commit_github_has_never_seen_is_unknown() {
        listing(release(NEW, "2026-10-29T15:25:27Z"))
        // No compare answer registered: the dispatcher returns GitHub's 404.

        assertUnknown(feed().check(OLD), "not on GitHub")
    }

    @Test
    fun rate_limiting_is_named_as_such() {
        releases = MockResponse().setResponseCode(403).addHeader("X-RateLimit-Remaining", "0")
        assertUnknown(feed().check(OLD), "limiting")

        releases = MockResponse().setResponseCode(429)
        assertUnknown(feed().check(OLD), "limiting")
    }

    @Test
    fun no_connection_is_unknown_and_says_so() {
        server.shutdown()

        assertUnknown(feed().check(OLD), "could not be reached")
    }

    @Test
    fun an_unreadable_answer_is_unknown_and_not_a_crash() {
        releases = MockResponse().setBody("<html>a captive portal</html>")

        assertUnknown(feed().check(OLD), "could not be read")
    }

    @Test
    fun a_build_with_no_recorded_commit_does_not_ask() {
        assertUnknown(feed().check(""), "does not record")
        assertEquals(0, server.requestCount)
    }

    @Test
    fun requests_name_themselves_as_github_requires() {
        listing(release(NEW, "2026-10-29T15:25:27Z"))
        feed().check(NEW)

        val request = server.takeRequest()
        assertEquals("AIDE-OS", request.getHeader("User-Agent"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
    }

    // -- Downloading -----------------------------------------------------------

    @Test
    fun a_download_that_matches_its_checksum_is_kept() {
        val bytes = ByteArray(700_000) { (it % 251).toByte() }
        apk = MockResponse().setResponseCode(206).setBody(Buffer().write(bytes))
        val release = releaseFor(bytes, sha256(bytes))
        val target = temp.root.resolve("update.apk")
        var lastProgress = 0L

        feed().download(release, target) { written, _ -> lastProgress = written }

        assertTrue(target.readBytes().contentEquals(bytes))
        assertEquals(bytes.size.toLong(), lastProgress)
        // Always a range: GitHub's CDN has answered plain GETs with 504.
        assertEquals("bytes=0-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun a_download_that_does_not_match_is_deleted_so_it_cannot_be_installed() {
        val bytes = ByteArray(1000) { 7 }
        apk = MockResponse().setBody(Buffer().write(bytes))
        val target = temp.root.resolve("update.apk")

        try {
            feed().download(releaseFor(bytes, "0".repeat(64)), target) { _, _ -> }
            fail("a mismatched download was accepted")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains("checksum"))
        }
        assertFalse(target.exists())
    }

    @Test
    fun a_verified_download_on_disk_is_recognised_and_a_changed_one_is_not() {
        val bytes = ByteArray(4096) { (it * 31).toByte() }
        val release = releaseFor(bytes, sha256(bytes))
        val file = temp.root.resolve("update.apk").apply { writeBytes(bytes) }

        assertTrue(feed().isDownloaded(release, file))

        file.appendBytes(byteArrayOf(0))
        assertFalse(feed().isDownloaded(release, file))
        assertFalse(feed().isDownloaded(release.copy(apkSha256 = null), file))
    }

    @Test
    fun a_release_with_no_checksum_is_refused_before_anything_is_fetched() {
        val target = temp.root.resolve("update.apk")

        try {
            feed().download(releaseFor(ByteArray(1), sha256 = null), target) { _, _ -> }
            fail("an unverifiable download was attempted")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("no checksum"))
        }
        assertEquals(0, server.requestCount)
    }

    private fun releaseFor(bytes: ByteArray, sha256: String?) = AppRelease(
        tag = "app-preview-${NEW.take(7)}",
        title = "AIDE-OS preview (${NEW.take(7)}): something new",
        commit = NEW,
        pageUrl = "",
        apkUrl = server.url("/download/update.apk").toString(),
        apkBytes = bytes.size.toLong(),
        apkSha256 = sha256,
    )

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assertUnknown(result: UpdateCheck, containing: String) {
        assertTrue("expected Unknown, got $result", result is UpdateCheck.Unknown)
        val reason = (result as UpdateCheck.Unknown).reason
        assertTrue("\"$reason\" does not mention \"$containing\"", reason.contains(containing))
    }

    private companion object {
        const val OLD = "012c620403bb7105abdc17810d9463bd2f505e7b"
        const val NEW = "147a15900e26a4bf5dd08b6313352d74bc3eabf8"
        const val DRAFT = "dddddddddddddddddddddddddddddddddddddddd"
    }
}
