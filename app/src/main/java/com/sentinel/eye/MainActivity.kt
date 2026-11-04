package com.sentinel.eye

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.view.WindowManager
import android.os.Bundle
import android.provider.OpenableColumns
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.sentinel.eye.analysis.AnalysisBus
import com.sentinel.eye.analysis.VideoAnalyzer
import com.sentinel.eye.core.AppLock
import com.sentinel.eye.core.BiometricHelper
import com.sentinel.eye.core.Config
import com.sentinel.eye.core.ConfigStore
import com.sentinel.eye.detect.ModelManager
import com.sentinel.eye.detect.ModelSpec
import com.sentinel.eye.monitor.MonitorBus
import com.sentinel.eye.monitor.MonitorService
import com.sentinel.eye.monitor.MonitorStatus
import com.sentinel.eye.ui.SentinelApp

class MainActivity : ComponentActivity() {

    private var pendingImport: ModelSpec? = null

    private val cfgListener: (Config) -> Unit = { cfg -> runOnUiThread { applySecure(cfg) } }

    private fun applySecure(cfg: Config) {
        if (cfg.lockEnabled && cfg.lockHidePreview) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val spec = pendingImport
        pendingImport = null
        if (uri != null && spec != null) ModelManager.importFrom(applicationContext, spec, uri)
    }

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onVideoPicked(uri)
    }

    private fun onVideoPicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
        var name = "فيديو"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cur ->
                val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && cur.moveToFirst()) name = cur.getString(i) ?: name
            }
        } catch (_: Throwable) {
        }
        AnalysisBus.selected.value = uri
        AnalysisBus.selectedName.value = name
        AnalysisBus.selectedInfo.value = "جارٍ قراءة المعلومات…"
        AnalysisBus.state.value = com.sentinel.eye.analysis.AnState.Idle
        AnalysisBus.export.value = com.sentinel.eye.analysis.ExState.Idle
        Thread {
            val txt = try {
                val p = VideoAnalyzer(applicationContext).probe(uri)
                val sec = p.durationSec
                "المدة %d:%02d · %d×%d".format(sec / 60, sec % 60, p.width, p.height)
            } catch (t: Throwable) {
                "تعذّر قراءة معلومات الفيديو"
            }
            AnalysisBus.selectedInfo.value = txt
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySecure(ConfigStore.current)
        ConfigStore.addListener(cfgListener)
        setContent {
            SentinelApp(
                onImportModel = { spec ->
                    pendingImport = spec
                    importLauncher.launch(arrayOf("*/*"))
                },
                onPickVideo = { videoPicker.launch(arrayOf("video/*")) },
                onBiometric = { BiometricHelper.prompt(this) { AppLock.unlock() } }
            )
        }
        resumeIfWanted()
    }

    /** إن كان المستخدم قد شغّل المراقبة سابقاً ثم أُغلقت العملية، نعيد تشغيلها عند فتح التطبيق. */
    private fun resumeIfWanted() {
        val cfg = ConfigStore.current
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (cfg.onboardingDone && cfg.wantMonitoring && granted && MonitorBus.status.value == MonitorStatus.Stopped) {
            MonitorService.start(this)
        }
    }

    override fun onStart() {
        super.onStart()
        MonitorBus.uiVisible = true
        AppLock.onStart()
    }

    override fun onStop() {
        MonitorBus.uiVisible = false
        AppLock.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        ConfigStore.removeListener(cfgListener)
        super.onDestroy()
    }
}
