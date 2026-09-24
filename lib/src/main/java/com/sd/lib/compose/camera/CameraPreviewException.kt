package com.sd.lib.compose.camera

import androidx.camera.core.CameraState

/** [CameraPreview] 无法选择、打开或继续使用摄像头 */
class CameraPreviewException internal constructor(
  val reason: Reason,
  message: String,
  /** 相机后端报告的错误码，只用于诊断 */
  val cameraErrorCode: Int? = null,
  cause: Throwable? = null,
) : RuntimeException(message, cause) {
  enum class Reason {
    /** 当前没有可用摄像头 */
    NO_AVAILABLE_CAMERA,

    /** 指定的 cameraId 不存在 */
    CAMERA_NOT_FOUND,

    /** 无法打开或配置摄像头 */
    CAMERA_OPEN_FAILED,

    /** 摄像头运行期间报告错误 */
    CAMERA_RUNTIME_ERROR,
  }
}

internal fun cameraSelectionException(cameraId: String?): CameraPreviewException {
  return if (cameraId == null) {
    CameraPreviewException(
      reason = CameraPreviewException.Reason.NO_AVAILABLE_CAMERA,
      message = "No camera is available.",
    )
  } else {
    CameraPreviewException(
      reason = CameraPreviewException.Reason.CAMERA_NOT_FOUND,
      message = "Camera ID '$cameraId' is not available.",
    )
  }
}

internal fun cameraOpenException(cameraId: String, cause: Exception): CameraPreviewException {
  return CameraPreviewException(
    reason = CameraPreviewException.Reason.CAMERA_OPEN_FAILED,
    message = "Failed to open or configure camera ID '$cameraId'.",
    cause = cause,
  )
}

/** 相机打开后发生的错误视为运行错误，其余视为打开失败 */
internal fun cameraStateException(type: CameraState.Type, error: CameraState.StateError): CameraPreviewException {
  val isRuntimeError = type == CameraState.Type.OPEN || type == CameraState.Type.CLOSING
  return CameraPreviewException(
    reason = if (isRuntimeError) {
      CameraPreviewException.Reason.CAMERA_RUNTIME_ERROR
    } else {
      CameraPreviewException.Reason.CAMERA_OPEN_FAILED
    },
    message = "The camera reported error code ${error.code} in state $type.",
    cameraErrorCode = error.code,
    cause = error.cause,
  )
}
