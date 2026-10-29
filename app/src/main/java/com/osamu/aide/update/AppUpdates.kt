package com.osamu.aide.update

import android.content.Context
import android.content.Intent
import android.os.Build
import com.osamu.aide.BuildConfig
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.engine.fast.ApkInstaller
import com.osamu.aide.engine.fast.InstallStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where the app's own update stands, for Home's banner and the About page. */
sealed interface UpdateState {
    /** Not asked yet in this process, and nothing remembered from the last one. */
    data object NotChecked : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease, val commitsAhead: Int) : UpdateState
    data class Unknown(val reason: String) : UpdateState
    data class Downloading(val release: AppRelease, val bytes: Long, val total: Long) : UpdateState

    /** Handed to the system installer; the next thing is its confirmation dialog. */
    data class Installing(val release: AppRelease) : UpdateState

    /**
     * The download or the install failed, and the release is kept so Update
     * can be tapped again. [settings] is set when the cause is that the app
     * may not install anything yet, which only the user can change.
     */
    data class Failed(
        val release: AppRelease,
        val commitsAhead: Int,
        val message: String,
        val settings: Intent? = null,
    ) : UpdateState
}

/**
 * Finds, fetches and installs newer previews of AIDE-OS itself.
 *
 * **A singleton with its own scope**, because a 56 MB download outlives the
 * screen that started it: tapping Update on Home and walking into Settings must
 * not cancel it, and both screens show the same progress.
 *
 * **Main process only.** It is created on first injection, and only the UI
 * injects it -- `Application.onCreate` runs again in `:build`, and work started
 * there ran twice before (CLAUDE.md, "The app is two processes").
 *
 * **What the user is told, and when.** A check is made at most once a day,
 * from Home, if automatic checks are on (they are by default, and About turns
 * them off). The request is to GitHub's public API and carries nothing but the
 * commit being compared. An update is remembered across restarts so the banner
 * does not vanish until the next daily check; "Later" hides it for that one
 * release only.
 */
class AppUpdates(
    private val context: Context,
    private val feed: ReleaseFeed,
    private val installer: ApkInstaller,
    private val dispatchers: DispatcherProvider,
    private val installedCommit: String = BuildConfig.GIT_COMMIT,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * False in an F-Droid build, which F-Droid updates: nothing here asks
     * GitHub anything or downloads anything. `app/build.gradle.kts`.
     */
    val enabled: Boolean = BuildConfig.SELF_UPDATE,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _dismissed = MutableStateFlow(preferences.getString(KEY_DISMISSED_TAG, null))

    /** The release whose banner "Later" put away, so Home can leave it hidden. */
    val dismissedTag: StateFlow<String?> = _dismissed.asStateFlow()

    /**
     * The installer's confirmation dialog, for whichever screen is showing.
     *
     * A channel rather than state: it must be launched once, from an Activity,
     * and a screen that is not composed when it arrives picks it up when it is.
     */
    private val _confirmations = Channel<Intent>(Channel.BUFFERED)
    val confirmations: Flow<Intent> = _confirmations.receiveAsFlow()

    private var work: Job? = null

    var checksAutomatically: Boolean
        get() = preferences.getBoolean(KEY_AUTOMATIC, true)
        set(value) {
            preferences.edit().putBoolean(KEY_AUTOMATIC, value).apply()
        }

    /** Checks if automatic checks are on and the last was over a day ago. */
    fun checkIfDue() {
        if (!enabled || !checksAutomatically) return
        val last = preferences.getLong(KEY_LAST_CHECKED, 0L)
        if (clock() - last < CHECK_INTERVAL_MILLIS) return
        check()
    }

    /** Asks GitHub now. Ignored while a check, download or install is running. */
    fun check() {
        if (!enabled || work?.isActive == true) return
        work = scope.launch {
            _state.value = UpdateState.Checking
            val result = withContext(dispatchers.io) { feed.check(installedCommit) }
            preferences.edit().putLong(KEY_LAST_CHECKED, clock()).apply()
            _state.value = when (result) {
                is UpdateCheck.Available -> UpdateState.Available(result.release, result.commitsAhead)
                UpdateCheck.UpToDate -> UpdateState.UpToDate
                is UpdateCheck.Unknown -> UpdateState.Unknown(result.reason)
            }
            remember(result)
        }
    }

    /** Hides Home's banner for the release on offer; About still shows it. */
    fun dismiss() {
        val tag = (state.value as? UpdateState.Available)?.release?.tag ?: return
        preferences.edit().putString(KEY_DISMISSED_TAG, tag).apply()
        _dismissed.value = tag
    }

    /** Downloads the release on offer, verifies it, and hands it to the installer. */
    fun install() {
        if (!enabled) return
        val (release, ahead) = when (val current = state.value) {
            is UpdateState.Available -> current.release to current.commitsAhead
            is UpdateState.Failed -> current.release to current.commitsAhead
            else -> return
        }
        if (work?.isActive == true) return
        work = scope.launch {
            _state.value = UpdateState.Downloading(release, 0L, release.apkBytes)
            val apk = File(context.cacheDir, "updates/AIDE-OS-${release.commit.take(7)}.apk")
            try {
                withContext(dispatchers.io) {
                    // One update at a time on disk: an earlier download that
                    // was abandoned is 56 MB nobody will use.
                    apk.parentFile?.listFiles()?.forEach { if (it != apk) it.delete() }
                    if (!feed.isDownloaded(release, apk)) {
                        feed.download(release, apk) { bytes, total ->
                            _state.value = UpdateState.Downloading(release, bytes, total)
                        }
                    }
                }
                withContext(dispatchers.io) { refuseUnusable(apk) }
            } catch (failure: IOException) {
                _state.value = UpdateState.Failed(release, ahead, failure.message ?: "The download failed.")
                return@launch
            }

            _state.value = UpdateState.Installing(release)
            installer.install(apk).collect { status ->
                when (status) {
                    is InstallStatus.NeedsConfirmation -> _confirmations.send(status.confirmation)
                    // Rarely seen: replacing this app ends this process, and
                    // the result usually arrives after there is no one to hear it.
                    is InstallStatus.Installed -> _state.value = UpdateState.UpToDate
                    is InstallStatus.Failed -> _state.value =
                        UpdateState.Failed(release, ahead, status.message, status.settings)
                }
            }
        }
    }

    /**
     * Fails early, and in words, on an APK the platform would reject anyway.
     *
     * The installer's own messages for these are "Installation failed" or a
     * package-manager constant. The commonest real case is the first preview
     * after versioning arrived: every one before it is versionCode 1, so
     * offering one to a build numbered by commits would be a downgrade.
     */
    private fun refuseUnusable(apk: File) {
        val archive = context.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: throw IOException("The download is not an Android app.")
        if (archive.packageName != context.packageName) {
            apk.delete()
            throw IOException("The download is a different app (${archive.packageName}).")
        }
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        if (archive.versionCodeCompat() < installed.versionCodeCompat()) {
            throw IOException(
                "That preview is version ${archive.versionName}, older than the installed " +
                    "${installed.versionName}, and Android will not install an older version over a newer one.",
            )
        }
    }

    /** Keeps an available update across restarts, and forgets it once it is installed. */
    private fun remember(result: UpdateCheck) {
        val editor = preferences.edit()
        if (result is UpdateCheck.Available) {
            editor.putString(KEY_AVAILABLE, result.toJson().toString())
        } else if (result is UpdateCheck.UpToDate) {
            editor.remove(KEY_AVAILABLE)
        }
        // Unknown keeps what was known: being offline does not un-publish anything.
        editor.apply()
    }

    private fun restore(): UpdateState {
        val saved = preferences.getString(KEY_AVAILABLE, null) ?: return UpdateState.NotChecked
        val available = runCatching { availableFrom(JSONObject(saved)) }.getOrNull()
            ?: return UpdateState.NotChecked
        // Installed since it was remembered: the running build is that release.
        if (available.release.commit.startsWith(installedCommit) || installedCommit.startsWith(available.release.commit)) {
            preferences.edit().remove(KEY_AVAILABLE).apply()
            return UpdateState.NotChecked
        }
        return available
    }

    private fun UpdateCheck.Available.toJson() = JSONObject()
        .put("tag", release.tag)
        .put("title", release.title)
        .put("commit", release.commit)
        .put("pageUrl", release.pageUrl)
        .put("apkUrl", release.apkUrl)
        .put("apkBytes", release.apkBytes)
        .put("apkSha256", release.apkSha256 ?: JSONObject.NULL)
        .put("commitsAhead", commitsAhead)

    private fun availableFrom(json: JSONObject) = UpdateState.Available(
        release = AppRelease(
            tag = json.getString("tag"),
            title = json.getString("title"),
            commit = json.getString("commit"),
            pageUrl = json.getString("pageUrl"),
            apkUrl = json.getString("apkUrl"),
            apkBytes = json.getLong("apkBytes"),
            // isNull, not optString: see ReleaseFeed's stringOrNull.
            apkSha256 = if (json.isNull("apkSha256")) null else json.getString("apkSha256"),
        ),
        commitsAhead = json.getInt("commitsAhead"),
    )

    private companion object {
        const val PREFERENCES = "app-updates"
        const val KEY_AUTOMATIC = "checkAutomatically"
        const val KEY_LAST_CHECKED = "lastChecked"
        const val KEY_DISMISSED_TAG = "dismissedTag"
        const val KEY_AVAILABLE = "available"
        val CHECK_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(1)
    }
}

private fun android.content.pm.PackageInfo.versionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()
