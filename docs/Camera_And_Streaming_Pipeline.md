# 相机采集与音视频推流流水线

本文详细说明相机画面如何从手机传感器进入系统，并同时服务于**视频推流、本地录像与 AI 识别**。

---

## 一、相机采集后端（`CaptureBackend`）

项目抽象了统一的 `CaptureBackend` 接口，按设置支持三种模式：
1. **`CameraXCapture`（默认首选，基于 Camera2 / CameraX）**
   - 使用 CameraX `Preview` / `VideoCapture` / `ImageAnalysis` 绑定生命周期：
     - **推流与录像一路**：输出到 OpenGL 管线（`CameraGlPipe`）及硬件编码器 `MediaCodec`；
     - **AI 分析一路**：`ImageAnalysis` 输出 `YUV_420_888` 帧，转为紧凑 `NV21` 后送入 `PlateRecognizer.processNv21Async(...)`；
     - **Camera2 扩展控制**：通过 `Camera2CameraControl` 精确下发手动 ISO、快门时间（`shutter_ns`）、区域测光（`AE_REGIONS`）、固定对焦距离与 EV 曝光补偿。
2. **`Camera1Capture`（兼容兜底模式）**
   - 针对部分 Camera2 异常的老机型提供旧版 `android.hardware.Camera` 支持，并通过 `PreviewCallback` 提供帧数据。

> [!NOTE]
> 切入网页实时监控时，不会反复重建相机采集会话或重置 3A，避免每次点进实时监控出现画面闪烁或重新对焦（详见 [Live_Preview_Refocus_Flash_Fix.md](Live_Preview_Refocus_Flash_Fix.md)）。

---

## 二、视频编码与多协议分发

### 1. H.264 硬件编码路径
- **核心类**：`H264HardwareEncoder`、`H264StreamingEncoder`、`CameraGlPipe`
- 通过 OpenGL ES 管线将相机纹理渲染到 `MediaCodec` 输入 Surface（支持旋转、镜像、缩放、对比度调整及 `LIMITED` 设备直出开关）。
- 编码输出的 NAL 单元（`H264Bitstream`）同时分发给：
  - **Web 端 fMP4 / WebSocket 流**（经 `StreamingServerHelper` 推送给浏览器 `<video>`）；
  - **RTSP 服务器**（`RtspServer` + `RtpPacketizer` 通过 RTP 打包发给 VLC / NVR / Frigate）；
  - **本地循环录像**（`LocalRecorder` 直接复用编码流写入 `.mp4`，几乎零额外编码开销）。

### 2. MJPEG 路径
- **核心类**：`MjpegStreamingEncoder`、`ImageConversionHelper`
- 在需要 MJPEG 兼容或开启旧版 ONVIF 移动侦测（`MotionMonitor` / `MotionDetectorCore`）时，逐帧压缩 JPEG 通过 HTTP `multipart/x-mixed-replace` 推送。

---

## 三、音频与双向对讲

- **拾音推流**：`AacAudioSource` 采集麦克风 PCM 并用 `MediaCodec` 编码为 AAC，附带音画同步延迟补偿（`avDelay`）与音频增益（`audioGain`）发送至前端及 RTSP。
- **按住对讲（PTT）**：浏览器端采集麦克风 16-bit PCM（单声道 44.1 kHz）分包 POST 到 `/talk`，服务端写入共享 `AudioTrack` 从手机扬声器实时播放。

---

## 四、ONVIF 局域网发现与联动

- **WS-Discovery（`OnvifDiscovery`）**：监听组播 UDP `3702` 端口，让 Home Assistant、UniFi Protect 等 NVR 自动发现本机摄像头。
- **ONVIF SOAP 服务（`OnvifServer`、`OnvifSoap`）**：提供设备信息、媒体配置（RTSP 地址）、快照 URI 与移动侦测事件订阅。
