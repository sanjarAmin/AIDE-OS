package com.osamu.aide.build

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.osamu.aide.core.fs.Project
import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.DebuggerRequest
import com.osamu.aide.engine.api.BuildResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * A build, run in [BuildService]'s process and reported back here.
 *
 * The seam is deliberately the same shape as the in-process builder — one
 * function returning a flow of events — so the caller cannot tell which it has,
 * and neither can the tests that already cover the events.
 */
class RemoteBuildRunner(
    private val context: Context,
) : BuildRunner {

    override fun build(
        project: Project,
        debuggable: Boolean,
        debugger: DebuggerRequest?,
    ): Flow<BuildEvent> = callbackFlow {
        val thread = HandlerThread("build-client").apply { start() }

        // Replies arrive on this thread, not the caller's: a build emits while
        // the collector is still working, and the flow's buffer is what absorbs
        // that rather than a blocked binder thread.
        val incoming = Messenger(
            Handler(thread.looper) { message ->
                if (message.what == BuildProtocol.MSG_EVENT) {
                    val bundle = message.data?.getBundle(BuildProtocol.KEY_EVENT)
                    val event = bundle?.let(BuildProtocol::decodeEvent)
                    if (event != null) {
                        trySend(event)
                        // Finished is the last event by contract, so closing
                        // here is what makes collecting this flow terminate
                        // rather than hang on a service that has nothing more
                        // to say.
                        if (event is BuildEvent.Finished) close()
                    }
                }
                true
            },
        )

        var outgoing: Messenger? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = Messenger(binder)
                outgoing = service
                val data = Bundle().apply {
                    putBundle(BuildProtocol.KEY_PROJECT, BuildProtocol.encodeProject(project))
                    putBoolean(BuildProtocol.KEY_DEBUGGABLE, debuggable)
                    debugger?.let {
                        putInt(BuildProtocol.KEY_DEBUG_PORT, it.port)
                        putString(BuildProtocol.KEY_DEBUG_HANDSHAKE, it.handshakeAuthority)
                    }
                }
                runCatching {
                    service.send(
                        Message.obtain(null, BuildProtocol.MSG_BUILD).apply {
                            this.data = data
                            replyTo = incoming
                        },
                    )
                }.onFailure { failed("The build process could not be reached.") }
            }

            /**
             * The build process died — which for this service is usually an
             * out-of-memory kill, the thing it exists to survive.
             *
             * Reported as a failed build rather than left to hang, because the
             * user is watching a progress bar and the process that would have
             * sent `Finished` is gone.
             */
            override fun onServiceDisconnected(name: ComponentName?) {
                failed("The build process stopped unexpectedly. It may have run out of memory.")
            }
        }

        val bound = context.bindService(
            Intent(context, BuildService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) failed("The build process could not be started.")

        awaitClose {
            outgoing?.let { service ->
                runCatching {
                    service.send(Message.obtain(null, BuildProtocol.MSG_CANCEL))
                }
            }
            runCatching { context.unbindService(connection) }
            thread.quitSafely()
        }
    }

    private fun kotlinx.coroutines.channels.ProducerScope<BuildEvent>.failed(message: String) {
        trySend(
            BuildEvent.Finished(
                BuildResult.Failure(stage = null, message = message, durationMillis = 0),
            ),
        )
        close()
    }
}
