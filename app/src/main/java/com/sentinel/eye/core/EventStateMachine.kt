package com.sentinel.eye.core

/**
 * آلة حالة الأحداث: تؤكد الظهور بعدد إطارات متتالية، وتنهي الحدث بعد مهلة غياب،
 * وتقسم الأحداث الطويلة.
 */
class EventStateMachine(
    @Volatile var confirmFrames: Int,
    @Volatile var postRollMs: Long,
    @Volatile var maxEventMs: Long
) {
    enum class Action { NONE, START, STOP, ROTATE }

    var active: Boolean = false
        private set
    private var positives = 0
    private var startMs = 0L
    private var lastSeenMs = 0L

    /** present: true/false عند وجود نتيجة كشف جديدة، أو null لمجرد فحص المهل. */
    @Synchronized
    fun update(nowMs: Long, present: Boolean?): Action {
        if (!active) {
            if (present == true) {
                positives++
                if (positives >= confirmFrames) {
                    active = true
                    positives = 0
                    startMs = nowMs
                    lastSeenMs = nowMs
                    return Action.START
                }
            } else if (present == false) {
                positives = 0
            }
            return Action.NONE
        }
        if (present == true) lastSeenMs = nowMs
        if (nowMs - lastSeenMs >= postRollMs) {
            active = false
            positives = 0
            return Action.STOP
        }
        if (nowMs - startMs >= maxEventMs) {
            startMs = nowMs
            return Action.ROTATE
        }
        return Action.NONE
    }

    @Synchronized
    fun forceStop() { active = false; positives = 0 }
}
