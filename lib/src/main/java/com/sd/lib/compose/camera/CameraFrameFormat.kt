package com.sd.lib.compose.camera

/** [CameraPreview] 传给帧回调的数据格式 */
enum class CameraFrameFormat {
  /** 原始 NV21 数据 */
  NV21,

  /** 编码后的 JPEG 数据 */
  JPEG,
}
