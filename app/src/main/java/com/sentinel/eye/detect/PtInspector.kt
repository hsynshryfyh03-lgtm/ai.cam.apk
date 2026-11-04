package com.sentinel.eye.detect

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

enum class PtKind { ULTRALYTICS, YOLOV5_LEGACY, TORCHSCRIPT, OTHER_TORCH, NOT_TORCH }

/** نتيجة فحص ملف .pt دون تحميله ودون تنفيذ أي شيفرة بداخله. */
class PtReport(
    val fileName: String,
    val sizeBytes: Long,
    val kind: PtKind,
    val family: String,
    val task: String,
    val names: List<String>,
    val imgsz: Int,
    val risks: List<String>,
    val parsed: Boolean
) {
    val safe: Boolean get() = risks.isEmpty()
    val convertible: Boolean get() = safe && (kind == PtKind.ULTRALYTICS || kind == PtKind.YOLOV5_LEGACY)
}

/**
 * ملف .pt هو أرشيف ZIP يحوي كائن Python مُسلسَلاً بـ pickle، وهو ليس رسماً حسابياً قابلاً للتشغيل،
 * وفتحه بـ pickle العادي ينفّذ شيفرة قد تكون خبيثة. لذلك نقرأ التعليمات فقط بآلة افتراضية لا تنفّذ شيئاً،
 * ونستخرج منها المعلومات (العائلة، المهمة، الأصناف، حجم الصورة) ونبلّغ عن أي استدعاء خطر.
 */
object PtInspector {
    private const val MAX_PKL = 64L * 1024 * 1024

    private val BAD_MODULES = setOf(
        "os", "posix", "nt", "subprocess", "sys", "socket", "shutil", "runpy", "importlib", "ctypes",
        "pty", "marshal", "pickle", "_pickle", "webbrowser", "urllib", "urllib2", "http", "requests",
        "asyncio", "threading", "multiprocessing", "code", "codeop", "platform", "tempfile", "commands",
        "timeit", "pip", "setuptools", "distutils", "ftplib", "smtplib", "telnetlib", "xmlrpc",
        "socketserver", "signal", "resource", "pdb", "trace", "profile", "cProfile"
    )
    private val BAD_BUILTINS = setOf("eval", "exec", "compile", "__import__", "open", "input", "breakpoint")
    private val BAD_FULL = listOf(
        "torch.hub.", "torch.load", "torch.jit.load", "torch.utils.cpp_extension.",
        "operator.attrgetter", "operator.methodcaller", "types.CodeType", "types.FunctionType"
    )

    fun isDangerous(global: String): Boolean {
        val dot = global.indexOf('.')
        val top = if (dot > 0) global.substring(0, dot) else global
        if (top == "builtins" || top == "__builtin__") {
            return BAD_BUILTINS.contains(global.substring(dot + 1))
        }
        if (BAD_MODULES.contains(top)) return true
        for (p in BAD_FULL) if (global.startsWith(p)) return true
        return false
    }

    fun inspect(file: File, displayName: String): PtReport {
        val size = file.length()
        val head = ByteArray(4)
        val n = file.inputStream().use { it.read(head) }
        val isZip = n >= 2 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
        if (!isZip) {
            val legacy = n >= 1 && head[0] == 0x80.toByte()
            val kind = if (legacy) PtKind.OTHER_TORCH else PtKind.NOT_TORCH
            return PtReport(displayName, size, kind, "", "", emptyList(), 0, emptyList(), false)
        }
        return try {
            ZipFile(file).use { zf -> inspectZip(zf, displayName, size) }
        } catch (t: Throwable) {
            PtReport(displayName, size, PtKind.NOT_TORCH, "", "", emptyList(), 0, emptyList(), false)
        }
    }

    private fun inspectZip(zf: ZipFile, displayName: String, size: Long): PtReport {
        var pkl: ZipEntry? = null
        var script = false
        val en = zf.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            val nm = e.name
            if ((nm.endsWith("/data.pkl") || nm == "data.pkl") && pkl == null) pkl = e
            if (nm.endsWith("constants.pkl") || nm.contains("/code/__torch__")) script = true
        }
        val entry = pkl
            ?: return PtReport(displayName, size, PtKind.NOT_TORCH, "", "", emptyList(), 0, emptyList(), false)
        if (script) {
            return PtReport(displayName, size, PtKind.TORCHSCRIPT, "", "", emptyList(), 0, emptyList(), true)
        }
        if (entry.size > MAX_PKL) {
            return PtReport(displayName, size, PtKind.OTHER_TORCH, "", "", emptyList(), 0, emptyList(), false)
        }
        val bytes = zf.getInputStream(entry).use { it.readBytes() }
        return analyse(displayName, size, PickleScan.run(bytes))
    }

    private fun analyse(name: String, size: Long, scan: PickleScan.Result): PtReport {
        val g = scan.globals
        val risks = g.filter { isDangerous(it) }
        var kind = PtKind.OTHER_TORCH
        if (g.any { it.startsWith("ultralytics.") }) kind = PtKind.ULTRALYTICS
        else if (g.any { it.startsWith("models.yolo") || it.startsWith("models.common") }) kind = PtKind.YOLOV5_LEGACY

        fun has(suffix: String): Boolean = g.any { it.endsWith(suffix) }
        val family = when {
            has(".RTDETRDecoder") || has(".RTDETRDetectionModel") -> "RT-DETR"
            has(".WorldDetect") || has(".WorldModel") -> "YOLO-World"
            g.any { it.contains("YOLOE") } -> "YOLOE"
            has(".A2C2f") || has(".AAttn") -> "YOLO12"
            has(".v10Detect") || has(".SCDown") || has(".CIB") -> "YOLOv10"
            has(".RepNCSPELAN4") -> "YOLOv9"
            has(".C3k2") || has(".C2PSA") -> "YOLO11"
            has(".C2f") -> "YOLOv8"
            has(".C3") && kind == PtKind.ULTRALYTICS -> "YOLOv5u"
            has(".C3") -> "YOLOv5"
            else -> ""
        }

        val top = scan.top
        val modelObj: PObj? = if (top is PDict) {
            (top.map["ema"] as? PObj) ?: (top.map["model"] as? PObj)
        } else {
            top as? PObj
        }
        var task = ""
        if (modelObj != null) {
            val c = modelObj.cls
            task = when {
                c.endsWith("SegmentationModel") -> "segment"
                c.endsWith("PoseModel") -> "pose"
                c.endsWith("OBBModel") -> "obb"
                c.endsWith("ClassificationModel") -> "classify"
                c.endsWith("DetectionModel") || c.endsWith("RTDETRDetectionModel") || c.endsWith("WorldModel") -> "detect"
                else -> ""
            }
        }
        val trainArgs = (top as? PDict)?.map?.get("train_args")
        val argTask = (dget(trainArgs, "task") as? String) ?: ""
        if (argTask.isNotEmpty()) task = argTask

        var names = toNames(dget(modelObj, "names"))
        if (names.isEmpty()) names = toNames(dget(top, "names"))
        if (names.isEmpty()) names = toNames(dget(dget(modelObj, "yaml"), "names"))

        var imgsz = firstInt(dget(trainArgs, "imgsz"))
        if (imgsz <= 0) imgsz = firstInt(dget(dget(modelObj, "args"), "imgsz"))
        return PtReport(name, size, kind, family, task, names, imgsz, risks, scan.complete)
    }

    private fun dget(o: Any?, key: String): Any? = when (o) {
        is PDict -> o.map[key]
        is PObj -> dget(o.state, key)
        else -> null
    }

    private fun firstInt(v: Any?): Int = when (v) {
        is Long -> v.toInt()
        is PList -> if (v.items.isNotEmpty()) firstInt(v.items[0]) else 0
        else -> 0
    }

    private fun toNames(v: Any?): List<String> {
        val out = java.util.TreeMap<Int, String>()
        when (v) {
            is PDict -> {
                for ((k, x) in v.map) {
                    if (k is Long && x is String) out[k.toInt()] = x
                }
            }
            is PList -> {
                for (i in v.items.indices) {
                    val x = v.items[i]
                    if (x is String) out[i] = x
                }
            }
            else -> {
            }
        }
        if (out.isEmpty()) return emptyList()
        val n = out.lastKey() + 1
        if (n > 5000) return emptyList()
        return List(n) { k -> out[k] ?: ("class$k") }
    }
}

// ───────────── آلة pickle لا تنفّذ شيئاً ─────────────

internal class PGlobal(val name: String)
internal class PDict { val map = LinkedHashMap<Any?, Any?>() }
internal class PList { val items = ArrayList<Any?>() }
internal class PObj(val cls: String) {
    var args: Any? = null
    var state: Any? = null
}

internal object PickleScan {
    class Result(val globals: Set<String>, val top: Any?, val complete: Boolean)

    fun run(buf: ByteArray): Result {
        val globals = LinkedHashSet<String>()
        val stack = ArrayList<Any?>()
        val marks = ArrayList<Int>()
        val memo = HashMap<Int, Any?>()
        var memoNext = 0
        var p = 0
        var complete = false
        var top: Any? = null
        var ops = 0

        fun need(k: Int) {
            if (k < 0 || p + k > buf.size) throw IllegalStateException("eof")
        }
        fun u8(): Int {
            need(1)
            return buf[p++].toInt() and 0xFF
        }
        fun u16(): Int {
            need(2)
            val v = (buf[p].toInt() and 0xFF) or ((buf[p + 1].toInt() and 0xFF) shl 8)
            p += 2
            return v
        }
        fun i32(): Int {
            need(4)
            val v = (buf[p].toInt() and 0xFF) or ((buf[p + 1].toInt() and 0xFF) shl 8) or
                ((buf[p + 2].toInt() and 0xFF) shl 16) or ((buf[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        fun i64(): Long {
            need(8)
            var v = 0L
            for (k in 7 downTo 0) v = (v shl 8) or (buf[p + k].toLong() and 0xFF)
            p += 8
            return v
        }
        fun str(len: Int): String {
            if (len < 0) throw IllegalStateException("len")
            need(len)
            val s = String(buf, p, len, Charsets.UTF_8)
            p += len
            return s
        }
        fun line(): String {
            var e = p
            while (e < buf.size && buf[e] != 0x0A.toByte()) e++
            if (e >= buf.size) throw IllegalStateException("eof")
            val s = String(buf, p, e - p, Charsets.UTF_8)
            p = e + 1
            return s
        }
        fun push(v: Any?) {
            stack.add(v)
        }
        fun pop(): Any? {
            if (stack.isEmpty()) throw IllegalStateException("underflow")
            return stack.removeAt(stack.size - 1)
        }
        fun popMark(): ArrayList<Any?> {
            if (marks.isEmpty()) throw IllegalStateException("nomark")
            val m = marks.removeAt(marks.size - 1)
            val out = ArrayList<Any?>()
            while (stack.size > m) out.add(stack.removeAt(m))
            return out
        }
        fun mkList(items: List<Any?>): PList {
            val l = PList()
            l.items.addAll(items)
            return l
        }
        fun reduce(callable: Any?, args: Any?): Any? {
            val name = (callable as? PGlobal)?.name ?: "?"
            return when (name) {
                "collections.OrderedDict", "builtins.dict", "__builtin__.dict" -> PDict()
                "builtins.set", "builtins.frozenset", "builtins.list", "builtins.tuple" ->
                    if (args is PList && args.items.size == 1 && args.items[0] is PList) args.items[0] else PList()
                "torch._utils._rebuild_parameter" -> if (args is PList && args.items.isNotEmpty()) args.items[0] else PObj(name)
                else -> {
                    val o = PObj(name)
                    o.args = args
                    o
                }
            }
        }
        fun longOf(n: Int): Long {
            need(n)
            var v = 0L
            if (n in 1..8) {
                for (k in n - 1 downTo 0) v = (v shl 8) or (buf[p + k].toLong() and 0xFF)
                val sign = (buf[p + n - 1].toLong() and 0x80L) != 0L
                if (sign && n < 8) v = v or (-1L shl (8 * n))
            }
            p += n
            return v
        }
        fun putMemo(idx: Int) {
            if (stack.isNotEmpty()) memo[idx] = stack[stack.size - 1]
        }
        fun setItems(target: Any?, flat: List<Any?>) {
            if (target is PDict) {
                var i = 0
                while (i + 1 < flat.size) {
                    target.map[flat[i]] = flat[i + 1]
                    i += 2
                }
            }
        }

        try {
            while (p < buf.size && ops < 8_000_000) {
                ops++
                val op = u8()
                when (op) {
                    0x80 -> u8()                                   // PROTO
                    0x95 -> { need(8); p += 8 }                    // FRAME
                    0x2E -> {                                      // STOP
                        top = if (stack.isNotEmpty()) stack[stack.size - 1] else null
                        complete = true
                        break
                    }
                    0x28 -> marks.add(stack.size)                  // MARK
                    0x30 -> pop()                                  // POP
                    0x31 -> popMark()                              // POP_MARK
                    0x32 -> push(if (stack.isNotEmpty()) stack[stack.size - 1] else null) // DUP
                    0x7D -> push(PDict())                          // EMPTY_DICT
                    0x5D -> push(PList())                          // EMPTY_LIST
                    0x29 -> push(PList())                          // EMPTY_TUPLE
                    0x8F -> push(PList())                          // EMPTY_SET
                    0x4E -> push(null)                             // NONE
                    0x88 -> push(true)                             // NEWTRUE
                    0x89 -> push(false)                            // NEWFALSE
                    0x61 -> { val v = pop(); val t = stack.lastOrNull(); if (t is PList) t.items.add(v) } // APPEND
                    0x65, 0x90 -> {                                // APPENDS, ADDITEMS
                        val items = popMark()
                        val t = stack.lastOrNull()
                        if (t is PList) t.items.addAll(items)
                    }
                    0x73 -> {                                      // SETITEM
                        val v = pop()
                        val k = pop()
                        setItems(stack.lastOrNull(), kotlin.collections.listOf(k, v))
                    }
                    0x75 -> {                                      // SETITEMS
                        val items = popMark()
                        setItems(stack.lastOrNull(), items)
                    }
                    0x74 -> push(mkList(popMark()))               // TUPLE
                    0x85 -> { val a = pop(); push(mkList(kotlin.collections.listOf(a))) }                    // TUPLE1
                    0x86 -> { val b = pop(); val a = pop(); push(mkList(kotlin.collections.listOf(a, b))) }  // TUPLE2
                    0x87 -> { val c = pop(); val b = pop(); val a = pop(); push(mkList(kotlin.collections.listOf(a, b, c))) } // TUPLE3
                    0x6C -> push(mkList(popMark()))               // LIST
                    0x64 -> {                                      // DICT
                        val d = PDict()
                        setItems(d, popMark())
                        push(d)
                    }
                    0x91 -> push(mkList(popMark()))               // FROZENSET
                    0x71 -> putMemo(u8())                          // BINPUT
                    0x72 -> putMemo(i32())                         // LONG_BINPUT
                    0x94 -> { putMemo(memoNext); memoNext++ }      // MEMOIZE
                    0x68 -> push(memo[u8()])                       // BINGET
                    0x6A -> push(memo[i32()])                      // LONG_BINGET
                    0x4B -> push(u8().toLong())                    // BININT1
                    0x4D -> push(u16().toLong())                   // BININT2
                    0x4A -> push(i32().toLong())                   // BININT
                    0x8A -> push(longOf(u8()))                     // LONG1
                    0x8B -> push(longOf(i32()))                    // LONG4
                    0x47 -> {                                      // BINFLOAT (big-endian)
                        need(8)
                        var bits = 0L
                        for (k in 0 until 8) bits = (bits shl 8) or (buf[p + k].toLong() and 0xFF)
                        p += 8
                        push(java.lang.Double.longBitsToDouble(bits))
                    }
                    0x58 -> push(str(i32()))                       // BINUNICODE
                    0x8C -> push(str(u8()))                        // SHORT_BINUNICODE
                    0x8D -> push(str(i64().toInt()))               // BINUNICODE8
                    0x55 -> push(str(u8()))                        // SHORT_BINSTRING
                    0x54 -> push(str(i32()))                       // BINSTRING
                    0x42 -> { val n = i32(); need(n); p += n; push(PObj("bytes")) }        // BINBYTES
                    0x43 -> { val n = u8(); need(n); p += n; push(PObj("bytes")) }         // SHORT_BINBYTES
                    0x8E, 0x96 -> { val n = i64().toInt(); need(n); p += n; push(PObj("bytes")) } // BINBYTES8, BYTEARRAY8
                    0x63 -> {                                      // GLOBAL
                        val m = line()
                        val nm = line()
                        globals.add("$m.$nm")
                        push(PGlobal("$m.$nm"))
                    }
                    0x93 -> {                                      // STACK_GLOBAL
                        val nm = pop() as? String ?: "?"
                        val m = pop() as? String ?: "?"
                        globals.add("$m.$nm")
                        push(PGlobal("$m.$nm"))
                    }
                    0x52 -> { val a = pop(); val c = pop(); push(reduce(c, a)) }   // REDUCE
                    0x81 -> {                                      // NEWOBJ
                        val a = pop()
                        val c = pop()
                        val o = PObj((c as? PGlobal)?.name ?: "?")
                        o.args = a
                        push(o)
                    }
                    0x92 -> {                                      // NEWOBJ_EX
                        pop()
                        val a = pop()
                        val c = pop()
                        val o = PObj((c as? PGlobal)?.name ?: "?")
                        o.args = a
                        push(o)
                    }
                    0x62 -> {                                      // BUILD
                        var st = pop()
                        if (st is PList && st.items.size == 2) {
                            val first = st.items[0]
                            st = if (first is PDict) first else st.items[1]
                        }
                        val t = stack.lastOrNull()
                        if (t is PObj) {
                            val old = t.state
                            if (old is PDict && st is PDict) old.map.putAll(st.map) else t.state = st
                        }
                    }
                    0x6F -> {                                      // OBJ
                        val items = popMark()
                        val c = items.firstOrNull()
                        push(PObj((c as? PGlobal)?.name ?: "?"))
                    }
                    0x69 -> {                                      // INST
                        val m = line()
                        val nm = line()
                        globals.add("$m.$nm")
                        popMark()
                        push(PObj("$m.$nm"))
                    }
                    0x51 -> { pop(); push(PObj("<storage>")) }     // BINPERSID
                    else -> throw IllegalStateException("op " + op)
                }
            }
        } catch (t: Throwable) {
            complete = false
        }
        return Result(globals, top, complete)
    }
}
