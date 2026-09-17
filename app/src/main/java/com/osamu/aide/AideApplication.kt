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

        // **A server the last run started is not this run's to assume.** The
        // address outlives the process that published it -- force-stopping the
        // app kills `llama-server` but runs no code to withdraw it -- so
        // without this the on-device provider reports itself ready and the
        // first message goes to a port nobody is listening on. Probed rather
        // than blindly cleared, because a crashed app can leave a working
        // server behind. Found by driving the app; `LocalModelServer`.
        //
        // On the main thread deliberately: it is one loopback probe with a
        // one-second timeout, and doing it later would mean a window where the
        // provider still lies about being ready.
        koinApp.koin.get<LocalModelServer>().adoptOrForgetExistingServer()
    }
}
