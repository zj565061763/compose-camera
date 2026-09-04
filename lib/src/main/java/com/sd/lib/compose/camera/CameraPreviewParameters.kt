@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.hardware.Camera
import android.view.Surface
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

private const val MAX_PREVIEW_PIXELS = 1280 * 960
private const val PREVIEW_QUALITY_AREA_DIVISOR = 4

internal fun choosePreviewSize(
  sizes: List<IntSize>,
  previewViewSize: IntSize,
  rotationDegrees: Int,
): IntSize {
  require(sizes.isNotEmpty()) { "The camera did not report any preview size." }
  val targetAspectRatio = if (previewViewSize.width > 0 && previewViewSize.height > 0) {
    previewViewSize.width.toFloat() / previewViewSize.height
  } else {
    1f
  }
  val normalizedRotation = normalizeRotation(rotationDegrees)
  val isQuarterTurn = normalizedRotation == 90 || normalizedRotation == 270
  val aspectComparator = compareBy<IntSize> { size ->
    val orientedWidth = if (isQuarterTurn) size.height else size.width
    val orientedHeight = if (isQuarterTurn) size.width else size.height
    abs(orientedWidth.toFloat() / orientedHeight - targetAspectRatio)
  }
  val boundedSizes = sizes.filter { size -> previewArea(size) <= MAX_PREVIEW_PIXELS }
  return if (boundedSizes.isNotEmpty()) {
    val largestArea = boundedSizes.maxOf(::previewArea)
    boundedSizes
      .filter { size -> previewArea(size) >= largestArea / PREVIEW_QUALITY_AREA_DIVISOR }
      .minWithOrNull(aspectComparator.thenByDescending(::previewArea))
  } else {
    sizes.minByOrNull(::previewArea)
  } ?: sizes.first()
}

private fun previewArea(size: IntSize): Long = size.width.toLong() * size.height

internal fun calculateCameraDisplayOrientation(cameraInfo: Camera.CameraInfo, displayRotation: Int): Int {
  val displayDegrees = displayRotationDegrees(displayRotation)
  return if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
    val result = (cameraInfo.orientation + displayDegrees) % 360
    (360 - result) % 360
  } else {
    (cameraInfo.orientation - displayDegrees + 360) % 360
  }
}

internal fun calculateCameraFrameRotation(cameraInfo: Camera.CameraInfo, displayRotation: Int): Int {
  val displayDegrees = displayRotationDegrees(displayRotation)
  return if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
    (cameraInfo.orientation + displayDegrees) % 360
  } else {
    (cameraInfo.orientation - displayDegrees + 360) % 360
  }
}

private fun displayRotationDegrees(displayRotation: Int): Int {
  return when (displayRotation) {
    Surface.ROTATION_0 -> 0
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
  }
}

internal fun chooseFocusMode(supportedModes: List<String>?): String? {
  return when {
    supportedModes == null -> null
    Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in supportedModes -> {
      Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
    }
    Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO in supportedModes -> {
      Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
    }
    Camera.Parameters.FOCUS_MODE_AUTO in supportedModes -> Camera.Parameters.FOCUS_MODE_AUTO
    else -> null
  }
}
