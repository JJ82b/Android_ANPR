package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.util.Log
import com.hyperai.hyperlpr3.HyperLPR3
import com.hyperai.hyperlpr3.bean.HyperLPRParameter
import java.util.concurrent.atomic.AtomicBoolean

class PlateRecognizer(context: Context) {

    private val inferExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    // 提供给网页api的变量
    @Volatile
    var latestPlate: String = ""
        private set

    @Volatile
    var latestConfidence: Float = 0.0f
        private set

    @Volatile
    var latestTimestamp: Long = 0
        private set
    
    private val isProcessing = AtomicBoolean(false)
    private var lastRunTimeMs = 0L
    // 车牌检测间隔
    private val intervalMs = 200L // 200ms (受 isProcessing 保护，不会堆积)

    companion object {
        private const val MIN_REC_CONFIDENCE = 0.65f
    }

    init {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val param = HyperLPRParameter()
            .setDetLevel(HyperLPR3.DETECT_LEVEL_HIGH)
            .setMaxNum(3)
            .setBoxConfThreshold(0.20f)
            .setRecConfidenceThreshold(MIN_REC_CONFIDENCE)
            .setThreads(threads) // cpu线程数

        HyperLPR3.getInstance().init(context.applicationContext, param)
    }

    /**
     * 原方案：直接从ImageProxy接受YUV数据进行车牌识别
     * 现：接受NV21数据进行车牌识别，避免了重复转换
     */

    fun processNv21Async(
        nv21Source: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        onDetected: ((String, Float) -> Unit)? = null
    ) {
        val now = System.currentTimeMillis()
        // 節流閥：時間未到或上一幀還在算，直接跳過，不影響串流幀率
        if (now - lastRunTimeMs < intervalMs || !isProcessing.compareAndSet(false, true)) {
            return
        }
        lastRunTimeMs = now

        // 複製一份快照給背景執行緒使用（避免與下一幀的 nv21Buffer 記憶體覆蓋衝突）
        val frameCopy = nv21Source.copyOf()

        inferExecutor.execute {
            try {
                val rotation = when (rotationDegrees) {
                    90 -> HyperLPR3.CAMERA_ROTATION_90
                    180 -> HyperLPR3.CAMERA_ROTATION_180
                    270 -> HyperLPR3.CAMERA_ROTATION_270
                    else -> HyperLPR3.CAMERA_ROTATION_0
                }

                val plates = HyperLPR3.getInstance().plateRecognition(
                    frameCopy,
                    height,
                    width,
                    rotation,
                    HyperLPR3.STREAM_YUV_NV21
                )

                if (!plates.isNullOrEmpty()) {
                    val best = plates.maxByOrNull { it.confidence }
                    if (best != null && best.confidence >= MIN_REC_CONFIDENCE && best.code.isNotBlank()) {
                        latestPlate = best.code
                        latestConfidence = best.confidence
                        latestTimestamp = System.currentTimeMillis()
                        Log.i("ALPR", "识别到车牌: ${best.code} (${String.format("%.1f", best.confidence * 100)}%)")
                        onDetected?.invoke(best.code, best.confidence)
                    }
                }
            } catch (e: Exception) {
                Log.e("ALPR", "识别异常: ${e.message}")
            } finally {
                isProcessing.set(false)
            }
        }
    }
}