package com.sd.lib.compose.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

/**
 * 预览回调产生的分析帧。
 *
 * [data] 是 NV21 数据，只在 `CameraPreview.onFrame` 回调期间有效。
 * 需要异步处理时应在回调内复制数据，或先通过 [toBitmap] 创建独立图片。
 * [width] 和 [height] 表示未旋转的原始帧尺寸。
 * [rotationDegrees] 表示把原始帧顺时针旋转到当前预览方向所需的角度。
 */
class CameraFrame internal constructor(
  val data: ByteArray,
  val width: Int,
  val height: Int,
  val rotationDegrees: Int,
  internal val transformIdentity: CameraFrameTransformIdentity?,
) {
  /** 可在帧回调结束后保留，用于判断异步结果是否仍属于当前预览变换。 */
  val transformToken = CameraFrameTransformToken(transformIdentity)

  /** 把当前帧数据转换为独立的未旋转 Bitmap，转换失败时返回 `null`。 */
  fun toBitmap(): Bitmap? {
    val imageData = nv21ToJpeg(data, width, height) ?: return null
    return BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
  }
}

internal fun nv21ToJpeg(data: ByteArray, width: Int, height: Int): ByteArray? {
  val output = ByteArrayOutputStream()
  val compressed = YuvImage(data, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 100, output)
  if (!compressed) return null
  return output.toByteArray()
}
