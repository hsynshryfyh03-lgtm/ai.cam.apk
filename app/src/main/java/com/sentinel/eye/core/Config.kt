package com.sentinel.eye.core

import android.content.Context
import android.content.SharedPreferences
import com.sentinel.eye.detect.Coco
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** كل إعدادات التطبيق في كائن واحد ثابت، يُحفظ كـ JSON. */
data class Config(
    val modelId: String = "yolo11n-320",
    val accel: Int = 0,
    val threads: Int = 4,
    val analysisWidth: Int = 640,
    val analysisFps: Int = 10,
    val confidence: Float = 0.45f,
    val minHeightPct: Int = 8,
    val confirmFrames: Int = 2,
    val detectPersons: Boolean = true,
    val detectVehicles: Boolean = false,
    val detectAnimals: Boolean = false,
    val motionGate: Boolean = true,
    val zoneEnabled: Boolean = false,
    val zoneL: Float = 0f,
    val zoneT: Float = 0f,
    val zoneR: Float = 1f,
    val zoneB: Float = 1f,
    val preRollSec: Int = 5,
    val postRollSec: Int = 8,
    val maxEventMin: Int = 10,
    val videoHeight: Int = 720,
    val fps: Int = 15,
    val quality: Int = 1,
    val codec: Int = 0,
    val useFront: Boolean = false,
    val orientation: Int = 0,
    val maxStorageGb: Int = 10,
    val maxDays: Int = 30,
    val notify: Boolean = true,
    val alarm: Boolean = false,
    val appearance: Int = 0,
    val onboardingDone: Boolean = false,
    val autostartAck: Boolean = false,
    val wantMonitoring: Boolean = false,
    val modelsRepo: String = "",
    val maxPerf: Boolean = true,
    val anPersons: Boolean = true,
    val anAnimals: Boolean = true,
    val anVehicles: Boolean = true,
    val anSampling: Int = 0,
    val anPadSec: Int = 1,
    val anGapSec: Int = 2,
    val anConfidence: Float = 0.4f,
    val audio: Boolean = false,
    val tgEnabled: Boolean = false,
    val tgToken: String = "",
    val tgChat: String = "",
    val tgPhoto: Boolean = true,
    val tgVideo: Boolean = false,
    val tgInstant: Boolean = false,
    val tgApiBase: String = "",
    val tgProxy: String = "",
    val lockEnabled: Boolean = false,
    val lockHash: String = "",
    val lockSalt: String = "",
    val lockBio: Boolean = true,
    val lockTimeout: Int = 30,
    val lockHidePreview: Boolean = true,
    val shzAuto: Boolean = false,
    val shzApplied: String = "",
    val ver: Int = 3
) {
    fun allowedClasses(): IntArray = Coco.allowedIds(detectPersons, detectVehicles, detectAnimals)

    /** مفتاح يتغير عند تغيّر أي إعداد يستلزم إعادة تشغيل الكاميرا والمرمّز. */
    fun captureKey(): String =
        listOf(useFront, videoHeight, fps, quality, codec, orientation, preRollSec, analysisWidth).joinToString("|")

    /** مفتاح يتغير عند تغيّر ما يستلزم إعادة تحميل النموذج. */
    fun modelKey(): String = "$modelId|$accel|$threads"

    fun toJson(): JSONObject = JSONObject().apply {
        put("modelId", modelId); put("accel", accel); put("threads", threads)
        put("analysisWidth", analysisWidth); put("analysisFps", analysisFps)
        put("confidence", confidence.toDouble()); put("minHeightPct", minHeightPct)
        put("confirmFrames", confirmFrames); put("detectPersons", detectPersons)
        put("detectVehicles", detectVehicles); put("detectAnimals", detectAnimals)
        put("motionGate", motionGate); put("zoneEnabled", zoneEnabled)
        put("zoneL", zoneL.toDouble()); put("zoneT", zoneT.toDouble())
        put("zoneR", zoneR.toDouble()); put("zoneB", zoneB.toDouble())
        put("preRollSec", preRollSec); put("postRollSec", postRollSec); put("maxEventMin", maxEventMin)
        put("videoHeight", videoHeight); put("fps", fps); put("quality", quality); put("codec", codec)
        put("useFront", useFront); put("orientation", orientation)
        put("maxStorageGb", maxStorageGb); put("maxDays", maxDays)
        put("notify", notify); put("alarm", alarm); put("appearance", appearance)
        put("onboardingDone", onboardingDone); put("autostartAck", autostartAck)
        put("wantMonitoring", wantMonitoring)
        put("modelsRepo", modelsRepo); put("maxPerf", maxPerf)
        put("anPersons", anPersons); put("anAnimals", anAnimals); put("anVehicles", anVehicles)
        put("anSampling", anSampling); put("anPadSec", anPadSec); put("anGapSec", anGapSec)
        put("anConfidence", anConfidence.toDouble()); put("ver", ver)
        put("audio", audio); put("tgEnabled", tgEnabled); put("tgToken", tgToken); put("tgChat", tgChat)
        put("tgPhoto", tgPhoto); put("tgVideo", tgVideo); put("tgInstant", tgInstant)
        put("tgApiBase", tgApiBase); put("tgProxy", tgProxy)
        put("lockEnabled", lockEnabled); put("lockHash", lockHash); put("lockSalt", lockSalt)
        put("lockBio", lockBio); put("lockTimeout", lockTimeout); put("lockHidePreview", lockHidePreview)
        put("shzAuto", shzAuto); put("shzApplied", shzApplied)
    }

    companion object {
        fun fromJson(j: JSONObject): Config {
            val d = Config()
            val storedVer = j.optInt("ver", 1)
            fun f(k: String, def: Float) = j.optDouble(k, def.toDouble()).toFloat()
            val zl = f("zoneL", d.zoneL).coerceIn(0f, 0.9f)
            val zt = f("zoneT", d.zoneT).coerceIn(0f, 0.9f)
            val zr = f("zoneR", d.zoneR).coerceIn(zl + 0.05f, 1f)
            val zb = f("zoneB", d.zoneB).coerceIn(zt + 0.05f, 1f)
            return Config(
                modelId = j.optString("modelId", d.modelId),
                accel = j.optInt("accel", d.accel).coerceIn(0, 2),
                threads = j.optInt("threads", d.threads).coerceIn(1, 8),
                analysisWidth = j.optInt("analysisWidth", d.analysisWidth).coerceIn(320, 1280),
                analysisFps = (if (storedVer < 2) d.analysisFps else j.optInt("analysisFps", d.analysisFps)).coerceIn(0, 30),
                confidence = f("confidence", d.confidence).coerceIn(0.1f, 0.95f),
                minHeightPct = j.optInt("minHeightPct", d.minHeightPct).coerceIn(0, 80),
                confirmFrames = j.optInt("confirmFrames", d.confirmFrames).coerceIn(1, 10),
                detectPersons = j.optBoolean("detectPersons", d.detectPersons),
                detectVehicles = j.optBoolean("detectVehicles", d.detectVehicles),
                detectAnimals = j.optBoolean("detectAnimals", d.detectAnimals),
                motionGate = j.optBoolean("motionGate", d.motionGate),
                zoneEnabled = j.optBoolean("zoneEnabled", d.zoneEnabled),
                zoneL = zl, zoneT = zt, zoneR = zr, zoneB = zb,
                preRollSec = j.optInt("preRollSec", d.preRollSec).coerceIn(0, 30),
                postRollSec = j.optInt("postRollSec", d.postRollSec).coerceIn(1, 120),
                maxEventMin = j.optInt("maxEventMin", d.maxEventMin).coerceIn(1, 60),
                videoHeight = j.optInt("videoHeight", d.videoHeight).let { if (it == 480 || it == 720 || it == 1080) it else 720 },
                fps = j.optInt("fps", d.fps).let { if (it == 15 || it == 24 || it == 30) it else 15 },
                quality = j.optInt("quality", d.quality).coerceIn(0, 2),
                codec = j.optInt("codec", d.codec).coerceIn(0, 1),
                useFront = j.optBoolean("useFront", d.useFront),
                orientation = j.optInt("orientation", d.orientation).let { if (it == 0 || it == 90 || it == 270) it else 0 },
                maxStorageGb = j.optInt("maxStorageGb", d.maxStorageGb).coerceIn(1, 500),
                maxDays = j.optInt("maxDays", d.maxDays).coerceIn(0, 365),
                notify = j.optBoolean("notify", d.notify),
                alarm = j.optBoolean("alarm", d.alarm),
                appearance = j.optInt("appearance", d.appearance).coerceIn(0, 2),
                onboardingDone = j.optBoolean("onboardingDone", d.onboardingDone),
                autostartAck = j.optBoolean("autostartAck", d.autostartAck),
                wantMonitoring = j.optBoolean("wantMonitoring", d.wantMonitoring),
                modelsRepo = j.optString("modelsRepo", d.modelsRepo).trim(),
                maxPerf = j.optBoolean("maxPerf", d.maxPerf),
                anPersons = j.optBoolean("anPersons", d.anPersons),
                anAnimals = j.optBoolean("anAnimals", d.anAnimals),
                anVehicles = j.optBoolean("anVehicles", d.anVehicles),
                anSampling = j.optInt("anSampling", d.anSampling).coerceIn(0, 1),
                anPadSec = j.optInt("anPadSec", d.anPadSec).coerceIn(0, 10),
                anGapSec = j.optInt("anGapSec", d.anGapSec).coerceIn(0, 10),
                anConfidence = f("anConfidence", d.anConfidence).coerceIn(0.1f, 0.95f),
                audio = j.optBoolean("audio", d.audio),
                tgEnabled = j.optBoolean("tgEnabled", d.tgEnabled),
                tgToken = j.optString("tgToken", d.tgToken).trim(),
                tgChat = j.optString("tgChat", d.tgChat).trim(),
                tgPhoto = j.optBoolean("tgPhoto", d.tgPhoto),
                tgVideo = j.optBoolean("tgVideo", d.tgVideo),
                tgInstant = j.optBoolean("tgInstant", d.tgInstant),
                tgApiBase = j.optString("tgApiBase", d.tgApiBase).trim(),
                tgProxy = j.optString("tgProxy", d.tgProxy).trim(),
                lockEnabled = j.optBoolean("lockEnabled", d.lockEnabled),
                lockHash = j.optString("lockHash", d.lockHash),
                lockSalt = j.optString("lockSalt", d.lockSalt),
                lockBio = j.optBoolean("lockBio", d.lockBio),
                lockTimeout = j.optInt("lockTimeout", d.lockTimeout).coerceIn(0, 3600),
                lockHidePreview = j.optBoolean("lockHidePreview", d.lockHidePreview),
                shzAuto = j.optBoolean("shzAuto", d.shzAuto),
                shzApplied = j.optString("shzApplied", d.shzApplied),
                ver = 3
            )
        }
    }
}

object ConfigStore {
    private var prefs: SharedPreferences? = null
    private val state = MutableStateFlow(Config())
    val flow: StateFlow<Config> get() = state

    @Volatile
    var current: Config = Config()
        private set

    private val listeners = CopyOnWriteArrayList<(Config) -> Unit>()

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("sentinel", Context.MODE_PRIVATE)
        prefs = p
        val s = p.getString("cfg", null)
        if (s != null) {
            try {
                current = Config.fromJson(JSONObject(s))
            } catch (t: Throwable) {
                AppLog.w("Config", "تعذّر قراءة الإعدادات، استخدام الافتراضي", t)
            }
        }
        state.value = current
    }

    @Synchronized
    fun update(block: (Config) -> Config) {
        val n = block(current)
        if (n == current) return
        current = n
        state.value = n
        prefs?.edit()?.putString("cfg", n.toJson().toString())?.apply()
        for (l in listeners) {
            try { l(n) } catch (t: Throwable) { AppLog.e("Config", "listener", t) }
        }
    }

    fun addListener(l: (Config) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Config) -> Unit) { listeners.remove(l) }
}
