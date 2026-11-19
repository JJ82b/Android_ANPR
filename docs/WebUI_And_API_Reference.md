# Web 管理后台与 HTTP API 说明

Web 管理界面位于 [app/src/main/assets/index.html](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/assets/index.html)，由手机端 `StreamingServerHelper` 直接提供静态资源与 REST 接口（默认 HTTPS 端口 `4444`，可在设置中调整）。

---

## 一、三大功能标签页

1. **📺 实时监控**
   - **视频与 Canvas 实时叠加层**：在视频上方精确映射并绘制：
     - **车牌框**（按蓝/绿/黄/白车牌类型着色 + 车牌号标签）；
     - **车辆预检框**（橙色虚线框 + 类别与置信度，如 `car 82%`）；
     - **运动团块框**（蓝色虚线框）与 **动态裁剪框**（紫色实线框）；
     - **强光区块框**（金黄色虚线框 + `强光` 标签）。
   - **防误触折叠面板**：所有相机、流媒体、曝光、旋转、录像控制与 ALPR 高级参数均按功能分类收纳在默认折叠的下拉面板（`<details class="ctrl-accordion">`）中，避免手机滑动误触。
2. **🎞️ 过车回放**
   - **带录像小点的日历选择**：点击日期可展开月历，下方带绿色小圆点的日期表示当天存在录像分段。
   - **最长 24 小时时间轴**：支持 `24小时 / 上午 (00-12) / 下午 (12-24) / 1小时 / 15分钟` 缩放、双击/双视频槽无缝切换播放、`-5s / +5s` 快进退、全屏与大图灯箱查看。
3. **📁 记录管理**
   - 支持录像文件（`.mp4`）与车牌过车记录的列表浏览、搜索、单删及**多选批量删除**（删录像时可同步清理关联事件）。

---

## 二、常用后端 HTTP API 一览

| 接口路径 | 方法 | 用途 |
| :--- | :--- | :--- |
| `/api/plate` | GET | 获取最新车牌、车辆检测框、耗时与 `motionDebug`（含强光/运动框坐标） |
| `/api/alpr/config` | GET / POST | 读取或热更新 ALPR、门控、车辆预检、强光与抓拍曝光参数 |
| `/api/vehicle/models` | GET | 列出内置与已上传的 `.tflite` 车辆检测模型 |
| `/api/vehicle/models/upload` | POST | 上传自定义 `.tflite` 模型（Base64 传输） |
| `/api/vehicle/models/delete` | POST | 删除已上传的自定义 `.tflite` 模型 |
| `/api/recording/loop` | GET / POST | 查询或开关 24h 循环录像及分段时长/容量上限 |
| `/api/events` | GET | 分页查询与搜索过车记录 |
| `/api/events/image` | GET | 读取过车记录抓拍图（JPEG） |
| `/api/events/delete` | POST | 单条、多选或清空删除过车记录 |
| `/files.json` / `/files/*` / `/files/stream/*` | GET / POST | 录像文件列表、下载、HTTP Range 分段流播放与删除 |
| `/talk` | POST | 网页按住对讲（PTT）PCM 音频流上传 |
