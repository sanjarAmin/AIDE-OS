package com.osamu.aide

import android.app.Application
import com.osamu.aide.ai.LocalModelServer
import com.osamu.aide.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class AideApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val koinApp = startKoin {
            androidContext(this@AideApplication)
            modules(appModule)
        }

        // **A server the last run left behind is not this run's.** See
        // LocalModelServer.reapOrphanedServers for why it is ended, not kept.
        //
        // Only in the main process: onCreate runs in every process, and the
        // build process starting up used to withdraw the address of a server
        // the main process was using at that moment. And off the main thread,
        // where Android throws on any network call -- loopback included --
        // which is how the old probe always failed.
        if (isMainProcess()) {
            val server = koinApp.koin.get<LocalModelServer>()
            Thread({ server.reapOrphanedServers() }, "reap-local-servers").start()
        }
    }

    /** The app's own process, not `:build` or any other it declares. */
    private fun isMainProcess(): Boolean {
        val name = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            runCatching { java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrNull()
        }
        return name == packageName
    }
}
