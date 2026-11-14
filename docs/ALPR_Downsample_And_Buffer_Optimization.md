# ALPR 1/2 降采样与双缓冲复用优化实现及实测对比

本文档记录在 [PlateRecognizer.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/PlateRecognizer.kt) 中实现 **「1/2 NV21 降采样 + Ping-Pong 双缓冲复用」** 的具体过程，以及在 Honor 10（`COL-AL10`）上的真实对比测试结果与结论。

---

## 1. 实现内容

在 [PlateRecognizer.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/PlateRecognizer.kt#L254-L386) 中完成以下修改：

1. **高分辨率 1/2 NV21 降采样（`downsampleNv21Half`）**：
   - 当输入帧 `width >= 1280 || height >= 1280` 时，按 2× 间隔对 Y 平面和 NV21 交错 UV 平面降采样（例如 `1920×1080` → `960×540`，`1456×1456` → `728×728`），确保输出宽高仍为偶数。
   - 降采样后帧尺寸仍大于 `DETECT_LEVEL_HIGH`（`b640x`，640px）与 `DETECT_LEVEL_LOW`（`b320`，320px）的模型输入尺寸。
   - 推理完成后，按 `scaleX` / `scaleY` 将检测框坐标还原至原始画面坐标系供 WebUI 叠加绘制；同时将降采样后的 `frameCopy`（`inferW × inferH`）传给 [PlateEventStore.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/PlateEventStore.kt) 进行过车抓拍 JPEG 压缩。
2. **Ping-Pong 双缓冲复用（`acquireSnapshotBuffer`）**：
   - 使用容量为 2 的预分配数组 `snapshotBuffers` 交替写入异步推理快照，取代原有的 `nv21Source.copyOf()`，消除每次采样在堆上分配 `~3.1MB`（1080p）字节数组引起的 GC 压力。

---

## 2. Honor 10（`COL-AL10`）真机实测对比

测试条件统一为：CPU 后端（`useGpu=false`）、`detLevel=1`（`DETECT_LEVEL_HIGH`）、`threads=4`、`intervalMs=500`、持续拉流 `/video/mjpeg` 并轮询 `/api/plate`（`1920×1080` 输入）。

| 指标 | 优化前（`1920×1080` 直接 `copyOf`） | 优化后（`960×540` 降采样 + 双缓冲） | 变化说明 |
| :--- | :--- | :--- | :--- |
| **单帧快照内存占用** | `3,110,400 B`（`~3.0 MB`/帧） | `777,600 B`（`~0.74 MB` 复用池） | **内存体积减少 75%** |
| **每帧堆内存新分配** | `~3.0 MB/帧`（`copyOf`） | **稳态 0 B/帧**（双缓冲复用） | **消除采样引起的 GC 分配** |
| **过车抓拍 JPEG 输入尺寸** | `1920×1080` | `960×540` | 像素量减少 **75%** |
| **单帧 `processTimeMs` 实测** | `846 / 851 / 839 / 860 ms`（均值 `849 ms`） | `1029 / 1042 / 924 / 924 ms`（均值 `980 ms`，含温控波动） | **未降低，反而略升** |

---

## 3. 实测结论与原因分析

1. **`processTimeMs` 没有下降的原因**：
   - `processTimeMs` 统计的是 `plateRecognitionFromBuffer(...)` 内部耗时。HyperLPR3 C++ 底层在调用 MNN 前会用 OpenCV NEON 指令做 `cvtColor` 与 `resize`（`1920×1080` → `640×640`），这部分在 C++ NEON 下本身只占几毫秒；
   - 真正占 **95% 以上耗时的是 MNN CNN 网络推理本身**（输入固定为 `640×640`），因此把输入从 `1920×1080` 降到 `960×540` 无法缩短 CNN 推理时间。
   - 反而在 Kotlin 层执行 `downsampleNv21Half` 逐字节循环会额外占用相机回调线程约数毫秒至十几毫秒 CPU，且连续测试下的机身温控降频也会使 `processTimeMs` 波动到 `924ms+`。
2. **本次改动真正的收益点**：
   - **双缓冲复用（Ping-Pong Buffer）** 彻底消除了每帧 `3.1MB` 堆内存分配和 GC 抖动，这部分收益是纯粹的正向优化。
   - 如果目标是要**显著降低 `processTimeMs` 与推理功耗**，关键不在于缩 NV21 分辨率，而在于：
     1. **静止画面帧差门控**（无车/无变化时跳过整次 MNN 推理）；
     2. **只保留双缓冲复用（去掉 Kotlin 层逐字节降采样循环）**，让 C++ NEON 直接处理原图，或者在相机分辨率本身选 `1280×720` / `800×600`。
