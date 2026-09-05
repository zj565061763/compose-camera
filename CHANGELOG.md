# Changelog

## 1.1.1

### ♻️ Changes

- 优化相机与帧分析线程复用，减少会话重建开销。

### 🐛 Bug Fixes

- 修复会话切换、停止和销毁时的帧回调竞态与资源释放问题。
- 修复预览首帧误判，以及布局变化时短暂拉伸和坐标错位的问题。
- 修复采样帧调度与中断清理，确保处理最新请求并正确回收 Bitmap。

## 1.1.0

### ✨ Features

- 新增 `FrameProcessor`，支持直接接收 NV21 帧或按间隔截取预览区域。
- 新增可覆盖的 `displayRotation`，默认自动监听当前显示器旋转。
- 新增 `CameraPreviewState.takeScreenshot()`，支持按指定 `CameraMirrorMode` 截取当前预览区域。
- 新增 `CameraPreviewState.requestFocus()`，用于显式请求当前单次自动对焦会话重新对焦。
- 新增 `CameraPreviewState.failure`，区分需要重新枚举设备或重建相机会话的当前故障和仅用于诊断的普通异常。

### ♻️ Changes

- 重构相机会话、设备枚举、预览渲染和帧分发，移除 CameraX 依赖。
- `cameraId` 保持为不透明的 `String`，`CameraFrame` 不再暴露 `ImageProxy`。
- `CameraFrame` 调整为 `Preview` 和 `PreviewSampled` 两种帧类型，移除 `CameraPreview.onFrame`。
- `CameraFrame.Preview.toBitmap()` 转换失败时返回 `null`。
- 移除当前相机后端无法识别的 `CameraLens.EXTERNAL`。

### 🐛 Bug Fixes

- 修复会话重建时首个 `SurfaceTexture` 更新可能被漏掉或误认旧缓冲，导致首帧矩阵跳跃的问题。
- 修复前置摄像头原始帧旋转方向和预览显示方向混用的问题。
- 不支持连续对焦但支持单次自动对焦时，仅在首个有效预览帧自动对焦，不再定期触发。
- 启用帧回调时校验设备实际采用的预览格式，避免把异常格式误当作 NV21 帧处理。
- 相机会话操作迁移到专用线程，避免打开相机时阻塞主线程。
- 镜头方向枚举暂时失败时，会话启动同步校正预览镜像和帧坐标状态。

## 1.0.1

### 🐛 Bug Fixes

- **修复预览镜像模式切换不生效**：改用嵌入式 Viewfinder，确保 `CameraMirrorMode.ON` 和 `CameraMirrorMode.OFF` 能正确改变预览镜像。
