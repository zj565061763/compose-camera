@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraPreviewStateTest {
  @Test
  fun createTransformToPreview_cropCentersBuffer() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(200, 200), ContentScale.Crop, isMirrored = false)
    state.startSession(identity, IntSize(640, 480), rotationDegrees = 0, isMirrored = false)

    val matrix = checkNotNull(state.createTransformToPreview(frame(identity, 640, 480, 0)))
    val center = floatArrayOf(320f, 240f).also(matrix::mapPoints)
    val bounds = RectF(0f, 0f, 640f, 480f).also(matrix::mapRect)

    assertThat(center[0]).isWithin(0.01f).of(100f)
    assertThat(center[1]).isWithin(0.01f).of(100f)
    assertThat(bounds.left).isLessThan(0f)
    assertThat(bounds.top).isWithin(0.01f).of(0f)
    assertThat(bounds.bottom).isWithin(0.01f).of(200f)
  }

  @Test
  fun createTransformToPreview_quarterTurnMapsRawCoordinates() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(225, 300), ContentScale.FillBounds, isMirrored = false)
    state.startSession(identity, IntSize(640, 480), rotationDegrees = 90, isMirrored = false)

    val matrix = checkNotNull(state.createTransformToPreview(frame(identity, 640, 480, 90)))
    val points = floatArrayOf(
      0f, 0f,
      640f, 0f,
      0f, 480f,
    ).also(matrix::mapPoints)

    assertThat(points[0]).isWithin(0.01f).of(225f)
    assertThat(points[1]).isWithin(0.01f).of(0f)
    assertThat(points[2]).isWithin(0.01f).of(225f)
    assertThat(points[3]).isWithin(0.01f).of(300f)
    assertThat(points[4]).isWithin(0.01f).of(0f)
    assertThat(points[5]).isWithin(0.01f).of(0f)
  }

  @Test
  fun createTextureViewTransform_cropPreservesRotatedBufferAspect() {
    val geometry = checkNotNull(
      calculatePreviewGeometry(
        bufferSize = IntSize(352, 288),
        rotationDegrees = 90,
        previewSize = IntSize(1080, 1080),
        contentScale = ContentScale.Crop,
      ),
    )

    val matrix = createTextureViewTransform(geometry)
    val bounds = RectF(0f, 0f, 1080f, 1080f).also(matrix::mapRect)

    assertThat(bounds.left).isWithin(0.01f).of(0f)
    assertThat(bounds.top).isWithin(0.01f).of(-120f)
    assertThat(bounds.right).isWithin(0.01f).of(1080f)
    assertThat(bounds.bottom).isWithin(0.01f).of(1200f)
  }

  @Test
  fun createTextureViewTransform_fitPreservesRotatedBufferAspect() {
    val geometry = checkNotNull(
      calculatePreviewGeometry(
        bufferSize = IntSize(352, 288),
        rotationDegrees = 90,
        previewSize = IntSize(1080, 1080),
        contentScale = ContentScale.Fit,
      ),
    )

    val matrix = createTextureViewTransform(geometry)
    val bounds = RectF(0f, 0f, 1080f, 1080f).also(matrix::mapRect)

    assertThat(bounds.left).isWithin(0.01f).of(98.18f)
    assertThat(bounds.top).isWithin(0.01f).of(0f)
    assertThat(bounds.right).isWithin(0.01f).of(981.82f)
    assertThat(bounds.bottom).isWithin(0.01f).of(1080f)
  }

  @Test
  fun calculatePreviewGeometry_cropKeepsUniformFloatingPointScale() {
    val geometry = checkNotNull(
      calculatePreviewGeometry(
        bufferSize = IntSize(640, 480),
        rotationDegrees = 0,
        previewSize = IntSize(200, 200),
        contentScale = ContentScale.Crop,
      ),
    )

    assertThat(geometry.scaleX).isWithin(0.000001f).of(geometry.scaleY)
    assertThat(geometry.contentSize.width).isWithin(0.001f).of(266.66666f)
    assertThat(geometry.contentSize.height).isWithin(0.001f).of(200f)
  }

  @Test
  fun calculatePreviewGeometry_subpixelFitKeepsUniformScale() {
    val geometry = checkNotNull(
      calculatePreviewGeometry(
        bufferSize = IntSize(1_000, 1),
        rotationDegrees = 0,
        previewSize = IntSize(1, 1),
        contentScale = ContentScale.Fit,
      ),
    )

    assertThat(geometry.scaleX).isWithin(0.000001f).of(geometry.scaleY)
    assertThat(geometry.contentSize.width).isWithin(0.000001f).of(1f)
    assertThat(geometry.contentSize.height).isWithin(0.000001f).of(0.001f)
  }

  @Test
  fun createTransformToPreview_mirrorsAroundPreviewWidth() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(200, 100), ContentScale.FillBounds, isMirrored = true)
    state.startSession(identity, IntSize(200, 100), rotationDegrees = 0, isMirrored = true)

    val matrix = checkNotNull(state.createTransformToPreview(frame(identity, 200, 100, 0)))
    val points = floatArrayOf(0f, 0f, 200f, 100f).also(matrix::mapPoints)

    assertThat(points.asList()).containsExactly(200f, 0f, 0f, 100f).inOrder()
  }

  @Test
  fun createTransformToPreview_mirrorMatchesPreviewCenterForOddCrop() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(3, 3), ContentScale.Crop, isMirrored = true)
    state.startSession(identity, IntSize(4, 2), rotationDegrees = 0, isMirrored = true)

    val matrix = checkNotNull(state.createTransformToPreview(frame(identity, 4, 2, 0)))
    val points = floatArrayOf(0f, 0f, 4f, 2f).also(matrix::mapPoints)

    assertThat(points.asList()).containsExactly(4.5f, 0f, -1.5f, 3f).inOrder()
  }

  @Test
  fun createTransformToPreview_sampledFrameMirrorsAroundPreviewCenter() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(4, 3), ContentScale.Fit, isMirrored = true)
    state.startSession(identity, IntSize(2, 2), rotationDegrees = 0, isMirrored = true)
    val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
    val frame = CameraFrame.PreviewSampled(bitmap, rotationDegrees = 0, transformIdentity = identity)

    val matrix = checkNotNull(state.createTransformToPreview(frame))
    val points = floatArrayOf(0f, 0f, 3f, 3f).also(matrix::mapPoints)

    assertThat(points.asList()).containsExactly(4f, 0f, 1f, 3f).inOrder()
    bitmap.recycle()
  }

  @Test
  fun currentTextureViewTransform_sameResolutionUsesLatestSessionRotation() {
    val state = CameraPreviewState()
    val firstIdentity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(300, 300), ContentScale.Crop, isMirrored = false)
    state.startSession(firstIdentity, IntSize(400, 200), rotationDegrees = 0, isMirrored = false)
    state.markPreviewFrameAvailable(firstIdentity)
    val firstRevision = state.previewTransformRevision
    val firstTransform = checkNotNull(state.createCurrentTextureViewTransform(firstIdentity))

    val secondIdentity = CameraFrameTransformIdentity()
    state.startSession(secondIdentity, IntSize(400, 200), rotationDegrees = 90, isMirrored = false)
    assertThat(state.createCurrentTextureViewTransform(secondIdentity)).isNull()
    state.markPreviewFrameAvailable(secondIdentity)

    val secondTransform = checkNotNull(state.createCurrentTextureViewTransform(secondIdentity))
    val firstBounds = RectF(0f, 0f, 300f, 300f).also(firstTransform::mapRect)
    val secondBounds = RectF(0f, 0f, 300f, 300f).also(secondTransform::mapRect)
    assertThat(state.previewTransformRevision).isNotEqualTo(firstRevision)
    assertThat(state.createCurrentTextureViewTransform(firstIdentity)).isNull()
    assertThat(firstBounds).isEqualTo(RectF(-150f, 0f, 450f, 300f))
    assertThat(secondBounds).isEqualTo(RectF(0f, -150f, 300f, 450f))
  }

  @Test
  fun firstPreviewFrameAppliesAdditionalMirrorWithContentTransform() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(200, 100), ContentScale.FillBounds, isMirrored = false)
    state.startSession(
      sessionIdentity = identity,
      bufferSize = IntSize(200, 100),
      rotationDegrees = 0,
      isPreviewMirrored = true,
      isMirrored = false,
    )

    assertThat(state.createCurrentTextureViewTransform(identity)).isNull()
    val transform = checkNotNull(state.markPreviewFrameAvailable(identity))
    val points = floatArrayOf(0f, 0f, 200f, 100f).also(transform::mapPoints)

    assertThat(points.asList()).containsExactly(200f, 0f, 0f, 100f).inOrder()
  }

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
  fun layoutChange_invalidatesOldFrameToken() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(200, 100), ContentScale.Fit, isMirrored = false)
    state.startSession(identity, IntSize(200, 100), rotationDegrees = 0, isMirrored = false)
    val oldFrame = frame(identity, 200, 100, 0)
    assertThat(state.isFrameTransformCurrent(oldFrame.transformToken)).isTrue()

    state.updatePreviewLayout(IntSize(100, 200), ContentScale.Fit, isMirrored = false)

    assertThat(state.isFrameTransformCurrent(oldFrame.transformToken)).isFalse()
  }

  @Test
  fun contentScaleChange_invalidatesOldFrameTokenWhenGeometryIsUnchanged() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(200, 200), ContentScale.Crop, isMirrored = false)
    state.startSession(identity, IntSize(200, 200), rotationDegrees = 0, isMirrored = false)
    val oldFrame = frame(identity, 200, 200, 0)
    assertThat(state.isFrameTransformCurrent(oldFrame.transformToken)).isTrue()

    state.updatePreviewLayout(IntSize(200, 200), ContentScale.Fit, isMirrored = false)

    assertThat(state.isFrameTransformCurrent(oldFrame.transformToken)).isFalse()
  }

  @Test
  fun startSession_overridesStaleMirrorState() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(200, 100), ContentScale.FillBounds, isMirrored = false)

    state.startSession(identity, IntSize(200, 100), rotationDegrees = 0, isMirrored = true)

    val matrix = checkNotNull(state.createTransformToPreview(frame(identity, 200, 100, 0)))
    val points = floatArrayOf(0f, 0f, 200f, 100f).also(matrix::mapPoints)
    assertThat(points.asList()).containsExactly(200f, 0f, 0f, 100f).inOrder()
  }

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

  @Test
  fun periodicAutoFocus_repeatsAfterOrdinaryFailure() {
    val attempts = AtomicInteger()
    val errorReceived = CountDownLatch(1)
    val focusSucceeded = CountDownLatch(1)
    val error = AtomicReference<Throwable?>()
    val firstFailure = IllegalStateException("focus")
    val periodicAutoFocus = PeriodicAutoFocus(
      handler = Handler(Looper.getMainLooper()),
      intervalMillis = 10,
      focus = {
        if (attempts.incrementAndGet() == 1) throw firstFailure
        focusSucceeded.countDown()
      },
      onError = { failure ->
        error.set(failure)
        errorReceived.countDown()
      },
    )

    periodicAutoFocus.start()

    assertThat(errorReceived.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(focusSucceeded.await(5, TimeUnit.SECONDS)).isTrue()
    periodicAutoFocus.close()
    assertThat(error.get()).isSameInstanceAs(firstFailure)
    assertThat(attempts.get()).isAtLeast(2)
  }

  @Test
  fun periodicAutoFocus_closeDuringFocusDoesNotScheduleAgain() {
    val attempts = AtomicInteger()
    val focused = CountDownLatch(1)
    lateinit var periodicAutoFocus: PeriodicAutoFocus
    periodicAutoFocus = PeriodicAutoFocus(
      handler = Handler(Looper.getMainLooper()),
      intervalMillis = 10,
      focus = {
        attempts.incrementAndGet()
        periodicAutoFocus.close()
        focused.countDown()
      },
      onError = {},
    )

    periodicAutoFocus.start()

    assertThat(focused.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(attempts.get()).isEqualTo(1)
  }

  @Test
  fun chooseFocusMode_prefersContinuousModeAndFallsBackToAuto() {
    assertThat(
      chooseFocusMode(
        listOf(
          Camera.Parameters.FOCUS_MODE_AUTO,
          Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO,
          Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE,
        ),
      ),
    ).isEqualTo(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)
    assertThat(chooseFocusMode(listOf(Camera.Parameters.FOCUS_MODE_AUTO)))
      .isEqualTo(Camera.Parameters.FOCUS_MODE_AUTO)
    assertThat(chooseFocusMode(listOf(Camera.Parameters.FOCUS_MODE_FIXED))).isNull()
  }

  @Test
  fun cleanupActions_runAllActionsAndAggregateFailures() {
    val calls = mutableListOf<Int>()
    val firstFailure = IllegalStateException("first")
    val secondFailure = IllegalArgumentException("second")

    val failure = runCameraCleanupActions(
      actions = listOf(
        {
          calls += 1
          throw firstFailure
        },
        { calls += 2 },
        {
          calls += 3
          throw secondFailure
        },
      ),
      finalAction = { calls += 4 },
    )

    assertThat(calls).containsExactly(1, 2, 3, 4).inOrder()
    assertThat(failure).isSameInstanceAs(firstFailure)
    assertThat(firstFailure.suppressed.asList()).containsExactly(secondFailure)
  }

  @Test
  fun postStopThenRelease_stopsBeforeReleasingSurface() {
    val calls = mutableListOf<String>()

    postStopThenRelease(
      post = { task ->
        task.run()
        true
      },
      stop = { calls += "stop" },
      release = { calls += "release" },
    )

    assertThat(calls).containsExactly("stop", "release").inOrder()
  }

  @Test
  fun postStopThenRelease_stopFailureStillReleasesSurface() {
    val failure = IllegalStateException("stop")
    var released = false

    val thrown = assertThrows(IllegalStateException::class.java) {
      postStopThenRelease(
        post = { task ->
          task.run()
          true
        },
        stop = { throw failure },
        release = { released = true },
      )
    }

    assertThat(thrown).isSameInstanceAs(failure)
    assertThat(released).isTrue()
  }

  @Test
  fun surfaceTextureReleaseCoordinator_waitsForExistingCameraUser() {
    val surfaceTexture = SurfaceTexture(0)
    val released = AtomicInteger()
    val coordinator = SurfaceTextureReleaseCoordinator { released.incrementAndGet() }
    coordinator.retain(surfaceTexture)

    coordinator.requestRelease(surfaceTexture)
    coordinator.requestRelease(surfaceTexture)

    assertThat(released.get()).isEqualTo(0)

    coordinator.releaseAfterUse(surfaceTexture)

    assertThat(released.get()).isEqualTo(1)
    coordinator.requestRelease(surfaceTexture)
    assertThat(released.get()).isEqualTo(1)
    assertThrows(IllegalStateException::class.java) { coordinator.retain(surfaceTexture) }
    surfaceTexture.release()
  }

  @Test
  fun reset_clearsRetryGeneration() {
    val state = CameraPreviewState()
    state.retry()

    state.reset()

    assertThat(state.retryGeneration).isEqualTo(0)
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
  fun frameDispatcher_keepsOnlyLatestPendingFrame() {
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val callbacks = CountDownLatch(2)
    val buffersReturned = CountDownLatch(3)
    val seen = mutableListOf<Int>()
    val returned = mutableListOf<Int>()
    val error = AtomicReference<Throwable?>()
    val dispatcher = CameraFrameDispatcher(
      onFrame = { frame ->
        synchronized(seen) { seen += frame.data[0].toInt() }
        if (frame.data[0].toInt() == 1) {
          firstStarted.countDown()
          check(releaseFirst.await(5, TimeUnit.SECONDS))
        }
        callbacks.countDown()
      },
      onError = error::set,
    )

    dispatcher.offerFrame(1, returned, buffersReturned)
    assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue()
    dispatcher.offerFrame(2, returned, buffersReturned)
    dispatcher.offerFrame(3, returned, buffersReturned)
    releaseFirst.countDown()

    assertThat(callbacks.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(buffersReturned.await(5, TimeUnit.SECONDS)).isTrue()
    dispatcher.close()
    assertThat(error.get()).isNull()
    assertThat(seen).containsExactly(1, 3).inOrder()
    assertThat(returned).containsExactly(1, 2, 3)
  }

  @Test
  fun frameDispatcher_closeDiscardsPendingFrameAndFinishesStartedCallback() {
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val firstFinished = CountDownLatch(1)
    val buffersReturned = CountDownLatch(2)
    val callbackCount = AtomicInteger()
    val returned = mutableListOf<Int>()
    val error = AtomicReference<Throwable?>()
    val dispatcher = CameraFrameDispatcher(
      onFrame = {
        callbackCount.incrementAndGet()
        firstStarted.countDown()
        check(releaseFirst.await(5, TimeUnit.SECONDS))
        firstFinished.countDown()
      },
      onError = error::set,
    )

    dispatcher.offerFrame(1, returned, buffersReturned)
    assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue()
    dispatcher.offerFrame(2, returned, buffersReturned)

    dispatcher.close()
    releaseFirst.countDown()

    assertThat(firstFinished.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(buffersReturned.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(callbackCount.get()).isEqualTo(1)
    assertThat(returned).containsExactly(1, 2)
    assertThat(error.get()).isNull()
  }

  @Test
  fun frameDispatcher_executorRejectionReturnsFrameAndReportsError() {
    val executor = Executors.newSingleThreadExecutor().also { it.shutdown() }
    val callback = CountDownLatch(1)
    val returned = CountDownLatch(1)
    val receivedError = AtomicReference<Throwable?>()
    val dispatcher = CameraFrameDispatcher(
      onFrame = { callback.countDown() },
      onError = receivedError::set,
      executor = executor,
    )

    dispatcher.offer(
      data = ByteArray(6),
      width = 2,
      height = 2,
      rotationDegrees = 0,
      transformIdentity = CameraFrameTransformIdentity(),
      returnBuffer = { returned.countDown() },
    )

    assertThat(returned.await(1, TimeUnit.SECONDS)).isTrue()
    assertThat(callback.await(100, TimeUnit.MILLISECONDS)).isFalse()
    assertThat(receivedError.get()).isInstanceOf(RejectedExecutionException::class.java)
    dispatcher.close()
  }

  @Test
  fun frameDispatcher_invalidFrameIsReturnedWithoutCallback() {
    val callback = CountDownLatch(1)
    val returned = CountDownLatch(1)
    val dispatcher = CameraFrameDispatcher(
      onFrame = { callback.countDown() },
      onError = {},
    )

    dispatcher.offer(
      data = ByteArray(1),
      width = 640,
      height = 480,
      rotationDegrees = 0,
      transformIdentity = CameraFrameTransformIdentity(),
      returnBuffer = { returned.countDown() },
    )

    assertThat(returned.await(1, TimeUnit.SECONDS)).isTrue()
    assertThat(callback.await(100, TimeUnit.MILLISECONDS)).isFalse()
    dispatcher.close()
  }

  @Test
  fun checkNv21PreviewFormat_rejectsDifferentConfiguredFormat() {
    checkNv21PreviewFormat(ImageFormat.NV21)

    val error = assertThrows(IllegalStateException::class.java) {
      checkNv21PreviewFormat(ImageFormat.YV12)
    }

    assertThat(error).hasMessageThat().contains(ImageFormat.YV12.toString())
    assertThat(error).hasMessageThat().contains("NV21")
  }

  @Test
  fun nv21BufferSize_rejectsOddOrOverflowingDimensions() {
    assertThat(nv21BufferSize(640, 480)).isEqualTo(460_800)
    assertThat(nv21BufferSize(1, 2)).isNull()
    assertThat(nv21BufferSize(2, 1)).isNull()
    assertThat(nv21BufferSize(Int.MAX_VALUE - 1, Int.MAX_VALUE - 1)).isNull()
  }

  @Test
  fun frameDispatcher_callbackAndBufferFailuresAreAggregated() {
    val callbackFailure = IllegalStateException("callback")
    val bufferFailure = IllegalArgumentException("buffer")
    val receivedFailure = AtomicReference<Throwable?>()
    val failureReceived = CountDownLatch(1)
    val dispatcher = CameraFrameDispatcher(
      onFrame = { throw callbackFailure },
      onError = { error ->
        receivedFailure.set(error)
        failureReceived.countDown()
      },
    )

    dispatcher.offer(
      data = ByteArray(6),
      width = 2,
      height = 2,
      rotationDegrees = 0,
      transformIdentity = CameraFrameTransformIdentity(),
      returnBuffer = { throw bufferFailure },
    )

    assertThat(failureReceived.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(receivedFailure.get()).isSameInstanceAs(callbackFailure)
    assertThat(callbackFailure.suppressed.asList()).containsExactly(bufferFailure)
    dispatcher.close()
  }

  @Test
  fun sampledFrameDispatcher_waitsForIntervalAndRunsOnAnalysisThread() {
    val now = AtomicLong(1_000)
    val callback = CountDownLatch(1)
    val result = AtomicReference<SampledFrameResult?>()
    val error = AtomicReference<Throwable?>()
    val identity = CameraFrameTransformIdentity()
    val dispatcher = PreviewSampledFrameDispatcher(
      mainHandler = Handler(Looper.getMainLooper()),
      intervalMillis = { 100 },
      captureFrame = { currentIdentity, _ ->
        CameraFrame.PreviewSampled(
          data = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
          rotationDegrees = 0,
          transformIdentity = currentIdentity,
        )
      },
      onFrame = { frame ->
        result.set(
          SampledFrameResult(
            token = frame.transformToken,
            threadName = Thread.currentThread().name,
            recycledDuringCallback = frame.data.isRecycled,
          ),
        )
        callback.countDown()
      },
      onError = error::set,
      elapsedRealtimeMillis = now::get,
    )
    dispatcher.start()

    now.set(1_099)
    dispatcher.offer(identity, isPreviewMirrored = false)
    assertThat(callback.await(100, TimeUnit.MILLISECONDS)).isFalse()
    now.set(1_100)
    dispatcher.offer(identity, isPreviewMirrored = false)

    assertThat(callback.await(5, TimeUnit.SECONDS)).isTrue()
    dispatcher.close()
    assertThat(error.get()).isNull()
    assertThat(checkNotNull(result.get()).threadName).isEqualTo(CAMERA_ANALYSIS_THREAD_NAME)
    assertThat(checkNotNull(result.get()).recycledDuringCallback).isFalse()
    assertThat(checkNotNull(result.get()).token.matches(identity)).isTrue()
  }

  @Test
  fun sampledFrameDispatcher_keepsOnlyLatestPendingCapture() {
    val now = AtomicLong(1_000)
    val firstCallbackStarted = CountDownLatch(1)
    val releaseFirstCallback = CountDownLatch(1)
    val callbacks = CountDownLatch(2)
    val capturedIdentities = mutableListOf<CameraFrameTransformIdentity>()
    val error = AtomicReference<Throwable?>()
    val firstIdentity = CameraFrameTransformIdentity()
    val replacedIdentity = CameraFrameTransformIdentity()
    val latestIdentity = CameraFrameTransformIdentity()
    val dispatcher = PreviewSampledFrameDispatcher(
      mainHandler = Handler(Looper.getMainLooper()),
      intervalMillis = { 100 },
      captureFrame = { identity, _ ->
        synchronized(capturedIdentities) { capturedIdentities += identity }
        CameraFrame.PreviewSampled(
          data = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
          rotationDegrees = 0,
          transformIdentity = identity,
        )
      },
      onFrame = { frame ->
        if (frame.transformToken.matches(firstIdentity)) {
          firstCallbackStarted.countDown()
          check(releaseFirstCallback.await(5, TimeUnit.SECONDS))
        }
        callbacks.countDown()
      },
      onError = error::set,
      elapsedRealtimeMillis = now::get,
    )
    dispatcher.start()

    now.set(1_100)
    dispatcher.offer(firstIdentity, isPreviewMirrored = false)
    assertThat(firstCallbackStarted.await(5, TimeUnit.SECONDS)).isTrue()
    now.set(1_200)
    dispatcher.offer(replacedIdentity, isPreviewMirrored = false)
    now.set(1_300)
    dispatcher.offer(latestIdentity, isPreviewMirrored = false)
    releaseFirstCallback.countDown()

    assertThat(callbacks.await(5, TimeUnit.SECONDS)).isTrue()
    dispatcher.close()
    assertThat(error.get()).isNull()
    assertThat(capturedIdentities).containsExactly(firstIdentity, latestIdentity).inOrder()
  }

  @Test
  fun sampledFrameDispatcher_closeDiscardsPendingCaptureAndFinishesStartedCallback() {
    val now = AtomicLong(1_000)
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val firstFinished = CountDownLatch(1)
    val callbackCount = AtomicInteger()
    val capturedIdentities = mutableListOf<CameraFrameTransformIdentity>()
    val error = AtomicReference<Throwable?>()
    val firstIdentity = CameraFrameTransformIdentity()
    val pendingIdentity = CameraFrameTransformIdentity()
    val dispatcher = PreviewSampledFrameDispatcher(
      mainHandler = Handler(Looper.getMainLooper()),
      intervalMillis = { 100 },
      captureFrame = { identity, _ ->
        synchronized(capturedIdentities) { capturedIdentities += identity }
        CameraFrame.PreviewSampled(
          data = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
          rotationDegrees = 0,
          transformIdentity = identity,
        )
      },
      onFrame = {
        callbackCount.incrementAndGet()
        firstStarted.countDown()
        check(releaseFirst.await(5, TimeUnit.SECONDS))
        firstFinished.countDown()
      },
      onError = error::set,
      elapsedRealtimeMillis = now::get,
    )
    dispatcher.start()
    now.set(1_100)
    dispatcher.offer(firstIdentity, isPreviewMirrored = false)
    assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue()
    now.set(1_200)
    dispatcher.offer(pendingIdentity, isPreviewMirrored = false)

    dispatcher.close()
    releaseFirst.countDown()

    assertThat(firstFinished.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(callbackCount.get()).isEqualTo(1)
    assertThat(capturedIdentities).containsExactly(firstIdentity)
    assertThat(error.get()).isNull()
  }

  @Test
  fun sampledFrameDispatcher_restartDiscardsPreviousCaptureBeforeCallback() {
    val now = AtomicLong(1_000)
    val captureStarted = CountDownLatch(1)
    val releaseCapture = CountDownLatch(1)
    val callback = CountDownLatch(1)
    val bitmap = AtomicReference<Bitmap?>()
    val error = AtomicReference<Throwable?>()
    val dispatcher = PreviewSampledFrameDispatcher(
      mainHandler = Handler(Looper.getMainLooper()),
      intervalMillis = { 100 },
      captureFrame = { identity, _ ->
        val data = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(bitmap::set)
        captureStarted.countDown()
        check(releaseCapture.await(5, TimeUnit.SECONDS))
        CameraFrame.PreviewSampled(data, rotationDegrees = 0, transformIdentity = identity)
      },
      onFrame = { callback.countDown() },
      onError = error::set,
      elapsedRealtimeMillis = now::get,
    )
    dispatcher.start()
    now.set(1_100)
    dispatcher.offer(CameraFrameTransformIdentity(), isPreviewMirrored = false)
    assertThat(captureStarted.await(5, TimeUnit.SECONDS)).isTrue()

    dispatcher.stop()
    now.set(2_000)
    dispatcher.start()
    releaseCapture.countDown()

    assertThat(callback.await(500, TimeUnit.MILLISECONDS)).isFalse()
    dispatcher.close()
    assertThat(error.get()).isNull()
    assertThat(checkNotNull(bitmap.get()).isRecycled).isTrue()
  }

  @Test
  fun sampledFrameDispatcher_callbackFailureIsReportedAndBitmapIsRecycled() {
    val now = AtomicLong(1_000)
    val failureReceived = CountDownLatch(1)
    val callbackFailure = IllegalStateException("sample callback")
    val receivedFailure = AtomicReference<Throwable?>()
    val bitmap = AtomicReference<Bitmap?>()
    val dispatcher = PreviewSampledFrameDispatcher(
      mainHandler = Handler(Looper.getMainLooper()),
      intervalMillis = { 100 },
      captureFrame = { identity, _ ->
        val data = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(bitmap::set)
        CameraFrame.PreviewSampled(data, rotationDegrees = 0, transformIdentity = identity)
      },
      onFrame = { throw callbackFailure },
      onError = { error ->
        receivedFailure.set(error)
        failureReceived.countDown()
      },
      elapsedRealtimeMillis = now::get,
    )
    dispatcher.start()

    now.set(1_100)
    dispatcher.offer(CameraFrameTransformIdentity(), isPreviewMirrored = false)

    assertThat(failureReceived.await(5, TimeUnit.SECONDS)).isTrue()
    dispatcher.close()
    assertThat(receivedFailure.get()).isSameInstanceAs(callbackFailure)
    assertThat(checkNotNull(bitmap.get()).isRecycled).isTrue()
  }

  @Test
  fun previewSampledProcessor_rejectsNonPositiveInterval() {
    assertThrows(IllegalArgumentException::class.java) {
      FrameProcessor.PreviewSampled(intervalMillis = 0) {}
    }
  }
}

private data class SampledFrameResult(
  val token: CameraFrameTransformToken,
  val threadName: String,
  val recycledDuringCallback: Boolean,
)

private fun assertMatrixEquals(actual: Matrix?, expected: Matrix) {
  val actualValues = FloatArray(9).also(checkNotNull(actual)::getValues)
  val expectedValues = FloatArray(9).also(expected::getValues)
  assertThat(actualValues.asList()).containsExactlyElementsIn(expectedValues.asList()).inOrder()
}

private fun CameraFrameDispatcher.offerFrame(
  value: Int,
  returned: MutableList<Int>,
  buffersReturned: CountDownLatch,
) {
  offer(
    data = ByteArray(6).also { it[0] = value.toByte() },
    width = 2,
    height = 2,
    rotationDegrees = 0,
    transformIdentity = CameraFrameTransformIdentity(),
    returnBuffer = { buffer ->
      synchronized(returned) { returned += buffer[0].toInt() }
      buffersReturned.countDown()
    },
  )
}

private fun cameraInfo(facing: Int, orientation: Int): Camera.CameraInfo {
  return Camera.CameraInfo().also {
    it.facing = facing
    it.orientation = orientation
  }
}

private fun frame(
  identity: CameraFrameTransformIdentity,
  width: Int,
  height: Int,
  rotationDegrees: Int,
  data: ByteArray = ByteArray(width * height * 3 / 2),
): CameraFrame.Preview {
  return CameraFrame.Preview(
    data = data,
    width = width,
    height = height,
    rotationDegrees = rotationDegrees,
    transformIdentity = identity,
  )
}
