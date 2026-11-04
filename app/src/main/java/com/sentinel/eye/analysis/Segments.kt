package com.sentinel.eye.analysis

/** مقطع بالثواني: [startSec, endSec). */
data class Segment(val startSec: Int, val endSec: Int) {
    val length: Int get() = endSec - startSec
}

object Segments {
    /**
     * يحوّل ثواني الإصابة إلى مقاطع: يوسّع كل إصابة بـ [pad] ثانية من الجهتين،
     * ثم يدمج المقاطع التي تفصلها فجوة لا تزيد عن [gap] ثانية.
     */
    fun build(hits: BooleanArray, durationSec: Int, pad: Int, gap: Int): List<Segment> {
        val n = minOf(durationSec, hits.size)
        if (n <= 0) return emptyList()
        val mark = BooleanArray(n)
        for (s in 0 until n) {
            if (!hits[s]) continue
            for (k in (s - pad)..(s + pad)) if (k in 0 until n) mark[k] = true
        }
        val out = ArrayList<Segment>()
        var s = 0
        while (s < n) {
            if (!mark[s]) { s++; continue }
            var last = s
            var i = s
            while (i < n) {
                if (mark[i]) {
                    last = i
                    i++
                } else {
                    var j = i
                    while (j < n && !mark[j]) j++
                    if (j < n && (j - i) <= gap) i = j else break
                }
            }
            out.add(Segment(s, last + 1))
            s = last + 1
        }
        return out
    }
}

/**
 * قرار أخذ العيّنات داخل كل ثانية من الفيديو:
 * أول إطار، والإطار الأوسط (منتصف عدد الإطارات)، وآخر إطار.
 * ولأن آخر إطار لا يُعرف إلا بعد انتهاء الثانية، تُحفَظ إطارات الذيل كمرشّحين ويُحلَّل آخرهم.
 * إن وُجد كائن في أي إطار تُحسم الثانية كاملة ويُتخطّى ما تبقّى منها.
 */
class SecondSampler(private val fps: Int, private val three: Boolean) {
    enum class Act { SKIP, ANALYZE, TAIL }

    var curSec: Int = -1
        private set
    var decided: Boolean = false
    private var idx = 0
    private var midDone = false

    fun wouldChange(relUs: Long): Boolean = (relUs / 1_000_000L).toInt() != curSec

    fun onFrame(relUs: Long): Act {
        val s = (relUs / 1_000_000L).toInt()
        val off = relUs - s * 1_000_000L
        if (s != curSec) {
            curSec = s
            idx = 0
            midDone = false
            decided = false
        }
        val i = idx++
        if (decided) return Act.SKIP
        if (i == 0) return Act.ANALYZE
        if (!three) return Act.SKIP
        val midIdx = fps / 2
        if (!midDone && (i >= midIdx || off >= 500_000L)) {
            midDone = true
            return Act.ANALYZE
        }
        if (midDone && (i >= fps - 3 || off >= 800_000L)) return Act.TAIL
        return Act.SKIP
    }
}
