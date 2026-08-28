[![Maven Central](https://img.shields.io/maven-central/v/io.github.zj565061763.android/compose-camera)](https://central.sonatype.com/search?q=g:io.github.zj565061763.android+compose-camera)

## 依赖

```kotlin
implementation("io.github.zj565061763.android:compose-camera:$version")
```

## 帧处理

`CameraPreview` 默认不产生分析帧。可以通过 `FrameProcessor` 直接接收 NV21 帧，或按间隔截取已应用旋转和 `ContentScale`、但不包含镜像和 overlay 的预览区域：

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

# Changelog

版本更新记录：[CHANGELOG.md](CHANGELOG.md)
