package com.osamu.aide.debug

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * Bound by an app under debug, so Android does not freeze the IDE while that
 * app is in front.
 *
 * **Without it a breakpoint session hangs the moment the user looks at their
 * app.** Android 14 freezes cached processes; the IDE goes cached as soon as
 * the debuggee covers it, and a frozen IDE cannot resume a thread its debugger
 * holds -- so the app stalls on its next class load, and on the next breakpoint
 * nothing notices. A process bound by the app on screen is not cached, so the
 * debuggee binds this.
 *
 * **The other direction is not a binding.** The IDE keeps the debuggee awake
 * with an unstable `ContentProviderClient` on the agent's provider: binding a
 * service in the debuggee creates it on the debuggee's main thread -- the thread
 * a breakpoint may be holding -- and got the app killed for an ANR. Found by
 * driving it: `debugger/FINDINGS.md` section 9.
 *
 * It returns an empty binder and does nothing, which is why being exported is
 * harmless: the only thing binding it achieves is keeping this process awake.
 */
class DebugSessionService : Service() {
    private val binder = Binder()
    override fun onBind(intent: Intent?): IBinder = binder
}
