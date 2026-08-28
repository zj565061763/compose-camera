[![Maven Central](https://img.shields.io/maven-central/v/io.github.zj565061763.android/compose-camera)](https://central.sonatype.com/search?q=g:io.github.zj565061763.android+compose-camera)

## 依赖

```kotlin
implementation("io.github.zj565061763.android:compose-camera:$version")
```

## 帧处理

`CameraPreview` 默认不产生分析帧。可以通过 `FrameProcessor` 直接接收 NV21 帧，或按间隔截取已应用旋转和 `ContentScale`、但不包含镜像和 overlay 的预览区域。缩放后未由相机内容覆盖的区域透明：

```kotlin
CameraPreview(
  frameProcessor = FrameProcessor.Preview { frame ->
    // 同步处理 frame.data
  },
)

CameraPreview(
  frameProcessor = FrameProcessor.PreviewSampled(intervalMillis = 200) { frame ->
    // 同步处理 frame.data Bitmap
  },
)
```

## 拍照

通过传给 `CameraPreview` 的 `CameraPreviewState` 截取当前预览区域。返回图片已经应用显示旋转、`ContentScale` 和指定镜像模式，不包含预览上层内容；预览尚未产生有效帧或普通截图失败时返回 `null`，截图异常同时通过 `CameraPreview.onError` 报告。此方法必须在主线程调用，返回的 `Bitmap` 由调用方负责回收：

```kotlin
val state = rememberCameraPreviewState()

CameraPreview(state = state)

Button(
  onClick = {
    state.takePicture(CameraMirrorMode.AUTO)?.also { bitmap ->
      // 使用并持有 bitmap，结束后调用 bitmap.recycle()
    }
  },
) {
  Text("拍照")
}
```

# Changelog

版本更新记录：[CHANGELOG.md](CHANGELOG.md)
