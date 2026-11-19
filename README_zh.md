# 车牌抓拍 (Android ANPR)

[English](README.md) | [简体中文](README_zh.md)

**车牌抓拍（Android ANPR）**（包名 `io.github.jj82b.android_anpr` · 版本 `v0.50.1`）是一款运行在 Android 手机上的**边缘车牌识别（ANPR）+ 高清网络摄像头 + 24 小时循环录像 + 过车回放一体机**。

本项目基于开源项目 [android-ip-camera](https://github.com/DigitallyRefined/android-ip-camera) 深度扩展，集成了离线车牌识别（[HyperLPR3](https://github.com/szad670401/HyperLPR)）、运动门控、可替换 TFLite 车辆预检模型、Top-Hat 运动强光与红尾灯检测、自动闪光灯/夜间抓拍曝光控制、24 小时分段循环录像以及功能完善的 Web 管理后台。

---

## ✨ 核心特性

### 🚗 离线车牌识别与多级智能预检
* **离线 HyperLPR3 识别**：支持 CPU 多线程与 OpenCL GPU 加速，纯本地运行，无需联网。
* **动作/强光全速检测 + 仅 ALPR 静止退避**：通过 `32×18` 亮度网格运动门控（MotionGate），静止画面跳过重型 AI 推理以降低发热功耗，同时保持毫秒级运动与强光唤醒。
* **双模式运动车辆预检**（在送入 HyperLPR3 之前过滤行人、树影等误触发）：
  * **方案一（几何 + 轨迹过滤）**：零模型、耗时 < 0.1 ms，按宽高比与帧间位移筛选。
  * **方案二（TFLite 车辆检测，支持热切换与自定义上传）**：
    * 内置 **EfficientDet-Lite0**（默认，320×320）、**EfficientDet-Lite1**（384×384 高精度远距）、**MobileNetV1-SSD**（300×300 极速）；
    * 支持在网页端直接上传自定义 `.tflite` 模型（同时兼容 SSD/EfficientDet 4 输出格式与 Ultralytics **YOLOv8n / YOLO11n** 单输出格式及内置 NMS）。
* **160×90 Top-Hat 运动强光与红尾灯检测**：基于可分离 `9×9` 白顶帽形态学与 YCbCr 色度约束，识别移动中的白色前大灯与红色尾灯，可联动自动闪光灯和夜间抓拍曝光策略。
* **夜间抓拍防过曝**：支持手动 ISO/快门抓拍预设、ROI 区域测光（`AE_REGIONS`）与峰值防过曝自动降 EV。

### 💾 24 小时循环录像、时间轴回放与记录管理
* **24h 分段循环录像**：支持自定义分段时长（1–30 分钟）与容量上限（GB），容量满时自动滚动清理最旧分段。
* **SQLite 过车事件库**：自动保存车牌号、置信度、车牌类型、识别框坐标、全景抓拍图、对应录像文件名与时间偏移。
* **带录像标记的日历 + 24 小时时间轴回放**：
  * 日历弹窗上用绿色小圆点直观标记有录像的日期；
  * 双视频槽无缝续播，时间轴单屏上限 24 小时，支持 **`24小时` / `上午 (00-12)` / `下午 (12-24)` / `1小时` / `15分钟`** 缩放、`-5s` / `+5s` 步进、全屏及抓拍大图查看。
* **多选记录管理**：独立「记录管理」标签页，支持查看真实文件大小、关键词搜索以及多选批量删除录像与过车记录。

### 📡 网络摄像头与 Web 远程管理
* **内置 Web 管理后台**：手机端启动服务后，局域网浏览器访问 `https://[手机IP]:4444` 即可使用（全部设置采用防误触折叠下拉面板设计）。
* **实时叠加可视化**：支持在监控画面上实时绘制车牌框、车辆预检框（橙色虚线）、运动团块（蓝色虚线）、动态裁剪框（紫色实线）与强光框（金黄色虚线）。
* **多协议兼容**：支持 **H.264 / MJPEG / RTSP (`rtsps://[IP]:8554/live`) / ONVIF**（可接入 Frigate、Home Assistant、UniFi Protect、VLC）及浏览器按住双向语音对讲（PTT）。

---

## 🛠️ 编译与安装

需要 Android SDK（`compileSdk 34`, `minSdk 24`）与 JDK 17+：

```bash
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew assembleDebug
# 连接 ADB 设备后可直接编译并安装：
./gradlew installDebug
```

---

## 📖 项目文档

* **软件总体架构与运作原理**：[docs/Architecture_Overview.md](docs/Architecture_Overview.md)
* **相机采集与音视频推流流水线**：[docs/Camera_And_Streaming_Pipeline.md](docs/Camera_And_Streaming_Pipeline.md)
* **运动门控、车辆预检与强光抓拍机制**：[docs/Vehicle_And_Glare_PreDetection.md](docs/Vehicle_And_Glare_PreDetection.md)
* **Web 管理后台与 HTTP API 说明**：[docs/WebUI_And_API_Reference.md](docs/WebUI_And_API_Reference.md)

---

## ⚠️ 24 小时运行与电池安全提醒

* 如果计划 7×24 小时插电运行，请尽量开启手机的**充电上限保护**（如充至 80% 停充），避免电池长期处于 100% 高压高温状态导致鼓包损坏。
* 请在系统设置中将本应用的电池策略设为 **「无限制 / 不优化」**，且不要从最近任务列表中划掉 App，推流时可以安全关闭手机屏幕。

---

## 🙏 致谢与开源协议

* 上游 IP 摄像头基础项目：[DigitallyRefined/android-ip-camera](https://github.com/DigitallyRefined/android-ip-camera)
* 离线车牌识别引擎：[HyperLPR3](https://github.com/szad670401/HyperLPR)
* 遵循仓库根目录 [LICENSE](LICENSE) 开源协议。
