# HyperLPR3 GPU (OpenCL) 独立库编译与 CPU/GPU 运行时切换配置指南

本文档记录在 **不修改/不替换原 CPU SDK（`hyperlpr3-android-sdk:1.0.3` / `libhyperlpr3.so`）** 的前提下，从源码编译支持 MNN OpenCL GPU 加速的独立原生库 `libhyperlpr3_gpu.so`，并在 Android 端与 WebUI 中实现运行时切换 **CPU / GPU** 推理后端的完整配置过程。

---

## 1. 整体架构设计

为保证兼容性并支持运行时自由切换，项目采用 **双库共存** 方案：

| 后端 | 原生库 | Java JNI 入口 | 依赖来源 | 适用 ABI |
| :--- | :--- | :--- | :--- | :--- |
| **CPU** | `libhyperlpr3.so` | `com.hyperai.hyperlpr3.core.HyperLPRCore` | Maven `hyperlpr3-android-sdk:1.0.3` | 全 ABI（随原 AAR） |
| **GPU (OpenCL)** | `libhyperlpr3_gpu.so` | `com.hyperai.hyperlpr3.core.HyperLPRGpuCore` | `app/src/main/jniLibs/arm64-v8a/` | `arm64-v8a` |

- `hyperlpr3-android-sdk:1.0.3` 继续保留在 `app/build.gradle` 中，提供 `HyperLPR3`、`HyperLPRParameter`、`Plate` 等 Java Bean 以及内置模型文件释放逻辑。
- `libhyperlpr3_gpu.so` 静态链接了开启 OpenCL 的 `libMNN.a`（`-DANDROID_STL=c++_static`），并在运行时通过 `dlopen` 动态加载系统 `libOpenCL.so`；若设备不支持 OpenCL，会自动回退至 MNN CPU 后端。

---

## 2. 编译环境与依赖准备

本机已配置工具链：
- **Android NDK**: `/home/chunxiaobian/Android/Sdk/ndk/android-ndk-r29`
- **CMake & Ninja**: 系统已安装 `cmake`、`ninja`
- **源码与第三方依赖目录**（位于 `third_party/`）：
  - `third_party/HyperLPR`：HyperLPR3 官方源码仓库
  - `third_party/MNN`：MNN 2.2.0 源码（用于编译支持 OpenCL 的静态库 `libMNN.a`）
  - `third_party/OpenCV-android-sdk`：OpenCV 4.6.0 Android SDK（来自 `third_party/opencv-4.6.0-android-sdk.zip`）

---

## 3. 关键构建步骤

### 3.1 编译 MNN 2.2.0 静态库（开启 OpenCL）

构建输出目录为 `third_party/build_mnn_arm64`，核心 CMake 参数如下（来自 `third_party/build_mnn_arm64/CMakeCache.txt`）：

- `CMAKE_TOOLCHAIN_FILE=/home/chunxiaobian/Android/Sdk/ndk/android-ndk-r29/build/cmake/android.toolchain.cmake`
- `ANDROID_ABI=arm64-v8a`
- `ANDROID_PLATFORM=android-24`
- `ANDROID_STL=c++_static`
- `MNN_OPENCL=ON`
- `MNN_ARM82=ON`

最终生成静态库 `third_party/build_mnn_arm64/libMNN.a`（全量静态归档，供下游以 `-Wl,--whole-archive` 链接以保留 OpenCL 注册符号）。

### 3.2 修改 HyperLPR3 源码（支持独立库名、独立 JNI 类名与 OpenCL 后端）

在 `third_party/HyperLPR` 中进行了 4 处最小化修改：

1. **`CMakeLists.txt`**：允许通过 `-DHYPERLPR_LIB_NAME=hyperlpr3_gpu` 指定输出库名（默认仍为 `hyperlpr3`），避免与原库名冲突。
2. **`cpp/platform/jni/common/jni_utils.h`**：新增 `HYPERLPR_CORE_API(method)` 宏；当定义 `HYPERLPR_GPU_JNI` 时映射到 `Java_com_hyperai_hyperlpr3_core_HyperLPRGpuCore_<method>`，否则保持 `HyperLPRCore`。
3. **`cpp/platform/jni/android/hyperlpr_native.cpp`**：将 4 个核心 JNI 函数（`CreateRecognizerContext`、`ReleaseRecognizerContext`、`PlateRecognitionFromImage`、`PlateRecognitionFromBuffer`）切换为使用 `HYPERLPR_CORE_API(...)`。
4. **`cpp/src/inference_helper_module/inference_helper_mnn.cpp`**：将 MNN `ScheduleConfig` 配置为：
   - `scheduleConfig.type = MNN_FORWARD_OPENCL;`
   - `scheduleConfig.backupType = MNN_FORWARD_CPU;`
   - `BackendConfig`：`Power_High`、`Precision_Low`。

### 3.3 编译并裁剪 `libhyperlpr3_gpu.so`

构建目录 `third_party/build_hyperlpr_gpu_arm64`（`ANDROID_ABI=arm64-v8a`、`ANDROID_PLATFORM=android-24`、`ANDROID_STL=c++_static`、`HYPERLPR_LIB_NAME=hyperlpr3_gpu`、`HYPERLPR_GPU_JNI`）：

1. 链接生成 `third_party/build_hyperlpr_gpu_arm64/libhyperlpr3_gpu.so`（未裁剪前约 `101M`）。
2. 复制到 `app/src/main/jniLibs/arm64-v8a/libhyperlpr3_gpu.so` 并使用 NDK `llvm-strip` 裁剪调试符号：
   ```bash
   /home/chunxiaobian/Android/Sdk/ndk/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip --strip-unneeded app/src/main/jniLibs/arm64-v8a/libhyperlpr3_gpu.so
   ```
   裁剪后体积降至约 **`11M`**。

---

## 4. Android 端与 WebUI 集成说明

### 4.1 独立 Java JNI 封装
新增 [HyperLPRGpuCore.java](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/java/com/hyperai/hyperlpr3/core/HyperLPRGpuCore.java)：
- 静态加载 `System.loadLibrary("hyperlpr3_gpu")`，捕获 `UnsatisfiedLinkError` 并通过 `isAvailable()` / `getLoadError()` 对外暴露可用性。
- 声明与 `HyperLPRCore` 一致的 native 方法：`createRecognizerContext`、`plateRecognitionFromBuffer`、`plateRecognitionFromImage`、`release`。

### 4.2 运行时后端切换与持久化
- [PlateRecognizer.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/PlateRecognizer.kt)：
  - `AlprConfig` 新增 `useGpu: Boolean = false`（SharedPreferences 键 `alpr_use_gpu`），`toJson()` 返回 `useGpu` 与 `gpuAvailable`。
  - 首次初始化仍先由 `HyperLPR3.getInstance().init(...)` 释放内置模型至 `cachedModelPath`；若配置了 `useGpu && HyperLPRGpuCore.isAvailable()`，则释放默认实例并创建 `HyperLPRGpuCore`。
  - `reloadConfig()` 中通过 `releaseAllCores()` + `activateBackend()` 实现 CPU / GPU 热切换（若 GPU 初始化失败会自动回退至 CPU `HyperLPRCore`）。
- [StreamingService.kt](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/StreamingService.kt)：`/api/alpr/config` 接收 `useGpu` 参数并触发热重载。

### 4.3 WebUI 切换入口
- [index.html](file:///home/chunxiaobian/Projects/Android_ANPR/app/src/main/assets/index.html)：在 ALPR「高级选项」面板中新增 **推理后端 (Backend)** 下拉框（`CPU (libhyperlpr3.so)` / `GPU OpenCL (libhyperlpr3_gpu.so)`）及对应中英文 I18N 词条；当 `gpuAvailable === false` 时自动禁用 GPU 选项。

---

## 5. 注意事项与待真机验证项

- 当前仅编译打包了 `arm64-v8a` 架构的 `libhyperlpr3_gpu.so`；非 `arm64-v8a` 设备会自动隐藏/禁用 GPU 切换并安全保持 CPU 模式。
- OpenCL 首次初始化可能会触发 GPU shader 编译（通常仅首帧或首次创建会话较慢），后续帧推理耗时与硬件 GPU 驱动兼容性需在目标 `arm64-v8a` 真机上实测对比。
