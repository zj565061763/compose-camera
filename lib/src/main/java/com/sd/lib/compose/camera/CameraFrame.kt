package com.sd.lib.compose.camera

import androidx.camera.core.ImageProxy

/**
 * 摄像头分析帧。
 *
 * [image] 的格式由 `CameraPreview.frameFormat` 决定；它只在 `onFrame` 回调执行期间有效，
 * 回调结束后会由 CameraPreview 自动关闭。
 */
class CameraFrame internal constructor(
  val image: ImageProxy,
  internal val transformIdentity: CameraFrameTransformIdentity?,
) {
  /** 可在帧回调结束后保留，用于判断异步结果是否仍属于当前预览变换。 */
  val transformToken = CameraFrameTransformToken(transformIdentity)
}
