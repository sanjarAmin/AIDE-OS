package com.osamu.aide.engine.fast

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where the install got to.
 *
 * Not a single result, because installing is not a single step: the system
 * always puts a confirmation dialog in front of the user, and the outcome only
 * arrives after they answer it.
 */
sealed interface InstallStatus {

    /**
     * The system wants the user to confirm. Launch [confirmation] from an
     * Activity; the flow keeps running and reports what they chose.
     */
    data class NeedsConfirmation(val confirmation: Intent) : InstallStatus

    data object Installed : InstallStatus

    /**
     * [settings] is non-null when the app is not allowed to install at all and
     * the user has to grant it in Settings -- a different thing to show than a
     * failed install, and the common case on a first run.
     */
    data class Failed(val message: String, val settings: Intent? = null) : InstallStatus
}

/**
 * Hands a built APK to the platform installer.
 *
 * Not a build stage: it runs after a build has succeeded, needs a Context, and
 * cannot complete without the user. It lives here rather than in `:app` because
 * the fast path owns the whole route from source to installed app -- see
 * docs/PLAN.md -- and because the failures worth explaining are all about the
 * APK, which is this module's output.
 */
class ApkInstaller(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) {

    /**
     * Installs [apk], reporting each step.
     *
     * The flow stays open across the confirmation dialog and closes when the
     * system reports the outcome. Cancelling it abandons the session rather
     * than leaving one open -- the platform allows only so many.
     */
    fun install(apk: File): Flow<InstallStatus> = callbackFlow {
        if (!canInstall()) {
            trySend(
                InstallStatus.Failed(
                    "AIDE-OS is not allowed to install apps yet.",
                    settings = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ),
                ),
            )
            close()
            return@callbackFlow
        }

        val action = "${context.packageName}.INSTALL_RESULT.${apk.absolutePath.hashCode()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirmation = intent.confirmationIntent()
                        if (confirmation == null) {
                            // The platform promised an intent and did not send
                            // one. Nothing further will arrive, so say so rather
                            // than leave the caller waiting on a dead session.
                            trySend(InstallStatus.Failed("The installer did not open."))
                            close()
                        } else {
                            trySend(InstallStatus.NeedsConfirmation(confirmation))
                        }
                    }

                    PackageInstaller.STATUS_SUCCESS -> {
                        trySend(InstallStatus.Installed)
                        close()
                    }

                    else -> {
                        trySend(InstallStatus.Failed(describe(status, intent)))
                        close()
                    }
                }
            }
        }

        registerReceiver(receiver, action)

        val installer = context.packageManager.packageInstaller
        val sessionId = try {
            withContext(dispatchers.io) { writeSession(installer, apk) }
        } catch (failure: Exception) {
            trySend(InstallStatus.Failed(failure.message ?: "The APK could not be staged."))
            close()
            // awaitClose still runs, so the receiver is unregistered.
            return@callbackFlow awaitClose { context.unregisterReceiver(receiver) }
        }

        installer.openSession(sessionId).use { session ->
            session.commit(
                PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(action).setPackage(context.packageName),
                    // MUTABLE because the system fills the result in. An
                    // immutable one is rejected outright from API 31.
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ).intentSender,
            )
        }

        awaitClose {
            context.unregisterReceiver(receiver)
            // Only reached on cancellation; a session the system has already
            // finished with is gone, and abandoning it again is a no-op.
            runCatching { installer.abandonSession(sessionId) }
        }
    }

    /**
     * True when the user has allowed this app to install others.
     *
     * `REQUEST_INSTALL_PACKAGES` in the manifest is necessary and not
     * sufficient: it is a special permission the user toggles in Settings, and
     * an app installed from a file manager or F-Droid starts without it -- which
     * is how AIDE-OS is meant to be distributed. See R5 in docs/PLAN.md.
     */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    private fun writeSession(installer: PackageInstaller, apk: File): Int {
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            // Lets the system reserve the space up front and fail early, rather
            // than part way through a copy of a large APK.
            setSize(apk.length())
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(APK_ENTRY, 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                // The session's bytes are not durable until this returns, and
                // commit on a partially flushed session fails as a corrupt APK.
                session.fsync(out)
            }
        }
        return sessionId
    }

    private fun registerReceiver(receiver: BroadcastReceiver, action: String) {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Required from API 33, and correct at every level: the only sender
            // is this app's own PendingIntent.
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun Intent.confirmationIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_INTENT)
        }

    /**
     * The platform's status message where there is one.
     *
     * It is usually more specific than anything derivable from the status code
     * -- "signatures do not match the previously installed version" rather than
     * INSTALL_FAILED_UPDATE_INCOMPATIBLE -- so it is preferred, with the code
     * kept as a fallback for the cases where it sends none.
     */
    private fun describe(status: Int, intent: Intent): String =
        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            ?.takeIf { it.isNotBlank() }
            ?: when (status) {
                PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation was cancelled."
                PackageInstaller.STATUS_FAILURE_INVALID -> "The APK is not a valid package."
                PackageInstaller.STATUS_FAILURE_CONFLICT ->
                    "Another app with the same package name is already installed."
                PackageInstaller.STATUS_FAILURE_STORAGE -> "There is not enough storage."
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    "This device cannot run the app."
                PackageInstaller.STATUS_FAILURE_BLOCKED -> "The install was blocked."
                else -> "Installation failed."
            }

    private companion object {
        /** The name a full-install session expects its only APK under. */
        const val APK_ENTRY = "base.apk"
    }
}
