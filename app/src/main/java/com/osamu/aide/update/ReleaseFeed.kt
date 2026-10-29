package com.osamu.aide.update

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A published preview of the app, as GitHub lists it. */
data class AppRelease(
    val tag: String,
    val title: String,
    /** The commit it was built from: a full sha, or the tag's short one. */
    val commit: String,
    val pageUrl: String,
    val apkUrl: String,
    val apkBytes: Long,
    /** Lowercase hex, or null when neither GitHub nor the notes publish one. */
    val apkSha256: String?,
) {
    /** "Home and Settings, redrawn" out of "AIDE-OS preview (147a159): Home and Settings, redrawn". */
    val headline: String get() = title.substringAfter("): ", title)
}

/** What GitHub says about the build that is running. */
sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck

    /** [commitsAhead] is how far [release] is past the running build. */
    data class Available(val release: AppRelease, val commitsAhead: Int) : UpdateCheck

    /**
     * The check could not answer, and [reason] says why in the user's terms.
     *
     * Kept apart from [UpToDate] on purpose: "no update" when the truth is
     * "could not ask" is the answer that stops someone ever looking again.
     */
    data class Unknown(val reason: String) : UpdateCheck
}

/**
 * The app's own releases on GitHub, and the one question asked of them.
 *
 * **Previews are pre-releases, so `releases/latest` cannot find them.** It
 * skips pre-releases by definition and answers with the newest toolchain
 * release instead -- Python, at the time of writing. The list is read and
 * filtered by tag.
 *
 * **"Newer" is asked of git, not of a version number.** GitHub's compare
 * endpoint says whether the published commit *contains* the running one
 * (`ahead`), which a number cannot: previews before 2026-10-29 were all
 * versionCode 1, a build from a branch can count higher than `main` without
 * being newer, and a build from an unpushed commit is simply not comparable --
 * which the endpoint reports as a 404 and this reports as [UpdateCheck.Unknown].
 *
 * Blocking throughout. Call it off the main thread: Android throws on network
 * I/O there, and that is not caught here, so the mistake is loud.
 */
class ReleaseFeed(
    private val client: OkHttpClient,
    private val apiRoot: String = "https://api.github.com",
    private val repository: String = "sanjarAmin/AIDE-OS",
    private val userAgent: String = "AIDE-OS",
) {

    /**
     * Whether a preview newer than [installedCommit] has been published.
     *
     * Never throws for anything GitHub sends: a reply that is not the JSON it
     * documents is an answer that could not be read, which is [UpdateCheck.Unknown],
     * not a crash of whatever scope asked.
     */
    fun check(installedCommit: String): UpdateCheck = try {
        compare(installedCommit)
    } catch (_: org.json.JSONException) {
        UpdateCheck.Unknown(UNREADABLE)
    }

    private fun compare(installedCommit: String): UpdateCheck {
        if (installedCommit.isBlank()) {
            return UpdateCheck.Unknown("This build does not record the commit it was built from.")
        }

        val releases = when (val reply = get("/repos/$repository/releases?per_page=50")) {
            is Reply.Ok -> reply.body
            is Reply.Status -> return UpdateCheck.Unknown(describe(reply))
            Reply.Offline -> return UpdateCheck.Unknown(OFFLINE)
        }
        val latest = latestAppPreview(releases)
            ?: return UpdateCheck.Unknown("GitHub lists no published preview of the app.")

        // Either may be the short form: a release's commit falls back to the
        // tag's seven characters when it was created from a branch name.
        if (latest.commit.startsWith(installedCommit) || installedCommit.startsWith(latest.commit)) {
            return UpdateCheck.UpToDate
        }

        val comparison = when (val reply = get("/repos/$repository/compare/$installedCommit...${latest.commit}")) {
            is Reply.Ok -> JSONObject(reply.body)
            is Reply.Status -> return UpdateCheck.Unknown(
                if (reply.code == 404) {
                    "This build's commit is not on GitHub, so it cannot be compared with the published previews."
                } else {
                    describe(reply)
                },
            )
            Reply.Offline -> return UpdateCheck.Unknown(OFFLINE)
        }
        return when (comparison.stringOrNull("status")) {
            "ahead" -> UpdateCheck.Available(latest, comparison.optInt("ahead_by", 0))
            // Behind is a build newer than anything published -- this
            // project's own builds, usually. Nothing to offer, and not unknown.
            "identical", "behind" -> UpdateCheck.UpToDate
            "diverged" -> UpdateCheck.Unknown(
                "This build is from a branch the published previews do not follow.",
            )
            else -> UpdateCheck.Unknown(UNREADABLE)
        }
    }

    /**
     * Downloads [release]'s APK to [target] and checks it against its published SHA-256.
     *
     * **No checksum, no install.** The digest is the only thing that ties the
     * bytes that arrived to the bytes that were published; without one, this
     * refuses rather than installing something it cannot vouch for, and the
     * release page remains the way to get it. Anything that fails the check is
     * deleted, so a bad download cannot be installed by a later tap.
     *
     * **Always a range request**, as `:toolchain:manager` learned: GitHub's
     * release CDN once answered every plain GET for an asset with 504 while
     * `bytes=0-` came back whole. `toolchain/manager/FINDINGS.md`.
     */
    fun download(release: AppRelease, target: File, onProgress: (bytes: Long, total: Long) -> Unit) {
        val expected = release.apkSha256
            ?: throw IOException("This preview publishes no checksum, so the download could not be verified.")

        target.parentFile?.mkdirs()
        target.delete()
        val request = Request.Builder()
            .url(release.apkUrl)
            .header("User-Agent", userAgent)
            .header("Range", "bytes=0-")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code != 200 && response.code != 206) {
                throw IOException("The download failed (HTTP ${response.code}).")
            }
            val body = response.body ?: throw IOException("The download came back empty.")
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var sinceReport = 0L
            target.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        sinceReport += read
                        if (sinceReport >= PROGRESS_INTERVAL_BYTES) {
                            onProgress(written, total)
                            sinceReport = 0
                        }
                    }
                }
            }
            onProgress(written, total)

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) {
                target.delete()
                throw IOException(
                    if (written < total) {
                        "The connection was lost during the download."
                    } else {
                        "The download did not match its published checksum, so it was discarded."
                    },
                )
            }
        }
    }

    /**
     * True when [file] is already [release]'s APK, byte for byte.
     *
     * The commonest first update fails *after* downloading: the app is not yet
     * allowed to install anything, the user grants it in system Settings, and
     * taps Try again. Without this that retry fetched all 56 MB again.
     */
    fun isDownloaded(release: AppRelease, file: File): Boolean {
        val expected = release.apkSha256 ?: return false
        if (!file.isFile) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == expected
    }

    private fun get(path: String): Reply {
        val request = Request.Builder()
            .url(apiRoot + path)
            // GitHub refuses API calls without a User-Agent.
            .header("User-Agent", userAgent)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Reply.Ok(body)
                } else {
                    Reply.Status(response.code, response.header("X-RateLimit-Remaining"))
                }
            }
        } catch (_: IOException) {
            Reply.Offline
        }
    }

    private fun describe(reply: Reply.Status): String = when {
        reply.code == 429 || (reply.code == 403 && reply.rateLimitRemaining == "0") ->
            "GitHub is limiting how often it can be asked. Try again in an hour."
        else -> "GitHub answered HTTP ${reply.code}."
    }

    private sealed interface Reply {
        data class Ok(val body: String) : Reply
        data class Status(val code: Int, val rateLimitRemaining: String?) : Reply
        data object Offline : Reply
    }

    companion object {
        /** How every app preview's tag begins; toolchain releases share the repository. */
        const val TAG_PREFIX = "app-preview-"

        private const val OFFLINE = "GitHub could not be reached. Check the connection and try again."
        private const val UNREADABLE = "GitHub's answer could not be read."
        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_INTERVAL_BYTES = 512 * 1024L
    }
}

/**
 * The newest app preview in a GitHub releases listing, or null when there is none.
 *
 * Sorted by publication here rather than trusting the listing's order, and
 * drafts are skipped -- a draft has no public download and no publication date.
 * A preview with no APK attached is skipped too: there is nothing to install.
 */
internal fun latestAppPreview(releasesJson: String): AppRelease? {
    val releases = JSONArray(releasesJson)
    return (0 until releases.length())
        .map { releases.getJSONObject(it) }
        .filter { !it.optBoolean("draft") && it.stringOrNull("tag_name")?.startsWith(ReleaseFeed.TAG_PREFIX) == true }
        // ISO-8601 in UTC, so the text sorts as the time does.
        .sortedByDescending { it.stringOrNull("published_at").orEmpty() }
        .firstNotNullOfOrNull { it.toAppRelease() }
}

private fun JSONObject.toAppRelease(): AppRelease? {
    val tag = stringOrNull("tag_name") ?: return null
    val assets = optJSONArray("assets") ?: return null
    val apk = (0 until assets.length())
        .map { assets.getJSONObject(it) }
        .firstOrNull { it.stringOrNull("name")?.endsWith(".apk") == true }
        ?: return null
    val url = apk.stringOrNull("browser_download_url") ?: return null
    return AppRelease(
        tag = tag,
        title = stringOrNull("name") ?: tag,
        // A release created with `--target <sha>` names the commit in full; one
        // created from a branch says "main", which compares as a moving target.
        commit = stringOrNull("target_commitish")?.takeIf { FULL_SHA.matches(it) }
            ?: tag.removePrefix(ReleaseFeed.TAG_PREFIX),
        pageUrl = stringOrNull("html_url").orEmpty(),
        apkUrl = url,
        apkBytes = apk.optLong("size", 0L),
        apkSha256 = apk.stringOrNull("digest")?.removePrefix("sha256:")?.lowercase()?.takeIf { SHA256.matches(it) }
            ?: stringOrNull("body")?.let { NOTES_SHA256.find(it)?.groupValues?.get(1)?.lowercase() },
    )
}

/**
 * A string field, or null when it is absent or JSON null.
 *
 * **Never `optString` alone.** Android's org.json reads JSON null as the text
 * "null" where the JVM artifact the unit tests use reads it as "" -- so a null
 * `digest` became the checksum "null" on a phone and nowhere else.
 * `ai/core/FINDINGS.md` §16, where the same thing put "null" into a file path.
 */
private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private val FULL_SHA = Regex("[0-9a-f]{40}")
private val SHA256 = Regex("[0-9a-f]{64}")

/** The line every preview's notes end with: SHA-256: `<hex>`. */
private val NOTES_SHA256 = Regex("SHA-256:\\s*`([0-9a-fA-F]{64})`")
