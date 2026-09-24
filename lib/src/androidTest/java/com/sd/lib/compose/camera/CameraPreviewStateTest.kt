package com.sd.lib.compose.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.test.annotation.UiThreadTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraPreviewStateTest {
  @Test
  @UiThreadTest
  fun createTransformToPreview_cropCentersFrame() {
    val state = CameraPreviewState()
    state.updatePreviewLayout(IntSize(200, 200), ContentScale.Crop, isMirrored = false)
    state.startSession(session(contentSize = IntSize(640, 480), rawFrame = rawFrame(IntSize(640, 480), 0)))

    val matrix = checkNotNull(state.createTransformToPreview(currentFrame(state, 640, 480, 0)))
    val center = floatArrayOf(320f, 240f).also(matrix::mapPoints)
    val bounds = RectF(0f, 0f, 640f, 480f).also(matrix::mapRect)

    assertThat(center[0]).isWithin(0.01f).of(100f)
    assertThat(center[1]).isWithin(0.01f).of(100f)
    assertThat(bounds.left).isLessThan(0f)
    assertThat(bounds.top).isWithin(0.01f).of(0f)
    assertThat(bounds.bottom).isWithin(0.01f).of(200f)
  }

  @Test
  @UiThreadTest
  fun createTransformToPreview_quarterTurnsMapRawCorners() {
    val expectedCorners = mapOf(
      90 to listOf(300f, 0f, 300f, 400f, 0f, 0f),
      180 to listOf(400f, 300f, 0f, 300f, 400f, 0f),
      270 to listOf(0f, 400f, 0f, 0f, 300f, 400f),
    )
    expectedCorners.forEach { (rotation, expected) ->
      val state = CameraPreviewState()
      val contentSize = IntSize(400, 300).rotate(rotation)
      state.updatePreviewLayout(contentSize, ContentScale.Fit, isMirrored = false)
      state.startSession(session(contentSize = contentSize, rawFrame = rawFrame(IntSize(400, 300), rotation)))

      val matrix = checkNotNull(state.createTransformToPreview(currentFrame(state, 400, 300, rotation)))
      // 原点、右上角和左下角
      val points = floatArrayOf(0f, 0f, 400f, 0f, 0f, 300f).also(matrix::mapPoints)

      assertPoints(points, *expected.toFloatArray())
    }
  }

  @Test
  @UiThreadTest
  fun createTransformToPreview_appliesRawFrameCropRect() {
    val state = CameraPreviewState()
    state.updatePreviewLayout(IntSize(100, 100), ContentScale.Fit, isMirrored = false)
    state.startSession(
      session(
        contentSize = IntSize(100, 100),
        rawFrame = RawFrameLayout(IntSize(200, 100), IntRect(50, 0, 150, 100), 0),
      ),
    )

    val matrix = checkNotNull(state.createTransformToPreview(currentFrame(state, 200, 100, 0)))
    val points = floatArrayOf(50f, 0f, 150f, 100f).also(matrix::mapPoints)

    assertPoints(points, 0f, 0f, 100f, 100f)
  }

  @Test
  @UiThreadTest
  fun createTransformToPreview_targetMirrorFlipsAroundPreviewCenter() {
    val state = CameraPreviewState()
    state.updatePreviewLayout(IntSize(4, 3), ContentScale.Fit, isMirrored = true)
    state.startSession(session(contentSize = IntSize(2, 2), rawFrame = rawFrame(IntSize(2, 2), 0)))
    val rawMatrix = checkNotNull(state.createTransformToPreview(currentFrame(state, 2, 2, 0)))
    val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
    val sampledFrame = CameraFrame.PreviewSampled(bitmap, 0, state.currentTransformIdentity())
    val sampledMatrix = checkNotNull(state.createTransformToPreview(sampledFrame))

    assertPoints(floatArrayOf(0f, 0f).also(rawMatrix::mapPoints), 3.5f, 0f)
    assertPoints(floatArrayOf(0f, 0f, 3f, 3f).also(sampledMatrix::mapPoints), 4f, 0f, 1f, 3f)
    bitmap.recycle()
  }

  @Test
  @UiThreadTest
  fun createTransformToPreview_rejectsMismatchedFrame() {
    val state = CameraPreviewState()
    state.updatePreviewLayout(IntSize(100, 100), ContentScale.Crop, isMirrored = false)
    state.startSession(session(contentSize = IntSize(100, 100), rawFrame = rawFrame(IntSize(100, 100), 90)))

    assertThat(state.createTransformToPreview(currentFrame(state, 100, 100, 0))).isNull()
    assertThat(state.createTransformToPreview(currentFrame(state, 200, 100, 90))).isNull()
    assertThat(state.createTransformToPreview(frame(null, 100, 100, 90))).isNull()
  }

  @Test
  @UiThreadTest
  fun createTransformToPreview_sampledFrameUsesSizeRecordedBeforeRecycle() {
    val state = CameraPreviewState()
    state.updatePreviewLayout(IntSize(4, 3), ContentScale.Fit, isMirrored = false)
    state.startSession(session(contentSize = IntSize(2, 2)))
    val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
    val frame = CameraFrame.PreviewSampled(bitmap, 0, state.currentTransformIdentity())
    // 模拟回调结束后 data 已被回收
    bitmap.recycle()

    assertThat(state.createTransformToPreview(frame)).isNotNull()
  }

  @Test
  @UiThreadTest
  fun transformToken_invalidatesOnSessionLayoutScaleOrMirrorChange() {
    val state = CameraPreviewState()
    state.updatePreviewLayout(IntSize(100, 100), ContentScale.Crop, isMirrored = false)
    assertThat(state.currentTransformIdentity()).isNull()

    val session = session(contentSize = IntSize(100, 100))
    state.startSession(session)
    val changes = listOf<() -> Unit>(
      { state.updatePreviewLayout(IntSize(200, 200), ContentScale.Crop, isMirrored = false) },
      { state.updatePreviewLayout(IntSize(200, 200), ContentScale.Fit, isMirrored = false) },
      { state.updatePreviewLayout(IntSize(200, 200), ContentScale.Fit, isMirrored = true) },
      { state.startSession(session(contentSize = IntSize(100, 100))) },
    )
    changes.forEach { change ->
      val token = CameraFrameTransformToken(state.currentTransformIdentity())
      assertThat(state.isFrameTransformCurrent(token)).isTrue()
      change()
      assertThat(state.isFrameTransformCurrent(token)).isFalse()
    }
  }

  @Test
  @UiThreadTest
  fun transformToken_keepsIdentityForSameLayoutAndClearsWithoutGeometry() {
    val state = CameraPreviewState()
    val session = session(contentSize = IntSize(100, 100))
    state.updatePreviewLayout(IntSize(100, 100), ContentScale.Crop, isMirrored = false)
    state.startSession(session)
    val identity = state.currentTransformIdentity()

    state.updatePreviewLayout(IntSize(100, 100), ContentScale.Crop, isMirrored = false)
    assertThat(state.currentTransformIdentity()).isSameInstanceAs(identity)

    state.updatePreviewLayout(IntSize.Zero, ContentScale.Crop, isMirrored = false)
    assertThat(state.currentTransformIdentity()).isNull()

    state.updatePreviewLayout(IntSize(100, 100), ContentScale.Crop, isMirrored = false)
    state.clearSession(session)
    assertThat(state.currentTransformIdentity()).isNull()
    assertThat(state.previewResolution.value).isEqualTo(IntSize.Zero)
  }

  @Test
  @UiThreadTest
  fun clearSession_ignoresReplacedSession() {
    val state = CameraPreviewState()
    val oldSession = session(contentSize = IntSize(100, 100))
    val newSession = session(contentSize = IntSize(100, 100))
    state.updatePreviewLayout(IntSize(100, 100), ContentScale.Crop, isMirrored = false)
    state.startSession(oldSession)
    state.startSession(newSession)

    state.clearSession(oldSession)

    assertThat(state.currentTransformIdentity()).isNotNull()
    assertThat(state.previewResolution.value).isEqualTo(newSession.resolution)
    assertThat(state.displayedSession.value).isSameInstanceAs(newSession)
  }

  @Test
  fun transformToken_invalidTokenIsNotSameAsItself() {
    val invalidToken = CameraFrameTransformToken(null)
    val identity = CameraFrameTransformIdentity()
    val validToken = CameraFrameTransformToken(identity)

    assertThat(invalidToken.isSameTransform(invalidToken)).isFalse()
    assertThat(invalidToken.isSameTransform(CameraFrameTransformToken(null))).isFalse()
    assertThat(validToken.isSameTransform(CameraFrameTransformToken(identity))).isTrue()
  }

  @Test
  @UiThreadTest
  fun failure_prefersSessionFailureAndClearsOnSessionStartOrRetry() {
    val state = CameraPreviewState()
    val devicesFailure = IllegalStateException("devices")
    val sessionFailure = IllegalStateException("session")

    state.updateDevicesFailure(devicesFailure)
    state.reportSessionFailure(sessionFailure)
    assertThat(state.failure.value).isSameInstanceAs(sessionFailure)

    state.startSession(session(contentSize = IntSize(100, 100)))
    assertThat(state.failure.value).isSameInstanceAs(devicesFailure)

    state.reportSessionFailure(sessionFailure)
    state.retry()
    assertThat(state.failure.value).isNull()
    assertThat(state.retryGeneration).isEqualTo(1)
  }

  @Test
  @UiThreadTest
  fun reset_clearsStateActionsAndRetryGeneration() {
    val state = CameraPreviewState()
    state.startSession(session(contentSize = IntSize(100, 100)))
    state.reportSessionFailure(IllegalStateException())
    state.attachRequestFocusAction {}
    state.attachTakeScreenshotAction { null }
    state.retry()

    state.reset()

    assertThat(state.retryGeneration).isEqualTo(0)
    assertThat(state.failure.value).isNull()
    assertThat(state.displayedSession.value).isNull()
    assertThat(state.previewResolution.value).isEqualTo(IntSize.Zero)
    assertThat(state.takeScreenshot()).isNull()
  }

  @Test
  @UiThreadTest
  fun capturePreview_rendersContentBoundsAndRequestedMirror() {
    val state = CameraPreviewState()
    // 预览区域 4×2，内容 2×2 居中，左右各留 1 像素透明
    state.updatePreviewLayout(IntSize(4, 2), ContentScale.Fit, isMirrored = false)
    state.startSession(session(contentSize = IntSize(2, 2), isPreviewMirrored = true))

    val unmirrored = checkNotNull(state.capturePreview(CameraMirrorMode.OFF) { twoColorBitmap() }).data
    val mirrored = checkNotNull(state.capturePreview(CameraMirrorMode.AUTO) { twoColorBitmap() }).data

    assertThat(unmirrored.width).isEqualTo(4)
    assertThat(Color.alpha(unmirrored.getPixel(0, 0))).isEqualTo(0)
    // 截图包含平台镜像，OFF 需要翻转回来
    assertThat(unmirrored.getPixel(1, 0)).isEqualTo(Color.BLUE)
    assertThat(unmirrored.getPixel(2, 0)).isEqualTo(Color.RED)
    assertThat(mirrored.getPixel(1, 0)).isEqualTo(Color.RED)
    assertThat(mirrored.getPixel(2, 0)).isEqualTo(Color.BLUE)
    unmirrored.recycle()
    mirrored.recycle()
  }

  @Test
  @UiThreadTest
  fun capturePreview_returnsNullWithoutSessionAndTransfersMatchingBitmap() {
    val state = CameraPreviewState()
    state.updatePreviewLayout(IntSize(2, 2), ContentScale.Crop, isMirrored = false)
    var captured = false
    assertThat(state.capturePreview(CameraMirrorMode.AUTO) { twoColorBitmap().also { captured = true } }).isNull()
    assertThat(captured).isFalse()

    state.startSession(session(contentSize = IntSize(2, 2)))
    val source = twoColorBitmap()
    val frame = checkNotNull(state.capturePreview(CameraMirrorMode.AUTO) { source })

    assertThat(frame.data).isSameInstanceAs(source)
    assertThat(frame.rotationDegrees).isEqualTo(0)
    assertThat(state.isFrameTransformCurrent(frame.transformToken)).isTrue()
    source.recycle()
  }

  @Test
  fun chooseAspectRatio_matchesOrientedPreviewArea() {
    val ratio4By3 = androidx.camera.core.AspectRatio.RATIO_4_3
    val ratio16By9 = androidx.camera.core.AspectRatio.RATIO_16_9

    assertThat(chooseAspectRatio(IntSize(1080, 1920), sensorRotationDegrees = 90)).isEqualTo(ratio16By9)
    assertThat(chooseAspectRatio(IntSize(1920, 1080), sensorRotationDegrees = 0)).isEqualTo(ratio16By9)
    assertThat(chooseAspectRatio(IntSize(300, 400), sensorRotationDegrees = 270)).isEqualTo(ratio4By3)
    assertThat(chooseAspectRatio(IntSize(500, 500), sensorRotationDegrees = 90)).isEqualTo(ratio4By3)
    assertThat(chooseAspectRatio(IntSize.Zero, sensorRotationDegrees = 90)).isEqualTo(ratio4By3)
  }

  private fun assertPoints(actual: FloatArray, vararg expected: Float) {
    assertThat(actual.size).isEqualTo(expected.size)
    actual.forEachIndexed { index, value -> assertThat(value).isWithin(0.01f).of(expected[index]) }
  }

  private fun session(
    contentSize: IntSize,
    isPreviewMirrored: Boolean = false,
    rawFrame: RawFrameLayout? = null,
  ): PreviewSession {
    return PreviewSession(
      contentSize = contentSize,
      isPreviewMirrored = isPreviewMirrored,
      resolution = rawFrame?.bufferSize ?: contentSize,
      rawFrame = rawFrame,
    )
  }

  private fun rawFrame(bufferSize: IntSize, rotationDegrees: Int): RawFrameLayout {
    return RawFrameLayout(bufferSize, IntRect(0, 0, bufferSize.width, bufferSize.height), rotationDegrees)
  }

  private fun currentFrame(state: CameraPreviewState, width: Int, height: Int, rotationDegrees: Int): CameraFrame.Preview {
    return frame(state.currentTransformIdentity(), width, height, rotationDegrees)
  }

  /** 左半红色、右半蓝色的 2×2 图片 */
  private fun twoColorBitmap(): Bitmap {
    return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
      setPixel(0, 0, Color.RED)
      setPixel(0, 1, Color.RED)
      setPixel(1, 0, Color.BLUE)
      setPixel(1, 1, Color.BLUE)
    }
  }
}
