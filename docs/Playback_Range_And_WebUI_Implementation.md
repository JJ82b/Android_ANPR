# 24 小时 ANPR 录像回放系统：第 3 步与第 4 步实现文档

本文档记录 24 小时不间断录像与车牌检索回放系统的**第 3 步（支持 HTTP Range 的分段视频流接口）**和**第 4 步（WebUI 过车回放与检索页面）**的具体实现细节。

---

## 一、总体架构与跳转回放流程

```mermaid
flowchart LR
    U[WebUI 过车回放页] -->|GET /api/events?q=&type=| E[PlateEventStore SQLite]
    U -->|GET /api/events/image?file=| I[filesDir/alpr_snaps/*.jpg]
    U -->|GET /files.json| L[RecordingsHelper.list]
    U -->|点击过车卡片| V["<video src='/files/stream/xxx.mp4'>"]
    V -->|Range: bytes=start-end| S[StreamingServerHelper -> FileManager.serveStreamFile]
    S -->|206 Partial Content / 200 OK| V
    V -->|loadedmetadata -> currentTime = max(0, offsetMs/1000 - 2)| P[提前 2 秒预卷播放]
```

---

## 二、第 3 步：HTTP `Range` 视频流接口与联动清理

### 1. `FileManager.kt`：新增 `/files/stream/<filename>` 在线流式接口

- **文件路径**：[FileManager.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/FileManager.kt)
- **接口设计**：保留原有 `GET /files/<filename>`（`Content-Disposition: attachment` 强制下载）以兼容 `/files` 文件管理页；新增 `GET` / `HEAD` `/files/stream/<filename>` 专供浏览器 `<video>` 在线播放与拖动跳转。
- **HTTP `Range` 协议支持（`serveStreamFile` / `parseByteRange`）**：
  - 自动识别 MIME 类型（`.mp4` → `video/mp4`、`.mkv` → `video/x-matroska`、`.webm` → `video/webm`），并固定返回 `Accept-Ranges: bytes`。
  - 解析请求头中的 `Range: bytes=...`（不区分大小写）：
    - `bytes=start-`：返回 `[start, totalSize - 1]`
    - `bytes=start-end`：返回 `[start, min(end, totalSize - 1)]`
    - `bytes=-suffix`：返回最后 `suffix` 字节
  - 合法 Range 返回 **`206 Partial Content`** 与 `Content-Range: bytes start-end/totalSize`、`Content-Length: end - start + 1`。
  - 越界或格式非法的 Range 返回 **`416 Range Not Satisfiable`** 与 `Content-Range: bytes */totalSize`。
  - 无 Range 请求头时返回 **`200 OK`** 与完整文件流。
  - 支持 `HEAD` 方法：仅输出响应头后立即返回，不读取文件体。
- **流定位与精确拷贝（`skipFully` / `copyExact`）**：
  - 针对 Android `InputStream.skip()` 可能单次跳过不足的问题，使用循环 `skipFully()` 确保准确跳过前 `start` 字节后再按 64KB 分块输出精确的 `contentLength` 字节。
- **手动删除联动清理**：
  - 在 `DELETE /files/<filename>` 删除文件成功后，同步调用 `PlateEventStore.getInstance(context).deleteEventsForVideo(fileName)`，避免手动删录像后在数据库留下孤立记录与抓拍图片。

### 2. `StreamingServerHelper.kt`：透传请求头

- **文件路径**：[StreamingServerHelper.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/StreamingServerHelper.kt)
- **改动**：在调用 `fileManager.handleRequest(...)` 时传入解析好的 HTTP `headers` 列表，使 `FileManager` 可以读取客户端的 `Range` 请求头。

---

## 三、第 4 步：WebUI 过车回放与车牌检索页面

- **文件路径**：[index.html](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/assets/index.html)

### 1. 顶部视图切换

- 在页面 `.header` 新增 **📹 实时监控** (`#liveTabBtn`) 与 **📼 过车回放** (`#playbackTabBtn`) 切换按钮：
  - 切换到 **过车回放** 时隐藏实时流工具栏与实时面板，自动拉取 `/files.json` 录像分段列表与 `/api/events` 过车事件。
  - 切回 **实时监控** 时自动暂停回放 `<video>`，避免后台持续占用解码资源。

### 2. 统一大时间轨与双视频无缝衔接

- **文件路径**：[index.html](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/assets/index.html)
- **统一时间轨 (`#playbackTimelineCanvas`)**：
  - 自动解析 `/files.json` 中所有 `.mp4` 分段文件名的 `yyyyMMdd_HHmmss` 起始时间与 `lastModifiedMs`（并在视频 `loadedmetadata` 后用实际 `video.duration` 校准），按时间顺序拼接为连续时间轨。
  - 在时间轨上同步绘制**录像覆盖色块**、**时间刻度**、**过车事件打点**与**实时播放指针**，支持点击/拖拽任意时间点跳转、`全部 / 1小时 / 15分钟` 缩放以及悬停提示车牌与时间。
  - 提供统一的播放控制栏（`▶ 播放 / ⏸ 暂停`、`-10s`、`+10s`、`1.0x / 2.0x / 4.0x` 倍速与实时墙钟时间显示）。
- **双 `<video>` 无缝衔接（`playbackVideoA` + `playbackVideoB`）**：
  - 当前播放分段距离结束不足 2.5 秒时，后台备用 `<video>` 自动预加载下一分段；当前分段触发 `ended` 时立即切换显隐并续播，消除分段切换时的黑屏卡顿。

### 3. 回放车牌看板与画面车牌框叠加

- **数据库扩展（`PlateEventStore.kt` v2）**：
  - [PlateEventStore.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/PlateEventStore.kt) 升级 `DB_VERSION = 2`（自动执行 `ALTER TABLE` 迁移），持久化归一化坐标 `box_x1 / box_y1 / box_x2 / box_y2`（`0.0..1.0`），并在 `/api/events` 中返回 `box: { x1, y1, x2, y2 }`。
- **回放画面车牌看板（`#pb-alpr-card` + `#pb-alpr-extra-cards`）**：
  - 沿用实时监控界面的 `PLATE_TYPE_STYLES` 颜色与大号字样，随回放时间轴实时展示当前画面出现的车牌（支持多车牌同帧展示）。
- **回放画面车牌框叠加（`#playbackOverlayCanvas`）**：
  - 依据 `<video>` `object-fit: contain` 的实际绘制区域动态映射归一化坐标，在回放视频上实时绘制与实时预览一致的彩色车牌框与车牌号标签。

### 4. 过车记录检索与字段修复

- 修复前端字段映射：`plateCode`、`plateType`（整型 `0..9`）、`eventTimeMs`、`snapImg`、`plateImg`，解决此前列表显示 `undefined` 的问题，并放大过车列表中的车牌号（`19px`）与缩略图（`124×48`）。
- 保留车牌关键字检索、车牌类型筛选（整型枚举）、分页器与点击记录联动全景抓拍 + 提前 2 秒预卷定位播放。
