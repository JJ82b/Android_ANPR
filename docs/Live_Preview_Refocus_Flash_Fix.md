# 实时监控切换/重连导致画面闪烁与重新对焦问题修复记录

## 1. 问题现象
每次在 WebUI 中从“过车回放 / 记录管理”切回“实时监控”，或新开浏览器标签页连接实时画面时，手机相机画面都会明显黑闪一下并重新触发一次自动对焦（AF）。

---

## 2. 根因分析

在 [StreamingService.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/StreamingService.kt) 中，客户端连接与断开生命周期存在两处不必要的相机重启：

1. **新客户端接入无条件重启相机（`handleClientConnected()`）**：
   - 原逻辑只要 `captureRunning == true`，就无条件调用 `debouncedStartCamera()` -> `startCamera()`。
   - 而 `startCamera()` 会先调用 `stopCamera()` 彻底销毁当前的 CameraX / Camera1 会话与编码器，再重新绑定相机，导致正在推流或后台保持的相机被迫重启、闪烁并重新对焦。
2. **客户端短暂断开立刻卸载编码器（`handleClientDisconnected()`）**：
   - 当切换 Tab（`pauseLivePreview()` 断开视频流）或刷新页面时，最后一个客户端断开仅延迟 `300ms` 就触发 `startCamera(force = true)` 卸载 H.264 编码器；且多个客户端中某一个断开时，也会调用 `debouncedStartCamera()` 导致其余客户端一起闪烁重启。

---

## 3. 修复方案

1. **复用已运行的相机与 H.264 管线**（[StreamingService.kt#L722-L737](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/StreamingService.kt#L722-L737)）
   - 在 `handleClientConnected()` 中，仅当 H.264 客户端接入且当前硬件编码器尚未绑定（`h264HardwareEncoder == null && supportsSurfaceEncoder()`）时才重启配置；
   - 若相机与编码器已在运行，直接取消待执行的空闲卸载任务（`pendingStartJob?.cancel()`），并调用 `h264StreamingEncoder?.h264HardwareEncoder?.requestKeyFrame()` 立即下发一个 IDR 关键帧，让新页面零延迟出图，**不再重建相机、不闪烁、不重新对焦**。
2. **延长空闲卸载缓冲窗口并避免部分断开重启**（[StreamingService.kt#L755-L768](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/StreamingService.kt#L755-L768)）
   - 将无客户端后的空闲卸载延迟从 `300ms` 放宽至 `5000ms`（覆盖刷新页面或短时切换 Tab）；
   - 当仍有其他客户端或预览存在时断开连接，改为仅调用 `updateSubStream()`，不再触发 `debouncedStartCamera()`。

---

## 4. 验证方式
- 在 WebUI 连续切换 `实时监控` ↔ `过车回放` ↔ `记录管理`，或新开第二个浏览器标签页访问实时流，画面直接出图，手机端无黑闪与重新对焦动作。
