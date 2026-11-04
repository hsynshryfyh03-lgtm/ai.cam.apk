package com.sentinel.eye

import android.app.Application
import com.sentinel.eye.core.AppLog
import com.sentinel.eye.core.ConfigStore
import com.sentinel.eye.core.Storage
import com.sentinel.eye.detect.ModelManager
import com.sentinel.eye.monitor.Notifier
import com.sentinel.eye.shizuku.ShizukuHelper

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.installCrashHandler()
        ConfigStore.init(this)
        Storage.init(this)
        ModelManager.init(this)
        Notifier.init(this)
        ShizukuHelper.init()
        AppLog.i("App", "بدء التطبيق")
    }
}
