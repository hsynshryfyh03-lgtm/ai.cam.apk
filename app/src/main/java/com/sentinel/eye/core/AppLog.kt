package com.sentinel.eye.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** سجل ملفي بسيط يُعرض داخل التطبيق ويمكن مشاركته عند حدوث خطأ. */
object AppLog {
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private const val MAX_BYTES = 400_000L
    private const val KEEP_BYTES = 200_000

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "app.log")
        file = f
        try {
            if (f.exists() && f.length() > MAX_BYTES) {
                val bytes = f.readBytes()
                val start = (bytes.size - KEEP_BYTES).coerceAtLeast(0)
                f.writeBytes(bytes.copyOfRange(start, bytes.size))
            }
        } catch (_: Throwable) {
        }
    }

    fun logFile(): File? = file

    @Synchronized
    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val line = buildString {
            append(fmt.format(Date())).append(' ').append(level).append('/').append(tag).append(": ").append(msg)
            if (t != null) append("\n").append(Log.getStackTraceString(t))
            append('\n')
        }
        try {
            file?.appendText(line)
        } catch (_: Throwable) {
        }
    }

    fun i(tag: String, msg: String) { Log.i(tag, msg); write("I", tag, msg, null) }
    fun w(tag: String, msg: String, t: Throwable? = null) { Log.w(tag, msg, t); write("W", tag, msg, t) }
    fun e(tag: String, msg: String, t: Throwable? = null) { Log.e(tag, msg, t); write("E", tag, msg, t) }

    @Synchronized
    fun tail(maxLines: Int = 300): String {
        val f = file ?: return ""
        return try {
            val lines = f.readLines()
            lines.takeLast(maxLines).joinToString("\n")
        } catch (_: Throwable) {
            ""
        }
    }

    @Synchronized
    fun clear() {
        try { file?.writeText("") } catch (_: Throwable) { }
    }

    fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            write("F", "CRASH", "خطأ غير معالج في الخيط ${t.name}", e)
            prev?.uncaughtException(t, e)
        }
    }
}
