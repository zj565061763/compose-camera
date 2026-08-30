@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.hardware.Camera
import androidx.compose.runtime.staticCompositionLocalOf

/** 单次自动对焦使用的相机硬件操作边界 */
internal interface CameraAutoFocusOperations {
  fun cancelAutoFocus()

  fun autoFocus(onComplete: () -> Unit)
}

internal fun interface CameraAutoFocusOperationsFactory {
  fun create(camera: Camera): CameraAutoFocusOperations
}

private class PlatformCameraAutoFocusOperations(
  private val camera: Camera,
) : CameraAutoFocusOperations {
  override fun cancelAutoFocus() {
    camera.cancelAutoFocus()
  }

  override fun autoFocus(onComplete: () -> Unit) {
    camera.autoFocus { _, _ -> onComplete() }
  }
}

internal val PlatformCameraAutoFocusOperationsFactory = CameraAutoFocusOperationsFactory { camera ->
  PlatformCameraAutoFocusOperations(camera)
}

internal val LocalCameraAutoFocusOperationsFactory = staticCompositionLocalOf {
  PlatformCameraAutoFocusOperationsFactory
}
