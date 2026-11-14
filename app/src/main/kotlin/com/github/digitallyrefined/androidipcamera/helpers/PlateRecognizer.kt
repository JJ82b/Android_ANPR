package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.hyperai.hyperlpr3.HyperLPR3
import com.hyperai.hyperlpr3.bean.HyperLPRParameter
import com.hyperai.hyperlpr3.core.HyperLPRCore
import com.hyperai.hyperlpr3.core.HyperLPRGpuCore
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
    val intervalMs: Long = 200L,
    val useGpu: Boolean = false,
    val motionGateEnabled: Boolean = true,
    val roiEnabled: Boolean = false,
    val roiX: Float = 0.0f,
    val roiY: Float = 0.25f,
    val roiW: Float = 1.0f,
    val roiH: Float = 0.75f,
    val motionCropEnabled: Boolean = false,
    val motionTorchEnabled: Boolean = false
) {
    fun toJson(): String = String.format(
        Locale.US,
        """{"detLevel":%d,"maxNum":%d,"boxConfThreshold":%.2f,"recConfidenceThreshold":%.2f,"threads":%d,"intervalMs":%d,"useGpu":%b,"gpuAvailable":%b,"motionGateEnabled":%b,"roiEnabled":%b,"roiX":%.2f,"roiY":%.2f,"roiW":%.2f,"roiH":%.2f,"motionCropEnabled":%b,"motionTorchEnabled":%b}""",
        detLevel,
        maxNum,
        boxConfThreshold,
        recConfidenceThreshold,
        threads,
        intervalMs,
        useGpu && HyperLPRGpuCore.isAvailable(),
        HyperLPRGpuCore.isAvailable(),
        motionGateEnabled,
        roiEnabled,
        roiX,
        roiY,
        roiW,
        roiH,
        motionCropEnabled,
        motionTorchEnabled
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

    data class MotionDebugInfo(
        val hasMotion: Boolean,
        val motionCropActive: Boolean,
        val clusterX1: Float,
        val clusterY1: Float,
        val clusterX2: Float,
        val clusterY2: Float,
        val cropX1: Float,
        val cropY1: Float,
        val cropX2: Float,
        val cropY2: Float,
        val cropW: Int,
        val cropH: Int,
        val frameW: Int,
        val frameH: Int,
        val timestampMs: Long
    )

    @Volatile
    var latestProcessTimeMs: Long = 0
        private set

    @Volatile
    var latestMotionDebug: MotionDebugInfo? = null
        private set
    
    private val isProcessing = AtomicBoolean(false)
    private var lastRunTimeMs = 0L
    private val snapshotBuffers = arrayOfNulls<ByteArray>(2)
    private var snapshotIndex = 0
    private val lumaBg = FloatArray(LUMA_GRID_W * LUMA_GRID_H)
    private val lumaCur = FloatArray(LUMA_GRID_W * LUMA_GRID_H)
    private val changedGrid = BooleanArray(LUMA_GRID_W * LUMA_GRID_H)
    private val visitedGrid = BooleanArray(LUMA_GRID_W * LUMA_GRID_H)
    private val bfsQueue = IntArray(LUMA_GRID_W * LUMA_GRID_H)
    private var hasLumaGrid = false
    private var lastGridKey = ""
    private var consecutiveStaticChecks = 0
    @Volatile private var effectiveIntervalMs = DEFAULT_INTERVAL_MS
    @Volatile private var autoTorchActive = false
    private var lastMotionForTorchMs = 0L
    @Volatile var onMotionTorchChanged: ((Boolean) -> Unit)? = null

    companion object {
        private const val PREF_DET_LEVEL = "alpr_det_level"
        private const val PREF_MAX_NUM = "alpr_max_num"
        private const val PREF_BOX_CONF = "alpr_box_conf_threshold"
        private const val PREF_REC_CONF = "alpr_rec_conf_threshold"
        private const val PREF_THREADS = "alpr_threads"
        private const val PREF_INTERVAL_MS = "alpr_interval_ms"
        private const val PREF_USE_GPU = "alpr_use_gpu"
        private const val PREF_MOTION_GATE = "alpr_motion_gate"
        private const val PREF_ROI_ENABLED = "alpr_roi_enabled"
        private const val PREF_ROI_X = "alpr_roi_x"
        private const val PREF_ROI_Y = "alpr_roi_y"
        private const val PREF_ROI_W = "alpr_roi_w"
        private const val PREF_ROI_H = "alpr_roi_h"
        private const val PREF_MOTION_CROP = "alpr_motion_crop_enabled"
        private const val PREF_MOTION_TORCH = "alpr_motion_torch_enabled"

        private const val DEFAULT_DET_LEVEL = HyperLPR3.DETECT_LEVEL_HIGH
        private const val DEFAULT_MAX_NUM = 3
        private const val DEFAULT_BOX_CONF = 0.20f
        private const val DEFAULT_REC_CONF = 0.65f
        private const val DEFAULT_INTERVAL_MS = 200L
        private const val DEFAULT_USE_GPU = false
        private const val DEFAULT_MOTION_GATE = true
        private const val DEFAULT_ROI_ENABLED = false
        private const val DEFAULT_ROI_X = 0.0f
        private const val DEFAULT_ROI_Y = 0.25f
        private const val DEFAULT_ROI_W = 1.0f
        private const val DEFAULT_ROI_H = 0.75f
        private const val DEFAULT_MOTION_CROP = false
        private const val DEFAULT_MOTION_TORCH = false
        private const val AUTO_TORCH_HOLD_MS = 5000L

        private const val LUMA_GRID_W = 32
        private const val LUMA_GRID_H = 18
        private const val LUMA_PIXEL_DIFF_THRESHOLD = 14.0f
        private const val LUMA_CHANGED_RATIO_THRESHOLD = 0.03f
        private const val LUMA_BG_ALPHA = 0.05f
        private const val MOTION_CROP_MIN_CELLS = 4
        private const val MOTION_CROP_MAX_RATIO = 0.55f
        private const val MOTION_CROP_PAD_RATIO = 0.28f
        private const val MOTION_CROP_MIN_W = 640
        private const val MOTION_CROP_MIN_H = 360
        private const val STATIC_CHECK_BACKOFF_MS = 1000L
        private const val STATIC_HEARTBEAT_INTERVAL = 8

        // 共享单线程推理/生命周期执行器，避免 CameraXCapture 重建时重复初始化或并发访问 native 上下文
        @Volatile private var inferExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        @Volatile private var currentTaskStartedAtMs = 0L
        @Volatile private var gpuAutoFallbackTriggered = false
        @Volatile private var sdkInitialized = false
        @Volatile private var cachedModelPath: String? = null
        @Volatile private var customCore: HyperLPRCore? = null
        @Volatile private var customGpuCore: HyperLPRGpuCore? = null
        @Volatile private var currentRecThreshold: Float = DEFAULT_REC_CONF
        @Volatile private var currentIntervalMs: Long = DEFAULT_INTERVAL_MS
        @Volatile private var currentMotionGateEnabled: Boolean = DEFAULT_MOTION_GATE
        @Volatile private var currentRoiEnabled: Boolean = DEFAULT_ROI_ENABLED
        @Volatile private var currentRoiX: Float = DEFAULT_ROI_X
        @Volatile private var currentRoiY: Float = DEFAULT_ROI_Y
        @Volatile private var currentRoiW: Float = DEFAULT_ROI_W
        @Volatile private var currentRoiH: Float = DEFAULT_ROI_H
        @Volatile private var currentMotionCropEnabled: Boolean = DEFAULT_MOTION_CROP
        @Volatile private var currentMotionTorchEnabled: Boolean = DEFAULT_MOTION_TORCH
        @Volatile var videoContextProvider: (() -> Pair<String?, Long>)? = null

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
            val useGpu = prefs.getBoolean(PREF_USE_GPU, DEFAULT_USE_GPU) && HyperLPRGpuCore.isAvailable()
            val motionGate = prefs.getBoolean(PREF_MOTION_GATE, DEFAULT_MOTION_GATE)
            val roiEnabled = prefs.getBoolean(PREF_ROI_ENABLED, DEFAULT_ROI_ENABLED)
            val roiX = prefs.getFloat(PREF_ROI_X, DEFAULT_ROI_X).coerceIn(0f, 0.9f)
            val roiY = prefs.getFloat(PREF_ROI_Y, DEFAULT_ROI_Y).coerceIn(0f, 0.9f)
            val roiW = prefs.getFloat(PREF_ROI_W, DEFAULT_ROI_W).coerceIn(0.1f, 1.0f - roiX)
            val roiH = prefs.getFloat(PREF_ROI_H, DEFAULT_ROI_H).coerceIn(0.1f, 1.0f - roiY)
            val motionCrop = prefs.getBoolean(PREF_MOTION_CROP, DEFAULT_MOTION_CROP)
            val motionTorch = prefs.getBoolean(PREF_MOTION_TORCH, DEFAULT_MOTION_TORCH)
            return AlprConfig(
                detLevel, maxNum, boxConf, recConf, threads, intervalMs, useGpu, motionGate,
                roiEnabled, roiX, roiY, roiW, roiH, motionCrop, motionTorch
            )
        }

        fun saveConfig(context: Context, config: AlprConfig): AlprConfig {
            val safeRoiX = config.roiX.coerceIn(0f, 0.9f)
            val safeRoiY = config.roiY.coerceIn(0f, 0.9f)
            val sanitized = AlprConfig(
                detLevel = if (config.detLevel == HyperLPR3.DETECT_LEVEL_LOW) HyperLPR3.DETECT_LEVEL_LOW else HyperLPR3.DETECT_LEVEL_HIGH,
                maxNum = config.maxNum.coerceIn(1, 10),
                boxConfThreshold = config.boxConfThreshold.coerceIn(0.05f, 0.95f),
                recConfidenceThreshold = config.recConfidenceThreshold.coerceIn(0.30f, 0.99f),
                threads = config.threads.coerceIn(1, 8),
                intervalMs = config.intervalMs.coerceIn(50L, 2000L),
                useGpu = config.useGpu && HyperLPRGpuCore.isAvailable(),
                motionGateEnabled = config.motionGateEnabled,
                roiEnabled = config.roiEnabled,
                roiX = safeRoiX,
                roiY = safeRoiY,
                roiW = config.roiW.coerceIn(0.1f, 1.0f - safeRoiX),
                roiH = config.roiH.coerceIn(0.1f, 1.0f - safeRoiY),
                motionCropEnabled = config.motionCropEnabled,
                motionTorchEnabled = config.motionTorchEnabled
            )
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt(PREF_DET_LEVEL, sanitized.detLevel)
                .putInt(PREF_MAX_NUM, sanitized.maxNum)
                .putFloat(PREF_BOX_CONF, sanitized.boxConfThreshold)
                .putFloat(PREF_REC_CONF, sanitized.recConfidenceThreshold)
                .putInt(PREF_THREADS, sanitized.threads)
                .putLong(PREF_INTERVAL_MS, sanitized.intervalMs)
                .putBoolean(PREF_USE_GPU, sanitized.useGpu)
                .putBoolean(PREF_MOTION_GATE, sanitized.motionGateEnabled)
                .putBoolean(PREF_ROI_ENABLED, sanitized.roiEnabled)
                .putFloat(PREF_ROI_X, sanitized.roiX)
                .putFloat(PREF_ROI_Y, sanitized.roiY)
                .putFloat(PREF_ROI_W, sanitized.roiW)
                .putFloat(PREF_ROI_H, sanitized.roiH)
                .putBoolean(PREF_MOTION_CROP, sanitized.motionCropEnabled)
                .putBoolean(PREF_MOTION_TORCH, sanitized.motionTorchEnabled)
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

        private fun releaseAllCores() {
            val hadCustom = customCore != null || customGpuCore != null
            customCore?.release()
            customCore = null
            customGpuCore?.release()
            customGpuCore = null
            if (!hadCustom && sdkInitialized) {
                HyperLPR3.getInstance().release()
            }
        }

        private fun activateBackend(config: AlprConfig, modelDir: String?) {
            val param = buildParameter(config, modelDir)
            if (config.useGpu && HyperLPRGpuCore.isAvailable()) {
                val gpuCore = HyperLPRGpuCore()
                if (gpuCore.createRecognizerContext(param)) {
                    customGpuCore = gpuCore
                    Log.i("ALPR", "已切换至 GPU (OpenCL) 推理后端: $config")
                    return
                }
                Log.w("ALPR", "GPU (OpenCL) 后端初始化失败，回退至 CPU: ${HyperLPRGpuCore.getLoadError()}")
            }
            customCore = HyperLPRCore().apply {
                createRecognizerContext(param)
            }
            Log.i("ALPR", "已切换至 CPU 推理后端: $config")
        }

        private fun ensureInitialized(appContext: Context, config: AlprConfig) {
            currentRecThreshold = config.recConfidenceThreshold
            currentIntervalMs = config.intervalMs
            currentMotionGateEnabled = config.motionGateEnabled
            currentRoiEnabled = config.roiEnabled
            currentRoiX = config.roiX
            currentRoiY = config.roiY
            currentRoiW = config.roiW
            currentRoiH = config.roiH
            currentMotionCropEnabled = config.motionCropEnabled
            currentMotionTorchEnabled = config.motionTorchEnabled
            if (sdkInitialized) return
            try {
                val param = buildParameter(config, null)
                HyperLPR3.getInstance().init(appContext, param)
                cachedModelPath = param.modelPath
                    ?: appContext.getExternalFilesDir(null)?.let { "${it.absolutePath}/" }
                sdkInitialized = true
                if (config.useGpu && HyperLPRGpuCore.isAvailable()) {
                    HyperLPR3.getInstance().release()
                    activateBackend(config, cachedModelPath)
                } else {
                    Log.i("ALPR", "HyperLPR3 CPU 初始化完成: $config, modelPath=$cachedModelPath")
                }
            } catch (e: Exception) {
                Log.e("ALPR", "初始化 HyperLPR3 失败: ${e.message}")
            }
        }
    }

    init {
        val initialConfig = loadConfig(appContext)
        effectiveIntervalMs = initialConfig.intervalMs
        inferExecutor.execute {
            currentTaskStartedAtMs = System.currentTimeMillis()
            try {
                ensureInitialized(appContext, initialConfig)
            } finally {
                currentTaskStartedAtMs = 0L
            }
        }
    }

    fun reloadConfig(config: AlprConfig = loadConfig(appContext)) {
        val saved = saveConfig(appContext, config)
        currentRecThreshold = saved.recConfidenceThreshold
        currentIntervalMs = saved.intervalMs
        currentMotionGateEnabled = saved.motionGateEnabled
        currentRoiEnabled = saved.roiEnabled
        currentRoiX = saved.roiX
        currentRoiY = saved.roiY
        currentRoiW = saved.roiW
        currentRoiH = saved.roiH
        currentMotionCropEnabled = saved.motionCropEnabled
        currentMotionTorchEnabled = saved.motionTorchEnabled
        if ((!saved.motionGateEnabled || !saved.motionTorchEnabled) && autoTorchActive) {
            autoTorchActive = false
            onMotionTorchChanged?.invoke(false)
        }
        effectiveIntervalMs = saved.intervalMs
        consecutiveStaticChecks = 0
        hasLumaGrid = false
        lastGridKey = ""
        gpuAutoFallbackTriggered = false

        val stuckMs = currentTaskStartedAtMs.let { if (it > 0L) System.currentTimeMillis() - it else 0L }
        if (stuckMs > 1500L) {
            Log.w("ALPR", "检测到推理线程已阻塞 ${stuckMs}ms，重建执行器以避免回落 CPU 卡死")
            inferExecutor.shutdownNow()
            inferExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
            currentTaskStartedAtMs = 0L
            isProcessing.set(false)
            // 不在新线程 release 可能仍卡在 native 中的旧 gpuCore，直接置空引用防止并发冲突
            customGpuCore = null
        }

        inferExecutor.execute {
            currentTaskStartedAtMs = System.currentTimeMillis()
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
                releaseAllCores()
                activateBackend(saved, modelDir)
                Log.i("ALPR", "HyperLPR3 参数已热重载: $saved")
            } catch (e: Exception) {
                Log.e("ALPR", "重载 HyperLPR3 失败: ${e.message}")
            } finally {
                currentTaskStartedAtMs = 0L
            }
        }
    }

    /**
     * 原方案：直接从ImageProxy接受YUV数据进行车牌识别
     * 现：接受NV21数据进行车牌识别，避免了重复转换
     */

    fun canProcessNow(): Boolean {
        val now = System.currentTimeMillis()
        val taskStart = currentTaskStartedAtMs
        if (customGpuCore != null && taskStart > 0L && now - taskStart > 5000L && !gpuAutoFallbackTriggered) {
            gpuAutoFallbackTriggered = true
            Log.w("ALPR", "GPU 推理阻塞超过 5000ms，自动回落至 CPU 后端")
            reloadConfig(loadConfig(appContext).copy(useGpu = false))
            return false
        }
        val gateInterval = if (currentMotionGateEnabled) effectiveIntervalMs else currentIntervalMs
        return !isProcessing.get() && (now - lastRunTimeMs >= gateInterval)
    }

    private data class NormRect(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
    private data class MotionAnalysis(val hasMotion: Boolean, val cropNorm: NormRect?, val clusterNorm: NormRect? = null)

    private fun activeRoiNorm(): NormRect {
        if (!currentRoiEnabled) return NormRect(0f, 0f, 1f, 1f)
        val x1 = currentRoiX.coerceIn(0f, 0.9f)
        val y1 = currentRoiY.coerceIn(0f, 0.9f)
        val x2 = (x1 + currentRoiW).coerceIn(x1 + 0.1f, 1f)
        val y2 = (y1 + currentRoiH).coerceIn(y1 + 0.1f, 1f)
        return NormRect(x1, y1, x2, y2)
    }

    private fun rotatedToSensorPixel(
        rx: Float,
        ry: Float,
        width: Int,
        height: Int,
        rotationDegrees: Int
    ): Pair<Int, Int> {
        val cx = rx.coerceIn(0f, 0.9999f)
        val cy = ry.coerceIn(0f, 0.9999f)
        val sx: Int
        val sy: Int
        when (rotationDegrees) {
            90 -> {
                sx = (cy * width).toInt()
                sy = ((1f - cx) * height).toInt()
            }
            180 -> {
                sx = ((1f - cx) * width).toInt()
                sy = ((1f - cy) * height).toInt()
            }
            270 -> {
                sx = ((1f - cy) * width).toInt()
                sy = (cx * height).toInt()
            }
            else -> {
                sx = (cx * width).toInt()
                sy = (cy * height).toInt()
            }
        }
        return sx.coerceIn(0, width - 1) to sy.coerceIn(0, height - 1)
    }

    /**
     * 在活动 ROI 内采样 32×18 亮度网格，采用“AE 亮度漂移补偿 + 慢速自适应背景”检测运动，
     * 并通过最大连通域聚类估算移动车辆外接框（支持大外扩、最小尺寸兜底与异常回退）。
     */
    private fun analyzeSceneMotion(
        nv21: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        roi: NormRect
    ): MotionAnalysis {
        val ySize = width * height
        if (nv21.size < ySize || width < LUMA_GRID_W || height < LUMA_GRID_H) {
            return MotionAnalysis(hasMotion = true, cropNorm = null)
        }
        val gridKey = "${width}x${height}r${rotationDegrees}_${roi.x1}_${roi.y1}_${roi.x2}_${roi.y2}"
        if (gridKey != lastGridKey) {
            lastGridKey = gridKey
            hasLumaGrid = false
        }

        val roiW = roi.x2 - roi.x1
        val roiH = roi.y2 - roi.y1
        val totalSamples = LUMA_GRID_W * LUMA_GRID_H
        var idx = 0
        var shiftSum = 0f
        for (gy in 0 until LUMA_GRID_H) {
            val ry = roi.y1 + ((gy + 0.5f) / LUMA_GRID_H) * roiH
            for (gx in 0 until LUMA_GRID_W) {
                val rx = roi.x1 + ((gx + 0.5f) / LUMA_GRID_W) * roiW
                val (sx, sy) = rotatedToSensorPixel(rx, ry, width, height, rotationDegrees)
                val luma = (nv21[sy * width + sx].toInt() and 0xFF).toFloat()
                lumaCur[idx] = luma
                if (hasLumaGrid) {
                    shiftSum += luma - lumaBg[idx]
                }
                idx++
            }
        }
        if (!hasLumaGrid) {
            lumaCur.copyInto(lumaBg)
            hasLumaGrid = true
            return MotionAnalysis(hasMotion = true, cropNorm = null)
        }

        val shift = shiftSum / totalSamples
        var changedCount = 0
        for (i in 0 until totalSamples) {
            val diff = kotlin.math.abs(lumaCur[i] - lumaBg[i] - shift)
            val changed = diff >= LUMA_PIXEL_DIFF_THRESHOLD
            changedGrid[i] = changed
            if (changed) changedCount++
            lumaBg[i] += (lumaCur[i] - lumaBg[i]) * LUMA_BG_ALPHA
        }

        val changedRatio = changedCount.toFloat() / totalSamples
        if (changedRatio < LUMA_CHANGED_RATIO_THRESHOLD) {
            return MotionAnalysis(hasMotion = false, cropNorm = null)
        }

        if (changedRatio >= MOTION_CROP_MAX_RATIO) {
            return MotionAnalysis(hasMotion = true, cropNorm = null)
        }

        // 寻找最大 4-邻域连通移动团块，避免零星树影/噪点拉大裁剪框
        visitedGrid.fill(false)
        var bestCount = 0
        var bestMinX = 0
        var bestMaxX = 0
        var bestMinY = 0
        var bestMaxY = 0

        for (start in 0 until totalSamples) {
            if (!changedGrid[start] || visitedGrid[start]) continue
            var head = 0
            var tail = 0
            bfsQueue[tail++] = start
            visitedGrid[start] = true
            var count = 0
            var minX = LUMA_GRID_W
            var maxX = -1
            var minY = LUMA_GRID_H
            var maxY = -1
            while (head < tail) {
                val cur = bfsQueue[head++]
                val cx = cur % LUMA_GRID_W
                val cy = cur / LUMA_GRID_W
                count++
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy

                if (cx > 0) {
                    val n = cur - 1
                    if (changedGrid[n] && !visitedGrid[n]) { visitedGrid[n] = true; bfsQueue[tail++] = n }
                }
                if (cx + 1 < LUMA_GRID_W) {
                    val n = cur + 1
                    if (changedGrid[n] && !visitedGrid[n]) { visitedGrid[n] = true; bfsQueue[tail++] = n }
                }
                if (cy > 0) {
                    val n = cur - LUMA_GRID_W
                    if (changedGrid[n] && !visitedGrid[n]) { visitedGrid[n] = true; bfsQueue[tail++] = n }
                }
                if (cy + 1 < LUMA_GRID_H) {
                    val n = cur + LUMA_GRID_W
                    if (changedGrid[n] && !visitedGrid[n]) { visitedGrid[n] = true; bfsQueue[tail++] = n }
                }
            }
            if (count > bestCount) {
                bestCount = count
                bestMinX = minX
                bestMaxX = maxX
                bestMinY = minY
                bestMaxY = maxY
            }
        }

        val clusterRatio = bestCount.toFloat() / totalSamples
        if (bestCount < MOTION_CROP_MIN_CELLS || clusterRatio >= MOTION_CROP_MAX_RATIO) {
            return MotionAnalysis(hasMotion = true, cropNorm = null)
        }

        // 将团块外接框映射回全图归一化坐标，四周各外扩 28% 并按最小尺寸 640×360 兜底
        val rawX1 = roi.x1 + (bestMinX.toFloat() / LUMA_GRID_W) * roiW
        val rawY1 = roi.y1 + (bestMinY.toFloat() / LUMA_GRID_H) * roiH
        val rawX2 = roi.x1 + ((bestMaxX + 1).toFloat() / LUMA_GRID_W) * roiW
        val rawY2 = roi.y1 + ((bestMaxY + 1).toFloat() / LUMA_GRID_H) * roiH
        val clusterNorm = NormRect(rawX1, rawY1, rawX2, rawY2)

        if (!currentMotionCropEnabled || latestPlates.isNotEmpty()) {
            return MotionAnalysis(hasMotion = true, cropNorm = null, clusterNorm = clusterNorm)
        }

        var cx1 = rawX1
        var cy1 = rawY1
        var cx2 = rawX2
        var cy2 = rawY2

        val padW = (cx2 - cx1) * MOTION_CROP_PAD_RATIO
        val padH = (cy2 - cy1) * MOTION_CROP_PAD_RATIO
        cx1 -= padW
        cy1 -= padH
        cx2 += padW
        cy2 += padH

        val rotatedW = (if (rotationDegrees == 90 || rotationDegrees == 270) height else width).toFloat()
        val rotatedH = (if (rotationDegrees == 90 || rotationDegrees == 270) width else height).toFloat()
        val minNormW = (MOTION_CROP_MIN_W / rotatedW).coerceAtMost(roiW)
        val minNormH = (MOTION_CROP_MIN_H / rotatedH).coerceAtMost(roiH)

        if (cx2 - cx1 < minNormW) {
            val mid = (cx1 + cx2) * 0.5f
            cx1 = mid - minNormW * 0.5f
            cx2 = mid + minNormW * 0.5f
        }
        if (cy2 - cy1 < minNormH) {
            val mid = (cy1 + cy2) * 0.5f
            cy1 = mid - minNormH * 0.5f
            cy2 = mid + minNormH * 0.5f
        }

        if (cx1 < roi.x1) { cx2 += (roi.x1 - cx1); cx1 = roi.x1 }
        if (cx2 > roi.x2) { cx1 -= (cx2 - roi.x2); cx2 = roi.x2 }
        if (cy1 < roi.y1) { cy2 += (roi.y1 - cy1); cy1 = roi.y1 }
        if (cy2 > roi.y2) { cy1 -= (cy2 - roi.y2); cy2 = roi.y2 }

        cx1 = cx1.coerceIn(roi.x1, roi.x2)
        cy1 = cy1.coerceIn(roi.y1, roi.y2)
        cx2 = cx2.coerceIn(roi.x1, roi.x2)
        cy2 = cy2.coerceIn(roi.y1, roi.y2)

        if (cx2 - cx1 >= roiW * 0.88f && cy2 - cy1 >= roiH * 0.88f) {
            return MotionAnalysis(hasMotion = true, cropNorm = null, clusterNorm = clusterNorm)
        }
        return MotionAnalysis(hasMotion = true, cropNorm = NormRect(cx1, cy1, cx2, cy2), clusterNorm = clusterNorm)
    }

    private fun cropNv21Full(
        src: ByteArray,
        srcW: Int,
        srcH: Int,
        cropX: Int,
        cropY: Int,
        cropW: Int,
        cropH: Int,
        dst: ByteArray
    ) {
        for (y in 0 until cropH) {
            System.arraycopy(src, (cropY + y) * srcW + cropX, dst, y * cropW, cropW)
        }
        val srcYSize = srcW * srcH
        val dstYSize = cropW * cropH
        val uvRows = cropH shr 1
        val srcUvStartY = cropY shr 1
        for (uvY in 0 until uvRows) {
            System.arraycopy(
                src,
                srcYSize + (srcUvStartY + uvY) * srcW + cropX,
                dst,
                dstYSize + uvY * cropW,
                cropW
            )
        }
    }

    private fun cropNv21Half(
        src: ByteArray,
        srcW: Int,
        srcH: Int,
        cropX: Int,
        cropY: Int,
        dst: ByteArray,
        dstW: Int,
        dstH: Int
    ) {
        for (y in 0 until dstH) {
            val srcRowOffset = (cropY + (y shl 1)) * srcW + cropX
            val dstRowOffset = y * dstW
            var sx = 0
            for (x in 0 until dstW) {
                dst[dstRowOffset + x] = src[srcRowOffset + sx]
                sx += 2
            }
        }
        val srcYSize = srcW * srcH
        val dstYSize = dstW * dstH
        val uvRows = dstH shr 1
        val srcUvStartY = cropY shr 1
        for (uvY in 0 until uvRows) {
            val srcUvRow = srcYSize + (srcUvStartY + (uvY shl 1)) * srcW + cropX
            val dstUvRow = dstYSize + uvY * dstW
            var sx = 0
            var dx = 0
            while (dx < dstW) {
                dst[dstUvRow + dx] = src[srcUvRow + sx]
                dst[dstUvRow + dx + 1] = src[srcUvRow + sx + 1]
                sx += 4
                dx += 2
            }
        }
    }

    private fun acquireSnapshotBuffer(requiredBytes: Int): ByteArray {
        val idx = snapshotIndex
        snapshotIndex = idx xor 1
        val existing = snapshotBuffers[idx]
        if (existing != null && existing.size == requiredBytes) {
            return existing
        }
        return ByteArray(requiredBytes).also { snapshotBuffers[idx] = it }
    }

    /**
     * 对高分辨率 NV21 做 1/2 降采样（如 1920x1080 -> 960x540），减少 75% 内存拷贝与 C++ 缩放耗时，
     * 同时仍大于 HyperLPR3 b640x(640px)/b320(320px) 输入尺寸。
     */
    private fun downsampleNv21Half(
        src: ByteArray,
        srcW: Int,
        srcH: Int,
        dst: ByteArray,
        dstW: Int,
        dstH: Int
    ) {
        for (y in 0 until dstH) {
            val srcRowOffset = (y shl 1) * srcW
            val dstRowOffset = y * dstW
            var sx = 0
            for (x in 0 until dstW) {
                dst[dstRowOffset + x] = src[srcRowOffset + sx]
                sx += 2
            }
        }
        val srcYSize = srcW * srcH
        val dstYSize = dstW * dstH
        val uvRows = dstH shr 1
        for (uvY in 0 until uvRows) {
            val srcUvRow = srcYSize + (uvY shl 1) * srcW
            val dstUvRow = dstYSize + uvY * dstW
            var sx = 0
            var dx = 0
            while (dx < dstW) {
                dst[dstUvRow + dx] = src[srcUvRow + sx]
                dst[dstUvRow + dx + 1] = src[srcUvRow + sx + 1]
                sx += 4
                dx += 2
            }
        }
    }

    fun processNv21Async(
        nv21Source: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        onDetected: ((String, Float) -> Unit)? = null
    ) {
        val now = System.currentTimeMillis()
        // 若 GPU 推理阻塞超过 5 秒，自动回落至 CPU，避免推理线程永久卡死
        val taskStart = currentTaskStartedAtMs
        if (customGpuCore != null && taskStart > 0L && now - taskStart > 5000L && !gpuAutoFallbackTriggered) {
            gpuAutoFallbackTriggered = true
            Log.w("ALPR", "GPU 推理阻塞超过 5000ms，自动回落至 CPU 后端")
            reloadConfig(loadConfig(appContext).copy(useGpu = false))
            return
        }
        // 節流閥：時間未到或上一幀還在算，直接跳過，不影響串流幀率
        val gateInterval = if (currentMotionGateEnabled) effectiveIntervalMs else currentIntervalMs
        if (now - lastRunTimeMs < gateInterval || !isProcessing.compareAndSet(false, true)) {
            return
        }
        lastRunTimeMs = now

        val baseRoi = activeRoiNorm()
        var targetNorm: NormRect? = if (currentRoiEnabled) baseRoi else null
        var motionHasMotion = false
        var motionCropActive = false
        var motionCluster: NormRect? = null

        if (currentMotionGateEnabled) {
            val motion = analyzeSceneMotion(nv21Source, width, height, rotationDegrees, baseRoi)
            motionHasMotion = motion.hasMotion
            motionCluster = motion.clusterNorm
            if (currentMotionTorchEnabled) {
                if (motion.hasMotion) {
                    lastMotionForTorchMs = now
                    if (!autoTorchActive) {
                        autoTorchActive = true
                        onMotionTorchChanged?.invoke(true)
                    }
                } else if (autoTorchActive && latestPlates.isEmpty() && now - lastMotionForTorchMs >= AUTO_TORCH_HOLD_MS) {
                    autoTorchActive = false
                    onMotionTorchChanged?.invoke(false)
                }
            }
            if (!motion.hasMotion && latestPlates.isEmpty()) {
                val rotW = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
                val rotH = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
                latestMotionDebug = MotionDebugInfo(
                    hasMotion = false,
                    motionCropActive = false,
                    clusterX1 = 0f, clusterY1 = 0f, clusterX2 = 0f, clusterY2 = 0f,
                    cropX1 = 0f, cropY1 = 0f, cropX2 = 0f, cropY2 = 0f,
                    cropW = 0, cropH = 0, frameW = rotW, frameH = rotH,
                    timestampMs = now
                )
                consecutiveStaticChecks++
                if (consecutiveStaticChecks >= 2 && consecutiveStaticChecks % STATIC_HEARTBEAT_INTERVAL != 0) {
                    effectiveIntervalMs = maxOf(currentIntervalMs, STATIC_CHECK_BACKOFF_MS)
                    isProcessing.set(false)
                    return
                }
            } else if (motion.hasMotion) {
                consecutiveStaticChecks = 0
                effectiveIntervalMs = currentIntervalMs
                if (currentMotionCropEnabled && motion.cropNorm != null) {
                    targetNorm = motion.cropNorm
                    motionCropActive = true
                }
            } else {
                // 画面静止但当前仍有已识别车牌：保持心跳或常规频率以追踪离开
                effectiveIntervalMs = currentIntervalMs
            }
        } else {
            effectiveIntervalMs = currentIntervalMs
            latestMotionDebug = null
        }

        // 计算传感器偶数对齐裁剪窗口及在旋转后全图中的偏移
        var cropX = 0
        var cropY = 0
        var cropW = width
        var cropH = height
        if (targetNorm != null && width >= 8 && height >= 8) {
            val sx1f: Float
            val sy1f: Float
            val sx2f: Float
            val sy2f: Float
            when (rotationDegrees) {
                90 -> {
                    sx1f = targetNorm.y1 * width
                    sx2f = targetNorm.y2 * width
                    sy1f = (1f - targetNorm.x2) * height
                    sy2f = (1f - targetNorm.x1) * height
                }
                180 -> {
                    sx1f = (1f - targetNorm.x2) * width
                    sx2f = (1f - targetNorm.x1) * width
                    sy1f = (1f - targetNorm.y2) * height
                    sy2f = (1f - targetNorm.y1) * height
                }
                270 -> {
                    sx1f = (1f - targetNorm.y2) * width
                    sx2f = (1f - targetNorm.y1) * width
                    sy1f = targetNorm.x1 * height
                    sy2f = targetNorm.x2 * height
                }
                else -> {
                    sx1f = targetNorm.x1 * width
                    sx2f = targetNorm.x2 * width
                    sy1f = targetNorm.y1 * height
                    sy2f = targetNorm.y2 * height
                }
            }
            val ex1 = (sx1f.toInt() and 1.inv()).coerceIn(0, width - 4)
            val ey1 = (sy1f.toInt() and 1.inv()).coerceIn(0, height - 4)
            val ex2 = ((sx2f.toInt() + 1) and 1.inv()).coerceIn(ex1 + 4, width)
            val ey2 = ((sy2f.toInt() + 1) and 1.inv()).coerceIn(ey1 + 4, height)
            val cw = ex2 - ex1
            val ch = ey2 - ey1
            if (cw >= 64 && ch >= 64 && (cw < width || ch < height)) {
                cropX = ex1
                cropY = ey1
                cropW = cw
                cropH = ch
            }
        }
        val isCropped = cropW < width || cropH < height
        val rotOffX = when (rotationDegrees) {
            90 -> height - (cropY + cropH)
            180 -> width - (cropX + cropW)
            270 -> cropY
            else -> cropX
        }.toFloat()
        val rotOffY = when (rotationDegrees) {
            90 -> cropX
            180 -> height - (cropY + cropH)
            270 -> width - (cropX + cropW)
            else -> cropY
        }.toFloat()

        if (currentMotionGateEnabled) {
            val rotW = (if (rotationDegrees == 90 || rotationDegrees == 270) height else width).coerceAtLeast(1)
            val rotH = (if (rotationDegrees == 90 || rotationDegrees == 270) width else height).coerceAtLeast(1)
            val cropRotW = if (rotationDegrees == 90 || rotationDegrees == 270) cropH else cropW
            val cropRotH = if (rotationDegrees == 90 || rotationDegrees == 270) cropW else cropH
            latestMotionDebug = MotionDebugInfo(
                hasMotion = motionHasMotion,
                motionCropActive = motionCropActive && isCropped,
                clusterX1 = motionCluster?.x1 ?: 0f,
                clusterY1 = motionCluster?.y1 ?: 0f,
                clusterX2 = motionCluster?.x2 ?: 0f,
                clusterY2 = motionCluster?.y2 ?: 0f,
                cropX1 = (rotOffX / rotW).coerceIn(0f, 1f),
                cropY1 = (rotOffY / rotH).coerceIn(0f, 1f),
                cropX2 = ((rotOffX + cropRotW) / rotW).coerceIn(0f, 1f),
                cropY2 = ((rotOffY + cropRotH) / rotH).coerceIn(0f, 1f),
                cropW = cropRotW,
                cropH = cropRotH,
                frameW = rotW,
                frameH = rotH,
                timestampMs = now
            )
        }

        // 1080p 及以下直接全尺寸进双缓冲；仅 2K/4K(>=2560) 开启 1/2 降采样
        val useHalfScale = (cropW >= 2560 || cropH >= 2560) && cropW >= 4 && cropH >= 4
        val inferW = if (useHalfScale) ((cropW shr 1) and 1.inv()) else cropW
        val inferH = if (useHalfScale) ((cropH shr 1) and 1.inv()) else cropH
        val requiredBytes = inferW * inferH * 3 / 2
        val frameCopy = acquireSnapshotBuffer(requiredBytes)
        when {
            !isCropped && !useHalfScale ->
                System.arraycopy(nv21Source, 0, frameCopy, 0, minOf(nv21Source.size, requiredBytes))
            !isCropped && useHalfScale ->
                downsampleNv21Half(nv21Source, width, height, frameCopy, inferW, inferH)
            isCropped && !useHalfScale ->
                cropNv21Full(nv21Source, width, height, cropX, cropY, cropW, cropH, frameCopy)
            else ->
                cropNv21Half(nv21Source, width, height, cropX, cropY, frameCopy, inferW, inferH)
        }

        inferExecutor.execute {
            currentTaskStartedAtMs = System.currentTimeMillis()
            try {
                val rotation = when (rotationDegrees) {
                    90 -> HyperLPR3.CAMERA_ROTATION_90
                    180 -> HyperLPR3.CAMERA_ROTATION_180
                    270 -> HyperLPR3.CAMERA_ROTATION_270
                    else -> HyperLPR3.CAMERA_ROTATION_0
                }

                if (!sdkInitialized) return@execute
                val startNs = System.nanoTime()
                val activeGpuCore = customGpuCore
                val activeCore = customCore
                val plates = when {
                    activeGpuCore != null -> activeGpuCore.plateRecognitionFromBuffer(
                        frameCopy,
                        inferH,
                        inferW,
                        rotation,
                        HyperLPR3.STREAM_YUV_NV21
                    )
                    activeCore != null -> activeCore.plateRecognitionFromBuffer(
                        frameCopy,
                        inferH,
                        inferW,
                        rotation,
                        HyperLPR3.STREAM_YUV_NV21
                    )
                    else -> HyperLPR3.getInstance().plateRecognition(
                        frameCopy,
                        inferH,
                        inferW,
                        rotation,
                        HyperLPR3.STREAM_YUV_NV21
                    )
                }
                latestProcessTimeMs = (System.nanoTime() - startNs) / 1_000_000L

                val minRecConf = currentRecThreshold
                val rotatedW = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
                val rotatedH = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
                val cropRotatedW = if (rotationDegrees == 90 || rotationDegrees == 270) cropH else cropW
                val cropRotatedH = if (rotationDegrees == 90 || rotationDegrees == 270) cropW else cropH
                val inferRotatedW = if (rotationDegrees == 90 || rotationDegrees == 270) inferH else inferW
                val inferRotatedH = if (rotationDegrees == 90 || rotationDegrees == 270) inferW else inferH
                val scaleX = cropRotatedW.toFloat() / inferRotatedW.coerceAtLeast(1)
                val scaleY = cropRotatedH.toFloat() / inferRotatedH.coerceAtLeast(1)

                val validPlates = (plates ?: emptyArray())
                    .filter { it != null && it.confidence >= minRecConf && it.code.isNotBlank() }
                    .sortedByDescending { it.confidence }

                val detected = validPlates.map {
                    DetectedPlate(
                        code = it.code,
                        confidence = it.confidence,
                        type = it.type,
                        x1 = it.x1 * scaleX + rotOffX,
                        y1 = it.y1 * scaleY + rotOffY,
                        x2 = it.x2 * scaleX + rotOffX,
                        y2 = it.y2 * scaleY + rotOffY
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

                    val store = PlateEventStore.getInstance(appContext)
                    val (videoFile, videoStartMs) = videoContextProvider?.invoke() ?: (null to 0L)
                    for (p in validPlates) {
                        store.recordDetection(
                            DetectedPlate(p.code, p.confidence, p.type, p.x1, p.y1, p.x2, p.y2),
                            frameCopy,
                            inferW,
                            inferH,
                            rotationDegrees,
                            videoFile,
                            videoStartMs
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("ALPR", "识别异常: ${e.message}")
            } finally {
                currentTaskStartedAtMs = 0L
                isProcessing.set(false)
            }
        }
    }
}