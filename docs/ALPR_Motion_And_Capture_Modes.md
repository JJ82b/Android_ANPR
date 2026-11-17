# ALPR 运动节能、ROI 裁切与智能抓拍模式说明

本文档说明 Android ANPR 中 **Y 平面静止门控（MotionGate）**、**ROI 与运动团块裁切（MotionCrop）**、**闪光抓拍（Flash Capture）** 与 **车灯高亮抓拍（Headlight Capture）** 的工作原理、相互关系及参数调节建议。

---

## 1. 总体架构与开关关系

所有动态分析均在 `PlateRecognizer.analyzeSceneMotion()` 中基于同一份 `32×18`（576 格）NV21 Y 平面亮度网格完成（耗时 `< 0.1ms`），**零额外神经网络开销**，但各功能的触发条件与动作相互独立：

| 功能 | 开关位置 | 触发条件 | 执行动作 |
| :--- | :--- | :--- | :--- |
| **静止节能门控 (`MotionGate`)** | 高级选项 `#alprMotionGate`（默认开） | 网格亮度变化比例 `>= motionAreaRatio`（默认 `5%`，单格差 `>= 20`） | 静止时退避至 `motionBackoffMs`（默认 `1000ms`）跳过 CNN 推理；有变化时恢复高频识别 |
| **固定区域裁剪 (`ROI`)** | 高级选项 `#alprRoiEnabled`（默认关） | 手动启用并设定归一化框 `X/Y/W/H` | 仅对 ROI 内做运动检测与 HyperLPR3 识别，屏蔽无关背景 |
| **运动团块裁切 (`MotionCrop`)** | 高级选项 `#alprMotionCrop`（默认关） | 4-邻域最大连通块 `>= motionCropMinCells`（默认 `6` 格）且面积适中 | 将最大移动团块外扩 `28%`（最小 `640×360` 兜底）送入 HyperLPR3 |
| **运动自动闪光灯 (`MotionTorch`)** | 高级选项 `#alprMotionTorch`（默认关） | `MotionGate` 检测到运动 | 自动开启闪光灯，静止后延时 `motionTorchHoldMs` 熄灭，并在开关瞬间进入 `torchSettleMs` 稳定期防自激 |
| **闪光抓拍 (`FlashCapture`)** | 卡片右上角 **`闪光抓拍`** 按钮 | **闪光灯处于点亮状态**（自动或手动开灯均生效） | 自动下发预设 `ISO` 与 `快门速度`；熄灯后恢复常规/手动 AE |
| **车灯高亮抓拍 (`HeadlightCapture`)** | 卡片右上角 **`车灯抓拍`** 按钮 | 局部网格亮度跳变 `>= headlightDiffThreshold`（默认 `55`），且占比在 `headlightMinRatio..headlightMaxRatio`（默认 `2%~55%`） | **不依赖普通运动灵敏度**，仅局部强光跳变时切入预设 `ISO` 与 `快门速度`，高亮消失后自动恢复 |

---

## 2. “车灯高亮抓拍”和“普通运动捕捉”是分开的吗？

**触发逻辑与执行动作完全独立（即使关闭 ONVIF 移动侦测或关闭 `MotionGate`，`车灯抓拍` 仍可独立运行）：**

1. **普通运动（行人/树影/轻微移动）不会误触发车灯抓拍**
   - 普通运动使用较低阈值（默认 `motionDiffThreshold = 20`），触发后只负责**唤醒 ALPR 推理**（以及可选的 `MotionCrop` / `MotionTorch`）。
   - 车灯抓拍使用独立的强跳变阈值（默认 `headlightDiffThreshold = 55`），一般行人、树枝晃动亮度变化达不到 `55`，**不会切换相机快门/ISO**，避免预览画面频繁变暗。
2. **全屏光照突变不会误触发车灯抓拍**
   - 通过 `headlightMaxRatio`（默认 `55%`）限制最大高亮面积占比，同时配合 `motionGlobalJumpRatio`（默认 `75%`），可有效过滤路灯开启、闪电或闪光灯开关导致的整屏亮度跳变，确保**只有“部分区域亮度大幅变化”（如车辆大灯/尾灯驶入）时才进入抓拍模式**。
3. **可单独使用，也可与闪光抓拍配合**
   - 如果路口本身有车灯照明，可以只开 **`车灯抓拍`**（不开 `MotionTorch`），车灯照亮画面瞬间自动切入低 ISO + 高快门（如 `ISO 200` + `1/500s`）消除拖影与大灯过曝，车辆驶离后自动恢复常规自动曝光。

---

## 3. 关键参数与调优建议

### 3.1 常规运动检测调试
- **单格亮度变化阈值 (`motionDiffThreshold`, 默认 `20`)**：夜间噪点或微风树影误触发多时，建议调至 `25~35`。
- **触发运动最小面积占比 (`motionAreaRatio`, 默认 `5%`)**：远距离小车易漏检可降至 `2%~3%`；近距离防小动物/飞虫可升至 `8%~12%`。
- **背景自适应速度 (`motionBgAlpha`, 默认 `0.05`)**：数值越大，停稳的车辆或光线渐变越快融入背景。
- **闪光灯切换稳定期 (`torchSettleMs`, 默认 `1000ms`)**：关灯后若仍偶发自激重新亮灯，可调大至 `1200ms~1500ms`。
- **全屏光照突变抑制阈值 (`motionGlobalJumpRatio`, 默认 `75%`)**：当全屏超过该比例网格发生同向亮度跳变时，直接重设背景而不判定为运动。

### 3.2 车灯高亮抓拍调试
- **车灯亮度跳变阈值 (`headlightDiffThreshold`, 默认 `55`)**：夜间车灯明显可设为 `55~75`；黄昏或车灯较弱可降至 `40~50`。
- **高亮区最小占比 (`headlightMinRatio`, 默认 `2%`)**：过滤远处小光点或飞虫反光。
- **高亮区最大占比 (`headlightMaxRatio`, 默认 `55%`)**：防止整屏亮/暗突变误触发抓拍模式。

---

## 4. 可视化排查

点击 ALPR 卡片右上角 **`运动区块`** 按钮，可在实时监控画面左上角及画面中观察实时状态：
- **蓝色虚线框**：检测到的最大运动连通团块；
- **紫色实线框**：`MotionCrop` 生效时实际送入 HyperLPR3 的裁切区域；
- **状态徽章**：实时显示 `动态裁切生效 W×H`、`运动唤醒 · 全图/ROI W×H`、`画面静止 · 节能退避中` 以及 `· 车灯抓拍中`。

---

## 5. 相关代码索引
- 核心检测与状态机：[PlateRecognizer.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/PlateRecognizer.kt)
- 相机手动 ISO / 快门控制：[CameraXCapture.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/CameraXCapture.kt)
- 曝光切换与闪光灯联动：[StreamingService.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/StreamingService.kt)
- WebUI 控制与可视化：[index.html](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/assets/index.html)
