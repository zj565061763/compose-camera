package com.sd.lib.compose.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

/** 摄像头预览产生的分析帧 */
sealed interface CameraFrame {
  /** 把帧顺时针旋转到当前预览方向所需的角度 */
  val rotationDegrees: Int

  /** 可在帧回调结束后保留，用于判断异步结果是否仍属于当前预览变换。 */
  val transformToken: CameraFrameTransformToken

  /**
   * 原始 NV21 预览帧。
   *
   * [data] 只在 [FrameProcessor.Preview.onFrame] 回调期间有效；需要异步处理时应复制数据，
   * 或先通过 [toBitmap] 创建独立图片。[width] 和 [height] 表示未旋转的原始帧尺寸。
   */
  class Preview internal constructor(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    override val rotationDegrees: Int,
    internal val transformIdentity: CameraFrameTransformIdentity?,
  ) : CameraFrame {
    override val transformToken = CameraFrameTransformToken(transformIdentity)

    /** 把当前帧转换为独立的未旋转 Bitmap，转换失败时返回 `null`。 */
    fun toBitmap(): Bitmap? {
      val imageData = nv21ToJpeg(data, width, height) ?: return null
      return BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
    }
  }

  /**
   * 从预览区域采样得到的帧。
   *
   * [data] 已应用显示旋转和 [androidx.compose.ui.layout.ContentScale]，不包含镜像和预览上层内容，
   * 只在 [FrameProcessor.PreviewSampled.onFrame] 回调期间有效；需要保留时应在回调内复制。
   */
  class PreviewSampled internal constructor(
    val data: Bitmap,
    override val rotationDegrees: Int,
    internal val transformIdentity: CameraFrameTransformIdentity?,
  ) : CameraFrame {
    override val transformToken = CameraFrameTransformToken(transformIdentity)
  }
}

internal fun nv21ToJpeg(data: ByteArray, width: Int, height: Int): ByteArray? {
  val output = ByteArrayOutputStream()
  val compressed = YuvImage(data, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 100, output)
  if (!compressed) return null
  return output.toByteArray()
}
