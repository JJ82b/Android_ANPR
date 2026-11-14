# 软件计算瓶颈分析报告

本文档基于当前工程代码实现与 Honor 10（`COL-AL10`）真机实测数据，梳理目前软件在 **推理、图像格式转换、视频编码与持久化** 四条链路上的主要计算瓶颈。

---

## 1. 核心瓶颈总览（按耗时与 CPU/GPU 压力排序）

| 优先级 | 模块 / 路径 | 瓶颈类型 | 实测 / 代码表现 | 影响范围 |
| :--- | :--- | :--- | :--- | :--- |
| **1** | **ALPR 车牌识别推理**（[PlateRecognizer.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/PlateRecognizer.kt)） | 神经网络推理（CPU / OpenCL） | Honor 10 实测单帧 `processTimeMs`：CPU 约 `276ms–431ms`（`1456x1456`~`1920x1080`），GPU OpenCL 首帧/热身约 `799ms` | 决定车牌识别最大吞吐率（约 `2~3.5 FPS`）与持续 CPU/GPU 负载 |
| **2** | **YUV_420_888 → NV21 内存拷贝**（[ImageConversionHelper.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/ImageConversionHelper.kt#L10) & [CameraXCapture.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/CameraXCapture.kt#L179-L199)） | CPU 逐行/逐像素内存搬运 | CameraX `ImageAnalysis` 采样帧需在 CPU 侧重排为连续 NV21，且 `processNv21Async()` 需再 `nv21Source.copyOf()` 一份快照（1080p 约 `3.1MB/帧`） | 占用内存带宽与 L2/L3 缓存，旧设备（如 `Camera1` 回调）还会增加 GC/拷贝压力 |
| **3** | **MJPEG 软编码（`YuvImage.compressToJpeg`）**（[ImageConversionHelper.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/ImageConversionHelper.kt#L72)） | CPU JPEG 压缩（Skia `onFlyCompress`） | Honor 10 拉流 `/video/mjpeg` 时 logcat 持续输出 `D/skia: onFlyCompress`，每帧约 `25–35ms` 间隔压一次 JPEG | 有 MJPEG 客户端连接时 CPU 占用显著上升；**日常建议优先使用 H.264 硬编** |
| **4** | **Camera1 兼容路径的 GL + Callback 双重输出**（[Camera1Capture.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/Camera1Capture.kt#L68-L82) & [StreamingService.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/StreamingService.kt#L991-L1020)） | HAL 纹理渲染 + CPU 回调并发 | `Camera1` 在输出 `SurfaceTexture → CameraGlPipe → H264HardwareEncoder` 的同时，还需通过 `setPreviewCallbackWithBuffer` 向 CPU 拷贝 NV21 帧 | 老 HAL 同时驱动两路输出时帧率下降、预览流畅度不如 `CameraX`（Camera2） |
| **5** | **过车事件抓拍 JPEG 压缩与落盘**（[PlateEventStore.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/PlateEventStore.kt#L128) & [第 385 行](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/PlateEventStore.kt#L385)） | CPU JPEG 压缩 + 文件/数据库 I/O | 每次检测到新/有效车牌事件会调用 `YuvImage.compressToJpeg(..., 90, ...)` 保存全图并写入存储 | 仅在触发过车事件时发生，已通过去重窗口控制频率，突发多车时有瞬时 CPU/IO 尖峰 |

---

## 2. 各瓶颈详细剖析

### 2.1 ALPR 车牌识别（最大单点算力消耗）
1. **多模型串联推理**：HyperLPR3 单次调用需串联执行「车牌检测（`b320` / `b640x`）→ 方向/类型分类（`litemodel_cls_96x`）→ 字符识别（`rpv3_mdict_160`）」。
2. **输入尺寸与 `DetLevel` 敏感**：
   - `DETECT_LEVEL_HIGH`（`b640x`）比 `DETECT_LEVEL_LOW`（`b320`）检测输入面积大 4 倍，在手机端 CPU 耗时约 `276ms–431ms`。
   - 传入图像分辨率越高（如 `1920x1080` 或 `1456x1456`），C++ 侧的图像缩放（`cv::resize`）与 NV21 → BGR 色彩空间转换开销也随之增大。
3. **CPU vs GPU（OpenCL）的权衡**：
   - **CPU 模式**：轻量 CNN 在大核/中核上直接内存计算，无跨设备同步，整体功耗与 CPU 占用反而更可控。
   - **GPU（OpenCL）模式**：由于是 3 个小网络串联且存在算子回退（`backupType = MNN_FORWARD_CPU`），每帧都需要 Host ↔ GPU 内存拷贝与 OpenCL 队列同步等待；在高通 Adreno 上更容易出现 GPU/CPU 同步开销大、功耗与 CPU 占用双高的问题，在旧 Mali（如 Mali-G72）上还需使用 `Precision_Normal`（FP32 + `IMAGE` 模式）以保证驱动兼容。

### 2.2 图像格式转换与内存拷贝（CPU 带宽瓶颈）
- **CameraX 路径**（[CameraXCapture.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/CameraXCapture.kt#L179-L199)）：
  - 虽然已经通过 `plateRecognizer.canProcessNow()` 做了节流（仅在达到 `intervalMs` 且上一帧空闲时才执行 `convertYUV420toNV21`），避免了无谓的全帧率转换；
  - 但每次采样仍需做：`YUV_420_888`（跨步长逐行拷贝）→ `nv21Buffer` → `nv21Source.copyOf()`（给后台推理线程快照），1080p 下单次采样约搬运 `6MB+` 内存。
- **Camera1 路径**（[Camera1Capture.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/Camera1Capture.kt#L75-L81)）：
  - HAL 每帧都向 3 个预分配的 `ByteArray` 回调写入 NV21 数据，即便 `plateRecognizer` 按 `intervalMs` 跳过大部分帧，HAL 层的逐帧填充依然存在。

### 2.3 视频流编码路径差异（H.264 硬编 vs MJPEG 软编）
- **H.264 硬件编码（推荐）**：
  - `CameraX`（`SurfaceProvider` 直出）与 `Camera1`（`SurfaceTexture` → `CameraGlPipe`）均通过 `MediaCodec` `OMX.*.video.encoder.avc` 走硬件表面编码（`useSurface = true`），几乎不占 CPU 算力。
  - 若设备受限于 `LIMITED` 级别且未开启 `allow_limited_surface`，则会退化为软件 YUV 喂编码器，CPU 占用会明显升高。
- **MJPEG 编码（高 CPU 消耗）**：
  - WebUI 若切换到 MJPEG 模式，每帧都要经过 `YuvImage.compressToJpeg`（底层 Skia `onFlyCompress`）在 CPU 上做 JPEG 压缩，高帧率下会持续占用大量 CPU。

### 2.4 外部网络异常连接导致的额外 CPU 消耗（实测发现）
- 在 Honor 10 真机 logcat 中观察到，当客户端（如 `192.168.31.206`）持续以不匹配的协议/旧连接请求 HTTPS `4444` 端口时，服务端每秒产生数十次 `SSL` 握手失败（`Handshake failed`），频繁触发 TLS 握手与日志输出，也会白白消耗一定的 CPU 和 I/O。

---

## 3. 针对性的优化建议（不改核心功能的前提下）

1. **视频流默认使用 H.264 硬编，避免长期挂着 MJPEG 客户端**。
2. **相机后端优先使用 `Camera2 (CameraX)`**：可在 WebUI「相机 API」选择 `Camera2 (CameraX)`，减少 `Camera1` 的 `PreviewCallbackWithBuffer` 逐帧拷贝和 `CameraGlPipe` 中转。
3. **ALPR 参数按场景调优**：
   - 长期无人值守监控建议：`后端 = CPU`、`DetLevel = 低功耗 (DETECT_LEVEL_LOW)`、`Interval = 300~500ms`、`Threads = 2~4`，能把 CPU 占用和发热降到最低；
   - 仅在高速过车且算力富余时提高 `Interval` 频率或切换 `DETECT_LEVEL_HIGH`。
4. **后续代码级可优化方向**（如需进一步压榨性能）：
   - 将送入 `PlateRecognizer` 的图像在 `convertYUV420toNV21` 或 C++ 侧做 **1/2 降采样**（例如 `1920x1080` → `960x540`），可大幅减少内存拷贝量以及 HyperLPR3 检测预处理耗时；
   - `PlateEventStore` 的 `compressToJpeg` 可异步池化复用 `ByteArrayOutputStream` 并适当降低抓拍图分辨率/质量（如 `80`）。
