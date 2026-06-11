package com.klipperremote.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class KlipperRemoteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
