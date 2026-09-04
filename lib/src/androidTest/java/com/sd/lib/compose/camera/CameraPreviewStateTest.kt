@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
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
    state.markPreviewFrameAvailable(identity)

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
    state.markPreviewFrameAvailable(identity)

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
    state.markPreviewFrameAvailable(identity)

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
    state.markPreviewFrameAvailable(identity)

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
    state.markPreviewFrameAvailable(identity)
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
  fun newSessionRawFrameToken_remainsInvalidUntilFirstPreviewFrame() {
    val state = CameraPreviewState()
    val firstSessionIdentity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(200, 100), ContentScale.Crop, isMirrored = true)
    state.startSession(firstSessionIdentity, IntSize(200, 100), rotationDegrees = 0, isMirrored = true)
    state.markPreviewFrameAvailable(firstSessionIdentity)
    val firstFrame = frame(firstSessionIdentity, 200, 100, 0)
    assertThat(state.isFrameTransformCurrent(firstFrame.transformToken)).isTrue()

    val secondSessionIdentity = CameraFrameTransformIdentity()
    state.startSession(secondSessionIdentity, IntSize(320, 240), rotationDegrees = 90, isMirrored = false)
    val earlyFrame = CameraFrame.Preview(
      data = ByteArray(320 * 240 * 3 / 2),
      width = 320,
      height = 240,
      rotationDegrees = 90,
      transformIdentity = state.currentTransformIdentity(),
    )

    assertThat(state.currentTransformIdentity()).isNull()
    assertThat(state.isFrameTransformCurrent(firstFrame.transformToken)).isFalse()
    assertThat(state.isFrameTransformCurrent(earlyFrame.transformToken)).isFalse()
    assertThat(state.createTransformToPreview(earlyFrame)).isNull()

    state.updatePreviewLayout(IntSize(240, 320), ContentScale.Fit, isMirrored = false)
    assertThat(state.currentTransformIdentity()).isNull()
    state.markPreviewFrameAvailable(secondSessionIdentity)

    assertThat(state.isFrameTransformCurrent(earlyFrame.transformToken)).isFalse()
    assertThat(state.createTransformToPreview(earlyFrame)).isNull()
    val currentFrame = frame(secondSessionIdentity, 320, 240, 90)
    assertThat(state.isFrameTransformCurrent(currentFrame.transformToken)).isTrue()
    assertThat(state.createTransformToPreview(currentFrame)).isNotNull()
  }

  @Test
  fun layoutChange_invalidatesOldFrameToken() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(200, 100), ContentScale.Fit, isMirrored = false)
    state.startSession(identity, IntSize(200, 100), rotationDegrees = 0, isMirrored = false)
    state.markPreviewFrameAvailable(identity)
    val oldFrame = frame(identity, 200, 100, 0)
    assertThat(state.isFrameTransformCurrent(oldFrame.transformToken)).isTrue()

    state.updatePreviewLayout(IntSize(100, 200), ContentScale.Fit, isMirrored = false)

    assertThat(state.isFrameTransformCurrent(oldFrame.transformToken)).isFalse()
  }

  @Test
  fun zeroSizeLayout_doesNotPublishCurrentFrameTransform() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(200, 100), ContentScale.Fit, isMirrored = false)
    state.startSession(identity, IntSize(200, 100), rotationDegrees = 0, isMirrored = false)
    state.markPreviewFrameAvailable(identity)
    val previousFrame = frame(identity, 200, 100, 0)

    state.updatePreviewLayout(IntSize.Zero, ContentScale.Fit, isMirrored = false)
    val hiddenFrame = frame(state.currentTransformIdentity(), 200, 100, 0)

    assertThat(state.currentTransformIdentity()).isNull()
    assertThat(state.isFrameTransformCurrent(previousFrame.transformToken)).isFalse()
    assertThat(state.isFrameTransformCurrent(hiddenFrame.transformToken)).isFalse()
    assertThat(state.createTransformToPreview(hiddenFrame)).isNull()
  }

  @Test
  fun firstFrameAtZeroSize_waitsForValidGeometryBeforePublishingTransform() {
    val state = CameraPreviewState()
    val sessionIdentity = CameraFrameTransformIdentity()
    state.startSession(sessionIdentity, IntSize(200, 100), rotationDegrees = 0, isMirrored = false)

    state.markPreviewFrameAvailable(sessionIdentity)
    val hiddenFrame = frame(state.currentTransformIdentity(), 200, 100, 0)

    assertThat(state.currentTransformIdentity()).isNull()
    assertThat(state.isFrameTransformCurrent(hiddenFrame.transformToken)).isFalse()
    assertThat(state.createTransformToPreview(hiddenFrame)).isNull()

    state.updatePreviewLayout(IntSize(200, 100), ContentScale.Fit, isMirrored = false)
    val visibleIdentity = checkNotNull(state.currentTransformIdentity())
    val visibleFrame = frame(visibleIdentity, 200, 100, 0)

    assertThat(state.isFrameTransformCurrent(visibleFrame.transformToken)).isTrue()
    assertThat(state.createTransformToPreview(visibleFrame)).isNotNull()
  }

  @Test
  fun contentScaleChange_invalidatesOldFrameTokenWhenGeometryIsUnchanged() {
    val state = CameraPreviewState()
    val identity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(200, 200), ContentScale.Crop, isMirrored = false)
    state.startSession(identity, IntSize(200, 200), rotationDegrees = 0, isMirrored = false)
    state.markPreviewFrameAvailable(identity)
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
    state.markPreviewFrameAvailable(identity)

    val matrix = checkNotNull(state.createTransformToPreview(frame(identity, 200, 100, 0)))
    val points = floatArrayOf(0f, 0f, 200f, 100f).also(matrix::mapPoints)
    assertThat(points.asList()).containsExactly(200f, 0f, 0f, 100f).inOrder()
  }

  @Test
  fun failure_onlyAcceptsCurrentAttemptAndRetryInvalidatesIt() {
    val state = CameraPreviewState()
    val firstAttempt = CameraPreviewAttemptIdentity()
    val secondAttempt = CameraPreviewAttemptIdentity()
    val firstFailure = IllegalStateException("first")
    val lateFailure = IllegalStateException("late")
    val secondFailure = IllegalStateException("second")

    state.beginAttempt(firstAttempt)
    state.reportFailure(firstAttempt, firstFailure)

    assertThat(state.failure.value).isSameInstanceAs(firstFailure)

    state.beginAttempt(secondAttempt)
    state.reportFailure(firstAttempt, lateFailure)
    assertThat(state.failure.value).isNull()

    state.endAttempt(firstAttempt)
    state.reportFailure(secondAttempt, secondFailure)
    assertThat(state.failure.value).isSameInstanceAs(secondFailure)

    state.retry()
    state.reportFailure(secondAttempt, lateFailure)
    assertThat(state.failure.value).isNull()
  }

  @Test
  fun firstFrameOfCurrentAttemptClearsFailureAndRejectsStaleAttempt() {
    val state = CameraPreviewState()
    val currentAttempt = CameraPreviewAttemptIdentity()
    val staleAttempt = CameraPreviewAttemptIdentity()
    val sessionIdentity = CameraFrameTransformIdentity()
    val failure = IllegalStateException("failure")
    state.beginAttempt(currentAttempt)
    state.reportFailure(currentAttempt, failure)

    val staleStarted = state.startSession(
      attemptIdentity = staleAttempt,
      sessionIdentity = CameraFrameTransformIdentity(),
      bufferSize = IntSize(320, 240),
      rotationDegrees = 0,
      isMirrored = false,
    )
    val currentStarted = state.startSession(
      attemptIdentity = currentAttempt,
      sessionIdentity = sessionIdentity,
      bufferSize = IntSize(640, 480),
      rotationDegrees = 0,
      isMirrored = false,
    )

    assertThat(staleStarted).isFalse()
    assertThat(currentStarted).isTrue()
    assertThat(state.currentSessionIdentity()).isSameInstanceAs(sessionIdentity)
    assertThat(state.failure.value).isSameInstanceAs(failure)

    state.markPreviewFrameAvailable(sessionIdentity)

    assertThat(state.failure.value).isNull()
  }

  @Test
  fun clearSession_onlyClearsCurrentSession() {
    val state = CameraPreviewState()
    val currentSessionIdentity = CameraFrameTransformIdentity()
    state.startSession(
      sessionIdentity = currentSessionIdentity,
      bufferSize = IntSize(640, 480),
      rotationDegrees = 0,
      isMirrored = false,
    )

    assertThat(state.clearSession(CameraFrameTransformIdentity())).isFalse()
    assertThat(state.currentSessionIdentity()).isSameInstanceAs(currentSessionIdentity)
    assertThat(state.previewResolution.value).isEqualTo(IntSize(640, 480))

    assertThat(state.clearSession(currentSessionIdentity)).isTrue()
    assertThat(state.currentSessionIdentity()).isNull()
    assertThat(state.previewResolution.value).isEqualTo(IntSize.Zero)
  }

  @Test
  fun failureAfterSessionStartIsNotClearedByFirstFrame() {
    val state = CameraPreviewState()
    val attemptIdentity = CameraPreviewAttemptIdentity()
    val sessionIdentity = CameraFrameTransformIdentity()
    val failure = IllegalStateException("runtime")
    state.beginAttempt(attemptIdentity)
    state.startSession(
      attemptIdentity = attemptIdentity,
      sessionIdentity = sessionIdentity,
      bufferSize = IntSize(640, 480),
      rotationDegrees = 0,
      isMirrored = false,
    )

    state.reportFailure(attemptIdentity, failure)
    state.markPreviewFrameAvailable(sessionIdentity)

    assertThat(state.failure.value).isSameInstanceAs(failure)
  }

  @Test
  fun cameraDevicesRecovery_restoresActiveSessionFailure() {
    val state = CameraPreviewState()
    val attemptIdentity = CameraPreviewAttemptIdentity()
    val cameraDevicesAttemptIdentity = CameraDevicesAttemptIdentity()
    val devicesState = CameraDevicesState()
    val sessionFailure = IllegalStateException("session")
    val devicesFailure = IllegalStateException("enumeration")
    state.beginAttempt(attemptIdentity)
    state.beginCameraDevicesAttempt(cameraDevicesAttemptIdentity)
    state.reportFailure(attemptIdentity, sessionFailure)

    state.reportCameraDevicesFailure(cameraDevicesAttemptIdentity, devicesState, devicesFailure)
    assertThat(state.failure.value).isSameInstanceAs(devicesFailure)

    state.clearCameraDevicesFailure(cameraDevicesAttemptIdentity, devicesState)

    assertThat(state.failure.value).isSameInstanceAs(sessionFailure)
  }

  @Test
  fun firstFrameClearsSessionFailure_butPreservesCameraDevicesFailure() {
    val state = CameraPreviewState()
    val attemptIdentity = CameraPreviewAttemptIdentity()
    val cameraDevicesAttemptIdentity = CameraDevicesAttemptIdentity()
    val sessionIdentity = CameraFrameTransformIdentity()
    val devicesState = CameraDevicesState()
    val sessionFailure = IllegalStateException("session")
    val devicesFailure = IllegalStateException("enumeration")
    state.beginAttempt(attemptIdentity)
    state.beginCameraDevicesAttempt(cameraDevicesAttemptIdentity)
    state.reportFailure(attemptIdentity, sessionFailure)
    state.reportCameraDevicesFailure(cameraDevicesAttemptIdentity, devicesState, devicesFailure)
    state.startSession(
      attemptIdentity = attemptIdentity,
      sessionIdentity = sessionIdentity,
      bufferSize = IntSize(640, 480),
      rotationDegrees = 0,
      isMirrored = false,
    )

    state.markPreviewFrameAvailable(sessionIdentity)

    assertThat(state.failure.value).isSameInstanceAs(devicesFailure)
    state.clearCameraDevicesFailure(cameraDevicesAttemptIdentity, devicesState)
    assertThat(state.failure.value).isNull()
  }

  @Test
  fun cameraDevicesFailure_survivesSessionAttemptChangeButRetryInvalidatesIt() {
    val state = CameraPreviewState()
    val firstAttempt = CameraPreviewAttemptIdentity()
    val secondAttempt = CameraPreviewAttemptIdentity()
    val cameraDevicesAttemptIdentity = CameraDevicesAttemptIdentity()
    val devicesState = CameraDevicesState()
    val failure = IllegalStateException("enumeration")
    state.beginCameraDevicesAttempt(cameraDevicesAttemptIdentity)
    state.beginAttempt(firstAttempt)
    state.reportCameraDevicesFailure(cameraDevicesAttemptIdentity, devicesState, failure)

    state.endAttempt(firstAttempt)
    state.beginAttempt(secondAttempt)

    assertThat(state.failure.value).isSameInstanceAs(failure)

    state.retry()
    state.reportCameraDevicesFailure(cameraDevicesAttemptIdentity, devicesState, failure)

    assertThat(state.failure.value).isNull()
  }

  @Test
  fun reset_clearsRetryGeneration() {
    val state = CameraPreviewState()
    state.retry()

    state.reset()

    assertThat(state.retryGeneration).isEqualTo(0)
  }

  @Test
  fun updatePreviewLayout_sameValuesKeepCurrentTransform() {
    val state = CameraPreviewState()
    val sessionIdentity = CameraFrameTransformIdentity()
    state.updatePreviewLayout(IntSize(200, 100), ContentScale.Fit, isMirrored = false)
    state.startSession(sessionIdentity, IntSize(200, 100), rotationDegrees = 0, isMirrored = false)
    state.markPreviewFrameAvailable(sessionIdentity)
    val revision = state.previewTransformRevision
    val transformIdentity = state.currentTransformIdentity()

    state.updatePreviewLayout(IntSize(200, 100), ContentScale.Fit, isMirrored = false)

    assertThat(state.previewTransformRevision).isEqualTo(revision)
    assertThat(state.currentTransformIdentity()).isSameInstanceAs(transformIdentity)
  }

  @Test
  fun previewTransformRevision_changesWhenSessionStartsAndClears() {
    val state = CameraPreviewState()
    val initialRevision = state.previewTransformRevision
    val sessionIdentity = CameraFrameTransformIdentity()

    state.startSession(sessionIdentity, IntSize(200, 100), rotationDegrees = 0, isMirrored = false)
    val startedRevision = state.previewTransformRevision
    state.clearSession(sessionIdentity)

    assertThat(startedRevision).isNotEqualTo(initialRevision)
    assertThat(state.previewTransformRevision).isNotEqualTo(startedRevision)
  }
}
