package com.osamu.aide

import android.app.Application
import com.osamu.aide.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class AideApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@AideApplication)
            modules(appModule)
        }
    }
}
