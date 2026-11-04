package com.sentinel.eye.detect

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.sentinel.eye.core.ImageUtil
import java.util.concurrent.TimeUnit

/**
 * ML Kit · الوضعية: يؤكد وجود جسم بشري عبر نقاط الهيكل. شخص واحد في الإطار، ويحتاج ظهوراً واضحاً.
 * النموذج مدمج في التطبيق فلا يحتاج تنزيلاً.
 */
class MlKitPoseDetector : Detector {
    private val client = PoseDetection.getClient(
        PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE).build()
    )
    override val name: String = "ML Kit · الوضعية"

    override fun detect(frame: Frame, minScore: Float, allowed: IntArray): List<Detection> {
        if (!allowed.contains(0)) return emptyList()
        val bmp = ImageUtil.bitmapFromArgb(frame.argb, frame.width, frame.height)
        try {
            val pose = Tasks.await(client.process(InputImage.fromBitmap(bmp, 0)), 8, TimeUnit.SECONDS)
            val lms = pose.allPoseLandmarks.filter { it.inFrameLikelihood >= 0.5f }
            if (lms.size < 6) return emptyList()
            var score = 0f
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = 0f
            var maxY = 0f
            for (l in lms) {
                score += l.inFrameLikelihood
                val p = l.position
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
            score /= lms.size
            if (score < minScore) return emptyList()
            val w = frame.width.toFloat()
            val h = frame.height.toFloat()
            val padX = (maxX - minX) * 0.12f
            val padY = (maxY - minY) * 0.12f
            val l = ((minX - padX) / w).coerceIn(0f, 1f)
            val t = ((minY - padY) / h).coerceIn(0f, 1f)
            val r = ((maxX + padX) / w).coerceIn(0f, 1f)
            val b = ((maxY + padY) / h).coerceIn(0f, 1f)
            if (r - l <= 0.01f || b - t <= 0.01f) return emptyList()
            return listOf(Detection(0, "person", score, l, t, r, b))
        } finally {
            bmp.recycle()
        }
    }

    override fun close() {
        try { client.close() } catch (_: Throwable) { }
    }
}

/** ML Kit · الوجه: أخف الخيارات، يكتشف الوجوه الظاهرة فقط (صندوق الوجه). */
class MlKitFaceDetector : Detector {
    private val client = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.06f)
            .build()
    )
    override val name: String = "ML Kit · الوجه"

    override fun detect(frame: Frame, minScore: Float, allowed: IntArray): List<Detection> {
        if (!allowed.contains(0)) return emptyList()
        val bmp = ImageUtil.bitmapFromArgb(frame.argb, frame.width, frame.height)
        try {
            val faces = Tasks.await(client.process(InputImage.fromBitmap(bmp, 0)), 8, TimeUnit.SECONDS)
            val w = frame.width.toFloat()
            val h = frame.height.toFloat()
            val out = ArrayList<Detection>()
            for (f in faces) {
                val bb = f.boundingBox
                val l = (bb.left / w).coerceIn(0f, 1f)
                val t = (bb.top / h).coerceIn(0f, 1f)
                val r = (bb.right / w).coerceIn(0f, 1f)
                val b = (bb.bottom / h).coerceIn(0f, 1f)
                if (r - l > 0.005f && b - t > 0.005f) out.add(Detection(0, "person", 0.9f, l, t, r, b))
            }
            return out
        } finally {
            bmp.recycle()
        }
    }

    override fun close() {
        try { client.close() } catch (_: Throwable) { }
    }
}
