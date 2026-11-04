package com.sentinel.eye.detect

import java.util.TreeMap

/**
 * البيانات الوصفية التي يضعها مُصدِّر Ultralytics داخل ملف ONNX:
 * أسماء الأصناف (names)، المهمة (task)، حجم المدخل (imgsz)، ونمط الإخراج بلا NMS (end2end).
 * عن طريقها يعمل أي نموذج YOLO بأصنافه الخاصة دون افتراض أنه COCO.
 */
class YoloMeta(val names: List<String>, val task: String, val imgsz: Int, val end2end: Boolean) {
    val nc: Int get() = names.size

    /** هل الأصناف هي أصناف COCO الـ 80 بالترتيب نفسه؟ حينها يُستعمل المسار الأصلي دون ترجمة. */
    val isCoco: Boolean
        get() {
            if (names.size != Coco.names.size) return false
            for (i in names.indices) if (!names[i].equals(Coco.names[i], ignoreCase = true)) return false
            return true
        }

    companion object {
        val EMPTY = YoloMeta(emptyList(), "", 0, false)

        private val DICT_ITEM = Regex("[\"']?(\\d+)[\"']?\\s*:\\s*(?:'((?:\\\\.|[^'\\\\])*)'|\"((?:\\\\.|[^\"\\\\])*)\")")
        private val QUOTED = Regex("'((?:\\\\.|[^'\\\\])*)'|\"((?:\\\\.|[^\"\\\\])*)\"")
        private val INT = Regex("\\d+")

        fun from(meta: Map<String, String>): YoloMeta {
            val names = parseNames(meta["names"] ?: "")
            val task = (meta["task"] ?: "").trim().lowercase()
            val img = parseInts(meta["imgsz"] ?: "").firstOrNull() ?: 0
            val e2e = (meta["end2end"] ?: "").trim().equals("true", ignoreCase = true)
            return YoloMeta(names, task, img, e2e)
        }

        fun parseInts(raw: String): List<Int> =
            INT.findAll(raw).mapNotNull { it.value.toIntOrNull() }.toList()

        /** يقرأ names بصيغة قاموس بايثون {0: 'person', 1: 'car'} أو قائمة ['a', 'b'] أو JSON. */
        fun parseNames(raw: String): List<String> {
            val s = raw.trim()
            if (s.isEmpty()) return emptyList()
            val out = TreeMap<Int, String>()
            if (s.startsWith("{")) {
                for (m in DICT_ITEM.findAll(s)) {
                    val idx = m.groupValues[1].toIntOrNull() ?: continue
                    val v = if (m.groupValues[2].isNotEmpty()) m.groupValues[2] else m.groupValues[3]
                    out[idx] = unescape(v)
                }
            } else {
                var i = 0
                for (m in QUOTED.findAll(s)) {
                    val v = if (m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.groupValues[2]
                    out[i] = unescape(v)
                    i++
                }
            }
            if (out.isEmpty()) return emptyList()
            val n = out.lastKey() + 1
            if (n > 5000) return emptyList()
            return List(n) { k -> out[k] ?: ("class$k") }
        }

        private fun unescape(s: String): String {
            if (s.indexOf('\\') < 0) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch == '\\' && i + 1 < s.length) {
                    val n = s[i + 1]
                    if (n == 'n' || n == 't') sb.append(' ') else sb.append(n)
                    i += 2
                } else {
                    sb.append(ch)
                    i++
                }
            }
            return sb.toString()
        }
    }
}

/**
 * يحدّد أي أصناف النموذج المخصّص تعمل، اعتماداً على مفاتيح «أشخاص / مركبات / حيوانات».
 * الصنف الذي اسمه ضمن إحدى المجموعات يتبع مفتاحها، وأي صنف آخر (مثل helmet أو fire) يعمل دائماً
 * لأن المستخدم استورد النموذج لأجله.
 */
object ClassFilter {
    private val ALIAS = mapOf(
        "people" to "person", "persons" to "person", "pedestrian" to "person",
        "pedestrians" to "person", "human" to "person", "humans" to "person"
    )

    private val groupNames: Set<String> by lazy {
        val s = HashSet<String>()
        for (id in Coco.persons) s.add(Coco.names[id])
        for (id in Coco.vehicles) s.add(Coco.names[id])
        for (id in Coco.animals) s.add(Coco.names[id])
        s
    }

    fun norm(name: String): String {
        val s = name.trim().lowercase().replace('_', ' ').replace('-', ' ')
        return ALIAS[s] ?: s
    }

    fun mask(names: List<String>, allowedCoco: IntArray): BooleanArray {
        val allowedNames = HashSet<String>()
        for (id in allowedCoco) if (id >= 0 && id < Coco.names.size) allowedNames.add(Coco.names[id])
        return BooleanArray(names.size) { i ->
            val n = norm(names[i])
            if (groupNames.contains(n)) allowedNames.contains(n) else true
        }
    }
}
