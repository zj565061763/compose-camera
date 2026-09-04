@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class CameraPreviewBitmapTest {
  @Test
  fun takeScreenshot_usesAttachedActionAndResetDetachesIt() {
    val state = CameraPreviewState()
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    var receivedMirrorMode: CameraMirrorMode? = null
    val action: (CameraMirrorMode) -> Bitmap? = { mirrorMode ->
      receivedMirrorMode = mirrorMode
      bitmap
    }
    state.attachTakeScreenshotAction(action)

    val result = state.takeScreenshot(CameraMirrorMode.OFF)

    assertThat(result).isSameInstanceAs(bitmap)
    assertThat(receivedMirrorMode).isEqualTo(CameraMirrorMode.OFF)
    state.reset()
    assertThat(state.takeScreenshot()).isNull()
    bitmap.recycle()
  }

  @Test
  fun copyIntoBitmapOrThrow_returnsCopiedBitmap() {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    val result = copyIntoBitmapOrThrow(bitmap) { destination -> destination.eraseColor(Color.RED) }

    assertThat(result).isSameInstanceAs(bitmap)
    assertThat(result.getPixel(1, 1)).isEqualTo(Color.RED)
    result.recycle()
  }

  @Test
  fun copyIntoBitmapOrThrow_unchangedBitmapThrowsAndRecycles() {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    val error = assertThrows(IllegalStateException::class.java) {
      copyIntoBitmapOrThrow(bitmap) {}
    }

    assertThat(error).hasMessageThat().contains("failed to copy")
    assertThat(bitmap.isRecycled).isTrue()
  }

  @Test
  fun capturePreviewScreenshot_appliesRequestedMirrorModeToPixels() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(4, 2), ContentScale.FillBounds, isMirrored = true)
    state.startSession(
      sessionIdentity = identity,
      bufferSize = IntSize(4, 2),
      rotationDegrees = 0,
      isPreviewMirrored = true,
      isMirrored = true,
    )
    state.markPreviewFrameAvailable(identity)

    fun capture(mirrorMode: CameraMirrorMode): Bitmap {
      return checkNotNull(
        capturePreviewScreenshot(
          state = state,
          mirrorMode = mirrorMode,
          captureBitmap = { width, height ->
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
              repeat(height) { y ->
                setPixel(0, y, Color.YELLOW)
                setPixel(1, y, Color.BLUE)
                setPixel(2, y, Color.GREEN)
                setPixel(3, y, Color.RED)
              }
            }
          },
        ),
      )
    }

    val auto = capture(CameraMirrorMode.AUTO)
    val on = capture(CameraMirrorMode.ON)
    val off = capture(CameraMirrorMode.OFF)

    assertThat((0 until 4).map { x -> auto.getPixel(x, 0) })
      .containsExactly(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED).inOrder()
    assertThat((0 until 4).map { x -> on.getPixel(x, 0) })
      .containsExactly(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED).inOrder()
    assertThat((0 until 4).map { x -> off.getPixel(x, 0) })
      .containsExactly(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).inOrder()
    auto.recycle()
    on.recycle()
    off.recycle()
  }

  @Test
  fun capturePreviewScreenshot_rearCameraOnMirrorsPixels() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(2, 2), ContentScale.FillBounds, isMirrored = false)
    state.startSession(
      sessionIdentity = identity,
      bufferSize = IntSize(2, 2),
      rotationDegrees = 0,
      isPreviewMirrored = false,
      isMirrored = false,
    )
    state.markPreviewFrameAvailable(identity)

    val bitmap = checkNotNull(
      capturePreviewScreenshot(
        state = state,
        mirrorMode = CameraMirrorMode.ON,
        captureBitmap = { width, height ->
          Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.YELLOW)
            repeat(height) { y -> setPixel(1, y, Color.RED) }
          }
        },
      ),
    )

    assertThat(bitmap.getPixel(0, 0)).isEqualTo(Color.RED)
    assertThat(bitmap.getPixel(1, 0)).isEqualTo(Color.YELLOW)
    bitmap.recycle()
  }

  @Test
  fun capturePreviewScreenshot_layoutChangeDuringCaptureDropsAndRecyclesSource() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(4, 4), ContentScale.Crop, isMirrored = false)
    state.startSession(identity, IntSize(4, 4), rotationDegrees = 0, isMirrored = false)
    state.markPreviewFrameAvailable(identity)
    lateinit var source: Bitmap

    val result = capturePreviewScreenshot(
      state = state,
      mirrorMode = CameraMirrorMode.OFF,
      captureBitmap = { width, height ->
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { source = it }
        state.updatePreviewLayout(IntSize(3, 3), ContentScale.Crop, isMirrored = false)
        source
      },
    )

    assertThat(result).isNull()
    assertThat(source.isRecycled).isTrue()
  }

  @Test
  fun capturePreviewSampledFrame_usesCurrentContentSizeAndCropsExplicitly() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(4, 4), ContentScale.Crop, isMirrored = false)
    state.startSession(identity, IntSize(8, 4), rotationDegrees = 0, isMirrored = false)
    state.markPreviewFrameAvailable(identity)
    state.updatePreviewLayout(IntSize(3, 3), ContentScale.Crop, isMirrored = false)
    lateinit var source: Bitmap
    var capturedSize = IntSize.Zero

    val frame = checkNotNull(
      capturePreviewSampledFrame(
        state = state,
        sessionIdentity = identity,
        isPreviewMirrored = false,
        captureBitmap = { width, height ->
          capturedSize = IntSize(width, height)
          Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { source = it }
        },
      ),
    )

    assertThat(capturedSize).isEqualTo(IntSize(6, 3))
    assertThat(frame.data.width).isEqualTo(3)
    assertThat(frame.data.height).isEqualTo(3)
    assertThat(source.isRecycled).isTrue()
    frame.data.recycle()
  }

  @Test
  fun previewSampleRequest_capsSourceAtOrientedBufferSize() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(3_840, 3_840), ContentScale.Crop, isMirrored = false)
    state.startSession(identity, IntSize(640, 480), rotationDegrees = 0, isMirrored = false)
    state.markPreviewFrameAvailable(identity)

    val request = checkNotNull(state.createPreviewSampleRequest(identity, isPreviewMirrored = false))

    assertThat(request.captureSize).isEqualTo(IntSize(640, 480))
    assertThat(request.contentBounds.width).isWithin(0.01f).of(5_120f)
    assertThat(request.contentBounds.height).isWithin(0.01f).of(3_840f)
  }

  @Test
  fun capturePreviewSampledFrame_identityGeometryTransfersSource() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(4, 4), ContentScale.Crop, isMirrored = false)
    state.startSession(identity, IntSize(4, 4), rotationDegrees = 0, isMirrored = false)
    state.markPreviewFrameAvailable(identity)
    val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    val frame = checkNotNull(
      capturePreviewSampledFrame(
        state = state,
        sessionIdentity = identity,
        isPreviewMirrored = false,
        captureBitmap = { width, height ->
          assertThat(IntSize(width, height)).isEqualTo(IntSize(4, 4))
          source
        },
      ),
    )

    assertThat(frame.data).isSameInstanceAs(source)
    assertThat(source.isRecycled).isFalse()
    frame.data.recycle()
  }

  @Test
  fun capturePreviewSampledFrame_layoutChangeDuringCaptureDropsAndRecyclesSource() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(4, 4), ContentScale.Crop, isMirrored = false)
    state.startSession(identity, IntSize(4, 4), rotationDegrees = 0, isMirrored = false)
    state.markPreviewFrameAvailable(identity)
    lateinit var source: Bitmap

    val frame = capturePreviewSampledFrame(
      state = state,
      sessionIdentity = identity,
      isPreviewMirrored = false,
      captureBitmap = { width, height ->
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { source = it }
        state.updatePreviewLayout(IntSize(3, 3), ContentScale.Crop, isMirrored = false)
        source
      },
    )

    assertThat(frame).isNull()
    assertThat(source.isRecycled).isTrue()
  }

  @Test
  fun createSampledFrame_appliesCropAndRemovesPlatformMirror() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(2, 2), ContentScale.Crop, isMirrored = false)
    state.startSession(
      identity,
      IntSize(4, 2),
      rotationDegrees = 0,
      isPreviewMirrored = true,
      isMirrored = false,
    )
    state.markPreviewFrameAvailable(identity)
    val request = checkNotNull(state.createPreviewSampleRequest(identity, isPreviewMirrored = true))
    val source = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply {
      repeat(height) { y ->
        setPixel(0, y, Color.YELLOW)
        setPixel(1, y, Color.BLUE)
        setPixel(2, y, Color.GREEN)
        setPixel(3, y, Color.RED)
      }
    }

    val frame = checkNotNull(state.createSampledFrame(source, request))

    assertThat(frame.rotationDegrees).isEqualTo(0)
    assertThat(frame.data.width).isEqualTo(2)
    assertThat(frame.data.height).isEqualTo(2)
    assertThat(frame.data.getPixel(0, 0)).isEqualTo(Color.GREEN)
    assertThat(frame.data.getPixel(1, 0)).isEqualTo(Color.BLUE)
    assertThat(state.createTransformToPreview(frame)).isNotNull()
    frame.data.recycle()
    source.recycle()
  }

  @Test
  fun createSampledFrame_platformMirrorCentersOddWidthContent() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(4, 3), ContentScale.Fit, isMirrored = false)
    state.startSession(
      identity,
      IntSize(2, 2),
      rotationDegrees = 0,
      isPreviewMirrored = true,
      isMirrored = false,
    )
    state.markPreviewFrameAvailable(identity)
    val request = checkNotNull(state.createPreviewSampleRequest(identity, isPreviewMirrored = true))
    val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
      repeat(height) { y ->
        setPixel(0, y, Color.YELLOW)
        setPixel(1, y, Color.RED)
      }
    }

    val frame = checkNotNull(state.createSampledFrame(source, request))

    assertThat(frame.data.getPixel(0, 0)).isEqualTo(Color.TRANSPARENT)
    assertThat(Color.alpha(frame.data.getPixel(1, 0))).isGreaterThan(0)
    assertThat(Color.alpha(frame.data.getPixel(2, 0))).isGreaterThan(0)
    assertThat(Color.alpha(frame.data.getPixel(3, 0))).isGreaterThan(0)
    frame.data.recycle()
    source.recycle()
  }

  @Test
  fun createSampledFrame_fitPlacesContentInsidePreview() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(4, 4), ContentScale.Fit, isMirrored = false)
    state.startSession(identity, IntSize(4, 2), rotationDegrees = 0, isMirrored = false)
    state.markPreviewFrameAvailable(identity)
    val request = checkNotNull(state.createPreviewSampleRequest(identity, isPreviewMirrored = false))
    val source = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    val frame = checkNotNull(state.createSampledFrame(source, request))

    assertThat(frame.data.width).isEqualTo(4)
    assertThat(frame.data.height).isEqualTo(4)
    assertThat(frame.data.getPixel(0, 0)).isEqualTo(Color.TRANSPARENT)
    assertThat(frame.data.getPixel(0, 1)).isEqualTo(Color.RED)
    assertThat(frame.data.getPixel(3, 2)).isEqualTo(Color.RED)
    assertThat(frame.data.getPixel(3, 3)).isEqualTo(Color.TRANSPARENT)
    frame.data.recycle()
    source.recycle()
  }

  @Test
  fun createSampledFrame_identityGeometryTransfersSourceOwnership() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(4, 4), ContentScale.Crop, isMirrored = false)
    state.startSession(identity, IntSize(4, 4), rotationDegrees = 0, isMirrored = false)
    state.markPreviewFrameAvailable(identity)
    val request = checkNotNull(state.createPreviewSampleRequest(identity, isPreviewMirrored = false))
    val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
      repeat(height) { y ->
        setPixel(0, y, Color.YELLOW)
        setPixel(1, y, Color.BLUE)
        setPixel(2, y, Color.GREEN)
        setPixel(3, y, Color.RED)
      }
    }

    val frame = checkNotNull(state.createSampledFrame(source, request))

    assertThat(frame.data).isSameInstanceAs(source)
    assertThat(frame.data.width).isEqualTo(4)
    assertThat(frame.data.height).isEqualTo(4)
    assertThat(frame.data.getPixel(0, 0)).isEqualTo(Color.YELLOW)
    assertThat(frame.data.getPixel(1, 0)).isEqualTo(Color.BLUE)
    assertThat(frame.data.getPixel(2, 0)).isEqualTo(Color.GREEN)
    assertThat(frame.data.getPixel(3, 0)).isEqualTo(Color.RED)
    frame.data.recycle()
  }

  @Test
  fun createSampledFrame_previewSizedSourceRemovesPlatformMirror() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(4, 4), ContentScale.Crop, isMirrored = false)
    state.startSession(
      identity,
      IntSize(4, 4),
      rotationDegrees = 0,
      isPreviewMirrored = true,
      isMirrored = false,
    )
    state.markPreviewFrameAvailable(identity)
    val request = checkNotNull(state.createPreviewSampleRequest(identity, isPreviewMirrored = true))
    val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
      repeat(height) { y ->
        setPixel(0, y, Color.YELLOW)
        setPixel(1, y, Color.BLUE)
        setPixel(2, y, Color.GREEN)
        setPixel(3, y, Color.RED)
      }
    }

    val frame = checkNotNull(state.createSampledFrame(source, request))

    assertThat(frame.data.getPixel(0, 0)).isEqualTo(Color.RED)
    assertThat(frame.data.getPixel(1, 0)).isEqualTo(Color.GREEN)
    assertThat(frame.data.getPixel(2, 0)).isEqualTo(Color.BLUE)
    assertThat(frame.data.getPixel(3, 0)).isEqualTo(Color.YELLOW)
    frame.data.recycle()
    source.recycle()
  }

  @Test
  fun cameraFrame_toBitmapCopiesNv21Pixels() {
    val width = 4
    val height = 4
    val data = ByteArray(width * height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8) { 128.toByte() }
    val bitmap = checkNotNull(frame(CameraFrameTransformIdentity(), width, height, 0, data).toBitmap())

    assertThat(bitmap.width).isEqualTo(width)
    assertThat(bitmap.height).isEqualTo(height)
    bitmap.recycle()
  }

  @Test
  fun cameraFrame_toBitmapReturnsNullForInvalidFrame() {
    val invalidFrame = frame(
      identity = CameraFrameTransformIdentity(),
      width = 0,
      height = 0,
      rotationDegrees = 0,
      data = ByteArray(0),
    )

    assertThat(invalidFrame.toBitmap()).isNull()
  }
}
