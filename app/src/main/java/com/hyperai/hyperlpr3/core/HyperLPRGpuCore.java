package com.hyperai.hyperlpr3.core;

import com.hyperai.hyperlpr3.bean.HyperLPRParameter;
import com.hyperai.hyperlpr3.bean.Plate;

/**
 * GPU-accelerated (MNN OpenCL) JNI wrapper for HyperLPR3.
 * Loads the separate native library `libhyperlpr3_gpu.so` without modifying the
 * original CPU library `libhyperlpr3.so`, allowing runtime switching between CPU and GPU.
 */
public class HyperLPRGpuCore {

    private static volatile boolean libraryLoaded = false;
    private static volatile String loadError = null;

    static {
        try {
            System.loadLibrary("hyperlpr3_gpu");
            libraryLoaded = true;
        } catch (Throwable t) {
            loadError = t.getMessage();
        }
    }

    public static boolean isAvailable() {
        return libraryLoaded;
    }

    public static String getLoadError() {
        return loadError;
    }

    private long handle = 0L;

    private static native long CreateRecognizerContext(HyperLPRParameter parameter);

    private static native int ReleaseRecognizerContext(long handle);

    private static native Plate[] PlateRecognitionFromImage(
            long handle, int[] buf, int height, int width, int rotation, int format);

    private static native Plate[] PlateRecognitionFromBuffer(
            long handle, byte[] buf, int height, int width, int rotation, int format);

    public boolean createRecognizerContext(HyperLPRParameter parameter) {
        if (!libraryLoaded) return false;
        if (handle != 0L) {
            ReleaseRecognizerContext(handle);
            handle = 0L;
        }
        handle = CreateRecognizerContext(parameter);
        return handle != 0L;
    }

    public Plate[] plateRecognitionFromBuffer(
            byte[] buf, int height, int width, int rotation, int format) {
        if (!libraryLoaded || handle == 0L) return new Plate[0];
        Plate[] res = PlateRecognitionFromBuffer(handle, buf, height, width, rotation, format);
        return res != null ? res : new Plate[0];
    }

    public Plate[] plateRecognitionFromImage(
            int[] buf, int height, int width, int rotation, int format) {
        if (!libraryLoaded || handle == 0L) return new Plate[0];
        Plate[] res = PlateRecognitionFromImage(handle, buf, height, width, rotation, format);
        return res != null ? res : new Plate[0];
    }

    public void release() {
        if (libraryLoaded && handle != 0L) {
            ReleaseRecognizerContext(handle);
            handle = 0L;
        }
    }
}
