package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.hyperai.hyperlpr3.HyperLPR3
import com.hyperai.hyperlpr3.bean.HyperLPRParameter
import com.hyperai.hyperlpr3.core.HyperLPRCore
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class DetectedPlate(
    val code: String,
    val confidence: Float,
    val type: Int,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
)

data class AlprConfig(
    val detLevel: Int,
    val maxNum: Int,
    val boxConfThreshold: Float,
    val recConfidenceThreshold: Float,
    val threads: Int,
    val intervalMs: Long = 200L
) {
    fun toJson(): String = String.format(
        Locale.US,
        """{"detLevel":%d,"maxNum":%d,"boxConfThreshold":%.2f,"recConfidenceThreshold":%.2f,"threads":%d,"intervalMs":%d}""",
        detLevel,
        maxNum,
        boxConfThreshold,
        recConfidenceThreshold,
        threads,
        intervalMs
    )
}

class PlateRecognizer(context: Context) {

    private val appContext = context.applicationContext

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

    @Volatile
    var latestPlateType: Int = HyperLPR3.PLATE_TYPE_UNKNOWN
        private set

    @Volatile
    var latestPlates: List<DetectedPlate> = emptyList()
        private set

    @Volatile
    var latestFrameWidth: Int = 0
        private set

    @Volatile
    var latestFrameHeight: Int = 0
        private set

    @Volatile
    var latestProcessTimeMs: Long = 0
        private set
    
    private val isProcessing = AtomicBoolean(false)
    private var lastRunTimeMs = 0L

    companion object {
        private const val PREF_DET_LEVEL = "alpr_det_level"
        private const val PREF_MAX_NUM = "alpr_max_num"
        private const val PREF_BOX_CONF = "alpr_box_conf_threshold"
        private const val PREF_REC_CONF = "alpr_rec_conf_threshold"
        private const val PREF_THREADS = "alpr_threads"
        private const val PREF_INTERVAL_MS = "alpr_interval_ms"

        private const val DEFAULT_DET_LEVEL = HyperLPR3.DETECT_LEVEL_HIGH
        private const val DEFAULT_MAX_NUM = 3
        private const val DEFAULT_BOX_CONF = 0.20f
        private const val DEFAULT_REC_CONF = 0.65f
        private const val DEFAULT_INTERVAL_MS = 200L

        // 共享单线程推理/生命周期执行器，避免 CameraXCapture 重建时重复初始化或并发访问 native 上下文
        private val inferExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        @Volatile private var sdkInitialized = false
        @Volatile private var cachedModelPath: String? = null
        private var customCore: HyperLPRCore? = null
        @Volatile private var currentRecThreshold: Float = DEFAULT_REC_CONF
        @Volatile private var currentIntervalMs: Long = DEFAULT_INTERVAL_MS

        private fun defaultThreads(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

        fun loadConfig(context: Context): AlprConfig {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val detLevel = prefs.getInt(PREF_DET_LEVEL, DEFAULT_DET_LEVEL)
                .let { if (it == HyperLPR3.DETECT_LEVEL_LOW) HyperLPR3.DETECT_LEVEL_LOW else HyperLPR3.DETECT_LEVEL_HIGH }
            val maxNum = prefs.getInt(PREF_MAX_NUM, DEFAULT_MAX_NUM).coerceIn(1, 10)
            val boxConf = prefs.getFloat(PREF_BOX_CONF, DEFAULT_BOX_CONF).coerceIn(0.05f, 0.95f)
            val recConf = prefs.getFloat(PREF_REC_CONF, DEFAULT_REC_CONF).coerceIn(0.30f, 0.99f)
            val threads = prefs.getInt(PREF_THREADS, defaultThreads()).coerceIn(1, 8)
            val intervalMs = prefs.getLong(PREF_INTERVAL_MS, DEFAULT_INTERVAL_MS).coerceIn(50L, 2000L)
            return AlprConfig(detLevel, maxNum, boxConf, recConf, threads, intervalMs)
        }

        fun saveConfig(context: Context, config: AlprConfig): AlprConfig {
            val sanitized = AlprConfig(
                detLevel = if (config.detLevel == HyperLPR3.DETECT_LEVEL_LOW) HyperLPR3.DETECT_LEVEL_LOW else HyperLPR3.DETECT_LEVEL_HIGH,
                maxNum = config.maxNum.coerceIn(1, 10),
                boxConfThreshold = config.boxConfThreshold.coerceIn(0.05f, 0.95f),
                recConfidenceThreshold = config.recConfidenceThreshold.coerceIn(0.30f, 0.99f),
                threads = config.threads.coerceIn(1, 8),
                intervalMs = config.intervalMs.coerceIn(50L, 2000L)
            )
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt(PREF_DET_LEVEL, sanitized.detLevel)
                .putInt(PREF_MAX_NUM, sanitized.maxNum)
                .putFloat(PREF_BOX_CONF, sanitized.boxConfThreshold)
                .putFloat(PREF_REC_CONF, sanitized.recConfidenceThreshold)
                .putInt(PREF_THREADS, sanitized.threads)
                .putLong(PREF_INTERVAL_MS, sanitized.intervalMs)
                .apply()
            return sanitized
        }

        private fun buildParameter(config: AlprConfig, modelPath: String?): HyperLPRParameter {
            val param = HyperLPRParameter()
                .setDetLevel(config.detLevel)
                .setMaxNum(config.maxNum)
                .setBoxConfThreshold(config.boxConfThreshold)
                .setRecConfidenceThreshold(config.recConfidenceThreshold)
                .setThreads(config.threads)
            if (!modelPath.isNullOrBlank()) {
                param.setModelPath(modelPath)
            }
            return param
        }

        private fun ensureInitialized(appContext: Context, config: AlprConfig) {
            currentRecThreshold = config.recConfidenceThreshold
            currentIntervalMs = config.intervalMs
            if (sdkInitialized) return
            try {
                val param = buildParameter(config, null)
                HyperLPR3.getInstance().init(appContext, param)
                cachedModelPath = param.modelPath
                    ?: appContext.getExternalFilesDir(null)?.let { "${it.absolutePath}/" }
                sdkInitialized = true
                Log.i("ALPR", "HyperLPR3 初始化完成: $config, modelPath=$cachedModelPath")
            } catch (e: Exception) {
                Log.e("ALPR", "初始化 HyperLPR3 失败: ${e.message}")
            }
        }
    }

    init {
        val initialConfig = loadConfig(appContext)
        inferExecutor.execute {
            ensureInitialized(appContext, initialConfig)
        }
    }

    fun reloadConfig(config: AlprConfig = loadConfig(appContext)) {
        val saved = saveConfig(appContext, config)
        currentRecThreshold = saved.recConfidenceThreshold
        currentIntervalMs = saved.intervalMs
        inferExecutor.execute {
            try {
                if (!sdkInitialized) {
                    ensureInitialized(appContext, saved)
                    return@execute
                }
                val modelDir = cachedModelPath
                    ?: appContext.getExternalFilesDir(null)?.let { "${it.absolutePath}/" }
                if (modelDir.isNullOrBlank()) {
                    Log.e("ALPR", "重载失败：modelPath 为空")
                    return@execute
                }
                if (customCore != null) {
                    customCore?.release()
                } else {
                    HyperLPR3.getInstance().release()
                }
                customCore = HyperLPRCore().apply {
                    createRecognizerContext(buildParameter(saved, modelDir))
                }
                Log.i("ALPR", "HyperLPR3 参数已热重载: $saved")
            } catch (e: Exception) {
                Log.e("ALPR", "重载 HyperLPR3 失败: ${e.message}")
            }
        }
    }

    /**
     * 原方案：直接从ImageProxy接受YUV数据进行车牌识别
     * 现：接受NV21数据进行车牌识别，避免了重复转换
     */

    fun canProcessNow(): Boolean =
        !isProcessing.get() && (System.currentTimeMillis() - lastRunTimeMs >= currentIntervalMs)

    fun processNv21Async(
        nv21Source: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        onDetected: ((String, Float) -> Unit)? = null
    ) {
        val now = System.currentTimeMillis()
        // 節流閥：時間未到或上一幀還在算，直接跳過，不影響串流幀率
        if (now - lastRunTimeMs < currentIntervalMs || !isProcessing.compareAndSet(false, true)) {
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

                if (!sdkInitialized) return@execute
                val startNs = System.nanoTime()
                val activeCore = customCore
                val plates = if (activeCore != null) {
                    activeCore.plateRecognitionFromBuffer(
                        frameCopy,
                        height,
                        width,
                        rotation,
                        HyperLPR3.STREAM_YUV_NV21
                    )
                } else {
                    HyperLPR3.getInstance().plateRecognition(
                        frameCopy,
                        height,
                        width,
                        rotation,
                        HyperLPR3.STREAM_YUV_NV21
                    )
                }
                latestProcessTimeMs = (System.nanoTime() - startNs) / 1_000_000L

                val minRecConf = currentRecThreshold
                val rotatedW = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
                val rotatedH = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
                val detected = (plates ?: emptyArray())
                    .filter { it != null && it.confidence >= minRecConf && it.code.isNotBlank() }
                    .sortedByDescending { it.confidence }
                    .map {
                        DetectedPlate(
                            code = it.code,
                            confidence = it.confidence,
                            type = it.type,
                            x1 = it.x1,
                            y1 = it.y1,
                            x2 = it.x2,
                            y2 = it.y2
                        )
                    }
                latestFrameWidth = rotatedW
                latestFrameHeight = rotatedH
                latestPlates = detected

                if (detected.isNotEmpty()) {
                    val best = detected.first()
                    latestPlate = best.code
                    latestConfidence = best.confidence
                    latestTimestamp = System.currentTimeMillis()
                    latestPlateType = best.type
                    Log.i("ALPR", "识别到车牌(${detected.size}): ${best.code} type=${best.type} (${String.format("%.1f", best.confidence * 100)}%)")
                    onDetected?.invoke(best.code, best.confidence)
                }
            } catch (e: Exception) {
                Log.e("ALPR", "识别异常: ${e.message}")
            } finally {
                isProcessing.set(false)
            }
        }
    }
}