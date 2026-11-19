# Android ANPR (车牌抓拍)

[English](README.md) | [简体中文](README_zh.md)

**Android ANPR** (`io.github.jj82b.android_anpr` · `v0.50.1`) turns an Android phone into an all-in-one **Edge ALPR (Automatic License Plate Recognition) + H.264/MJPEG IP Camera + 24h Loop Recorder + Timeline Playback NVR**.

Built on top of [android-ip-camera](https://github.com/DigitallyRefined/android-ip-camera), it adds offline license plate recognition ([HyperLPR3](https://github.com/szad670401/HyperLPR)), smart motion/vehicle/glare pre-detection, automatic night-time torch & exposure capture strategies, local 24h segmented MP4 loop recording, SQLite passing-event indexing, and a rich Web management console.

---

## ✨ Key Features

### 🚗 Offline ALPR & Smart Pre-Detection
* **Offline License Plate Recognition**: Integrated **HyperLPR3** supporting CPU multi-threading and OpenCL GPU acceleration.
* **Motion Gate with Decoupled Backoff**: `32×18` Y-plane adaptive motion gate skips heavy AI inference on static scenes while keeping motion/glare detection running at full sampling speed.
* **Dual Vehicle Pre-Filter Modes** (before HyperLPR3):
  * **Heuristic Mode**: Zero-overhead aspect ratio + trajectory filtering.
  * **TFLite Vehicle Detection**: Built-in **EfficientDet-Lite0** (default, 320×320), **EfficientDet-Lite1** (384×384), and **MobileNetV1-SSD** (300×300), plus **Web UI custom `.tflite` upload** (supports both 4-output SSD/EfficientDet and single-output Ultralytics **YOLOv8n/YOLO11n** TFLite models with NMS).
* **Moving Glare & Red Taillight Detection**: `160×90` separable `9×9` White Top-Hat morphology + YCbCr filtering detects approaching headlights and red taillights to trigger auto-torch and night capture exposure modes.
* **Night Capture & Anti-Overexposure**: Supports manual ISO/shutter capture preset, ROI AE region metering, and peak anti-overexposure EV stepping.

### 💾 24h Loop Recording, Playback & Management
* **24h Segmented MP4 Loop Recording**: Configurable segment duration (`1–30 min`) and storage cap (`GB`) with automatic oldest-file cleanup.
* **SQLite Passing Event Store**: Automatically records plate number, confidence, plate type, bounding box, full snapshot JPEG, video filename, and exact timestamp offset.
* **Calendar + 24h Timeline Playback**:
  * Custom calendar picker with green dots marking days that have recordings.
  * Seamless dual-video-slot timeline capped at 24 hours per day with **`24h` / `AM (00–12)` / `PM (12–24)` / `1h` / `15m`** zoom, `-5s` / `+5s` seek, fullscreen, and snapshot lightbox.
* **Multi-Select Record Management**: Dedicated tab to inspect file sizes, search, and batch-delete recordings or passing events.

### 📡 IP Camera, Streaming & Remote Control
* **Built-in HTTPS/HTTP Web Console**: Serves `index.html` on port `4444` (configurable) with mobile-friendly collapsible accordion controls to prevent accidental touches.
* **Live Overlay Visualization**: Renders color-coded plate boxes, orange dashed vehicle boxes, blue motion clusters, purple dynamic crops, and yellow moving glare boxes in real time.
* **H.264 / MJPEG / RTSP / ONVIF**: Compatible with Frigate, Home Assistant, UniFi Protect, VLC, and standard RTSP (`rtsps://[ip]:8554/live`) or MJPEG clients, including two-way Push-to-Talk audio.

---

## 🛠️ Build & Install

Requires Android SDK (`compileSdk 34`, `minSdk 24`) and JDK 17+:

```bash
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew assembleDebug
# Or install directly to a connected ADB device:
./gradlew installDebug
```

---

## 📖 Documentation

* **Master Architecture Overview**: [docs/Architecture_Overview.md](docs/Architecture_Overview.md)
* **Camera & Streaming Pipeline**: [docs/Camera_And_Streaming_Pipeline.md](docs/Camera_And_Streaming_Pipeline.md)
* **Motion Gate, Vehicle Pre-Filter & Glare Detection**: [docs/Vehicle_And_Glare_PreDetection.md](docs/Vehicle_And_Glare_PreDetection.md)
* **Web UI & HTTP API Reference**: [docs/WebUI_And_API_Reference.md](docs/WebUI_And_API_Reference.md)

---

## ⚠️ Battery & 24/7 Background Usage

* **Do not keep the battery at 100% 24/7** if your device supports a charging limit (e.g., 80%), as continuous heat and full charge can cause battery swelling.
* **Unrestricted Battery Usage**: Set **Settings > Apps > Android ANPR > Battery** to **Unrestricted / Don't optimise**, and do not swipe the app away from Recents.
* You can safely turn off the phone screen while the foreground service is running.

---

## 🙏 Credits & License

* Upstream IP Camera foundation: [DigitallyRefined/android-ip-camera](https://github.com/DigitallyRefined/android-ip-camera)
* License Plate Recognition SDK: [HyperLPR3](https://github.com/szad670401/HyperLPR)
* Licensed under the terms of the [LICENSE](LICENSE) file in this repository.
