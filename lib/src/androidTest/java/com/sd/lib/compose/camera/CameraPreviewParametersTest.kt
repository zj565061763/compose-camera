@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.hardware.Camera
import android.view.Surface
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraPreviewParametersTest {
  @Test
  fun choosePreviewSize_prefersClosestAspectThenLargestBoundedSize() {
    val selected = choosePreviewSize(
      sizes = listOf(IntSize(1920, 1080), IntSize(1280, 720), IntSize(1024, 768), IntSize(640, 480)),
      previewViewSize = IntSize(400, 400),
      rotationDegrees = 0,
    )

    assertThat(selected).isEqualTo(IntSize(1024, 768))
  }

  @Test
  fun choosePreviewSize_doesNotTradeMostResolutionForSmallAspectGain() {
    val selected = choosePreviewSize(
      sizes = listOf(
        IntSize(1920, 1920),
        IntSize(1280, 960),
        IntSize(1280, 720),
        IntSize(640, 480),
        IntSize(352, 288),
        IntSize(320, 240),
      ),
      previewViewSize = IntSize(1080, 1080),
      rotationDegrees = 90,
    )

    assertThat(selected).isEqualTo(IntSize(1280, 960))
  }

  @Test
  fun choosePreviewSize_allSizesAboveLimitSelectsSmallestBuffer() {
    val selected = choosePreviewSize(
      sizes = listOf(IntSize(7680, 7680), IntSize(4096, 3072), IntSize(1920, 1080)),
      previewViewSize = IntSize(1000, 1000),
      rotationDegrees = 0,
    )

    assertThat(selected).isEqualTo(IntSize(1920, 1080))
  }

  @Test
  fun cameraRotations_frontCameraSeparatesDisplayAndFrameRotation() {
    val cameraInfo = cameraInfo(Camera.CameraInfo.CAMERA_FACING_FRONT, orientation = 90)
    val displayRotations = listOf(
      Surface.ROTATION_0,
      Surface.ROTATION_90,
      Surface.ROTATION_180,
      Surface.ROTATION_270,
    )

    assertThat(displayRotations.map { calculateCameraDisplayOrientation(cameraInfo, it) })
      .containsExactly(270, 180, 90, 0).inOrder()
    assertThat(displayRotations.map { calculateCameraFrameRotation(cameraInfo, it) })
      .containsExactly(90, 180, 270, 0).inOrder()
  }

  @Test
  fun cameraRotations_backCameraUsesSameDisplayAndFrameRotation() {
    val cameraInfo = cameraInfo(Camera.CameraInfo.CAMERA_FACING_BACK, orientation = 90)
    val displayRotations = listOf(
      Surface.ROTATION_0,
      Surface.ROTATION_90,
      Surface.ROTATION_180,
      Surface.ROTATION_270,
    )
    val expected = listOf(90, 0, 270, 180)

    assertThat(displayRotations.map { calculateCameraDisplayOrientation(cameraInfo, it) }).isEqualTo(expected)
    assertThat(displayRotations.map { calculateCameraFrameRotation(cameraInfo, it) }).isEqualTo(expected)
  }
}

private fun cameraInfo(facing: Int, orientation: Int): Camera.CameraInfo {
  return Camera.CameraInfo().also {
    it.facing = facing
    it.orientation = orientation
  }
}
