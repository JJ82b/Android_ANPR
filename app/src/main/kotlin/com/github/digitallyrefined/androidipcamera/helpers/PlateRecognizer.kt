package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.hyperai.hyperlpr3.HyperLPR3
import com.hyperai.hyperlpr3.bean.HyperLPRParameter
import com.hyperai.hyperlpr3.core.HyperLPRCore
import com.hyperai.hyperlpr3.core.HyperLPRGpuCore
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class DetectedVehicle(
    val label: String,
    val confidence: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
)

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
    val motionTorchEnabled: Boolean = false,
    val flashCaptureEnabled: Boolean = false,
    val flashCaptureIso: Int = 200,
    val flashCaptureShutterNs: Long = 2_000_000L,
    val motionDiffThreshold: Float = 20.0f,
    val motionAreaRatio: Float = 0.05f,
    val motionBgAlpha: Float = 0.05f,
    val motionCropMinCells: Int = 6,
    val motionCropPadRatio: Float = 0.28f,
    val motionBackoffMs: Long = 1000L,
    val motionTorchHoldMs: Long = 5000L,
    val torchSettleMs: Long = 1000L,
    val motionGlobalJumpRatio: Float = 0.75f,
    val headlightCaptureEnabled: Boolean = false,
    val headlightDiffThreshold: Float = 55.0f,
    val headlightMinRatio: Float = 0.02f,
    val headlightMaxRatio: Float = 0.55f,
    val aeRegionEnabled: Boolean = false,
    val antiOverexposureEnabled: Boolean = false,
    val flashCaptureMode: String = "manual",
    val skipCaptureIfFastShutter: Boolean = true,
    val vehicleFilterMode: String = "off",
    val vehicleConfThreshold: Float = 0.35f,
    val vehicleCropEnabled: Boolean = true,
    val motionTorchTriggerMode: String = "motion",
    val feedVehicleOnly: Boolean = false,
    val vehicleModelName: String = "vehicle_efficientdet_lite0.tflite"
) {
    fun toJson(): String = String.format(
        Locale.US,
        """{"detLevel":%d,"maxNum":%d,"boxConfThreshold":%.2f,"recConfidenceThreshold":%.2f,"threads":%d,"intervalMs":%d,"useGpu":%b,"gpuAvailable":%b,"motionGateEnabled":%b,"roiEnabled":%b,"roiX":%.2f,"roiY":%.2f,"roiW":%.2f,"roiH":%.2f,"motionCropEnabled":%b,"motionTorchEnabled":%b,"flashCaptureEnabled":%b,"flashCaptureIso":%d,"flashCaptureShutterNs":%d,"motionDiffThreshold":%.0f,"motionAreaRatio":%.2f,"motionBgAlpha":%.2f,"motionCropMinCells":%d,"motionCropPadRatio":%.2f,"motionBackoffMs":%d,"motionTorchHoldMs":%d,"torchSettleMs":%d,"motionGlobalJumpRatio":%.2f,"headlightCaptureEnabled":%b,"headlightDiffThreshold":%.0f,"headlightMinRatio":%.2f,"headlightMaxRatio":%.2f,"aeRegionEnabled":%b,"antiOverexposureEnabled":%b,"flashCaptureMode":"%s","skipCaptureIfFastShutter":%b,"vehicleFilterMode":"%s","vehicleConfThreshold":%.2f,"vehicleCropEnabled":%b,"motionTorchTriggerMode":"%s","feedVehicleOnly":%b,"vehicleModelName":"%s"}""",
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
        motionTorchEnabled,
        flashCaptureEnabled,
        flashCaptureIso,
        flashCaptureShutterNs,
        motionDiffThreshold,
        motionAreaRatio,
        motionBgAlpha,
        motionCropMinCells,
        motionCropPadRatio,
        motionBackoffMs,
        motionTorchHoldMs,
        torchSettleMs,
        motionGlobalJumpRatio,
        headlightCaptureEnabled,
        headlightDiffThreshold,
        headlightMinRatio,
        headlightMaxRatio,
        aeRegionEnabled,
        antiOverexposureEnabled,
        flashCaptureMode,
        skipCaptureIfFastShutter,
        vehicleFilterMode,
        vehicleConfThreshold,
        vehicleCropEnabled,
        motionTorchTriggerMode,
        feedVehicleOnly,
        vehicleModelName
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
        val timestampMs: Long,
        val headlightCaptureActive: Boolean = false,
        val glareX1: Float = 0f,
        val glareY1: Float = 0f,
        val glareX2: Float = 0f,
        val glareY2: Float = 0f
    )

    @Volatile
    var latestProcessTimeMs: Long = 0
        private set

    @Volatile
    var latestMotionDebug: MotionDebugInfo? = null
        private set

    @Volatile
    var latestVehicles: List<DetectedVehicle> = emptyList()
        private set

    @Volatile
    var latestVehicleDetectMs: Long = 0L
        private set

    @Volatile
    var latestVehicleFilterStatus: String = "off"
        private set
    
    private val isProcessing = AtomicBoolean(false)
    private var lastRunTimeMs = 0L
    @Volatile private var lastAlprHeartbeatMs = 0L
    @Volatile private var torchTransitionUntilMs = 0L
    @Volatile private var torchTransitionSuppressMotion = false
    @Volatile private var pendingBgReseed = false
    @Volatile private var suppressSeedMotion = false
    private val snapshotBuffers = arrayOfNulls<ByteArray>(2)
    private var snapshotIndex = 0
    private val lumaBg = FloatArray(LUMA_GRID_W * LUMA_GRID_H)
    private val lumaCur = FloatArray(LUMA_GRID_W * LUMA_GRID_H)
    private val peakCur = FloatArray(LUMA_GRID_W * LUMA_GRID_H)
    private val changedGrid = BooleanArray(LUMA_GRID_W * LUMA_GRID_H)
    private val visitedGrid = BooleanArray(LUMA_GRID_W * LUMA_GRID_H)
    private val bfsQueue = IntArray(LUMA_GRID_W * LUMA_GRID_H)
    private var hasLumaGrid = false
    private val glareY = IntArray(GLARE_W * GLARE_H)
    private val glareU = IntArray(GLARE_W * GLARE_H)
    private val glareV = IntArray(GLARE_W * GLARE_H)
    private val glareBgY = FloatArray(GLARE_W * GLARE_H)
    private val glareTemp = IntArray(GLARE_W * GLARE_H)
    private val glareOpen = IntArray(GLARE_W * GLARE_H)
    private val glareMask = BooleanArray(GLARE_W * GLARE_H)
    private val glareVisited = BooleanArray(GLARE_W * GLARE_H)
    private val glareBfsQueue = IntArray(GLARE_W * GLARE_H)
    private var hasGlareBg = false
    private var glareStreak = 0
    private var lastGridKey = ""
    private var consecutiveStaticChecks = 0
    @Volatile private var effectiveIntervalMs = DEFAULT_INTERVAL_MS
    @Volatile private var autoTorchActive = false
    private var lastMotionForTorchMs = 0L
    @Volatile var onMotionTorchChanged: ((Boolean) -> Unit)? = null
    @Volatile private var headlightCaptureActive = false
    private var lastHeadlightTriggerMs = 0L
    @Volatile var onHeadlightCaptureChanged: ((Boolean) -> Unit)? = null
    private var lastAntiOverexpAdjustMs = 0L
    @Volatile var onAntiOverexposureStep: ((Int) -> Unit)? = null
    @Volatile var captureModeAntiOverexposureActive: Boolean = false

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
        private const val PREF_FLASH_CAPTURE = "alpr_flash_capture_enabled"
        private const val PREF_FLASH_CAPTURE_ISO = "alpr_flash_capture_iso"
        private const val PREF_FLASH_CAPTURE_SHUTTER_NS = "alpr_flash_capture_shutter_ns"
        private const val PREF_MOTION_DIFF_THRESHOLD = "alpr_motion_diff_threshold"
        private const val PREF_MOTION_AREA_RATIO = "alpr_motion_area_ratio"
        private const val PREF_MOTION_BG_ALPHA = "alpr_motion_bg_alpha"
        private const val PREF_MOTION_CROP_MIN_CELLS = "alpr_motion_crop_min_cells"
        private const val PREF_MOTION_CROP_PAD_RATIO = "alpr_motion_crop_pad_ratio"
        private const val PREF_MOTION_BACKOFF_MS = "alpr_motion_backoff_ms"
        private const val PREF_MOTION_TORCH_HOLD_MS = "alpr_motion_torch_hold_ms"
        private const val PREF_TORCH_SETTLE_MS = "alpr_torch_settle_ms"
        private const val PREF_MOTION_GLOBAL_JUMP_RATIO = "alpr_motion_global_jump_ratio"
        private const val PREF_HEADLIGHT_CAPTURE = "alpr_headlight_capture_enabled"
        private const val PREF_HEADLIGHT_DIFF = "alpr_headlight_diff_threshold"
        private const val PREF_HEADLIGHT_MIN_RATIO = "alpr_headlight_min_ratio"
        private const val PREF_HEADLIGHT_MAX_RATIO = "alpr_headlight_max_ratio"
        private const val PREF_AE_REGION = "alpr_ae_region_enabled"
        private const val PREF_ANTI_OVEREXP = "alpr_anti_overexposure_enabled"
        private const val PREF_FLASH_CAPTURE_MODE = "alpr_flash_capture_mode"
        private const val PREF_SKIP_FAST_SHUTTER = "alpr_skip_capture_fast_shutter"
        private const val PREF_VEHICLE_FILTER_MODE = "alpr_vehicle_filter_mode"
        private const val PREF_VEHICLE_CONF_THRESHOLD = "alpr_vehicle_conf_threshold"
        private const val PREF_VEHICLE_CROP_ENABLED = "alpr_vehicle_crop_enabled"
        private const val PREF_MOTION_TORCH_TRIGGER_MODE = "alpr_motion_torch_trigger_mode"
        private const val PREF_FEED_VEHICLE_ONLY = "alpr_feed_vehicle_only"
        private const val PREF_VEHICLE_MODEL_NAME = "alpr_vehicle_model_name"

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
        private const val DEFAULT_FLASH_CAPTURE = false
        private const val DEFAULT_FLASH_CAPTURE_ISO = 200
        private const val DEFAULT_FLASH_CAPTURE_SHUTTER_NS = 2_000_000L
        private const val DEFAULT_MOTION_DIFF_THRESHOLD = 20.0f
        private const val DEFAULT_MOTION_AREA_RATIO = 0.05f
        private const val DEFAULT_MOTION_BG_ALPHA = 0.05f
        private const val DEFAULT_MOTION_CROP_MIN_CELLS = 6
        private const val DEFAULT_MOTION_CROP_PAD_RATIO = 0.28f
        private const val DEFAULT_MOTION_BACKOFF_MS = 1000L
        private const val DEFAULT_MOTION_TORCH_HOLD_MS = 5000L
        private const val DEFAULT_TORCH_SETTLE_MS = 1000L
        private const val DEFAULT_MOTION_GLOBAL_JUMP_RATIO = 0.75f
        private const val DEFAULT_HEADLIGHT_CAPTURE = false
        private const val DEFAULT_HEADLIGHT_DIFF = 55.0f
        private const val DEFAULT_HEADLIGHT_MIN_RATIO = 0.02f
        private const val DEFAULT_HEADLIGHT_MAX_RATIO = 0.55f
        private const val DEFAULT_AE_REGION = false
        private const val DEFAULT_ANTI_OVEREXP = false
        private const val DEFAULT_FLASH_CAPTURE_MODE = "manual"
        private const val DEFAULT_SKIP_FAST_SHUTTER = true
        private const val DEFAULT_VEHICLE_FILTER_MODE = "off"
        private const val DEFAULT_VEHICLE_CONF_THRESHOLD = 0.35f
        private const val DEFAULT_VEHICLE_CROP_ENABLED = true
        private const val DEFAULT_MOTION_TORCH_TRIGGER_MODE = "motion"
        private const val DEFAULT_FEED_VEHICLE_ONLY = false
        private const val DEFAULT_VEHICLE_MODEL_NAME = "vehicle_efficientdet_lite0.tflite"
        private val BUILTIN_VEHICLE_MODELS = listOf(
            "vehicle_efficientdet_lite0.tflite" to "EfficientDet-Lite0 (320×320 · 推荐均衡)",
            "vehicle_efficientdet_lite1.tflite" to "EfficientDet-Lite1 (384×384 · 高精度远距)",
            "vehicle_ssd_mobilenet.tflite" to "MobileNetV1-SSD (300×300 · 极速轻量)"
        )
        private val VEHICLE_CLASS_NAMES = mapOf(
            2 to "car",
            3 to "motorcycle",
            5 to "bus",
            7 to "truck"
        )

        private const val LUMA_GRID_W = 32
        private const val LUMA_GRID_H = 18
        private const val GLARE_W = 160
        private const val GLARE_H = 90
        private const val GLARE_OPEN_RADIUS = 4
        private const val MOTION_CROP_MAX_RATIO = 0.55f
        private const val MOTION_CROP_MIN_W = 640
        private const val MOTION_CROP_MIN_H = 360
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
        @Volatile private var currentMotionDiffThreshold: Float = DEFAULT_MOTION_DIFF_THRESHOLD
        @Volatile private var currentMotionAreaRatio: Float = DEFAULT_MOTION_AREA_RATIO
        @Volatile private var currentMotionBgAlpha: Float = DEFAULT_MOTION_BG_ALPHA
        @Volatile private var currentMotionCropMinCells: Int = DEFAULT_MOTION_CROP_MIN_CELLS
        @Volatile private var currentMotionCropPadRatio: Float = DEFAULT_MOTION_CROP_PAD_RATIO
        @Volatile private var currentMotionBackoffMs: Long = DEFAULT_MOTION_BACKOFF_MS
        @Volatile private var currentMotionTorchHoldMs: Long = DEFAULT_MOTION_TORCH_HOLD_MS
        @Volatile private var currentTorchSettleMs: Long = DEFAULT_TORCH_SETTLE_MS
        @Volatile private var currentMotionGlobalJumpRatio: Float = DEFAULT_MOTION_GLOBAL_JUMP_RATIO
        @Volatile private var currentHeadlightCaptureEnabled: Boolean = DEFAULT_HEADLIGHT_CAPTURE
        @Volatile private var currentHeadlightDiffThreshold: Float = DEFAULT_HEADLIGHT_DIFF
        @Volatile private var currentHeadlightMinRatio: Float = DEFAULT_HEADLIGHT_MIN_RATIO
        @Volatile private var currentHeadlightMaxRatio: Float = DEFAULT_HEADLIGHT_MAX_RATIO
        @Volatile private var currentAntiOverexposureEnabled: Boolean = DEFAULT_ANTI_OVEREXP
        @Volatile private var currentVehicleFilterMode: String = DEFAULT_VEHICLE_FILTER_MODE
        @Volatile private var currentVehicleConfThreshold: Float = DEFAULT_VEHICLE_CONF_THRESHOLD
        @Volatile private var currentVehicleCropEnabled: Boolean = DEFAULT_VEHICLE_CROP_ENABLED
        @Volatile private var currentMotionTorchTriggerMode: String = DEFAULT_MOTION_TORCH_TRIGGER_MODE
        @Volatile private var currentFeedVehicleOnly: Boolean = DEFAULT_FEED_VEHICLE_ONLY
        @Volatile private var currentVehicleModelName: String = DEFAULT_VEHICLE_MODEL_NAME
        @Volatile private var vehicleInterpreter: Interpreter? = null
        @Volatile private var loadedVehicleModelName: String = ""
        @Volatile private var vehicleModelFailed: Boolean = false
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
            val flashCapture = prefs.getBoolean(PREF_FLASH_CAPTURE, DEFAULT_FLASH_CAPTURE)
            val flashIso = prefs.getInt(PREF_FLASH_CAPTURE_ISO, DEFAULT_FLASH_CAPTURE_ISO).coerceIn(50, 6400)
            val flashShutter = prefs.getLong(PREF_FLASH_CAPTURE_SHUTTER_NS, DEFAULT_FLASH_CAPTURE_SHUTTER_NS).coerceIn(100_000L, 100_000_000L)
            val motionDiff = prefs.getFloat(PREF_MOTION_DIFF_THRESHOLD, DEFAULT_MOTION_DIFF_THRESHOLD).coerceIn(5f, 60f)
            val motionArea = prefs.getFloat(PREF_MOTION_AREA_RATIO, DEFAULT_MOTION_AREA_RATIO).coerceIn(0.01f, 0.30f)
            val motionAlpha = prefs.getFloat(PREF_MOTION_BG_ALPHA, DEFAULT_MOTION_BG_ALPHA).coerceIn(0.01f, 0.30f)
            val motionMinCells = prefs.getInt(PREF_MOTION_CROP_MIN_CELLS, DEFAULT_MOTION_CROP_MIN_CELLS).coerceIn(2, 30)
            val motionPad = prefs.getFloat(PREF_MOTION_CROP_PAD_RATIO, DEFAULT_MOTION_CROP_PAD_RATIO).coerceIn(0.05f, 0.60f)
            val motionBackoff = prefs.getLong(PREF_MOTION_BACKOFF_MS, DEFAULT_MOTION_BACKOFF_MS).coerceIn(300L, 3000L)
            val motionTorchHold = prefs.getLong(PREF_MOTION_TORCH_HOLD_MS, DEFAULT_MOTION_TORCH_HOLD_MS).coerceIn(1000L, 15000L)
            val torchSettle = prefs.getLong(PREF_TORCH_SETTLE_MS, DEFAULT_TORCH_SETTLE_MS).coerceIn(300L, 3000L)
            val globalJump = prefs.getFloat(PREF_MOTION_GLOBAL_JUMP_RATIO, DEFAULT_MOTION_GLOBAL_JUMP_RATIO).coerceIn(0.50f, 0.95f)
            val headlightCap = prefs.getBoolean(PREF_HEADLIGHT_CAPTURE, DEFAULT_HEADLIGHT_CAPTURE)
            val headlightDiff = prefs.getFloat(PREF_HEADLIGHT_DIFF, DEFAULT_HEADLIGHT_DIFF).coerceIn(30f, 120f)
            val headlightMin = prefs.getFloat(PREF_HEADLIGHT_MIN_RATIO, DEFAULT_HEADLIGHT_MIN_RATIO).coerceIn(0.01f, 0.20f)
            val headlightMax = prefs.getFloat(PREF_HEADLIGHT_MAX_RATIO, DEFAULT_HEADLIGHT_MAX_RATIO).coerceIn(headlightMin + 0.05f, 0.90f)
            val aeRegion = prefs.getBoolean(PREF_AE_REGION, DEFAULT_AE_REGION)
            val antiOverexp = prefs.getBoolean(PREF_ANTI_OVEREXP, DEFAULT_ANTI_OVEREXP)
            val captureMode = sanitizeCaptureMode(prefs.getString(PREF_FLASH_CAPTURE_MODE, DEFAULT_FLASH_CAPTURE_MODE))
            val skipFast = prefs.getBoolean(PREF_SKIP_FAST_SHUTTER, DEFAULT_SKIP_FAST_SHUTTER)
            val vehicleFilterMode = sanitizeVehicleFilterMode(prefs.getString(PREF_VEHICLE_FILTER_MODE, DEFAULT_VEHICLE_FILTER_MODE))
            val vehicleConf = prefs.getFloat(PREF_VEHICLE_CONF_THRESHOLD, DEFAULT_VEHICLE_CONF_THRESHOLD).coerceIn(0.15f, 0.85f)
            val vehicleCrop = prefs.getBoolean(PREF_VEHICLE_CROP_ENABLED, DEFAULT_VEHICLE_CROP_ENABLED)
            val torchTriggerMode = sanitizeTorchTriggerMode(prefs.getString(PREF_MOTION_TORCH_TRIGGER_MODE, DEFAULT_MOTION_TORCH_TRIGGER_MODE))
            val feedVehicleOnly = prefs.getBoolean(PREF_FEED_VEHICLE_ONLY, DEFAULT_FEED_VEHICLE_ONLY)
            val vehicleModelName = sanitizeVehicleModelName(context, prefs.getString(PREF_VEHICLE_MODEL_NAME, DEFAULT_VEHICLE_MODEL_NAME))
            return AlprConfig(
                detLevel, maxNum, boxConf, recConf, threads, intervalMs, useGpu, motionGate,
                roiEnabled, roiX, roiY, roiW, roiH, motionCrop, motionTorch,
                flashCapture, flashIso, flashShutter,
                motionDiff, motionArea, motionAlpha, motionMinCells, motionPad, motionBackoff, motionTorchHold,
                torchSettle, globalJump,
                headlightCap, headlightDiff, headlightMin, headlightMax,
                aeRegion, antiOverexp, captureMode, skipFast,
                vehicleFilterMode, vehicleConf, vehicleCrop, torchTriggerMode, feedVehicleOnly,
                vehicleModelName
            )
        }

        private fun sanitizeVehicleModelName(context: Context, name: String?): String {
            val clean = name?.trim()?.substringAfterLast('/')?.substringAfterLast('\\').orEmpty()
            if (clean.isBlank()) return DEFAULT_VEHICLE_MODEL_NAME
            if (BUILTIN_VEHICLE_MODELS.any { it.first == clean }) return clean
            val customFile = File(File(context.filesDir, "vehicle_models"), clean)
            return if (customFile.exists() && customFile.isFile) clean else DEFAULT_VEHICLE_MODEL_NAME
        }

        fun listVehicleModelsJson(context: Context): String {
            val current = loadConfig(context).vehicleModelName
            val arr = org.json.JSONArray()
            for ((file, label) in BUILTIN_VEHICLE_MODELS) {
                arr.put(org.json.JSONObject().apply {
                    put("name", file)
                    put("label", label)
                    put("builtin", true)
                })
            }
            val dir = File(context.filesDir, "vehicle_models")
            val customFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".tflite", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?: emptyList()
            for (f in customFiles) {
                if (BUILTIN_VEHICLE_MODELS.any { it.first == f.name }) continue
                val sizeMb = String.format(Locale.US, "%.1f MB", f.length() / (1024.0 * 1024.0))
                arr.put(org.json.JSONObject().apply {
                    put("name", f.name)
                    put("label", "自定义: ${f.name} ($sizeMb)")
                    put("builtin", false)
                })
            }
            return org.json.JSONObject().apply {
                put("current", current)
                put("models", arr)
            }.toString()
        }

        fun saveCustomVehicleModel(context: Context, rawName: String, bytes: ByteArray): Pair<Boolean, String> {
            val safeName = rawName.trim().substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (safeName.isBlank() || !safeName.endsWith(".tflite", ignoreCase = true) || bytes.size < 1024) {
                return false to "文件名必须为 .tflite 且内容有效"
            }
            val dir = File(context.filesDir, "vehicle_models")
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, safeName)
            outFile.writeBytes(bytes)
            return true to safeName
        }

        fun deleteCustomVehicleModel(context: Context, rawName: String): Boolean {
            val safeName = rawName.trim().substringAfterLast('/').substringAfterLast('\\')
            if (BUILTIN_VEHICLE_MODELS.any { it.first == safeName }) return false
            val target = File(File(context.filesDir, "vehicle_models"), safeName)
            return target.exists() && target.delete()
        }

        private fun sanitizeTorchTriggerMode(mode: String?): String = when (mode) {
            "vehicle", "headlight", "vehicle_or_headlight" -> mode
            else -> DEFAULT_MOTION_TORCH_TRIGGER_MODE
        }

        private fun sanitizeCaptureMode(mode: String?): String = when (mode) {
            "ae_region", "anti_overexposure", "ae_region_peak" -> mode
            else -> DEFAULT_FLASH_CAPTURE_MODE
        }

        private fun sanitizeVehicleFilterMode(mode: String?): String = when (mode) {
            "heuristic", "tflite" -> mode
            else -> DEFAULT_VEHICLE_FILTER_MODE
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
                motionTorchEnabled = config.motionTorchEnabled,
                flashCaptureEnabled = config.flashCaptureEnabled,
                flashCaptureIso = config.flashCaptureIso.coerceIn(50, 6400),
                flashCaptureShutterNs = config.flashCaptureShutterNs.coerceIn(100_000L, 100_000_000L),
                motionDiffThreshold = config.motionDiffThreshold.coerceIn(5f, 60f),
                motionAreaRatio = config.motionAreaRatio.coerceIn(0.01f, 0.30f),
                motionBgAlpha = config.motionBgAlpha.coerceIn(0.01f, 0.30f),
                motionCropMinCells = config.motionCropMinCells.coerceIn(2, 30),
                motionCropPadRatio = config.motionCropPadRatio.coerceIn(0.05f, 0.60f),
                motionBackoffMs = config.motionBackoffMs.coerceIn(300L, 3000L),
                motionTorchHoldMs = config.motionTorchHoldMs.coerceIn(1000L, 15000L),
                torchSettleMs = config.torchSettleMs.coerceIn(300L, 3000L),
                motionGlobalJumpRatio = config.motionGlobalJumpRatio.coerceIn(0.50f, 0.95f),
                headlightCaptureEnabled = config.headlightCaptureEnabled,
                headlightDiffThreshold = config.headlightDiffThreshold.coerceIn(30f, 120f),
                headlightMinRatio = config.headlightMinRatio.coerceIn(0.01f, 0.20f),
                headlightMaxRatio = config.headlightMaxRatio.coerceIn(config.headlightMinRatio.coerceIn(0.01f, 0.20f) + 0.05f, 0.90f),
                aeRegionEnabled = config.aeRegionEnabled,
                antiOverexposureEnabled = config.antiOverexposureEnabled,
                flashCaptureMode = sanitizeCaptureMode(config.flashCaptureMode),
                skipCaptureIfFastShutter = config.skipCaptureIfFastShutter,
                vehicleFilterMode = sanitizeVehicleFilterMode(config.vehicleFilterMode),
                vehicleConfThreshold = config.vehicleConfThreshold.coerceIn(0.15f, 0.85f),
                vehicleCropEnabled = config.vehicleCropEnabled,
                motionTorchTriggerMode = sanitizeTorchTriggerMode(config.motionTorchTriggerMode),
                feedVehicleOnly = config.feedVehicleOnly,
                vehicleModelName = sanitizeVehicleModelName(context, config.vehicleModelName)
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
                .putBoolean(PREF_FLASH_CAPTURE, sanitized.flashCaptureEnabled)
                .putInt(PREF_FLASH_CAPTURE_ISO, sanitized.flashCaptureIso)
                .putLong(PREF_FLASH_CAPTURE_SHUTTER_NS, sanitized.flashCaptureShutterNs)
                .putFloat(PREF_MOTION_DIFF_THRESHOLD, sanitized.motionDiffThreshold)
                .putFloat(PREF_MOTION_AREA_RATIO, sanitized.motionAreaRatio)
                .putFloat(PREF_MOTION_BG_ALPHA, sanitized.motionBgAlpha)
                .putInt(PREF_MOTION_CROP_MIN_CELLS, sanitized.motionCropMinCells)
                .putFloat(PREF_MOTION_CROP_PAD_RATIO, sanitized.motionCropPadRatio)
                .putLong(PREF_MOTION_BACKOFF_MS, sanitized.motionBackoffMs)
                .putLong(PREF_MOTION_TORCH_HOLD_MS, sanitized.motionTorchHoldMs)
                .putLong(PREF_TORCH_SETTLE_MS, sanitized.torchSettleMs)
                .putFloat(PREF_MOTION_GLOBAL_JUMP_RATIO, sanitized.motionGlobalJumpRatio)
                .putBoolean(PREF_HEADLIGHT_CAPTURE, sanitized.headlightCaptureEnabled)
                .putFloat(PREF_HEADLIGHT_DIFF, sanitized.headlightDiffThreshold)
                .putFloat(PREF_HEADLIGHT_MIN_RATIO, sanitized.headlightMinRatio)
                .putFloat(PREF_HEADLIGHT_MAX_RATIO, sanitized.headlightMaxRatio)
                .putBoolean(PREF_AE_REGION, sanitized.aeRegionEnabled)
                .putBoolean(PREF_ANTI_OVEREXP, sanitized.antiOverexposureEnabled)
                .putString(PREF_FLASH_CAPTURE_MODE, sanitized.flashCaptureMode)
                .putBoolean(PREF_SKIP_FAST_SHUTTER, sanitized.skipCaptureIfFastShutter)
                .putString(PREF_VEHICLE_FILTER_MODE, sanitized.vehicleFilterMode)
                .putFloat(PREF_VEHICLE_CONF_THRESHOLD, sanitized.vehicleConfThreshold)
                .putBoolean(PREF_VEHICLE_CROP_ENABLED, sanitized.vehicleCropEnabled)
                .putString(PREF_MOTION_TORCH_TRIGGER_MODE, sanitized.motionTorchTriggerMode)
                .putBoolean(PREF_FEED_VEHICLE_ONLY, sanitized.feedVehicleOnly)
                .putString(PREF_VEHICLE_MODEL_NAME, sanitized.vehicleModelName)
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
            currentMotionDiffThreshold = config.motionDiffThreshold
            currentMotionAreaRatio = config.motionAreaRatio
            currentMotionBgAlpha = config.motionBgAlpha
            currentMotionCropMinCells = config.motionCropMinCells
            currentMotionCropPadRatio = config.motionCropPadRatio
            currentMotionBackoffMs = config.motionBackoffMs
            currentMotionTorchHoldMs = config.motionTorchHoldMs
            currentTorchSettleMs = config.torchSettleMs
            currentMotionGlobalJumpRatio = config.motionGlobalJumpRatio
            currentHeadlightCaptureEnabled = config.headlightCaptureEnabled
            currentHeadlightDiffThreshold = config.headlightDiffThreshold
            currentHeadlightMinRatio = config.headlightMinRatio
            currentHeadlightMaxRatio = config.headlightMaxRatio
            currentAntiOverexposureEnabled = config.antiOverexposureEnabled
            currentVehicleFilterMode = sanitizeVehicleFilterMode(config.vehicleFilterMode)
            currentVehicleConfThreshold = config.vehicleConfThreshold
            currentVehicleCropEnabled = config.vehicleCropEnabled
            currentMotionTorchTriggerMode = sanitizeTorchTriggerMode(config.motionTorchTriggerMode)
            currentFeedVehicleOnly = config.feedVehicleOnly
            currentVehicleModelName = sanitizeVehicleModelName(appContext, config.vehicleModelName)
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
        currentMotionDiffThreshold = saved.motionDiffThreshold
        currentMotionAreaRatio = saved.motionAreaRatio
        currentMotionBgAlpha = saved.motionBgAlpha
        currentMotionCropMinCells = saved.motionCropMinCells
        currentMotionCropPadRatio = saved.motionCropPadRatio
        currentMotionBackoffMs = saved.motionBackoffMs
        currentMotionTorchHoldMs = saved.motionTorchHoldMs
        currentTorchSettleMs = saved.torchSettleMs
        currentMotionGlobalJumpRatio = saved.motionGlobalJumpRatio
        currentHeadlightCaptureEnabled = saved.headlightCaptureEnabled
        currentHeadlightDiffThreshold = saved.headlightDiffThreshold
        currentHeadlightMinRatio = saved.headlightMinRatio
        currentHeadlightMaxRatio = saved.headlightMaxRatio
        currentAntiOverexposureEnabled = saved.antiOverexposureEnabled
        currentVehicleFilterMode = sanitizeVehicleFilterMode(saved.vehicleFilterMode)
        currentVehicleConfThreshold = saved.vehicleConfThreshold
        currentVehicleCropEnabled = saved.vehicleCropEnabled
        currentMotionTorchTriggerMode = sanitizeTorchTriggerMode(saved.motionTorchTriggerMode)
        currentFeedVehicleOnly = saved.feedVehicleOnly
        currentVehicleModelName = saved.vehicleModelName
        if (!saved.antiOverexposureEnabled) {
            onAntiOverexposureStep?.invoke(0)
        }
        if ((!saved.motionGateEnabled || !saved.motionTorchEnabled) && autoTorchActive) {
            autoTorchActive = false
            onMotionTorchChanged?.invoke(false)
        }
        if (!saved.headlightCaptureEnabled && headlightCaptureActive) {
            headlightCaptureActive = false
            onHeadlightCaptureChanged?.invoke(false)
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
        return !isProcessing.get() && (now - lastRunTimeMs >= currentIntervalMs)
    }

    private data class NormRect(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
    private data class MotionAnalysis(
        val hasMotion: Boolean,
        val cropNorm: NormRect?,
        val clusterNorm: NormRect? = null,
        val hasPartialBrightJump: Boolean = false,
        val glareNorm: NormRect? = null
    )

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

    fun notifyTorchTransition(turnedOn: Boolean, settleMs: Long = currentTorchSettleMs) {
        torchTransitionUntilMs = System.currentTimeMillis() + settleMs
        torchTransitionSuppressMotion = !turnedOn
        pendingBgReseed = true
        if (!turnedOn) {
            suppressSeedMotion = true
        }
    }

    /**
     * 160×90 白顶帽形态学 (Top-Hat = I - Open(I)) + YCbCr 色度约束 + 连通域分析检测运动强光。
     */
    private fun detectMovingGlareTopHat(
        nv21: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        roi: NormRect,
        brightThreshold: Float,
        bgAlpha: Float
    ): NormRect? {
        val total = GLARE_W * GLARE_H
        val ySize = width * height
        if (nv21.size < ySize) return null
        val roiW = roi.x2 - roi.x1
        val roiH = roi.y2 - roi.y1

        var idx = 0
        for (gy in 0 until GLARE_H) {
            val ry = roi.y1 + ((gy + 0.5f) / GLARE_H) * roiH
            for (gx in 0 until GLARE_W) {
                val rx = roi.x1 + ((gx + 0.5f) / GLARE_W) * roiW
                val (sx, sy) = rotatedToSensorPixel(rx, ry, width, height, rotationDegrees)
                glareY[idx] = nv21[sy * width + sx].toInt() and 0xFF
                val uvIdx = ySize + (sy shr 1) * width + (sx and 1.inv())
                glareV[idx] = nv21.getOrElse(uvIdx) { 128.toByte() }.toInt() and 0xFF
                glareU[idx] = nv21.getOrElse(uvIdx + 1) { 128.toByte() }.toInt() and 0xFF
                idx++
            }
        }
        if (!hasGlareBg) {
            for (i in 0 until total) glareBgY[i] = glareY[i].toFloat()
            hasGlareBg = true
            return null
        }

        // 可分离 9×9 形态学开运算：先腐蚀(min)后膨胀(max)
        val r = GLARE_OPEN_RADIUS
        for (y in 0 until GLARE_H) {
            val row = y * GLARE_W
            for (x in 0 until GLARE_W) {
                var m = 255
                val x0 = (x - r).coerceAtLeast(0)
                val x1 = (x + r).coerceAtMost(GLARE_W - 1)
                for (kx in x0..x1) {
                    val v = glareY[row + kx]
                    if (v < m) m = v
                }
                glareTemp[row + x] = m
            }
        }
        for (y in 0 until GLARE_H) {
            val y0 = (y - r).coerceAtLeast(0)
            val y1 = (y + r).coerceAtMost(GLARE_H - 1)
            val row = y * GLARE_W
            for (x in 0 until GLARE_W) {
                var m = 255
                for (ky in y0..y1) {
                    val v = glareTemp[ky * GLARE_W + x]
                    if (v < m) m = v
                }
                glareOpen[row + x] = m
            }
        }
        for (y in 0 until GLARE_H) {
            val row = y * GLARE_W
            for (x in 0 until GLARE_W) {
                var m = 0
                val x0 = (x - r).coerceAtLeast(0)
                val x1 = (x + r).coerceAtMost(GLARE_W - 1)
                for (kx in x0..x1) {
                    val v = glareOpen[row + kx]
                    if (v > m) m = v
                }
                glareTemp[row + x] = m
            }
        }
        for (y in 0 until GLARE_H) {
            val y0 = (y - r).coerceAtLeast(0)
            val y1 = (y + r).coerceAtMost(GLARE_H - 1)
            val row = y * GLARE_W
            for (x in 0 until GLARE_W) {
                var m = 0
                for (ky in y0..y1) {
                    val v = glareTemp[ky * GLARE_W + x]
                    if (v > m) m = v
                }
                glareOpen[row + x] = m
            }
        }

        // Top-Hat 孤立强光 或 饱和过曝光源核心(y>=215)，搭配时间正向突变与白/红车灯色度
        val topHatThresh = (brightThreshold * 0.45f).toInt().coerceIn(16, 70)
        val temporalThresh = brightThreshold * 0.40f
        var maskCount = 0
        var dbgMaxY = 0
        var dbgMaxTopHat = 0
        var dbgMaxBgDiff = 0f
        for (i in 0 until total) {
            val y = glareY[i]
            val u = glareU[i]
            val v = glareV[i]
            val topHat = y - glareOpen[i]
            val bgDiff = y - glareBgY[i]
            if (y > dbgMaxY) dbgMaxY = y
            if (topHat > dbgMaxTopHat) dbgMaxTopHat = topHat
            if (bgDiff > dbgMaxBgDiff) dbgMaxBgDiff = bgDiff
            val isWhiteHeadlight = kotlin.math.abs(u - 128) <= 50 && kotlin.math.abs(v - 128) <= 50 &&
                ((y >= 185 && topHat >= topHatThresh) || y >= 215)
            val isRedTaillight = y >= 130 && v >= 150 && (v - u) >= 28 && (topHat >= topHatThresh || y >= 180)
            val hit = (isWhiteHeadlight || isRedTaillight) && bgDiff >= temporalThresh
            glareMask[i] = hit
            if (hit) maskCount++
            glareBgY[i] += bgDiff * bgAlpha
        }
        val minArea = (total * currentHeadlightMinRatio * 0.015f).toInt().coerceIn(3, 15)
        if (dbgMaxY >= 190) {
            Log.d("ALPR_GLARE", "maxY=$dbgMaxY topHat=$dbgMaxTopHat(>=$topHatThresh) bgDiff=${dbgMaxBgDiff.toInt()}(>=$temporalThresh) mask=$maskCount(>=$minArea)")
        }
        if (maskCount < minArea) return null

        val maxArea = (total * currentHeadlightMaxRatio).toInt().coerceAtLeast(minArea + 16)
        glareVisited.fill(false)
        var unionMinX = GLARE_W
        var unionMinY = GLARE_H
        var unionMaxX = -1
        var unionMaxY = -1
        var validBlobs = 0

        for (start in 0 until total) {
            if (!glareMask[start] || glareVisited[start]) continue
            var head = 0
            var tail = 0
            glareVisited[start] = true
            glareBfsQueue[tail++] = start
            var count = 0
            var minX = start % GLARE_W; var maxX = minX
            var minY = start / GLARE_W; var maxY = minY
            while (head < tail) {
                val cur = glareBfsQueue[head++]
                count++
                val gx = cur % GLARE_W
                val gy = cur / GLARE_W
                if (gx < minX) minX = gx
                if (gx > maxX) maxX = gx
                if (gy < minY) minY = gy
                if (gy > maxY) maxY = gy
                if (gx > 0) { val n = cur - 1; if (glareMask[n] && !glareVisited[n]) { glareVisited[n] = true; glareBfsQueue[tail++] = n } }
                if (gx + 1 < GLARE_W) { val n = cur + 1; if (glareMask[n] && !glareVisited[n]) { glareVisited[n] = true; glareBfsQueue[tail++] = n } }
                if (gy > 0) { val n = cur - GLARE_W; if (glareMask[n] && !glareVisited[n]) { glareVisited[n] = true; glareBfsQueue[tail++] = n } }
                if (gy + 1 < GLARE_H) { val n = cur + GLARE_W; if (glareMask[n] && !glareVisited[n]) { glareVisited[n] = true; glareBfsQueue[tail++] = n } }
            }
            val bw = (maxX - minX + 1).toFloat()
            val bh = (maxY - minY + 1).toFloat().coerceAtLeast(1f)
            val aspect = bw / bh
            if (count in minArea..maxArea && aspect in 0.15f..7.0f) {
                validBlobs++
                if (minX < unionMinX) unionMinX = minX
                if (minY < unionMinY) unionMinY = minY
                if (maxX > unionMaxX) unionMaxX = maxX
                if (maxY > unionMaxY) unionMaxY = maxY
            }
        }
        if (validBlobs == 0 || unionMaxX < unionMinX) return null
        return NormRect(
            roi.x1 + (unionMinX.toFloat() / GLARE_W) * roiW,
            roi.y1 + (unionMinY.toFloat() / GLARE_H) * roiH,
            roi.x1 + ((unionMaxX + 1).toFloat() / GLARE_W) * roiW,
            roi.y1 + ((unionMaxY + 1).toFloat() / GLARE_H) * roiH
        )
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
            hasGlareBg = false
        }
        val nowMs = System.currentTimeMillis()
        if (nowMs < torchTransitionUntilMs) {
            hasLumaGrid = false
            hasGlareBg = false
            return MotionAnalysis(hasMotion = !torchTransitionSuppressMotion, cropNorm = null)
        }
        if (pendingBgReseed) {
            pendingBgReseed = false
            hasLumaGrid = false
            hasGlareBg = false
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
            val triggerMotion = !suppressSeedMotion
            suppressSeedMotion = false
            return MotionAnalysis(hasMotion = triggerMotion, cropNorm = null)
        }

        val shift = shiftSum / totalSamples
        val diffThreshold = currentMotionDiffThreshold
        val bgAlpha = currentMotionBgAlpha
        val brightThreshold = currentHeadlightDiffThreshold
        var changedCount = 0
        for (i in 0 until totalSamples) {
            val rawDiff = lumaCur[i] - lumaBg[i]
            val diff = kotlin.math.abs(rawDiff - shift)
            val changed = diff >= diffThreshold
            changedGrid[i] = changed
            if (changed) changedCount++
            lumaBg[i] += rawDiff * bgAlpha
        }

        val changedRatio = changedCount.toFloat() / totalSamples
        if (changedRatio >= currentMotionGlobalJumpRatio && kotlin.math.abs(shift) >= diffThreshold) {
            // 全屏均匀亮度剧变（如开关灯/AE突变），重设背景避免误判运动
            lumaCur.copyInto(lumaBg)
            hasGlareBg = false
            return MotionAnalysis(hasMotion = false, cropNorm = null)
        }

        // 方案一：160×90 白顶帽形态学 (Top-Hat) + YCbCr 色度过滤 + 连通域几何/时间差分
        val needGlareDetect = currentHeadlightCaptureEnabled ||
            (currentMotionTorchEnabled && (currentMotionTorchTriggerMode == "headlight" || currentMotionTorchTriggerMode == "vehicle_or_headlight"))
        var glareNorm: NormRect? = null
        var isPartialBrightJump = false
        if (changedRatio >= 0.70f) {
            // 仅超大面积场景切换/全屏突变时重置强光背景，避免暗室手电筒漫反射被误判为切场景
            hasGlareBg = false
            glareStreak = 0
        } else if (needGlareDetect) {
            val candidate = detectMovingGlareTopHat(nv21, width, height, rotationDegrees, roi, brightThreshold, bgAlpha)
            if (candidate != null) {
                glareStreak = (glareStreak + 1).coerceAtMost(10)
                glareNorm = candidate
                isPartialBrightJump = true
            } else {
                glareStreak = 0
            }
        } else {
            glareStreak = 0
        }
        if (changedRatio < currentMotionAreaRatio && !isPartialBrightJump) {
            return MotionAnalysis(hasMotion = false, cropNorm = null, glareNorm = glareNorm)
        }

        if (changedRatio >= MOTION_CROP_MAX_RATIO) {
            return MotionAnalysis(hasMotion = true, cropNorm = null, hasPartialBrightJump = isPartialBrightJump, glareNorm = glareNorm)
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
        if (bestCount < currentMotionCropMinCells || clusterRatio >= MOTION_CROP_MAX_RATIO) {
            return MotionAnalysis(hasMotion = true, cropNorm = null, hasPartialBrightJump = isPartialBrightJump, glareNorm = glareNorm)
        }

        // 将团块外接框映射回全图归一化坐标，四周各外扩 28% 并按最小尺寸 640×360 兜底
        val rawX1 = roi.x1 + (bestMinX.toFloat() / LUMA_GRID_W) * roiW
        val rawY1 = roi.y1 + (bestMinY.toFloat() / LUMA_GRID_H) * roiH
        val rawX2 = roi.x1 + ((bestMaxX + 1).toFloat() / LUMA_GRID_W) * roiW
        val rawY2 = roi.y1 + ((bestMaxY + 1).toFloat() / LUMA_GRID_H) * roiH
        val clusterNorm = NormRect(rawX1, rawY1, rawX2, rawY2)

        if (!currentMotionCropEnabled || latestPlates.isNotEmpty()) {
            return MotionAnalysis(hasMotion = true, cropNorm = null, clusterNorm = clusterNorm, hasPartialBrightJump = isPartialBrightJump, glareNorm = glareNorm)
        }

        var cx1 = rawX1
        var cy1 = rawY1
        var cx2 = rawX2
        var cy2 = rawY2

        val padW = (cx2 - cx1) * currentMotionCropPadRatio
        val padH = (cy2 - cy1) * currentMotionCropPadRatio
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
            return MotionAnalysis(hasMotion = true, cropNorm = null, clusterNorm = clusterNorm, hasPartialBrightJump = isPartialBrightJump, glareNorm = glareNorm)
        }
        return MotionAnalysis(hasMotion = true, cropNorm = NormRect(cx1, cy1, cx2, cy2), clusterNorm = clusterNorm, hasPartialBrightJump = isPartialBrightJump, glareNorm = glareNorm)
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

    private var prevHeuristicCx = -1f
    private var prevHeuristicCy = -1f
    private var prevHeuristicTs = 0L
    private var heuristicMoveStreak = 0
    private var vehicleInputBuffer: ByteBuffer? = null
    private var vehicleCropBuffer = ByteArray(0)

    private fun acquireVehicleCropBuffer(size: Int): ByteArray {
        if (vehicleCropBuffer.size < size) vehicleCropBuffer = ByteArray(size)
        return vehicleCropBuffer
    }

    private fun passesHeuristicVehicleFilter(cluster: NormRect?, nowMs: Long): Pair<Boolean, String> {
        if (cluster == null) return true to "pass:heuristic_full"
        val cw = (cluster.x2 - cluster.x1).coerceAtLeast(0.001f)
        val ch = (cluster.y2 - cluster.y1).coerceAtLeast(0.001f)
        val area = cw * ch
        val aspect = cw / ch
        if (area < 0.008f) return false to "skip:heuristic_small"
        if (aspect < 0.50f || aspect > 6.5f) return false to "skip:heuristic_aspect"

        val cx = (cluster.x1 + cluster.x2) * 0.5f
        val cy = (cluster.y1 + cluster.y2) * 0.5f
        val dt = nowMs - prevHeuristicTs
        val dist = if (prevHeuristicCx >= 0f && dt in 1L..1500L) {
            kotlin.math.hypot((cx - prevHeuristicCx).toDouble(), (cy - prevHeuristicCy).toDouble()).toFloat()
        } else {
            0f
        }
        if (dist >= 0.006f && dt in 1L..1500L) {
            heuristicMoveStreak = (heuristicMoveStreak + 1).coerceAtMost(10)
        } else if (dt > 1500L) {
            heuristicMoveStreak = 0
        } else {
            heuristicMoveStreak = (heuristicMoveStreak - 1).coerceAtLeast(0)
        }
        prevHeuristicCx = cx
        prevHeuristicCy = cy
        prevHeuristicTs = nowMs

        // 大目标直接放行，中小目标要求具备连续位移轨迹以过滤树叶/光影抖动
        if (area < 0.045f && heuristicMoveStreak < 1) {
            return false to "skip:heuristic_jitter"
        }
        return true to "pass:heuristic"
    }

    private fun ensureVehicleInterpreter(): Interpreter? {
        val modelName = currentVehicleModelName.ifBlank { DEFAULT_VEHICLE_MODEL_NAME }
        val existing = vehicleInterpreter
        if (existing != null && loadedVehicleModelName == modelName) return existing
        if (vehicleModelFailed && loadedVehicleModelName == modelName) return null
        if (loadedVehicleModelName != modelName) {
            try { existing?.close() } catch (_: Exception) {}
            vehicleInterpreter = null
            vehicleModelFailed = false
            loadedVehicleModelName = modelName
        }
        return try {
            val customFile = File(File(appContext.filesDir, "vehicle_models"), modelName)
            val mapped = if (customFile.exists() && customFile.isFile) {
                FileInputStream(customFile).use { fis ->
                    fis.channel.map(FileChannel.MapMode.READ_ONLY, 0L, customFile.length())
                }
            } else {
                val afd = appContext.assets.openFd(modelName)
                val buf = FileInputStream(afd.fileDescriptor).use { fis ->
                    fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                }
                afd.close()
                buf
            }
            val opts = Interpreter.Options().setNumThreads(2)
            val interp = Interpreter(mapped, opts)
            vehicleInterpreter = interp
            Log.i("ALPR", "TFLite 车辆预检模型加载完成: $modelName, outputs=${interp.outputTensorCount}, inType=${interp.getInputTensor(0).dataType()}")
            interp
        } catch (e: Exception) {
            vehicleModelFailed = true
            Log.e("ALPR", "加载 TFLite 车辆预检模型失败($modelName): ${e.message}")
            null
        }
    }

    private fun nv21ToRgbBuffer(
        nv21: ByteArray,
        srcW: Int,
        srcH: Int,
        rotationDegrees: Int,
        dstW: Int,
        dstH: Int,
        isFloat32: Boolean
    ): ByteBuffer {
        val needed = dstW * dstH * 3 * (if (isFloat32) 4 else 1)
        val existing = vehicleInputBuffer
        val buf = if (existing != null && existing.capacity() >= needed) {
            existing
        } else {
            ByteBuffer.allocateDirect(needed)
                .order(ByteOrder.nativeOrder())
                .also { vehicleInputBuffer = it }
        }
        buf.clear()
        buf.limit(needed)
        val rotW = if (rotationDegrees == 90 || rotationDegrees == 270) srcH else srcW
        val rotH = if (rotationDegrees == 90 || rotationDegrees == 270) srcW else srcH
        val ySize = srcW * srcH
        val inv255 = 1.0f / 255.0f
        for (dy in 0 until dstH) {
            val ry = (dy * rotH) / dstH
            for (dx in 0 until dstW) {
                val rx = (dx * rotW) / dstW
                val sx: Int
                val sy: Int
                when (rotationDegrees) {
                    90 -> { sx = ry; sy = srcH - 1 - rx }
                    180 -> { sx = srcW - 1 - rx; sy = srcH - 1 - ry }
                    270 -> { sx = srcW - 1 - ry; sy = rx }
                    else -> { sx = rx; sy = ry }
                }
                val cx = sx.coerceIn(0, srcW - 1)
                val cy = sy.coerceIn(0, srcH - 1)
                val yVal = (nv21[cy * srcW + cx].toInt() and 0xFF) - 16
                val uvIdx = ySize + (cy shr 1) * srcW + (cx and 1.inv())
                val vVal = (nv21.getOrElse(uvIdx) { 128.toByte() }.toInt() and 0xFF) - 128
                val uVal = (nv21.getOrElse(uvIdx + 1) { 128.toByte() }.toInt() and 0xFF) - 128
                val y1192 = 1192 * yVal.coerceAtLeast(0)
                val r = ((y1192 + 1634 * vVal) shr 10).coerceIn(0, 255)
                val g = ((y1192 - 833 * vVal - 400 * uVal) shr 10).coerceIn(0, 255)
                val b = ((y1192 + 2066 * uVal) shr 10).coerceIn(0, 255)
                if (isFloat32) {
                    buf.putFloat(r * inv255)
                    buf.putFloat(g * inv255)
                    buf.putFloat(b * inv255)
                } else {
                    buf.put(r.toByte())
                    buf.put(g.toByte())
                    buf.put(b.toByte())
                }
            }
        }
        buf.rewind()
        return buf
    }

    private fun nmsVehicles(candidates: List<DetectedVehicle>, iouThresh: Float = 0.45f): List<DetectedVehicle> {
        if (candidates.size <= 1) return candidates
        val sorted = candidates.sortedByDescending { it.confidence }
        val kept = ArrayList<DetectedVehicle>(sorted.size)
        for (cand in sorted) {
            var suppressed = false
            for (k in kept) {
                val ix1 = maxOf(cand.x1, k.x1)
                val iy1 = maxOf(cand.y1, k.y1)
                val ix2 = minOf(cand.x2, k.x2)
                val iy2 = minOf(cand.y2, k.y2)
                val iw = (ix2 - ix1).coerceAtLeast(0f)
                val ih = (iy2 - iy1).coerceAtLeast(0f)
                val inter = iw * ih
                if (inter > 0f) {
                    val areaA = (cand.x2 - cand.x1) * (cand.y2 - cand.y1)
                    val areaB = (k.x2 - k.x1) * (k.y2 - k.y1)
                    val iou = inter / (areaA + areaB - inter).coerceAtLeast(1e-6f)
                    if (iou > iouThresh) { suppressed = true; break }
                }
            }
            if (!suppressed) kept.add(cand)
        }
        return kept
    }

    private fun detectVehiclesTflite(
        nv21: ByteArray,
        inferW: Int,
        inferH: Int,
        rotationDegrees: Int,
        threshold: Float
    ): Pair<List<DetectedVehicle>, Long>? {
        val interp = ensureVehicleInterpreter() ?: return null
        val t0 = System.nanoTime()
        val inTensor = interp.getInputTensor(0)
        val inShape = inTensor.shape()
        val dstH = inShape.getOrElse(1) { 320 }
        val dstW = inShape.getOrElse(2) { 320 }
        val isFloat32 = inTensor.dataType() == DataType.FLOAT32
        val inputBuf = nv21ToRgbBuffer(nv21, inferW, inferH, rotationDegrees, dstW, dstH, isFloat32)

        if (interp.outputTensorCount >= 4) {
            val maxDet = interp.getOutputTensor(0).shape().getOrElse(1) { 10 }
            val locations = Array(1) { Array(maxDet) { FloatArray(4) } }
            val classes = Array(1) { FloatArray(maxDet) }
            val scores = Array(1) { FloatArray(maxDet) }
            val numDetections = FloatArray(1)
            val outputs = mapOf<Int, Any>(
                0 to locations,
                1 to classes,
                2 to scores,
                3 to numDetections
            )
            interp.runForMultipleInputsOutputs(arrayOf(inputBuf), outputs)
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
            val count = numDetections[0].toInt().coerceIn(0, maxDet)
            val out = ArrayList<DetectedVehicle>(count)
            for (i in 0 until count) {
                val score = scores[0][i]
                if (score < threshold) continue
                val clsId = classes[0][i].toInt()
                val label = VEHICLE_CLASS_NAMES[clsId] ?: continue
                val box = locations[0][i]
                val y1 = box[0].coerceIn(0f, 1f)
                val x1 = box[1].coerceIn(0f, 1f)
                val y2 = box[2].coerceIn(0f, 1f)
                val x2 = box[3].coerceIn(0f, 1f)
                if (x2 > x1 && y2 > y1) {
                    out.add(DetectedVehicle(label = label, confidence = score, x1 = x1, y1 = y1, x2 = x2, y2 = y2))
                }
            }
            return out to elapsedMs
        }

        // 支持 YOLOv8 / YOLO11 TFLite 单输出格式: [1, 4+C, N] 或 [1, N, 4+C]
        val outShape = interp.getOutputTensor(0).shape()
        if (outShape.size == 3 && outShape[0] == 1) {
            val d1 = outShape[1]
            val d2 = outShape[2]
            val outArr = Array(1) { Array(d1) { FloatArray(d2) } }
            interp.runForMultipleInputsOutputs(arrayOf(inputBuf), mapOf(0 to outArr))
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
            val transposed = d1 < d2 // [1, 84, 8400] -> channels first
            val numBoxes = if (transposed) d2 else d1
            val numAttrs = if (transposed) d1 else d2
            val numClasses = (numAttrs - 4).coerceAtLeast(1)
            val raw = ArrayList<DetectedVehicle>()
            for (b in 0 until numBoxes) {
                var bestCls = -1
                var bestScore = threshold
                for (c in 0 until numClasses) {
                    if (numClasses >= 20 && c !in VEHICLE_CLASS_NAMES) continue
                    val sc = if (transposed) outArr[0][4 + c][b] else outArr[0][b][4 + c]
                    if (sc > bestScore) {
                        bestScore = sc
                        bestCls = c
                    }
                }
                if (bestCls < 0) continue
                var cx = if (transposed) outArr[0][0][b] else outArr[0][b][0]
                var cy = if (transposed) outArr[0][1][b] else outArr[0][b][1]
                var bw = if (transposed) outArr[0][2][b] else outArr[0][b][2]
                var bh = if (transposed) outArr[0][3][b] else outArr[0][b][3]
                if (cx > 2f || cy > 2f || bw > 2f || bh > 2f) {
                    cx /= dstW.toFloat(); bw /= dstW.toFloat()
                    cy /= dstH.toFloat(); bh /= dstH.toFloat()
                }
                val x1 = (cx - bw * 0.5f).coerceIn(0f, 1f)
                val y1 = (cy - bh * 0.5f).coerceIn(0f, 1f)
                val x2 = (cx + bw * 0.5f).coerceIn(0f, 1f)
                val y2 = (cy + bh * 0.5f).coerceIn(0f, 1f)
                if (x2 > x1 && y2 > y1) {
                    val lbl = VEHICLE_CLASS_NAMES[bestCls] ?: "vehicle"
                    raw.add(DetectedVehicle(label = lbl, confidence = bestScore, x1 = x1, y1 = y1, x2 = x2, y2 = y2))
                }
            }
            return nmsVehicles(raw).take(8) to elapsedMs
        }

        return emptyList<DetectedVehicle>() to 0L
    }

    private fun updateAutoTorch(triggered: Boolean, nowMs: Long) {
        if (!currentMotionTorchEnabled) return
        if (triggered) {
            lastMotionForTorchMs = nowMs
            if (!autoTorchActive) {
                autoTorchActive = true
                onMotionTorchChanged?.invoke(true)
            }
        } else if (autoTorchActive && latestPlates.isEmpty() && nowMs - lastMotionForTorchMs >= currentMotionTorchHoldMs) {
            autoTorchActive = false
            onMotionTorchChanged?.invoke(false)
        }
    }

    private fun updateHeadlightCapture(triggered: Boolean, nowMs: Long) {
        if (!currentHeadlightCaptureEnabled) {
            if (headlightCaptureActive) {
                headlightCaptureActive = false
                onHeadlightCaptureChanged?.invoke(false)
            }
            return
        }
        if (triggered) {
            lastHeadlightTriggerMs = nowMs
            if (!headlightCaptureActive) {
                headlightCaptureActive = true
                onHeadlightCaptureChanged?.invoke(true)
            }
        } else if (headlightCaptureActive && latestPlates.isEmpty() && nowMs - lastHeadlightTriggerMs >= currentMotionTorchHoldMs) {
            headlightCaptureActive = false
            onHeadlightCaptureChanged?.invoke(false)
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
        // 节流阀：按车牌采样间隔运行动作/强光检测；仅对静止时的 ALPR 推理做退避
        if (now - lastRunTimeMs < currentIntervalMs || !isProcessing.compareAndSet(false, true)) {
            return
        }
        lastRunTimeMs = now

        val baseRoi = activeRoiNorm()
        var targetNorm: NormRect? = if (currentRoiEnabled) baseRoi else null
        var motionHasMotion = false
        var motionPartialBrightJump = false
        var motionCropActive = false
        var motionCluster: NormRect? = null
        var motionGlare: NormRect? = null
        var sampledCurGrid = false

        if (currentMotionGateEnabled || currentHeadlightCaptureEnabled) {
            val motion = analyzeSceneMotion(nv21Source, width, height, rotationDegrees, baseRoi)
            sampledCurGrid = true
            motionHasMotion = motion.hasMotion
            motionPartialBrightJump = motion.hasPartialBrightJump
            motionCluster = motion.clusterNorm
            motionGlare = motion.glareNorm
            if (currentMotionGateEnabled && currentMotionTorchEnabled) {
                when (currentMotionTorchTriggerMode) {
                    "motion" -> updateAutoTorch(motion.hasMotion, now)
                    "headlight" -> updateAutoTorch(motion.hasPartialBrightJump, now)
                    "vehicle_or_headlight" -> {
                        if (motion.hasPartialBrightJump) {
                            updateAutoTorch(true, now)
                        } else if (!motion.hasMotion) {
                            updateAutoTorch(false, now)
                        }
                    }
                    else -> if (!motion.hasMotion) updateAutoTorch(false, now)
                }
            }
            if (currentHeadlightCaptureEnabled) {
                updateHeadlightCapture(motion.hasPartialBrightJump, now)
            } else if (headlightCaptureActive) {
                updateHeadlightCapture(false, now)
            }
            if (currentMotionGateEnabled) {
                if (!motion.hasMotion && latestPlates.isEmpty()) {
                    val rotW = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
                    val rotH = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
                    latestMotionDebug = MotionDebugInfo(
                        hasMotion = false,
                        motionCropActive = false,
                        clusterX1 = 0f, clusterY1 = 0f, clusterX2 = 0f, clusterY2 = 0f,
                        cropX1 = 0f, cropY1 = 0f, cropX2 = 0f, cropY2 = 0f,
                        cropW = 0, cropH = 0, frameW = rotW, frameH = rotH,
                        timestampMs = now,
                        headlightCaptureActive = headlightCaptureActive
                    )
                    consecutiveStaticChecks++
                    val alprBackoffInterval = maxOf(currentIntervalMs, currentMotionBackoffMs)
                    if (consecutiveStaticChecks >= 2 && (now - lastAlprHeartbeatMs < alprBackoffInterval || consecutiveStaticChecks % STATIC_HEARTBEAT_INTERVAL != 0)) {
                        effectiveIntervalMs = currentIntervalMs
                        latestVehicles = emptyList()
                        latestVehicleFilterStatus = "idle"
                        isProcessing.set(false)
                        return
                    }
                    lastAlprHeartbeatMs = now
                } else if (motion.hasMotion) {
                    if (currentVehicleFilterMode == "heuristic") {
                        val (ok, reason) = passesHeuristicVehicleFilter(motion.clusterNorm, now)
                        if (currentMotionTorchTriggerMode == "vehicle" || (currentMotionTorchTriggerMode == "vehicle_or_headlight" && !motionPartialBrightJump)) {
                            updateAutoTorch(ok, now)
                        }
                        if (!ok && latestPlates.isEmpty()) {
                            latestVehicleFilterStatus = reason
                            latestVehicles = emptyList()
                            val rotW = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
                            val rotH = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
                            latestMotionDebug = MotionDebugInfo(
                                hasMotion = true,
                                motionCropActive = false,
                                clusterX1 = motion.clusterNorm?.x1 ?: 0f,
                                clusterY1 = motion.clusterNorm?.y1 ?: 0f,
                                clusterX2 = motion.clusterNorm?.x2 ?: 0f,
                                clusterY2 = motion.clusterNorm?.y2 ?: 0f,
                                cropX1 = 0f, cropY1 = 0f, cropX2 = 0f, cropY2 = 0f,
                                cropW = 0, cropH = 0, frameW = rotW, frameH = rotH,
                                timestampMs = now,
                                headlightCaptureActive = headlightCaptureActive
                            )
                            effectiveIntervalMs = currentIntervalMs
                            isProcessing.set(false)
                            return
                        }
                        latestVehicleFilterStatus = reason
                    }
                    consecutiveStaticChecks = 0
                    lastAlprHeartbeatMs = now
                    effectiveIntervalMs = currentIntervalMs
                    val allowMotionCrop = currentMotionCropEnabled && currentVehicleFilterMode != "tflite"
                    if (allowMotionCrop && motion.cropNorm != null) {
                        targetNorm = motion.cropNorm
                        motionCropActive = true
                    }
                } else {
                    // 画面静止但当前仍有已识别车牌：保持心跳或常规频率以追踪离开
                    effectiveIntervalMs = currentIntervalMs
                }
            } else {
                effectiveIntervalMs = currentIntervalMs
            }
        } else {
            effectiveIntervalMs = currentIntervalMs
            latestMotionDebug = null
        }

        if ((currentAntiOverexposureEnabled || captureModeAntiOverexposureActive) && now - lastAntiOverexpAdjustMs >= 450L) {
            lastAntiOverexpAdjustMs = now
            val totalSamples = LUMA_GRID_W * LUMA_GRID_H
            var overCount = 0
            var lumaSum = 0f
            var maxLuma = 0f
            if (sampledCurGrid) {
                for (i in 0 until totalSamples) {
                    val v = lumaCur[i]
                    lumaSum += v
                    if (v > maxLuma) maxLuma = v
                    if (v >= 235f) overCount++
                }
            } else if (nv21Source.size >= width * height && width >= LUMA_GRID_W && height >= LUMA_GRID_H) {
                val roiW = baseRoi.x2 - baseRoi.x1
                val roiH = baseRoi.y2 - baseRoi.y1
                for (gy in 0 until LUMA_GRID_H) {
                    val ry = baseRoi.y1 + ((gy + 0.5f) / LUMA_GRID_H) * roiH
                    for (gx in 0 until LUMA_GRID_W) {
                        val rx = baseRoi.x1 + ((gx + 0.5f) / LUMA_GRID_W) * roiW
                        val (sx, sy) = rotatedToSensorPixel(rx, ry, width, height, rotationDegrees)
                        val v = (nv21Source[sy * width + sx].toInt() and 0xFF).toFloat()
                        lumaSum += v
                        if (v > maxLuma) maxLuma = v
                        if (v >= 235f) overCount++
                    }
                }
            }
            val overRatio = overCount.toFloat() / totalSamples
            val meanLuma = lumaSum / totalSamples
            if (overRatio >= 0.06f || (overRatio >= 0.03f && maxLuma >= 250f)) {
                onAntiOverexposureStep?.invoke(-1)
            } else if (overRatio <= 0.01f && meanLuma < 115f) {
                onAntiOverexposureStep?.invoke(1)
            }
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

        if (currentMotionGateEnabled || currentHeadlightCaptureEnabled) {
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
                timestampMs = now,
                headlightCaptureActive = headlightCaptureActive,
                glareX1 = motionGlare?.x1 ?: 0f,
                glareY1 = motionGlare?.y1 ?: 0f,
                glareX2 = motionGlare?.x2 ?: 0f,
                glareY2 = motionGlare?.y2 ?: 0f
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

                val rotatedW = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
                val rotatedH = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
                val cropRotatedW = if (rotationDegrees == 90 || rotationDegrees == 270) cropH else cropW
                val cropRotatedH = if (rotationDegrees == 90 || rotationDegrees == 270) cropW else cropH
                val inferRotatedW = if (rotationDegrees == 90 || rotationDegrees == 270) inferH else inferW
                val inferRotatedH = if (rotationDegrees == 90 || rotationDegrees == 270) inferW else inferH
                val scaleX = cropRotatedW.toFloat() / inferRotatedW.coerceAtLeast(1)
                val scaleY = cropRotatedH.toFloat() / inferRotatedH.coerceAtLeast(1)

                var lprBuffer = frameCopy
                var lprW = inferW
                var lprH = inferH
                var lprRotOffX = rotOffX
                var lprRotOffY = rotOffY
                var lprScaleX = scaleX
                var lprScaleY = scaleY

                val filterMode = currentVehicleFilterMode
                val needTfliteForTorchOnly = (filterMode == "off" && currentMotionTorchEnabled && (currentMotionTorchTriggerMode == "vehicle" || currentMotionTorchTriggerMode == "vehicle_or_headlight"))
                if (filterMode == "tflite" || needTfliteForTorchOnly) {
                    val tfliteRes = detectVehiclesTflite(
                        frameCopy, inferW, inferH, rotationDegrees, currentVehicleConfThreshold
                    )
                    if (tfliteRes == null) {
                        latestVehicles = emptyList()
                        latestVehicleDetectMs = 0L
                        latestVehicleFilterStatus = "err:tflite_init"
                    } else {
                        val (localVehicles, vMs) = tfliteRes
                        latestVehicleDetectMs = vMs
                        val mappedVehicles = localVehicles.map { v ->
                            DetectedVehicle(
                                label = v.label,
                                confidence = v.confidence,
                                x1 = ((v.x1 * cropRotatedW + rotOffX) / rotatedW.coerceAtLeast(1)).coerceIn(0f, 1f),
                                y1 = ((v.y1 * cropRotatedH + rotOffY) / rotatedH.coerceAtLeast(1)).coerceIn(0f, 1f),
                                x2 = ((v.x2 * cropRotatedW + rotOffX) / rotatedW.coerceAtLeast(1)).coerceIn(0f, 1f),
                                y2 = ((v.y2 * cropRotatedH + rotOffY) / rotatedH.coerceAtLeast(1)).coerceIn(0f, 1f)
                            )
                        }
                        latestVehicles = mappedVehicles
                        val nowInfer = System.currentTimeMillis()
                        if (currentMotionTorchTriggerMode == "vehicle" || (currentMotionTorchTriggerMode == "vehicle_or_headlight" && !motionPartialBrightJump)) {
                            updateAutoTorch(mappedVehicles.isNotEmpty(), nowInfer)
                        }
                        if (filterMode == "tflite") {
                            if (mappedVehicles.isEmpty() && latestPlates.isEmpty()) {
                                latestVehicleFilterStatus = "skip:tflite(0)"
                                latestProcessTimeMs = vMs
                                return@execute
                            }
                            latestVehicleFilterStatus = "pass:tflite(${mappedVehicles.size})"
                        }

                        if (filterMode == "tflite" && currentMotionCropEnabled && localVehicles.isNotEmpty() && inferW >= 320 && inferH >= 240) {
                            var ux1 = 1f; var uy1 = 1f; var ux2 = 0f; var uy2 = 0f
                            for (v in localVehicles) {
                                if (v.x1 < ux1) ux1 = v.x1
                                if (v.y1 < uy1) uy1 = v.y1
                                if (v.x2 > ux2) ux2 = v.x2
                                if (v.y2 > uy2) uy2 = v.y2
                            }
                            val padW = (ux2 - ux1) * 0.12f
                            val padH = (uy2 - uy1) * 0.12f
                            ux1 = (ux1 - padW).coerceIn(0f, 1f)
                            uy1 = (uy1 - padH).coerceIn(0f, 1f)
                            ux2 = (ux2 + padW).coerceIn(0f, 1f)
                            uy2 = (uy2 + padH).coerceIn(0f, 1f)

                            // 映射回 frameCopy 原始未旋转坐标并按偶数对齐裁剪
                            val rx1f: Float; val ry1f: Float; val rx2f: Float; val ry2f: Float
                            when (rotationDegrees) {
                                90 -> {
                                    rx1f = uy1 * inferW; ry1f = (1f - ux2) * inferH
                                    rx2f = uy2 * inferW; ry2f = (1f - ux1) * inferH
                                }
                                180 -> {
                                    rx1f = (1f - ux2) * inferW; ry1f = (1f - uy2) * inferH
                                    rx2f = (1f - ux1) * inferW; ry2f = (1f - uy1) * inferH
                                }
                                270 -> {
                                    rx1f = (1f - uy2) * inferW; ry1f = ux1 * inferH
                                    rx2f = (1f - uy1) * inferW; ry2f = ux2 * inferH
                                }
                                else -> {
                                    rx1f = ux1 * inferW; ry1f = uy1 * inferH
                                    rx2f = ux2 * inferW; ry2f = uy2 * inferH
                                }
                            }
                            val vx1 = (rx1f.toInt() and 1.inv()).coerceIn(0, inferW - 64)
                            val vy1 = (ry1f.toInt() and 1.inv()).coerceIn(0, inferH - 64)
                            val vx2 = ((rx2f.toInt() + 1) and 1.inv()).coerceIn(vx1 + 64, inferW)
                            val vy2 = ((ry2f.toInt() + 1) and 1.inv()).coerceIn(vy1 + 64, inferH)
                            val vw = vx2 - vx1
                            val vh = vy2 - vy1
                            if (vw >= 160 && vh >= 120 && (vw < inferW || vh < inferH)) {
                                val cropBuf = acquireVehicleCropBuffer(vw * vh * 3 / 2)
                                cropNv21Full(frameCopy, inferW, inferH, vx1, vy1, vw, vh, cropBuf)
                                lprBuffer = cropBuf
                                lprW = vw
                                lprH = vh
                                val subRotOffX = when (rotationDegrees) {
                                    90 -> inferH - (vy1 + vh)
                                    180 -> inferW - (vx1 + vw)
                                    270 -> vy1
                                    else -> vx1
                                }.toFloat()
                                val subRotOffY = when (rotationDegrees) {
                                    90 -> vx1
                                    180 -> inferH - (vy1 + vh)
                                    270 -> inferW - (vx1 + vw)
                                    else -> vy1
                                }.toFloat()
                                lprRotOffX = rotOffX + subRotOffX * scaleX
                                lprRotOffY = rotOffY + subRotOffY * scaleY
                                lprScaleX = scaleX
                                lprScaleY = scaleY
                            }
                        }
                    }
                } else {
                    latestVehicles = emptyList()
                    latestVehicleDetectMs = 0L
                    if (filterMode == "off") latestVehicleFilterStatus = "off"
                }

                val startNs = System.nanoTime()
                val activeGpuCore = customGpuCore
                val activeCore = customCore
                val plates = when {
                    activeGpuCore != null -> activeGpuCore.plateRecognitionFromBuffer(
                        lprBuffer,
                        lprH,
                        lprW,
                        rotation,
                        HyperLPR3.STREAM_YUV_NV21
                    )
                    activeCore != null -> activeCore.plateRecognitionFromBuffer(
                        lprBuffer,
                        lprH,
                        lprW,
                        rotation,
                        HyperLPR3.STREAM_YUV_NV21
                    )
                    else -> HyperLPR3.getInstance().plateRecognition(
                        lprBuffer,
                        lprH,
                        lprW,
                        rotation,
                        HyperLPR3.STREAM_YUV_NV21
                    )
                }
                latestProcessTimeMs = (System.nanoTime() - startNs) / 1_000_000L

                val minRecConf = currentRecThreshold

                val validPlates = (plates ?: emptyArray())
                    .filter { it != null && it.confidence >= minRecConf && it.code.isNotBlank() }
                    .sortedByDescending { it.confidence }

                val detected = validPlates.map {
                    DetectedPlate(
                        code = it.code,
                        confidence = it.confidence,
                        type = it.type,
                        x1 = it.x1 * lprScaleX + lprRotOffX,
                        y1 = it.y1 * lprScaleY + lprRotOffY,
                        x2 = it.x2 * lprScaleX + lprRotOffX,
                        y2 = it.y2 * lprScaleY + lprRotOffY
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