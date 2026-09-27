package com.osamu.aide.debug

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import com.osamu.aide.engine.api.DebuggerRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * Which apps the IDE has just launched to debug, and for how long it expects
 * them.
 *
 * In memory, in the app's main process, on purpose: an expectation is for one
 * launch in the next few seconds, and one that survived a restart of the IDE
 * would hold some later, ordinary launch of the app for no debugger at all.
 */
object PendingDebuggees {

    private val until = ConcurrentHashMap<String, Long>()

    /** Expect [applicationId] to start within [withinMs]. */
    fun expect(applicationId: String, withinMs: Long = DEFAULT_WINDOW_MS) {
        until[applicationId] = SystemClock.elapsedRealtime() + withinMs
    }

    fun forget(applicationId: String) {
        until.remove(applicationId)
    }

    /**
     * Whether [applicationId] is expected, **consuming the expectation**.
     *
     * Consumed because it is for one launch. An app that crashes during startup
     * and is restarted by the system must not be held a second time for a
     * debugger that is already attached to the process that died.
     */
    fun claim(applicationId: String): Boolean {
        val deadline = until.remove(applicationId) ?: return false
        return SystemClock.elapsedRealtime() <= deadline
    }

    /**
     * Long enough for an install confirmation, a cold start and a slow phone;
     * short enough that walking away and launching the app later is not held.
     */
    private const val DEFAULT_WINDOW_MS = 60_000L
}

/**
 * Answers a debug build's startup question: is a debugger on its way?
 *
 * The generated agent in an app built with Debug calls this from its own
 * `ContentProvider.onCreate`, before any of the app's code runs, and holds
 * startup only if the answer is yes. `DebuggerRequest` has the reasoning.
 *
 * **Exported, and it answers only about the caller.** Any app may call it --
 * the agent runs in whatever package the user's project is -- so the one thing
 * it will say is whether *the calling package itself* is expected. Asking about
 * another package gets "no", which leaks nothing a caller could not learn by
 * watching its own launches.
 */
class DebugHandshakeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != DebuggerRequest.HANDSHAKE_METHOD) return null
        val caller = callingPackage
        val expected = arg != null && arg == caller && PendingDebuggees.claim(arg)
        return Bundle().apply {
            putBoolean(DebuggerRequest.HANDSHAKE_EXPECTED, expected)
            if (expected) {
                val self = context ?: return@apply
                putString(
                    DebuggerRequest.HANDSHAKE_IDE_SERVICE,
                    ComponentName(self, DebugSessionService::class.java).flattenToString(),
                )
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        /** This app's handshake authority, which a debug build is told to ask. */
        fun authority(packageName: String): String = "$packageName.debug-handshake"
    }
}
