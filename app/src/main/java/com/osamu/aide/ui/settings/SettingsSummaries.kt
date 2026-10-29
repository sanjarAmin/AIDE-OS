package com.osamu.aide.ui.settings

import com.osamu.aide.ai.core.AiProviderType
import com.osamu.aide.editor.EditorColorTheme
import com.osamu.aide.editor.EditorSettings
import kotlin.math.roundToInt

/**
 * How much a category's current state needs the user.
 *
 * There is no "good" tone, and that is deliberate. A green tick beside every
 * category that is merely configured would be on almost all the time, and a
 * light that is always on is the one people stop reading -- which is how this
 * app's toolbar came to lose its permanently green "Ready" dot. Only states
 * that stop something working are coloured.
 */
internal enum class Tone {
    NEUTRAL,

    /** Something a feature needs and does not have yet: the assistant with no key. */
    ATTENTION,

    /** Something that blocks an action outright: a commit with no identity. */
    PROBLEM,
}

/** One line under a category's name on the Settings hub, saying what it is set to now. */
internal data class Summary(val text: String, val tone: Tone = Tone.NEUTRAL)

/**
 * The hub's line for the editor: the three settings someone notices first.
 *
 * Size, indentation and colours, in that order -- the order of the controls on
 * the page, so the line reads as a preview of what tapping it opens.
 */
internal fun editorSummary(settings: EditorSettings): Summary {
    val colours = when (settings.theme) {
        EditorColorTheme.FOLLOW_SYSTEM -> "colours match the app"
        EditorColorTheme.LIGHT -> "light colours"
        EditorColorTheme.DARK -> "dark colours"
    }
    return Summary("${settings.fontSizeSp.roundToInt()} sp · ${settings.tabWidth}-space tabs · $colours")
}

/**
 * The hub's line for the assistant: which service, and whether it can be asked.
 *
 * **"Key saved", never "ready" or "connected".** A stored key is all that is
 * known here; whether the service accepts it is only learned by sending a
 * request -- the same distinction the provider chips in [ApiKeySection] keep.
 * The on-device and Custom providers are not keyed, so they are described by
 * what makes them usable instead: a running server, an address.
 */
internal fun aiSummary(provider: AiProviderType, ready: Boolean): Summary {
    val name = provider.displayName
    return when (provider) {
        AiProviderType.LOCAL ->
            if (ready) Summary("$name · model running") else Summary("$name · not running", Tone.ATTENTION)
        AiProviderType.CUSTOM ->
            if (ready) Summary("$name · address set") else Summary("$name · no address yet", Tone.ATTENTION)
        else ->
            if (ready) Summary("$name · key saved") else Summary("$name · no key yet", Tone.ATTENTION)
    }
}

/**
 * The hub's line for git: who commits, and to how many hosts a push can authenticate.
 *
 * The missing identity is the one problem on the hub, because it is the one
 * that refuses an action -- `GitSection` blocks Commit without it -- and the
 * wording is that section's own, so the two screens describe it the same way.
 */
internal fun gitSummary(identityName: String?, tokenHosts: Int): Summary {
    if (identityName == null) return Summary("No identity set, so committing is blocked", Tone.PROBLEM)
    val tokens = when (tokenHosts) {
        0 -> ""
        1 -> " · 1 access token"
        else -> " · $tokenHosts access tokens"
    }
    return Summary("Commits signed as $identityName$tokens")
}

/**
 * The hub's line for downloads, or a placeholder while they are measured.
 *
 * [totalBytes] is null until the walk finishes: clang alone is half a
 * gigabyte of small files, and a "0 MB" shown while it is counted would be
 * the opposite of the truth.
 */
internal fun toolchainSummary(totalBytes: Long?, count: Int): Summary = when {
    totalBytes == null -> Summary("Measuring…")
    count == 0 -> Summary("Nothing downloaded yet")
    else -> Summary("${storageLabel(totalBytes)} · $count ${if (count == 1) "download" else "downloads"}")
}

/**
 * The hub's line for release signing.
 *
 * Neutral either way: without a release key every debug build still works, and
 * calling that a problem would colour a line most people never need.
 */
internal fun signingSummary(configured: Boolean?): Summary = when (configured) {
    null -> Summary("Checking…")
    true -> Summary("Release key on this device")
    false -> Summary("No release key yet")
}

/**
 * The hub's line for About: the running version, and whether a newer one is out.
 *
 * An available update is Attention rather than Problem -- nothing stops
 * working for want of it -- and it is said here because About is where it is
 * installed from; Home's banner can be put away, and this line cannot.
 */
internal fun aboutSummary(version: String, updateAvailable: Boolean = false): Summary = when {
    updateAvailable && version.isNotEmpty() -> Summary("Version $version · update available", Tone.ATTENTION)
    updateAvailable -> Summary("An update is available", Tone.ATTENTION)
    version.isEmpty() -> Summary("GPLv3 · licences and credits")
    else -> Summary("Version $version · GPLv3")
}

/**
 * Bytes as someone reclaiming space reads them.
 *
 * Gigabytes once there are a thousand megabytes of them, because "1342 MB" is
 * a number to do arithmetic on and "1.3 GB" is one to decide with. Under a
 * megabyte reads as "less than 1 MB" rather than a rounded "0 MB", which on a
 * line about storage would look like a bug -- the rule ToolchainSection uses.
 */
internal fun storageLabel(bytes: Long): String {
    val mb = bytes / (1024 * 1024)
    return when {
        mb == 0L -> "less than 1 MB"
        mb < 1024 -> "$mb MB"
        else -> String.format(java.util.Locale.US, "%.1f GB", bytes.toDouble() / (1024L * 1024 * 1024))
    }
}
