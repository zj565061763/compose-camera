package com.sd.lib.compose.camera

import android.annotation.SuppressLint
import androidx.camera.core.CameraState

/**
 * [CameraPreview] 没有可用摄像头、无法按 cameraId 选择摄像头，或绑定后的摄像头进入错误状态。
 *
 * @property reason 错误所属阶段。
 * @property cameraStateErrorCode CameraX 的运行期错误码；非 [Reason.CAMERA_STATE_ERROR] 时为 `null`。
 */
class CameraPreviewException internal constructor(
  val reason: Reason,
  message: String,
  val cameraStateErrorCode: Int? = null,
  cause: Throwable? = null,
) : RuntimeException(message, cause) {
  enum class Reason {
    /** CameraX 当前没有可用摄像头 */
    NO_AVAILABLE_CAMERA,

    /** 指定的 Camera2 cameraId 不存在或当前不能被 CameraX 选择 */
    CAMERA_NOT_FOUND,

    /** CameraX 在摄像头绑定后报告了运行期错误 */
    CAMERA_STATE_ERROR,
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

internal fun CameraState.toCameraPreviewExceptionOrNull(): CameraPreviewException? {
  return error?.let { stateError ->
    CameraPreviewException(
      reason = CameraPreviewException.Reason.CAMERA_STATE_ERROR,
      message = stateError.message(),
      cameraStateErrorCode = stateError.code,
      cause = stateError.cause,
    )
  }
}

/** CameraX 不会自动恢复 CRITICAL 错误，关联 UseCase 必须解绑后重新创建。 */
internal fun CameraState.requiresCameraSessionClose(): Boolean {
  return error?.type == CameraState.ErrorType.CRITICAL
}

@SuppressLint("RestrictedApi")
private fun CameraState.StateError.message(): String = when (code) {
  CameraState.ERROR_MAX_CAMERAS_IN_USE -> "The maximum number of cameras is already in use."
  CameraState.ERROR_CAMERA_IN_USE -> "The selected camera is already in use."
  CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> "CameraX reported a recoverable camera error."
  CameraState.ERROR_STREAM_CONFIG -> "CameraX failed to configure the camera stream."
  CameraState.ERROR_CAMERA_DISABLED -> "The camera is disabled by device policy."
  CameraState.ERROR_CAMERA_FATAL_ERROR -> "CameraX reported a fatal camera error."
  CameraState.ERROR_CAMERA_REMOVED -> "The selected camera was removed from the device."
  CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> {
    "The camera cannot open while Do Not Disturb mode is enabled on this device."
  }

  else -> "CameraX reported camera error code $code."
}
