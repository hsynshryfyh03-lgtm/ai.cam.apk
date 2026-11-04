package com.sentinel.eye.core

import org.json.JSONObject
import java.io.File

data class EventMeta(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val maxScore: Float,
    val labels: String,
    val sizeBytes: Long,
    val engine: String,
    val width: Int,
    val height: Int,
    val hasThumb: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("startMs", startMs); put("endMs", endMs); put("durationMs", durationMs)
        put("maxScore", maxScore.toDouble()); put("labels", labels); put("sizeBytes", sizeBytes)
        put("engine", engine); put("width", width); put("height", height); put("hasThumb", hasThumb)
    }

    companion object {
        fun fromJson(j: JSONObject) = EventMeta(
            id = j.getString("id"),
            startMs = j.optLong("startMs"),
            endMs = j.optLong("endMs"),
            durationMs = j.optLong("durationMs"),
            maxScore = j.optDouble("maxScore", 0.0).toFloat(),
            labels = j.optString("labels", ""),
            sizeBytes = j.optLong("sizeBytes"),
            engine = j.optString("engine", ""),
            width = j.optInt("width"),
            height = j.optInt("height"),
            hasThumb = j.optBoolean("hasThumb", false)
        )
    }
}

/** تخزين الأحداث: لكل حدث ملف mp4 وصورة jpg وملف json، بنفس المعرّف. */
class EventStore(val dir: File) {
    fun videoFile(id: String) = File(dir, "$id.mp4")
    fun thumbFile(id: String) = File(dir, "$id.jpg")
    private fun jsonFile(id: String) = File(dir, "$id.json")

    fun save(meta: EventMeta) {
        val tmp = File(dir, meta.id + ".json.tmp")
        tmp.writeText(meta.toJson().toString())
        if (!tmp.renameTo(jsonFile(meta.id))) {
            jsonFile(meta.id).writeText(meta.toJson().toString())
            tmp.delete()
        }
    }

    fun list(): List<EventMeta> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        val out = ArrayList<EventMeta>(files.size)
        for (f in files) {
            try {
                val m = EventMeta.fromJson(JSONObject(f.readText()))
                if (videoFile(m.id).exists()) out.add(m) else f.delete()
            } catch (t: Throwable) {
                AppLog.w("EventStore", "ملف حدث تالف: ${f.name}", t)
            }
        }
        out.sortByDescending { it.startMs }
        return out
    }

    fun delete(id: String) {
        videoFile(id).delete(); thumbFile(id).delete(); jsonFile(id).delete()
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    /** يحذف الأقدم حتى يتحقق حد المساحة وحد العمر (0 = بلا حد زمني). */
    fun enforceQuota(maxBytes: Long, maxAgeDays: Int, protect: Set<String>) {
        try {
            val all = list().sortedBy { it.startMs }
            val now = System.currentTimeMillis()
            if (maxAgeDays > 0) {
                val limit = now - maxAgeDays * 86_400_000L
                for (m in all) if (m.startMs < limit && m.id !in protect) delete(m.id)
            }
            var total = totalBytes()
            if (total <= maxBytes) return
            for (m in list().sortedBy { it.startMs }) {
                if (total <= maxBytes) break
                if (m.id in protect) continue
                val sz = videoFile(m.id).length() + thumbFile(m.id).length()
                delete(m.id)
                total -= sz
            }
        } catch (t: Throwable) {
            AppLog.w("EventStore", "فشل تطبيق حد المساحة", t)
        }
    }

    /** يحذف ملفات mp4 التي لا يوجد لها json (تسجيل انقطع فجأة). */
    fun cleanOrphans(protect: Set<String>) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (!f.isFile) continue
            val n = f.name
            val id = n.substringBeforeLast('.')
            if (id in protect) continue
            if ((n.endsWith(".mp4") || n.endsWith(".jpg")) && !File(dir, "$id.json").exists()) f.delete()
            if (n.endsWith(".tmp")) f.delete()
        }
    }
}
