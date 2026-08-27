[![Maven Central](https://img.shields.io/maven-central/v/io.github.zj565061763.android/compose-camera)](https://central.sonatype.com/search?q=g:io.github.zj565061763.android+compose-camera)

# compose-camera

面向 Jetpack Compose 的轻量相机预览组件，支持设备枚举、镜像、缩放、帧回调和坐标转换。

## 依赖

```kotlin
implementation("io.github.zj565061763.android:compose-camera:$version")
```

库 Manifest 已声明 `CAMERA` 权限，并把相机与自动对焦硬件标记为可选。应用仍需在运行时取得相机权限，再组合 `CameraPreview`。

## 基本用法

```kotlin
val previewState = rememberCameraPreviewState()
val devicesState = rememberCameraDevicesState()
val devices by devicesState.devices

CameraPreview(
  modifier = Modifier.fillMaxWidth().aspectRatio(1f),
  state = previewState,
  devicesState = devicesState,
  cameraId = devices.firstOrNull()?.cameraId,
  mirrorMode = CameraMirrorMode.AUTO,
  contentScale = ContentScale.Crop,
  frameFormat = CameraFrameFormat.NV21,
  onError = { error -> /* 处理错误 */ },
  onFrame = { frame ->
    val bitmap = frame.toBitmap()
  },
)
```

`cameraId` 是不透明的字符串标识。传入 `null` 时使用设备列表的第一项；传入不存在的值时，通过 `onError` 报告 `CAMERA_NOT_FOUND`，不会回退到其他设备。设备列表不会自动监听热插拔，需调用 `CameraDevicesState.refresh()` 主动更新。

## 帧分析

`onFrame` 非空时，组件在名为 `CameraPreview-Analysis` 的单线程中同步发送最新帧。默认格式为 `NV21`，也可以选择 `JPEG`；JPEG 编码同样发生在分析线程。

`CameraFrame.data` 只保证在当前回调期间有效。异步处理前应复制数据，或在回调中调用 `CameraFrame.toBitmap()` 创建独立图片；转换失败时该方法返回 `null`。`rotationDegrees` 表示把原始帧旋转到当前预览方向所需的角度，`toBitmap()` 不会应用这项旋转。

`CameraPreviewState.previewResolution` 表示当前会话使用的原始帧分辨率，不是 Compose 布局尺寸；会话未运行时为 `IntSize.Zero`。

检测器返回原始缓冲区坐标时，可以通过 `CameraPreviewState.createTransformToPreview(frame)` 映射到 Compose 预览区域。异步结果写回前，应通过 `isFrameTransformCurrent(frame.transformToken)` 确认布局、镜像和相机会话没有发生变化。

## 显示行为

- `contentScale` 默认使用 `ContentScale.Crop`，并同时作用于预览显示和坐标矩阵。
- `CameraMirrorMode.AUTO` 默认镜像前置摄像头，`ON` 和 `OFF` 可以强制目标状态；镜像只影响显示和坐标矩阵，不修改帧数据。
- `displayRotation` 默认为 `null`，组件会监听当前 View 所在显示器的旋转；也可以传入 `Surface.ROTATION_*` 固定方向。
- 每个正在组合的预览必须使用独立的 `CameraPreviewState`。当前不支持不同 `cameraId` 同时预览。

# Changelog

版本更新记录：[CHANGELOG.md](CHANGELOG.md)
