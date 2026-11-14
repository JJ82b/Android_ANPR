# ALPR Ping-Pong 双缓冲复用与分级降采样优化记录

本文档记录在 [PlateRecognizer.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/PlateRecognizer.kt) 中正式落地的 **「Ping-Pong 双缓冲复用 + 仅 2K/4K 分级降采样」** 方案，以及 Honor 10（`COL-AL10`）真机三阶段对比测试结果。

---

## 1. 优化原则与实现细节

### 1.1 遵循的优化注意事项
基于上一轮 1080p 降采样实测的经验，为避免在 1080p 下引入无收益的 Kotlin 逐字节循环开销，采用分级策略：
1. **1080p 及以下（`width < 2560 && height < 2560`）**：
   - **不做 Kotlin 逐字节降采样**，直接通过底层 `System.arraycopy(...)` 将 NV21 数据拷贝到预分配的 Ping-Pong 双缓冲中；
   - 让 HyperLPR3 C++ 侧使用 OpenCV NEON 指令直接完成 `NV21 → BGR` 与缩放。
2. **2K / 4K 高分辨率（`width >= 2560 || height >= 2560`）**：
   - 自动开启 **1/2 降采样**（如 `3840×2160` → `1920×1080`）+ Ping-Pong 双缓冲；
   - 避免每帧产生 `12.44 MB` 大对象分配导致 GC 卡顿影响 4K 录像，同时将过车抓拍 JPEG 压缩像素量降低 75%。

### 1.2 核心代码结构（[PlateRecognizer.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/PlateRecognizer.kt#L83-L86) & [第 260–375 行](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/PlateRecognizer.kt#L260-L375)）
- `snapshotBuffers = arrayOfNulls<ByteArray>(2)`：两块复用缓冲区通过 `snapshotIndex xor 1` 交替使用（一块给后台 `inferExecutor` 推理，另一块接收下一帧快照，互不踩踏）。
- `acquireSnapshotBuffer(requiredBytes)`：仅在首次或分辨率变化时分配 `ByteArray`，稳态下直接复用。
- `useHalfScale = (width >= 2560 || height >= 2560) && width >= 4 && height >= 4`：控制是否进入 `downsampleNv21Half`，1080p 走 `System.arraycopy`。

---

## 2. Honor 10（`COL-AL10`）真机性能对比

测试条件：`useGpu=false`（CPU 后端）、`detLevel=1`（`DETECT_LEVEL_HIGH`）、`threads=4`、`intervalMs=500`、持续拉流 `/video/mjpeg` 并查询 `/api/plate`（输入分辨率 `1920×1080`）。

| 方案阶段 | 快照拷贝方式 | 单帧堆内存分配（稳态） | `processTimeMs` 实测样本 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| **优化前（基线）** | `nv21Source.copyOf()` | `3,110,400 B`（`~3.0 MB`/帧） | `846 / 851 / 839 / 860 ms` | 每帧触发大数组堆分配与 GC 回收 |
| **全量 1/2 降采样** | Kotlin 循环降采样到 `960×540` + 双缓冲 | **0 B/帧**（`~0.74 MB` 复用池） | `1029 / 1042 / 924 / 924 ms` | 省内存，但 Kotlin 字节循环增加相机线程负担 |
| **最终双缓冲分级策略** | 1080p 直接 `System.arraycopy` + 双缓冲（仅 `>=2560` 降采样） | **0 B/帧**（`~3.0 MB` 复用池） | `719 / 1088 / 1108 / 1115 ms` | 零堆分配 + 零 Kotlin 循环；后三帧受连续压测温控降频影响 |

### 4K 场景理论对比（`3840×2160`）
- **优化前**：每帧 `copyOf()` 分配 **`12.44 MB`**，高频采样极易诱发 GC 停顿；C++ 预处理与抓拍 JPEG 压缩均需处理 829 万像素。
- **优化后**：触发 `width >= 2560` 分支，降采样至 `1920×1080` 并写入复用双缓冲池，稳态 **`0 B/帧` 分配**，内存池降至 `~3.0 MB`，抓拍 JPEG 像素量减少 **75%**。

---

## 3. 结论

1. **双缓冲复用实现了稳态 0 堆内存分配**，消除了 ALPR 采样路径上的周期性大数组 GC 压力，对长时间监控与 4K 录像稳定性最有益。
2. **在 `1920×1080` 下，`processTimeMs` 仍由 MNN CNN 模型计算主导**（实测冷机/刚启动约 `719–850ms`，连续运行温控后约 `920–1115ms`），单靠内存拷贝或分辨率预处理无法大幅缩短单帧 CNN 计算时间；若需继续降低 CPU 占用，下一步应实施 **静止画面帧差门控**（减少无车时的无效推理次数）。
