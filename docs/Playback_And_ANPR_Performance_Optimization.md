# ANPR 与回放系统性能优化文档

本文档集中记录针对 24 小时不间断录像、车牌事件入库及 WebUI 回放页面的资源占用与性能优化实现。

---

## 一、后端入库锁粒度优化：`PlateEventStore.kt`

- **文件路径**：[PlateEventStore.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/PlateEventStore.kt)
- **优化前问题**：
  - `recordDetection()` 在 `synchronized(dbLock)` 临界区内直接执行 `saveEventImages()`，包含 `NV21 -> JPEG` 压缩、`Bitmap` 解码、`Matrix` 旋转裁剪与两次磁盘 JPEG 写入。
  - 这会导致前端检索 `/api/events`（同样需要获取 `dbLock` 读取 SQLite）在抓拍写入期间被长时间阻塞。
- **优化实现**：
  ```mermaid
  flowchart TD
      A[recordDetection] --> B["加锁 dbLock: 查询 15s 内去重匹配"]
      B -->|已有更高置信度记录| C["仅更新 last_seen_ms 并立即返回"]
      B -->|新事件或置信度更高| D["释放 dbLock"]
      D --> E["锁外执行 saveEventImages: YUV->Bitmap->裁剪/缩放->写JPEG"]
      E --> F["再次加锁 dbLock: 写入/更新 SQLite（若期间被超越则清理临时图）"]
  ```
  - 仅在必须保存新图像时才执行编解码，且编解码全过程移至 `dbLock` 之外，大幅缩短数据库锁持有时间，保障 WebUI 事件查询的响应速度。

---

## 二、回放页自动停止实时预览与轮询：`index.html`

- **文件路径**：[index.html](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/assets/index.html)
- **优化前问题**：
  - 切换到「过车回放」页时，隐藏的实时监控视图仍在后台持续拉取 `/video/h264` 或 `/video/mjpeg` 视频流，浏览器持续解码并运行 `requestAnimationFrame` 绘制；手机服务端也需维持实时编码与网络推流。
  - `/api/plate`（每 150ms~200ms 级别）和 `checkRecordingStatus`（每 3 秒）定时器仍在后台持续发起请求。
- **优化实现**：
  - 引入全局状态 `isPlaybackViewActive` 及 `pauseLivePreview()` / `resumeLivePreview()`：
    - 切入回放页时立即调用 `stopMjpegStream()` 与 `stopH264Stream()`，中止 `fetch` 连接、关闭 WebCodecs `VideoDecoder` 并停止绘制循环；手机端检测到连接断开后自动释放该客户端推流会话（不影响 24h 循环录像）。
    - 在 `/api/plate`、`checkRecordingStatus` 定时轮询以及 `visibilitychange`（页面从后台切回前台）回调中增加 `isPlaybackViewActive` 判断，回放模式下跳过轮询且不误重启实时流。
    - 切回「实时监控」页时调用 `resumeLivePreview()` 按当前 `streamMode` 恢复拉流与状态刷新。

---

## 三、双 `<video>` 解码器内存释放：`index.html`

- **文件路径**：[index.html](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/assets/index.html)
- **优化前问题**：
  - 回放页为实现分段无缝衔接使用了 `playbackVideoA` 与 `playbackVideoB` 两个 `<video>` 实例；切回实时页时若仅调用 `pause()`，浏览器仍会保留两路 MP4 解码器与视频帧缓存。
- **优化实现**：
  - 在 `switchTab('live')` 时对 `[videoA, videoB]` 执行：
    ```js
    v.pause();
    v.removeAttribute('src');
    v.load();
    ```
  - 彻底释放浏览器媒体解码器与缓冲内存，并重置 `currentSegIdx = -1` 与 `preloadedSegIdx = -1`。

---

## 四、回放看板 DOM 差异缓存：`index.html`

- **文件路径**：[index.html](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/assets/index.html)
- **优化前问题**：
  - 视频播放时 `timeupdate` 事件约每秒触发 4 次，每次都会重建 `#pb-alpr-card` 样式与 `#pb-alpr-extra-cards` 子节点，造成不必要的 DOM 重排。
- **优化实现**：
  - 在 `renderPlaybackCardAndOverlay()` 中计算当前激活车牌集合的签名 `pbCardKey`（由 `id`、`plateCode`、`confidence` 拼接），并与 `lastPbCardKey` 比对；仅当画面中的车牌发生变化时才更新看板 DOM。
