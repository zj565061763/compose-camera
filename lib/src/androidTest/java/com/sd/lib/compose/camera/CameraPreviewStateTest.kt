package com.sd.lib.compose.camera

import android.graphics.ImageFormat
import android.graphics.RectF
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraPreviewStateTest {
  @Test
  fun createTransformToPreview_cropCentersBuffer() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(200, 200), ContentScale.Crop, isMirrored = false)
    state.startSession(identity, IntSize(640, 480), rotationDegrees = 0)

    val matrix = checkNotNull(state.createTransformToPreview(frame(identity, 640, 480, 0)))
    val center = floatArrayOf(320f, 240f).also(matrix::mapPoints)
    val bounds = RectF(0f, 0f, 640f, 480f).also(matrix::mapRect)

    assertThat(center[0]).isWithin(0.01f).of(100.5f)
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
    state.startSession(identity, IntSize(640, 480), rotationDegrees = 90)

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
  fun createTransformToPreview_mirrorsAroundPreviewWidth() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(200, 100), ContentScale.FillBounds, isMirrored = true)
    state.startSession(identity, IntSize(200, 100), rotationDegrees = 0)

    val matrix = checkNotNull(state.createTransformToPreview(frame(identity, 200, 100, 0)))
    val points = floatArrayOf(0f, 0f, 200f, 100f).also(matrix::mapPoints)

    assertThat(points.asList()).containsExactly(200f, 0f, 0f, 100f).inOrder()
  }

  @Test
  fun createTransformToPreview_mirrorMatchesIntegerContentPlacement() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(3, 3), ContentScale.Crop, isMirrored = true)
    state.startSession(identity, IntSize(4, 2), rotationDegrees = 0)

    val matrix = checkNotNull(state.createTransformToPreview(frame(identity, 4, 2, 0)))
    val points = floatArrayOf(0f, 0f, 4f, 2f).also(matrix::mapPoints)

    assertThat(points.asList()).containsExactly(5f, 0f, -1f, 3f).inOrder()
  }

  @Test
  fun layoutChange_invalidatesOldFrameToken() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(200, 100), ContentScale.Fit, isMirrored = false)
    state.startSession(identity, IntSize(200, 100), rotationDegrees = 0)
    val oldFrame = frame(identity, 200, 100, 0)
    assertThat(state.isFrameTransformCurrent(oldFrame.transformToken)).isTrue()

    state.updatePreviewLayout(IntSize(100, 200), ContentScale.Fit, isMirrored = false)

    assertThat(state.isFrameTransformCurrent(oldFrame.transformToken)).isFalse()
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
    val seen = mutableListOf<Int>()
    val returned = mutableListOf<Int>()
    val error = AtomicReference<Throwable?>()
    val dispatcher = CameraFrameDispatcher(
      frameFormat = CameraFrameFormat.NV21,
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

    dispatcher.offerFrame(1, returned)
    assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue()
    dispatcher.offerFrame(2, returned)
    dispatcher.offerFrame(3, returned)
    releaseFirst.countDown()

    assertThat(callbacks.await(5, TimeUnit.SECONDS)).isTrue()
    dispatcher.close()
    assertThat(error.get()).isNull()
    assertThat(seen).containsExactly(1, 3).inOrder()
    assertThat(returned).containsExactly(1, 2, 3)
  }

  @Test
  fun frameDispatcher_invalidFrameIsReturnedWithoutCallback() {
    val callback = CountDownLatch(1)
    val returned = CountDownLatch(1)
    val dispatcher = CameraFrameDispatcher(
      frameFormat = CameraFrameFormat.NV21,
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
      frameFormat = CameraFrameFormat.NV21,
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
  fun frameDispatcher_jpegConvertsBeforeCallback() {
    val callback = CountDownLatch(1)
    val returned = CountDownLatch(1)
    val result = AtomicReference<CameraFrame?>()
    val dispatcher = CameraFrameDispatcher(
      frameFormat = CameraFrameFormat.JPEG,
      onFrame = { frame ->
        result.set(frame)
        callback.countDown()
      },
      onError = {},
    )
    val width = 4
    val height = 4
    val data = ByteArray(width * height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8) { 128.toByte() }

    dispatcher.offer(
      data = data,
      width = width,
      height = height,
      rotationDegrees = 0,
      transformIdentity = CameraFrameTransformIdentity(),
      returnBuffer = { returned.countDown() },
    )

    assertThat(callback.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(result.get()!!.format).isEqualTo(CameraFrameFormat.JPEG)
    assertThat(result.get()!!.toBitmap()).isNotNull()
    dispatcher.close()
  }
}

private fun CameraFrameDispatcher.offerFrame(value: Int, returned: MutableList<Int>) {
  offer(
    data = ByteArray(6).also { it[0] = value.toByte() },
    width = 2,
    height = 2,
    rotationDegrees = 0,
    transformIdentity = CameraFrameTransformIdentity(),
    returnBuffer = { buffer -> synchronized(returned) { returned += buffer[0].toInt() } },
  )
}

private fun frame(
  identity: CameraFrameTransformIdentity,
  width: Int,
  height: Int,
  rotationDegrees: Int,
  data: ByteArray = ByteArray(width * height * 3 / 2),
  format: CameraFrameFormat = CameraFrameFormat.NV21,
): CameraFrame {
  return CameraFrame(
    data = data,
    format = format,
    width = width,
    height = height,
    rotationDegrees = rotationDegrees,
    transformIdentity = identity,
  )
}
