package com.sentinel.eye.monitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sentinel.eye.core.AppLog
import com.sentinel.eye.core.ConfigStore
import com.sentinel.eye.core.Perf
import com.sentinel.eye.core.Storage
import com.sentinel.eye.shizuku.ShizukuHelper
import com.sentinel.eye.shizuku.ShzStatus

class MonitorService : Service() {

    companion object {
        const val ACTION_START = "com.sentinel.eye.START"
        const val ACTION_STOP = "com.sentinel.eye.STOP"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, MonitorService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ConfigStore.update { it.copy(wantMonitoring = false) }
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }
    }

    private var engine: MonitorEngine? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.init(applicationContext)
        ConfigStore.init(applicationContext)
        Storage.init(applicationContext)
        Notifier.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ConfigStore.update { it.copy(wantMonitoring = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        val micOk = ConfigStore.current.audio &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        MonitorBus.micFgs = micOk
        val fgsType = if (micOk) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        try {
            ServiceCompat.startForeground(
                this, Notifier.ID_FG, Notifier.foreground(this, "جارٍ التشغيل…"),
                fgsType
            )
        } catch (t: Throwable) {
            AppLog.e("Service", "تعذّر بدء الخدمة الأمامية (تحقق من إذن الكاميرا)", t)
            MonitorBus.status.value = MonitorStatus.Error("تعذّر بدء الخدمة: ${t.message}", false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (engine == null) {
            val e = MonitorEngine(applicationContext) { text -> Notifier.update(applicationContext, text) }
            engine = e
            ConfigStore.update { it.copy(wantMonitoring = true) }
            e.start()
            if (ConfigStore.current.shzAuto) {
                ShizukuHelper.refresh()
                if (ShizukuHelper.status.value == ShzStatus.READY) ShizukuHelper.run(this, null, false) { }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine?.stopAndWait()
        engine = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
