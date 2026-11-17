package com.github.digitallyrefined.androidipcamera.helpers

/**
 * A live camera backend feeding the H.264 encoder, plus a full-resolution still and basic controls.
 *  - CameraXCapture  — the global/modern default (works across phones; ImageAnalysis + ImageCapture).
 *  - Camera1Capture  — legacy fallback reaching 1920×1080 on old HALs where CameraX caps at 720p.
 * The service auto-picks one by hardware level (or a manual override) and talks to it through here.
 */
interface CaptureBackend {
    val width: Int
    val height: Int
    val ready: Boolean get() = true        // CameraX overrides (async bind); Camera1 is ready after start()
    /** Full-resolution JPEG; concurrent with the live stream where the hardware allows. */
    fun captureStill(onJpeg: (ByteArray?) -> Unit)
    /**
     * True only when THIS camera can drive the flash unit. Auxiliary lenses (ultra-wide, depth)
     * report no flash unit on many multi-lens phones — the caller must then route the torch
     * through a flash-capable camera instead of assuming setTorch() did anything.
     */
    val hasFlashUnit: Boolean get() = true
    fun getTorch(): Boolean
    fun setTorch(on: Boolean)
    fun setExposure(ev: Int)
    fun setZoom(ratio: Float)
    fun triggerAutoFocus()
    /**
     * Manual focus. [distance] in 0f..1f is a normalized fixed focus distance
     * (0f = far/infinity, 1f = nearest). A negative value returns the camera to
     * continuous autofocus. Backends apply this on a best-effort basis.
     */
    fun setManualFocus(distance: Float)
    /**
     * Manual ISO & shutter speed. [iso] <= 0 and [exposureTimeNs] <= 0 restores auto-exposure (AE).
     */
    fun setManualExposure(iso: Int, exposureTimeNs: Long) {}
    /**
     * Regional auto-exposure metering (normalized 0..1 in rotated display frame).
     */
    fun setAeRegion(enabled: Boolean, normX: Float, normY: Float, normW: Float, normH: Float, rotationDegrees: Int) {}
    /**
     * Supported exposure compensation index range (min..max).
     */
    fun getExposureRange(): Pair<Int, Int> = -4 to 4
    /**
     * Latest measured sensor exposure time in nanoseconds (0 if unknown).
     */
    val currentExposureTimeNs: Long get() = 0L
    fun stop()
}
