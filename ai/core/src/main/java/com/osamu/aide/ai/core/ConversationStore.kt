package com.osamu.aide.ai.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One past conversation, as the history list needs it. */
data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

/**
 * Conversations on disk, one JSON file each.
 *
 * **Not in the project directory**, though the conversations are about a
 * project. `/sdcard/Android/data/.../projects` is external app storage, and a
 * `connectedAndroidTest` run uninstalls the app and takes that directory with
 * it -- CLAUDE.md's warning about projects disappearing applies just as much to
 * a chat history stored beside them. It also keeps the user's repository free
 * of files they did not create: nobody wants `.aide-chats/` showing up in
 * `git status`.
 *
 * **Keyed by the project's path, not its name.** Two projects called `demo` in
 * different directories are different projects, and giving them one history
 * would silently blend them.
 *
 * **One file per conversation, rewritten whole.** A single index file would be
 * faster to list and would lose everything to one bad write; the per-file cost
 * is a `listFiles` and a parse of each header, on a list the size of a person's
 * chat history. Corruption is contained: an unreadable file is skipped, not
 * fatal, because the alternative is a chat panel that will not open.
 */
class ConversationStore(root: File, projectDir: File) {

    private val directory = File(root, "chats/${projectKey(projectDir)}")

    /**
     * Newest first, which is the only order a history list is ever read in.
     *
     * Unreadable files are skipped rather than surfaced. A truncated JSON file
     * -- a crash mid-write, a full disk -- must not stop the other twenty
     * conversations from being listed.
     */
    fun list(): List<ConversationSummary> {
        val files = directory.listFiles { file -> file.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching {
                val json = JSONObject(file.readText())
                ConversationSummary(
                    id = json.getString("id"),
                    title = json.optString("title").ifBlank { UNTITLED },
                    updatedAt = json.optLong("updatedAt"),
                    messageCount = json.optJSONArray("entries")?.length() ?: 0,
                )
            }.getOrNull()
        }.sortedByDescending { it.updatedAt }
    }

    fun load(id: String): List<ChatEntry> {
        val file = fileFor(id)
        if (!file.isFile) return emptyList()
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyList()
        val array = json.optJSONArray("entries") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching { array.getJSONObject(index).toEntry() }.getOrNull()
        }
    }

    /**
     * Writes the conversation, or deletes it when nothing is left to keep.
     *
     * **An empty conversation is removed rather than stored.** Opening the
     * panel and closing it again would otherwise leave a permanent "New chat"
     * in the history, and after a week the list is mostly those.
     */
    fun save(id: String, entries: List<ChatEntry>, title: String? = null) {
        if (entries.isEmpty()) {
            delete(id)
            return
        }
        directory.mkdirs()
        val json = JSONObject().apply {
            put("id", id)
            put("title", title?.takeIf { it.isNotBlank() } ?: titleFrom(entries))
            put("updatedAt", System.currentTimeMillis())
            put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } })
        }
        // Written to a temporary file and renamed, so a kill mid-write leaves
        // the previous version rather than half of this one. The app is a
        // foreground process on a phone; it gets killed.
        val target = fileFor(id)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(json.toString())
        if (!temporary.renameTo(target)) {
            target.writeText(json.toString())
            temporary.delete()
        }
    }

    fun rename(id: String, title: String) {
        val entries = load(id)
        if (entries.isNotEmpty()) save(id, entries, title)
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }

    /** The stored title, so a renamed conversation keeps its name when reopened. */
    fun titleOf(id: String): String? = runCatching {
        JSONObject(fileFor(id).readText()).optString("title").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun fileFor(id: String) = File(directory, "${id.filter { it.isLetterOrDigit() || it == '-' }}.json")

    companion object {
        const val UNTITLED = "New chat"

        /**
         * A title from the first thing the user said.
         *
         * Not from the assistant's reply, which is long and often starts with
         * the same three words every time ("I'll take a look..."), and not from
         * a model call, which would spend a request and, on a local model,
         * thirty seconds.
         */
        fun titleFrom(entries: List<ChatEntry>): String {
            val first = entries.filterIsInstance<ChatEntry.FromUser>().firstOrNull() ?: return UNTITLED
            // The attached-file marker the composer prepends is noise in a
            // title: every chat started from an open file would read "[@Main.kt]".
            val text = first.text.replace(Regex("^\\[@[^]]*]\\s*"), "").trim()
            val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return UNTITLED
            return if (firstLine.length <= TITLE_CHARS) {
                firstLine
            } else {
                // Cut on a word boundary when there is one nearby, because
                // "Why does the build fail wh…" reads better than a hard chop.
                val cut = firstLine.take(TITLE_CHARS)
                val lastSpace = cut.lastIndexOf(' ')
                (if (lastSpace > TITLE_CHARS / 2) cut.take(lastSpace) else cut).trimEnd() + "…"
            }
        }

        private const val TITLE_CHARS = 48

        private fun projectKey(projectDir: File): String {
            val path = runCatching { projectDir.canonicalPath }.getOrDefault(projectDir.path)
            // A readable prefix plus a hash: the prefix makes the directory
            // navigable while debugging, the hash makes it unique.
            val name = projectDir.name.filter { it.isLetterOrDigit() }.take(24).ifEmpty { "project" }
            return "$name-${path.hashCode().toUInt().toString(16)}"
        }
    }
}

// -- serialisation -----------------------------------------------------------

private fun ChatEntry.toJson(): JSONObject = when (this) {
    is ChatEntry.FromUser -> JSONObject()
        .put("kind", "user")
        .put("id", id)
        .put("at", at)
        .put("text", text)

    is ChatEntry.FromAssistant -> JSONObject()
        .put("kind", "assistant")
        .put("id", id)
        .put("at", at)
        .put("text", text)
        .put("provider", provider?.id)
        .put("model", model)

    is ChatEntry.Tool -> JSONObject()
        .put("kind", "tool")
        .put("id", id)
        .put("at", at)
        .put("name", name)
        .put("detail", detail)
        .put("declined", declined)
        .put("failed", failed)
        .put("durationMs", durationMs)
        .put("input", JSONObject(input.mapValues { it.value }))
        .put("result", result)
}

/**
 * **A fresh id, not the stored one.**
 *
 * An id is a list key for this process, not durable identity. Restoring the
 * stored value crashed the app: the counter restarts at zero in every process,
 * so a conversation saved yesterday came back holding ids 1 and 2, the next
 * message minted id 1 again, and `LazyColumn` threw on the duplicate key.
 *
 * The field is still written, because a file that records the order it was
 * saved in is easier to read when something goes wrong -- it is just not
 * trusted on the way back in.
 */
private fun JSONObject.toEntry(): ChatEntry {
    val id = ChatEntry.nextId()
    val at = optLong("at")
    return when (optString("kind")) {
        "user" -> ChatEntry.FromUser(text = getString("text"), id = id, at = at)
        "tool" -> ChatEntry.Tool(
            name = optString("name"),
            detail = optString("detail"),
            declined = optBoolean("declined"),
            failed = optBoolean("failed"),
            input = optJSONObject("input")?.toStringMap() ?: emptyMap(),
            // `optString` would turn an absent result into "", which renders as
            // an empty RESULT section on every restored card.
            result = if (isNull("result")) null else optString("result"),
            id = id,
            at = at,
            durationMs = optLong("durationMs"),
        )
        else -> ChatEntry.FromAssistant(
            text = optString("text"),
            id = id,
            at = at,
            provider = optString("provider").takeIf { it.isNotBlank() }
                ?.let { stored -> AiProviderType.entries.firstOrNull { it.id == stored } },
            model = optString("model").takeIf { it.isNotBlank() },
        )
    }
}

private fun JSONObject.toStringMap(): Map<String, String> {
    val out = mutableMapOf<String, String>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        out[key] = optString(key)
    }
    return out
}
