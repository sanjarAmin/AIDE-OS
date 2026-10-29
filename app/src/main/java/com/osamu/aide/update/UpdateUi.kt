package com.osamu.aide.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Launches the installer's confirmation dialog when an update asks for it.
 *
 * Placed on each screen an update can be started from. A screen that is not
 * showing is not composed, so exactly one collects; an intent that arrives
 * while neither is showing waits in the channel for the next one that is.
 *
 * **Deliberately not on the workspace.** An update that finishes downloading
 * while a project is open waits for Home or Settings rather than putting the
 * installer over the editor: a successful install ends this process at once,
 * and anything typed and not yet saved would end with it.
 */
@Composable
fun LaunchUpdateConfirmations(updates: AppUpdates) {
    val context = LocalContext.current
    LaunchedEffect(updates) {
        updates.confirmations.collect { confirmation -> context.startActivity(confirmation) }
    }
}

/** Opens a release's page, where its notes are. */
fun Context.openReleasePage(release: AppRelease) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)))
    } catch (_: ActivityNotFoundException) {
        // A phone with no browser has nowhere to show notes; nothing to say.
    }
}

/**
 * One sentence for where the update stands, in the user's terms.
 *
 * Shared by Home's banner and the About page so the two never describe the
 * same state differently.
 */
fun UpdateState.describe(): String = when (this) {
    UpdateState.NotChecked -> "Not checked yet."
    UpdateState.Checking -> "Checking GitHub…"
    UpdateState.UpToDate -> "This is the newest preview."
    is UpdateState.Available -> "${release.headline} · ${commitsLabel(commitsAhead)} newer than this build."
    is UpdateState.Unknown -> reason
    is UpdateState.Downloading -> "Downloading ${megabytes(bytes)} of ${megabytes(total)}."
    // Said before the dialog rather than after it: once the update installs,
    // this process is gone and nothing here can explain the app vanishing.
    is UpdateState.Installing -> "Confirm the install when Android asks. AIDE-OS closes to finish " +
        "updating; open it again from your launcher."
    is UpdateState.Failed -> message
}

/** "1 commit", "12 commits": how far ahead the published preview is. */
internal fun commitsLabel(count: Int): String = if (count == 1) "1 commit" else "$count commits"

/** Whole megabytes, the unit the release page lists the APK in. */
internal fun megabytes(bytes: Long): String = "${bytes / (1024 * 1024)} MB"
