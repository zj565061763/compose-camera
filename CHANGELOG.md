# Changelog

## 未发布

### ✨ Features

- 新增 `CameraFrameFormat.NV21` 和 `CameraFrameFormat.JPEG`，帧数据通过 `CameraFrame.data` 暴露。
- 新增可覆盖的 `displayRotation`，默认自动监听当前显示器旋转。

### ♻️ Changes

- 重构相机会话、设备枚举、预览渲染和帧分发，移除 CameraX 依赖。
- `cameraId` 保持为不透明的 `String`，`CameraFrame` 不再暴露 `ImageProxy`。
- `CameraFrame.toBitmap()` 转换失败时返回 `null`。

## 1.0.1

### 🐛 Bug Fixes

- **修复预览镜像模式切换不生效**：改用嵌入式 Viewfinder，确保 `CameraMirrorMode.ON` 和 `CameraMirrorMode.OFF` 能正确改变预览镜像。
