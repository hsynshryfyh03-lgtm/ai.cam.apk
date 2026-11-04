package com.sentinel.eye.core

/** عينة فيديو مضغوطة. */
class EncodedSample(val data: ByteArray, val ptsUs: Long, val isKey: Boolean)

/**
 * ذاكرة دائرية للعينات المضغوطة. تبدأ دائماً بإطار مفتاحي، وتغطي على الأقل [windowUs]
 * قبل أحدث عينة (متى توفّر ذلك).
 */
class SampleRing(@Volatile var windowUs: Long) {
    private val q = ArrayDeque<EncodedSample>()
    private var bytes = 0L

    @Synchronized
    fun add(s: EncodedSample) {
        if (q.isEmpty() && !s.isKey) return
        q.addLast(s)
        bytes += s.data.size
        val newest = s.ptsUs
        while (true) {
            var second = -1
            for (i in 1 until q.size) {
                if (q[i].isKey) { second = i; break }
            }
            if (second == -1) break
            if (newest - q[second].ptsUs >= windowUs) {
                repeat(second) { bytes -= q.removeFirst().data.size }
            } else break
        }
    }

    @Synchronized
    fun snapshot(): List<EncodedSample> = ArrayList(q)

    @Synchronized
    fun clear() { q.clear(); bytes = 0 }

    @Synchronized
    fun sizeBytes(): Long = bytes

    @Synchronized
    fun spanUs(): Long = if (q.size < 2) 0 else q.last().ptsUs - q.first().ptsUs
}
