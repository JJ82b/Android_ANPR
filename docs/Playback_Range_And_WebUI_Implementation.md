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

### 2. 检索、分页与事件卡片列表

- **多条件检索**：
  - 车牌关键字输入（`#eventSearchInput`，支持回车触发检索）
  - 车牌类型过滤（`#eventTypeSelect`：全部 / 蓝牌 / 绿牌 / 黄牌）
  - 手动刷新（`#eventRefreshBtn`）与分页器（`#eventPrevBtn` / `#eventNextBtn`，每页 `50` 条）
- **卡片内容**：
  - 车牌缩略图（`/api/events/image?file=<plateImg>`）
  - 车牌号、车牌类型徽章、置信度百分比
  - 过车时间（`YYYY-MM-DD HH:mm:ss`）与关联录像文件名及偏移（`MM:SS`）

### 3. 一键定位与 2 秒预卷播放

- 点击任意过车事件卡片后：
  1. 若存在 `fullImg`，在播放器下方展开**全景抓拍**预览（`/api/events/image?file=<fullImg>`），支持一键收起。
  2. 若存在 `videoFile`：
     - 自动计算预卷时间：`preRollSec = Math.max(0, Math.floor(videoOffsetMs / 1000) - 2)`（提前 2 秒定位，方便观察车辆驶入过程）。
     - 若当前 `<video>` 已加载同一个分段文件，则直接设置 `videoEl.currentTime = preRollSec` 并播放；否则切换 `src` 为 `/files/stream/<videoFile>`，在 `loadedmetadata` 事件触发后精确跳转到 `preRollSec` 并自动播放。
  3. 同步更新底部状态栏与 **下载 MP4** 链接（`/files/<videoFile>`），并允许通过分段下拉框（`#playbackSegmentSelect`）直接播放任意 MP4 分段。
