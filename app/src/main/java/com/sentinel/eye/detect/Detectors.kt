package com.sentinel.eye.detect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.sentinel.eye.core.AppLog
import com.sentinel.eye.core.Config
import com.sentinel.eye.core.ImageUtil
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * كاشف YOLO عبر ONNX Runtime مع تراجع تلقائي إلى CPU عند فشل المسرّع.
 * يدعم عائلات YOLOv5/v8/v9/v10/11/12/26 وأصنافاً مخصّصة، بقراءة البيانات الوصفية المضمّنة في ملف ONNX
 * (أو [metaOverride] المحفوظة عند الاستيراد إن لم تكن مضمّنة).
 */
class YoloOnnxDetector(
    modelFile: File, requested: Accel, threads: Int, fallbackSize: Int,
    metaOverride: YoloMeta? = null
) : Detector {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val inW: Int
    private val inH: Int
    val activeAccel: Accel
    override val name: String
    private val inputArray: FloatArray
    private val inputBuf: FloatBuffer
    private val meta: YoloMeta
    private val cocoMode: Boolean
    private val classBase: Int
    private var maskKey = Int.MIN_VALUE
    private var maskVal: BooleanArray = BooleanArray(0)

    init {
        var s: OrtSession? = null
        var used = requested
        var firstError: Throwable? = null
        for (a in listOf(requested, Accel.CPU).distinct()) {
            try {
                s = create(modelFile.absolutePath, a, threads)
                used = a
                break
            } catch (t: Throwable) {
                if (firstError == null) firstError = t
                AppLog.w("Yolo", "تعذّر إنشاء الجلسة بالمسرّع ${a.title}", t)
            }
        }
        session = s ?: throw (firstError ?: IllegalStateException("تعذّر إنشاء جلسة ONNX"))
        activeAccel = used
        inputName = session.inputNames.iterator().next()
        val md: Map<String, String> = try {
            session.metadata.customMetadata
        } catch (t: Throwable) {
            emptyMap()
        }
        var m = YoloMeta.from(md)
        if (m.names.isEmpty() && metaOverride != null) {
            m = YoloMeta(
                metaOverride.names,
                if (m.task.isNotEmpty()) m.task else metaOverride.task,
                if (m.imgsz > 0) m.imgsz else metaOverride.imgsz,
                m.end2end || metaOverride.end2end
            )
        }
        meta = m
        cocoMode = m.names.isEmpty() || m.isCoco
        classBase = if (cocoMode) 0 else Coco.registerCustom(m.names)
        val dyn = if (m.imgsz > 0) m.imgsz else fallbackSize
        val shape = (session.inputInfo[inputName]?.info as? TensorInfo)?.shape
        inH = if (shape != null && shape.size == 4 && shape[2] > 0) shape[2].toInt() else dyn
        inW = if (shape != null && shape.size == 4 && shape[3] > 0) shape[3].toInt() else dyn
        inputArray = FloatArray(3 * inW * inH)
        inputBuf = ByteBuffer.allocateDirect(inputArray.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        name = "YOLO · ${modelFile.nameWithoutExtension} · ${used.title}"
    }

    private fun create(path: String, accel: Accel, threads: Int): OrtSession {
        val o = OrtSession.SessionOptions()
        try {
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            when (accel) {
                Accel.CPU -> o.setIntraOpNumThreads(threads)
                Accel.XNNPACK -> {
                    o.setIntraOpNumThreads(1)
                    o.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                }
                Accel.GPU_NNAPI -> {
                    o.setIntraOpNumThreads(threads)
                    o.addNnapi()
                }
            }
            return env.createSession(path, o)
        } finally {
            o.close()
        }
    }

    override fun detect(frame: Frame, minScore: Float, allowed: IntArray): List<Detection> {
        val lb = Letterbox(frame.width, frame.height, inW, inH)
        YoloMath.preprocess(frame.argb, frame.width, frame.height, lb, inputArray)
        inputBuf.clear()
        inputBuf.put(inputArray)
        inputBuf.flip()
        val tensor = OnnxTensor.createTensor(env, inputBuf, longArrayOf(1, 3, inH.toLong(), inW.toLong()))
        try {
            session.run(mapOf(inputName to tensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val shape = out.info.shape
                if (shape.size != 3) return emptyList()
                val a = shape[1].toInt()
                val b = shape[2].toInt()
                val fb = out.floatBuffer
                val arr: FloatArray = if (fb.hasArray() && fb.arrayOffset() == 0) {
                    fb.array()
                } else {
                    val tmp = FloatArray(fb.remaining())
                    fb.get(tmp)
                    tmp
                }
                val channelsFirst = a < b
                val channels = if (channelsFirst) a else b
                val anchors = if (channelsFirst) b else a
                if (arr.size < channels * anchors) return emptyList()
                val e2e = YoloDecodeEx.isEnd2End(a, b, meta)
                val v5 = YoloDecodeEx.isV5(a, b, meta)
                // المسار الأصلي الأسرع لنماذج COCO القياسية (v8/11/12)؛ والعام لكل ما سواها.
                if (cocoMode && !e2e && !v5) {
                    return YoloMath.decode(arr, channels, anchors, channelsFirst, lb, allowed, minScore)
                }
                val mask: BooleanArray? = if (cocoMode) null else maskFor(allowed)
                return YoloDecodeEx.decode(arr, a, b, meta, lb, allowed, mask, classBase, minScore)
            }
        } finally {
            tensor.close()
        }
    }

    private fun maskFor(allowed: IntArray): BooleanArray {
        val k = allowed.contentHashCode()
        if (k != maskKey || maskVal.size != meta.nc) {
            maskVal = ClassFilter.mask(meta.names, allowed)
            maskKey = k
        }
        return maskVal
    }

    override fun close() {
        try { session.close() } catch (_: Throwable) { }
    }
}

/** كاشف MediaPipe Object Detector (EfficientDet-Lite). */
class MediaPipeDetector(context: Context, modelFile: File, useGpu: Boolean) : Detector {
    private val detector: ObjectDetector
    override val name: String

    init {
        val len = modelFile.length().toInt()
        val buf = ByteBuffer.allocateDirect(len).order(ByteOrder.nativeOrder())
        FileInputStream(modelFile).channel.use { ch ->
            while (buf.hasRemaining()) {
                if (ch.read(buf) < 0) break
            }
        }
        var created: ObjectDetector? = null
        var used = "CPU"
        var err: Throwable? = null
        val delegates = if (useGpu) listOf(Delegate.GPU, Delegate.CPU) else listOf(Delegate.CPU)
        for (d in delegates) {
            try {
                buf.rewind()
                val base = BaseOptions.builder().setModelAssetBuffer(buf).setDelegate(d).build()
                val opts = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.IMAGE)
                    .setScoreThreshold(0.25f)
                    .setMaxResults(25)
                    .build()
                created = ObjectDetector.createFromOptions(context, opts)
                used = d.name
                break
            } catch (t: Throwable) {
                err = t
                AppLog.w("MediaPipe", "تعذّر الإنشاء بالمفوّض ${d.name}", t)
            }
        }
        detector = created ?: throw (err ?: IllegalStateException("تعذّر إنشاء كاشف MediaPipe"))
        name = "MediaPipe · ${modelFile.nameWithoutExtension} · $used"
    }

    override fun detect(frame: Frame, minScore: Float, allowed: IntArray): List<Detection> {
        val bmp = ImageUtil.bitmapFromArgb(frame.argb, frame.width, frame.height)
        val mp = BitmapImageBuilder(bmp).build()
        try {
            val res = detector.detect(mp)
            val out = ArrayList<Detection>()
            val w = frame.width.toFloat()
            val h = frame.height.toFloat()
            for (d in res.detections()) {
                val cat = d.categories().firstOrNull() ?: continue
                val id = Coco.idOf(cat.categoryName())
                if (id < 0 || !allowed.contains(id) || cat.score() < minScore) continue
                val bb = d.boundingBox()
                out.add(
                    Detection(
                        id, Coco.nameOf(id), cat.score(),
                        (bb.left / w).coerceIn(0f, 1f), (bb.top / h).coerceIn(0f, 1f),
                        (bb.right / w).coerceIn(0f, 1f), (bb.bottom / h).coerceIn(0f, 1f)
                    )
                )
            }
            return out
        } finally {
            mp.close()
            bmp.recycle()
        }
    }

    override fun close() {
        try { detector.close() } catch (_: Throwable) { }
    }
}

object DetectorFactory {
    fun create(ctx: Context, spec: ModelSpec, file: File, cfg: Config): Detector = when (spec.engine) {
        EngineType.YOLO -> YoloOnnxDetector(file, Accel.of(cfg.accel), cfg.threads, spec.inputSize, CustomModels.metaOf(spec.id))
        EngineType.MEDIAPIPE -> MediaPipeDetector(ctx, file, Accel.of(cfg.accel) == Accel.GPU_NNAPI)
        EngineType.MLKIT -> if (spec.id == "mlkit-face") MlKitFaceDetector() else MlKitPoseDetector()
    }
}
