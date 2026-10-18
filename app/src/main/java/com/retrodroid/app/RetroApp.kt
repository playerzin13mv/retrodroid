package com.retrodroid.app

import android.app.Application

class RetroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        I18n.load(this)
    }
}
