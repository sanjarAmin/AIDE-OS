package com.osamu.aide.build

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.DebuggerRequest
import com.osamu.aide.ui.workspace.ProjectBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject

/**
 * Runs builds in a process of their own.
 *
 * **This is risk R3, and it is a memory problem rather than an architectural
 * preference.** The Kotlin compiler is held warm because its startup is around
 * eleven seconds, and its object graph does not fit comfortably in a default
 * per-app heap — `largeHeap` was the first answer and the plan has always
 * called it headroom rather than a fix. A heap that large in the *UI* process
 * means the editor, the file tree and every open buffer are competing with a
 * compiler, and when the compiler loses, Android kills the app: the user's
 * unsaved work goes with a build they could have simply retried.
 *
 * In a separate process the compiler's heap is its own. An out-of-memory kill
 * takes the build and leaves the editor standing, which turns the worst outcome
 * into the second-best one.
 *
 * `Messenger` rather than AIDL: the traffic is a request and a stream of small
 * events, one client at a time, and AIDL would add a generated interface and a
 * threading model to a boundary that is one message wide. `BuildProtocol` is
 * the whole contract.
 */
class BuildService : Service() {

    private val builder: ProjectBuilder by inject()
    private val dispatchers: DispatcherProvider by inject()

    /**
     * Its own thread, not the main looper.
     *
     * This process has a main thread like any other, and a build that ran on it
     * would make the service unable to receive the cancel message that is the
     * one thing worth sending it mid-build.
     */
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger
    private lateinit var scope: CoroutineScope
    private var running: Job? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("build-service").apply { start() }
        scope = CoroutineScope(SupervisorJob() + dispatchers.io)
        messenger = Messenger(Handler(thread.looper) { handle(it) })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        runBlocking { running?.cancelAndJoin() }
        thread.quitSafely()
        super.onDestroy()
    }

    private fun handle(message: Message): Boolean = when (message.what) {
        BuildProtocol.MSG_BUILD -> {
            startBuild(message)
            true
        }
        BuildProtocol.MSG_CANCEL -> {
            running?.cancel()
            true
        }
        else -> false
    }

    private fun startBuild(message: Message) {
        val reply = message.replyTo ?: return
        val bundle = message.data?.getBundle(BuildProtocol.KEY_PROJECT) ?: return
        val project = BuildProtocol.decodeProject(bundle)
        // Defaults to a debug build: an older client that does not send the flag
        // must not get a release one by accident.
        val debuggable = message.data?.getBoolean(BuildProtocol.KEY_DEBUGGABLE, true) ?: true
        val debugger = message.data
            ?.takeIf { it.containsKey(BuildProtocol.KEY_DEBUG_PORT) }
            ?.let {
                DebuggerRequest(
                    port = it.getInt(BuildProtocol.KEY_DEBUG_PORT),
                    handshakeAuthority = it.getString(BuildProtocol.KEY_DEBUG_HANDSHAKE),
                )
            }

        // One build at a time. A second request while one is running is the
        // user tapping Build twice, and two compilers in one process is the
        // memory problem this service exists to avoid.
        running?.cancel()
        running = scope.launch {
            builder.build(project, debuggable, debugger)
                .onEach { send(reply, it) }
                .collect()
        }
    }

    private fun send(reply: Messenger, event: BuildEvent) {
        val data = Bundle().apply {
            putBundle(BuildProtocol.KEY_EVENT, BuildProtocol.encodeEvent(event))
        }
        // A client that has gone away is an ordinary outcome -- the activity was
        // closed mid-build -- and not a reason to bring the build process down
        // with an unhandled RemoteException.
        runCatching {
            reply.send(Message.obtain(null, BuildProtocol.MSG_EVENT).apply { this.data = data })
        }
    }

    companion object {
        /** Whether this code is running in the build process rather than the app's. */
        fun isBuildProcess(processName: String?): Boolean =
            processName != null && processName.endsWith(PROCESS_SUFFIX)

        /** Must match `android:process` in the manifest. */
        const val PROCESS_SUFFIX = ":build"

        /** For tests and logging: the pid a build actually ran in. */
        fun currentPid(): Int = Process.myPid()
    }
}
