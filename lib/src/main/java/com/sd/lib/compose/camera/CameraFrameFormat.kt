package com.sd.lib.compose.camera

import androidx.camera.core.ImageAnalysis

/** [CameraPreview] 传给帧分析回调的像素格式 */
enum class CameraFrameFormat {
  /** CameraX 原生输出，通常有三个平面，不产生额外的格式转换。 */
  YUV_420_888,

  /** 单平面 RGBA 输出；CameraX 会为每帧执行 YUV 到 RGBA 的转换。 */
  RGBA_8888,
}

internal fun CameraFrameFormat.toImageAnalysisOutputFormat(): Int = when (this) {
  CameraFrameFormat.YUV_420_888 -> ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
  CameraFrameFormat.RGBA_8888 -> ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
}
