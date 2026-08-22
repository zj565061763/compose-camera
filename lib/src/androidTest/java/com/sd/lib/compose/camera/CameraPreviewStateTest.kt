package com.sd.lib.compose.camera

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.CameraState
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ExperimentalLensFacing
import androidx.camera.core.FlashState
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.ImmutableImageInfo
import androidx.camera.core.impl.TagBundle
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.compose.ui.graphics.Matrix as ComposeMatrix

@RunWith(AndroidJUnit4::class)
class CameraPreviewStateTest {
  @Test
  fun createTransformToPreview_fromAnalysisThread_usesPublishedTransforms() {
    val state = CameraPreviewState()
    val requestKey = configureState(
      state = state,
      previewSize = IntSize(1_000, 1_000),
      surfaceToViewfinder = contentScaleTransform(
        sourceSize = Size(400f, 200f),
        destinationSize = Size(1_000f, 1_000f),
        contentScale = ContentScale.Fit,
      ),
    )
    val frame = fakeFrame(requestKey = requestKey)

    val matrix = createTransformOnAnalysisThread(state, frame)

    assertThat(matrix).isNotNull()
    assertMaps(matrix!!, 0f, 0f, 0f, 250f)
    assertMaps(matrix, 400f, 200f, 1_000f, 750f)
  }

  @Test
  fun createTransformToPreview_supportsCropFitAndFillBounds() {
    val cases = listOf(
      ScaleCase(ContentScale.Crop, -500f, 0f, 1_500f, 1_000f),
      ScaleCase(ContentScale.Fit, 0f, 250f, 1_000f, 750f),
      ScaleCase(ContentScale.FillBounds, 0f, 0f, 1_000f, 1_000f),
    )

    cases.forEach { case ->
      val state = CameraPreviewState()
      val requestKey = configureState(
        state = state,
        previewSize = IntSize(1_000, 1_000),
        surfaceToViewfinder = contentScaleTransform(
          sourceSize = Size(400f, 200f),
          destinationSize = Size(1_000f, 1_000f),
          contentScale = case.contentScale,
        ),
      )
      val matrix = state.createTransformToPreview(fakeFrame(requestKey = requestKey))

      assertWithMessage(case.contentScale.toString()).that(matrix).isNotNull()
      assertMaps(matrix!!, 0f, 0f, case.left, case.top)
      assertMaps(matrix, 400f, 200f, case.right, case.bottom)
    }
  }

  @Test
  fun createTransformToPreview_supportsFrontAndBackMirrorModes() {
    val cameraXMirror = horizontalMirrorTransform(400f)
    val cases = listOf(
      MirrorCase("front auto", cameraXMirror, true, false, 350f),
      MirrorCase("back auto", Matrix(), false, false, 50f),
      MirrorCase("front off", cameraXMirror, true, true, 50f),
      MirrorCase("back on", Matrix(), false, true, 350f),
    )

    cases.forEach { case ->
      val state = CameraPreviewState()
      val requestKey = configureState(
        state = state,
        previewSize = IntSize(400, 200),
        surfaceToViewfinder = case.cameraXTransform,
        previewMirrored = case.cameraXMirrored,
        needsAdditionalMirror = case.needsAdditionalMirror,
      )
      val matrix = state.createTransformToPreview(fakeFrame(requestKey = requestKey))

      assertWithMessage(case.name).that(matrix).isNotNull()
      assertMaps(matrix!!, 50f, 25f, case.expectedX, 25f)
    }
  }

  @Test
  fun additionalMirror_usesCameraXTransformationInsteadOfLensAssumption() {
    assertThat(CameraMirrorMode.AUTO.needsAdditionalMirror(cameraXMirrored = true)).isFalse()
    assertThat(CameraMirrorMode.AUTO.needsAdditionalMirror(cameraXMirrored = false)).isFalse()
    assertThat(CameraMirrorMode.ON.needsAdditionalMirror(cameraXMirrored = true)).isFalse()
    assertThat(CameraMirrorMode.ON.needsAdditionalMirror(cameraXMirrored = false)).isTrue()
    assertThat(CameraMirrorMode.OFF.needsAdditionalMirror(cameraXMirrored = true)).isTrue()
    assertThat(CameraMirrorMode.OFF.needsAdditionalMirror(cameraXMirrored = false)).isFalse()
  }

  @Test
  @androidx.annotation.OptIn(markerClass = [ExperimentalLensFacing::class])
  fun cameraLensOrNull_isolatesInvalidVendorMetadata() {
    assertThat(cameraLensOrNull { CameraSelector.LENS_FACING_FRONT })
      .isEqualTo(CameraLens.FRONT)
    assertThat(cameraLensOrNull { CameraSelector.LENS_FACING_BACK })
      .isEqualTo(CameraLens.BACK)
    assertThat(cameraLensOrNull { CameraSelector.LENS_FACING_EXTERNAL })
      .isEqualTo(CameraLens.EXTERNAL)
    assertThat(cameraLensOrNull { CameraSelector.LENS_FACING_UNKNOWN }).isNull()
    assertThat(cameraLensOrNull { throw IllegalArgumentException("invalid lens") }).isNull()
  }

  @Test
  fun cameraDeviceKey_returnsFirstOrMatchingCamera() {
    val devices = listOf(
      CameraDeviceInfo(cameraId = "front", lens = CameraLens.FRONT),
      CameraDeviceInfo(cameraId = "unknown", lens = null),
      CameraDeviceInfo(cameraId = "back", lens = CameraLens.BACK),
    )

    assertThat(devices.cameraDeviceKey(cameraId = null)).isEqualTo("front")
    assertThat(devices.cameraDeviceKey(cameraId = "unknown")).isEqualTo("unknown")
    assertThat(devices.cameraDeviceKey(cameraId = "missing")).isNull()
  }

  @Test
  fun cameraDevicesState_tracksCurrentErrorAndClearsItAfterSuccess() {
    val state = CameraDevicesState()
    val initializationError = IllegalStateException("provider failed")
    var deliveryCount = 0

    runOnMain {
      state.addErrorListener { deliveryCount++ }
      state.publishError(initializationError)
    }
    assertThat(state.error.value).isSameInstanceAs(initializationError)
    assertThat(state.hasLoadedDevices.value).isFalse()
    assertThat(deliveryCount).isEqualTo(1)

    // 同一个仍处于活动状态的错误不应因为重复发布而重复回调。
    runOnMain { state.publishError(initializationError) }
    assertThat(state.error.value).isSameInstanceAs(initializationError)
    assertThat(deliveryCount).isEqualTo(1)

    runOnMain { state.publishDevices(emptyList()) }
    assertThat(state.error.value).isNull()
    assertThat(state.hasLoadedDevices.value).isTrue()

    // 错误清除后即使 Throwable 实例相同，也代表一次新的失败。
    runOnMain { state.publishError(initializationError) }
    assertThat(state.error.value).isSameInstanceAs(initializationError)
    assertThat(deliveryCount).isEqualTo(2)
  }

  @Test
  fun cameraDevicesState_refreshInvokesAttachedActionOnlyWhileActive() {
    val state = CameraDevicesState()
    var refreshCount = 0

    runOnMain {
      state.attachRefreshAction { refreshCount++ }
      state.refresh()
      state.attachRefreshAction(null)
      state.refresh()
    }

    assertThat(refreshCount).isEqualTo(1)
  }

  @Test
  fun cameraDevicesState_deliversConsecutiveErrorsToEveryListener() {
    val state = CameraDevicesState()
    val firstError = IllegalStateException("first")
    val secondError = IllegalArgumentException("second")
    val firstListenerErrors = mutableListOf<Throwable>()
    val secondListenerErrors = mutableListOf<Throwable>()

    runOnMain {
      state.addErrorListener { firstListenerErrors += it }
      state.addErrorListener { secondListenerErrors += it }
      state.publishError(firstError)
      state.publishError(secondError)
    }

    assertThat(firstListenerErrors).containsExactly(firstError, secondError).inOrder()
    assertThat(secondListenerErrors).containsExactly(firstError, secondError).inOrder()
  }

  @Test
  fun cameraDevicesState_listenerFailurePreservesPublishedDeviceError() {
    val state = CameraDevicesState()
    val deviceError = IllegalArgumentException("device")
    val callbackError = IllegalStateException("callback")
    val receivedErrors = mutableListOf<Throwable>()
    var thrownError: Throwable? = null

    runOnMain {
      state.addErrorListener { throw callbackError }
      state.addErrorListener { receivedErrors += it }
      try {
        state.publishError(deviceError)
      } catch (error: Throwable) {
        thrownError = error
      }
    }

    assertThat(thrownError).isSameInstanceAs(callbackError)
    assertThat(receivedErrors).containsExactly(deviceError)
    assertThat(state.error.value).isSameInstanceAs(deviceError)
  }

  @Test
  fun mainThreadErrorSubscription_afterCloseDropsQueuedDeviceError() {
    val deviceError = IllegalStateException("device")
    val receivedErrors = mutableListOf<Throwable>()

    runOnMain {
      val subscription = MainThreadErrorSubscription(
        MainThreadErrorDispatcher(receivedErrors::add),
      )
      subscription.dispatch(deviceError)
      subscription.close()
    }
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()

    assertThat(receivedErrors).isEmpty()
  }

  @Test
  fun createTransformToPreview_correctsCameraX15QuarterTurnMirrorAxis() {
    val cases = listOf(
      RotationCase(
        90,
        IntSize(200, 400),
        affineMatrix(0f, -1f, 200f, -1f, 0f, 400f),
        25f,
        50f,
      ),
      RotationCase(
        270,
        IntSize(200, 400),
        affineMatrix(0f, 1f, 0f, 1f, 0f, 0f),
        175f,
        350f,
      ),
    )

    cases.forEach { case ->
      val state = CameraPreviewState()
      val requestKey = configureState(
        state = state,
        previewSize = case.previewSize,
        surfaceToViewfinder = case.surfaceToViewfinder,
        previewRotationDegrees = case.rotationDegrees,
        previewMirrored = true,
      )

      val matrix = state.createTransformToPreview(fakeFrame(requestKey = requestKey))

      assertWithMessage("rotation ${case.rotationDegrees}").that(matrix).isNotNull()
      assertMaps(matrix!!, 50f, 25f, case.expectedX, case.expectedY)
    }
  }

  @Test
  fun createTransformToPreview_doesNotCorrectAlreadyFixedMirrorAxis() {
    val state = CameraPreviewState()
    val fixedTransform = affineMatrix(0f, -1f, 200f, -1f, 0f, 400f)
    val requestKey = configureState(
      state = state,
      previewSize = IntSize(200, 400),
      surfaceToViewfinder = fixedTransform,
      previewRotationDegrees = 270,
      previewMirrored = true,
    )

    val matrix = state.createTransformToPreview(fakeFrame(requestKey = requestKey))

    assertThat(matrix).isNotNull()
    assertMaps(matrix!!, 50f, 25f, 175f, 350f)
  }

  @Test
  fun createTransformToPreview_supportsAllRightAngleRotations() {
    val cases = listOf(
      RotationCase(0, IntSize(400, 200), affineMatrix(1f, 0f, 0f, 0f, 1f, 0f), 50f, 25f),
      RotationCase(90, IntSize(200, 400), affineMatrix(0f, -1f, 200f, 1f, 0f, 0f), 175f, 50f),
      RotationCase(180, IntSize(400, 200), affineMatrix(-1f, 0f, 400f, 0f, -1f, 200f), 350f, 175f),
      RotationCase(270, IntSize(200, 400), affineMatrix(0f, 1f, 0f, -1f, 0f, 400f), 25f, 350f),
    )

    cases.forEach { case ->
      val state = CameraPreviewState()
      val requestKey = configureState(
        state = state,
        previewSize = case.previewSize,
        surfaceToViewfinder = case.surfaceToViewfinder,
      )
      val frame = fakeFrame(
        requestKey = requestKey,
        rotationDegrees = case.rotationDegrees,
      )
      val matrix = state.createTransformToPreview(frame)

      assertWithMessage("rotation ${case.rotationDegrees}").that(matrix).isNotNull()
      assertMaps(matrix!!, 50f, 25f, case.expectedX, case.expectedY)
    }
  }

  @Test
  fun createTransformToPreview_mapsRawCoordinatesThroughSensorSpace() {
    val state = CameraPreviewState()
    val requestKey = configureState(
      state = state,
      previewSize = IntSize(800, 600),
      previewSensorToBuffer = affineMatrix(4f, 0f, 100f, 0f, 6f, 200f),
      surfaceToViewfinder = Matrix(),
    )
    val frame = fakeFrame(
      requestKey = requestKey,
      cropRect = Rect(20, 30, 700, 500),
      sensorToBuffer = affineMatrix(2f, 0f, 10f, 0f, 3f, 20f),
    )

    val matrix = state.createTransformToPreview(frame)

    assertThat(matrix).isNotNull()
    // 原始分析坐标 (20, 41) -> 传感器坐标 (5, 7) -> 预览缓冲区坐标 (120, 242)。
    assertMaps(matrix!!, 20f, 41f, 120f, 242f)
  }

  @Test
  fun createTransformToPreview_withDifferentSurfaceRequest_returnsNull() {
    val state = CameraPreviewState()
    configureState(
      state = state,
      previewSize = IntSize(400, 200),
      surfaceToViewfinder = Matrix(),
    )

    val matrix = state.createTransformToPreview(
      fakeFrame(requestKey = CameraFrameTransformIdentity()),
    )

    assertThat(matrix).isNull()
  }

  @Test
  fun frameTransformToken_tracksCurrentSurfaceRequest() {
    val state = CameraPreviewState()
    val oldRequest = configureState(
      state = state,
      previewSize = IntSize(400, 200),
      surfaceToViewfinder = Matrix(),
    )
    val oldFrame = fakeFrame(requestKey = oldRequest)
    val sameOldTransformToken = fakeFrame(oldRequest).transformToken

    assertThat(state.isFrameTransformCurrent(oldFrame.transformToken)).isTrue()
    assertThat(oldFrame.transformToken.isSameTransform(sameOldTransformToken)).isTrue()

    val newRequest = configureState(
      state = state,
      previewSize = IntSize(400, 200),
      surfaceToViewfinder = Matrix(),
    )
    val newFrame = fakeFrame(requestKey = newRequest)

    assertThat(state.isFrameTransformCurrent(oldFrame.transformToken)).isFalse()
    assertThat(state.isFrameTransformCurrent(newFrame.transformToken)).isTrue()
    assertThat(newFrame.transformToken.isSameTransform(oldFrame.transformToken)).isFalse()
    // 历史 token 即使已不再是当前变换，仍必须保持彼此的稳定身份关系。
    assertThat(oldFrame.transformToken.isSameTransform(sameOldTransformToken)).isTrue()

    runOnMain { state.clearSurfaceRequest(newRequest) }

    assertThat(state.isFrameTransformCurrent(newFrame.transformToken)).isFalse()
  }

  @Test
  fun createTransformToPreview_afterReset_returnsNullFromAnalysisThread() {
    val state = CameraPreviewState()
    val requestKey = configureState(
      state = state,
      previewSize = IntSize(400, 200),
      surfaceToViewfinder = Matrix(),
    )
    runOnMain { state.reset() }

    val matrix = createTransformOnAnalysisThread(state, fakeFrame(requestKey = requestKey))

    assertThat(matrix).isNull()
  }

  @Test
  fun reset_clearsRetryGeneration() {
    val state = CameraPreviewState()
    runOnMain { state.retry() }
    assertThat(state.retryGeneration).isEqualTo(1)

    runOnMain { state.reset() }

    assertThat(state.retryGeneration).isEqualTo(0)
  }

  @Test
  fun previewLayoutChange_invalidatesOldTokenAndWaitsForMatchingViewfinderTransform() {
    val state = CameraPreviewState()
    val requestKey = configureState(
      state = state,
      previewSize = IntSize(400, 200),
      contentScale = ContentScale.Crop,
      surfaceToViewfinder = Matrix(),
    )
    val oldFrame = fakeFrame(requestKey = requestKey)

    runOnMain {
      state.updatePreviewLayout(
        previewSize = IntSize(800, 400),
        contentScale = ContentScale.Fit,
        needsAdditionalMirror = false,
      )
    }
    assertThat(state.isFrameTransformCurrent(oldFrame.transformToken)).isFalse()
    assertThat(state.createTransformToPreview(oldFrame)).isNull()

    // 延迟到达的旧布局矩阵不能恢复坐标转换。
    runOnMain {
      state.updateViewfinderToBuffer(
        requestIdentity = requestKey,
        previewSize = IntSize(400, 200),
        contentScale = ContentScale.Crop,
        viewfinderToBuffer = Matrix(),
      )
    }
    assertThat(state.createTransformToPreview(oldFrame)).isNull()

    runOnMain {
      state.updateViewfinderToBuffer(
        requestIdentity = requestKey,
        previewSize = IntSize(800, 400),
        contentScale = ContentScale.Fit,
        viewfinderToBuffer = Matrix(),
      )
    }
    val currentFrame = fakeFrame(requestKey = checkNotNull(state.currentTransformIdentity()))
    assertThat(state.isFrameTransformCurrent(currentFrame.transformToken)).isTrue()
    assertThat(state.createTransformToPreview(currentFrame)).isNotNull()
  }

  @Test
  fun previewMirrorChange_invalidatesOldTokenWithoutDiscardingViewfinderTransform() {
    val state = CameraPreviewState()
    val requestKey = configureState(
      state = state,
      previewSize = IntSize(400, 200),
      surfaceToViewfinder = Matrix(),
    )
    val oldFrame = fakeFrame(requestKey = requestKey)

    runOnMain {
      state.updatePreviewLayout(
        previewSize = IntSize(400, 200),
        contentScale = ContentScale.Crop,
        needsAdditionalMirror = true,
      )
    }

    assertThat(state.isFrameTransformCurrent(oldFrame.transformToken)).isFalse()
    val currentIdentity = checkNotNull(state.currentTransformIdentity())
    val currentFrame = fakeFrame(requestKey = currentIdentity)
    val matrix = state.createTransformToPreview(currentFrame)
    assertThat(matrix).isNotNull()
    assertMaps(matrix!!, 50f, 25f, 350f, 25f)

    // 相同显示配置的重复 SideEffect 不能持续制造新 generation。
    runOnMain {
      state.updatePreviewLayout(
        previewSize = IntSize(400, 200),
        contentScale = ContentScale.Crop,
        needsAdditionalMirror = true,
      )
    }
    assertThat(state.currentTransformIdentity()).isSameInstanceAs(currentIdentity)
  }

  @Test
  fun oldSurfaceRequestCleanup_doesNotClearNewRequestState() {
    val state = CameraPreviewState()
    val oldRequest = configureState(
      state = state,
      previewSize = IntSize(400, 200),
      surfaceToViewfinder = Matrix(),
    )
    val newRequest = configureState(
      state = state,
      previewSize = IntSize(400, 200),
      surfaceToViewfinder = Matrix(),
    )
    runOnMain {
      state.updatePreviewResolution(newRequest, IntSize(1_632, 1_224))
      state.clearSurfaceRequest(oldRequest)
    }

    assertThat(state.previewResolution.value).isEqualTo(IntSize(1_632, 1_224))
    assertThat(state.createTransformToPreview(fakeFrame(requestKey = newRequest))).isNotNull()

    runOnMain { state.clearSurfaceRequest(newRequest) }
    assertThat(state.previewResolution.value).isEqualTo(IntSize.Zero)
    assertThat(state.createTransformToPreview(fakeFrame(requestKey = newRequest))).isNull()
  }

  @Test
  fun newSurfaceRequest_clearsPreviousResolution() {
    val state = CameraPreviewState()
    val oldRequest = CameraFrameTransformIdentity()
    val newRequest = CameraFrameTransformIdentity()
    runOnMain {
      state.updateSurfaceRequest(oldRequest)
      state.updatePreviewResolution(oldRequest, IntSize(1_280, 720))
    }
    assertThat(state.previewResolution.value).isEqualTo(IntSize(1_280, 720))

    runOnMain { state.updateSurfaceRequest(newRequest) }

    assertThat(state.previewResolution.value).isEqualTo(IntSize.Zero)
    runOnMain { state.updatePreviewResolution(oldRequest, IntSize(640, 480)) }
    assertThat(state.previewResolution.value).isEqualTo(IntSize.Zero)
  }

  @Test
  fun staleSurfaceRequest_cannotPublishResolution() {
    val state = CameraPreviewState()
    val oldRequest = CameraFrameTransformIdentity()
    val newRequest = configureState(
      state = state,
      previewSize = IntSize(400, 200),
      surfaceToViewfinder = Matrix(),
    )

    runOnMain {
      state.updatePreviewResolution(newRequest, IntSize(1_280, 720))
      state.updatePreviewResolution(oldRequest, IntSize(640, 480))
    }

    assertThat(state.previewResolution.value).isEqualTo(IntSize(1_280, 720))
  }

  @Test
  fun staleSurfaceRequest_cannotPublishTransformation() {
    val state = CameraPreviewState()
    val oldRequest = CameraFrameTransformIdentity()
    val newRequest = CameraFrameTransformIdentity()

    runOnMain {
      state.updateSurfaceRequest(oldRequest)
      state.updateSurfaceRequest(newRequest)

      assertThat(
        state.updatePreviewTransformation(
          requestIdentity = oldRequest,
          sensorToBuffer = android.graphics.Matrix(),
          cropRect = android.graphics.Rect(0, 0, 1, 1),
          rotationDegrees = 0,
          isMirrored = false,
        ),
      ).isFalse()
    }
  }

  @Test
  fun surfaceRequestPublicationGate_cancelAndPublishOrderingIsAtomic() {
    val cancelledGate = SurfaceRequestPublicationGate()
    var cancelledPublishCount = 0
    cancelledGate.cancel()

    val publishedAfterCancellation = cancelledGate.publishIfActive {
      cancelledPublishCount++
      true
    }

    assertThat(publishedAfterCancellation).isFalse()
    assertThat(cancelledPublishCount).isEqualTo(0)

    val activeGate = SurfaceRequestPublicationGate()
    var activePublishCount = 0
    val firstPublish = activeGate.publishIfActive {
      activePublishCount++
      true
    }
    activeGate.cancel()
    val secondPublish = activeGate.publishIfActive {
      activePublishCount++
      true
    }

    assertThat(firstPublish).isTrue()
    assertThat(secondPublish).isFalse()
    assertThat(activePublishCount).isEqualTo(1)
  }

  @Test
  fun frameDispatcher_afterClose_dropsQueuedFrameAndClosesImages() {
    val requestKey = CameraFrameTransformIdentity()
    val receivedFrames = mutableListOf<CameraFrame>()
    val dispatcher = CameraFrameDispatcher(
      transformIdentityProvider = { requestKey },
      onFrame = receivedFrames::add,
    )
    val activeImage = FakeImageProxy()
    val queuedImage = FakeImageProxy()

    dispatcher.dispatch(activeImage)
    dispatcher.close()
    dispatcher.dispatch(queuedImage)

    assertThat(receivedFrames).hasSize(1)
    assertThat(receivedFrames.single().transformIdentity).isSameInstanceAs(requestKey)
    assertThat(activeImage.isClosed).isTrue()
    assertThat(queuedImage.isClosed).isTrue()
  }

  @Test
  fun frameDispatcher_whenCallbackThrows_closesImageAndRethrowsException() {
    val callbackError = IllegalStateException("frame callback failed")
    val dispatcher = CameraFrameDispatcher(
      transformIdentityProvider = { CameraFrameTransformIdentity() },
      onFrame = { throw callbackError },
    )
    val image = FakeImageProxy()

    val actualError = runCatching { dispatcher.dispatch(image) }.exceptionOrNull()

    assertThat(actualError).isSameInstanceAs(callbackError)
    assertThat(image.isClosed).isTrue()
  }

  @Test
  fun frameDispatcher_whenCallbackAndCloseFail_rethrowsCloseError() {
    val callbackError = IllegalStateException("frame callback failed")
    val closeError = AssertionError("image close failed")
    val dispatcher = CameraFrameDispatcher(
      transformIdentityProvider = { CameraFrameTransformIdentity() },
      onFrame = { throw callbackError },
    )
    val image = FakeImageProxy(closeError = closeError)

    val actualError = runCatching { dispatcher.dispatch(image) }.exceptionOrNull()

    assertThat(actualError).isSameInstanceAs(closeError)
    assertThat(actualError!!.suppressed.asList()).containsExactly(callbackError)
    assertThat(image.isClosed).isTrue()
  }

  @Test
  fun frameDispatcher_whenCallbackErrorAndCloseException_rethrowsCallbackError() {
    val callbackError = AssertionError("frame callback failed")
    val closeException = IllegalStateException("image close failed")
    val dispatcher = CameraFrameDispatcher(
      transformIdentityProvider = { CameraFrameTransformIdentity() },
      onFrame = { throw callbackError },
    )
    val image = FakeImageProxy(closeError = closeException)

    val actualError = runCatching { dispatcher.dispatch(image) }.exceptionOrNull()

    assertThat(actualError).isSameInstanceAs(callbackError)
    assertThat(actualError!!.suppressed.asList()).containsExactly(closeException)
    assertThat(image.isClosed).isTrue()
  }

  @Test
  fun frameDispatcher_whenCallbackAndCloseThrowExceptions_preservesBoth() {
    val callbackException = IllegalArgumentException("frame callback failed")
    val closeException = IllegalStateException("image close failed")
    val dispatcher = CameraFrameDispatcher(
      transformIdentityProvider = { CameraFrameTransformIdentity() },
      onFrame = { throw callbackException },
    )
    val image = FakeImageProxy(closeError = closeException)

    val actualError = runCatching { dispatcher.dispatch(image) }.exceptionOrNull()

    assertThat(actualError).isSameInstanceAs(callbackException)
    assertThat(actualError!!.suppressed.asList()).containsExactly(closeException)
    assertThat(image.isClosed).isTrue()
  }

  @Test
  fun validPreviewSizeChanges_keepTheSameBindingKey() {
    val initialKey = IntSize(400, 400).isValidPreviewSize()

    assertThat(IntSize(1_000, 500).isValidPreviewSize()).isEqualTo(initialKey)
    assertThat(IntSize(500, 1_000).isValidPreviewSize()).isEqualTo(initialKey)
    assertThat(IntSize.Zero.isValidPreviewSize()).isFalse()
    assertThat(IntSize(0, 100).isValidPreviewSize()).isFalse()
  }

  @Test
  fun composeMatrixConversion_preservesAffineTransform() {
    val composeMatrix = ComposeMatrix().apply {
      this[0, 0] = 2f
      this[1, 0] = 3f
      this[3, 0] = 5f
      this[0, 1] = 7f
      this[1, 1] = 11f
      this[3, 1] = 13f
    }

    val androidMatrix = composeMatrix.toAndroidMatrix()

    assertMaps(androidMatrix, 1f, 2f, 13f, 42f)
  }

  @Test
  fun cameraStateError_preservesCodeAndCause() {
    val cause = IllegalStateException("camera busy")
    val cameraState = CameraState.create(
      CameraState.Type.OPENING,
      CameraState.StateError.create(CameraState.ERROR_CAMERA_IN_USE, cause),
    )

    val error = cameraState.toCameraPreviewExceptionOrNull()

    assertThat(error).isNotNull()
    assertThat(error!!.reason).isEqualTo(CameraPreviewException.Reason.CAMERA_STATE_ERROR)
    assertThat(error.cameraStateErrorCode).isEqualTo(CameraState.ERROR_CAMERA_IN_USE)
    assertThat(error.cause).isSameInstanceAs(cause)
  }

  @Test
  fun cameraStateWithoutError_isIgnored() {
    val cameraState = CameraState.create(CameraState.Type.OPEN)

    assertThat(cameraState.toCameraPreviewExceptionOrNull()).isNull()
  }

  @Test
  @SuppressLint("RestrictedApi")
  fun criticalCameraStates_requireSessionClose() {
    val removedState = CameraState.create(
      CameraState.Type.CLOSED,
      CameraState.StateError.create(CameraState.ERROR_CAMERA_REMOVED),
    )
    val streamConfigState = CameraState.create(
      CameraState.Type.CLOSING,
      CameraState.StateError.create(CameraState.ERROR_STREAM_CONFIG),
    )
    val doNotDisturbState = CameraState.create(
      CameraState.Type.CLOSED,
      CameraState.StateError.create(CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED),
    )
    val recoverableState = CameraState.create(
      CameraState.Type.OPENING,
      CameraState.StateError.create(CameraState.ERROR_CAMERA_IN_USE),
    )

    assertThat(removedState.requiresCameraSessionClose()).isTrue()
    assertThat(streamConfigState.requiresCameraSessionClose()).isTrue()
    assertThat(doNotDisturbState.requiresCameraSessionClose()).isTrue()
    assertThat(recoverableState.requiresCameraSessionClose()).isFalse()
    assertThat(removedState.toCameraPreviewExceptionOrNull())
      .hasMessageThat().contains("removed")
  }

  @Test
  fun noAvailableCameraError_hasDistinctReason() {
    val error = cameraSelectionException(cameraId = null)

    assertThat(error.reason).isEqualTo(CameraPreviewException.Reason.NO_AVAILABLE_CAMERA)
    assertThat(error.cameraStateErrorCode).isNull()
  }

  @Test
  fun missingExactCameraError_preservesRequestedId() {
    val error = cameraSelectionException(cameraId = "usb-camera-2")

    assertThat(error.reason).isEqualTo(CameraPreviewException.Reason.CAMERA_NOT_FOUND)
    assertThat(error.cameraStateErrorCode).isNull()
    assertThat(error).hasMessageThat().contains("usb-camera-2")
  }

  @Test
  fun cleanupActions_continueAfterExceptionsAndAlwaysRunFinalAction() {
    val firstError = IllegalStateException("clear analyzer failed")
    val secondError = IllegalArgumentException("unbind failed")
    val completedActions = mutableListOf<String>()

    val error = runCameraCleanupActions(
      actions = listOf(
        {
          completedActions += "clear"
          throw firstError
        },
        { completedActions += "remove observer" },
        {
          completedActions += "unbind"
          throw secondError
        },
      ),
      finalAction = { completedActions += "shutdown" },
    )

    assertThat(completedActions)
      .containsExactly("clear", "remove observer", "unbind", "shutdown")
      .inOrder()
    assertThat(error).isSameInstanceAs(firstError)
    assertThat(error!!.suppressed.asList()).containsExactly(secondError)
  }
}

private fun configureState(
  state: CameraPreviewState,
  previewSize: IntSize,
  contentScale: ContentScale = ContentScale.Crop,
  previewSensorToBuffer: Matrix = Matrix(),
  previewCropRect: Rect = Rect(0, 0, 400, 200),
  previewRotationDegrees: Int = 0,
  previewMirrored: Boolean = false,
  surfaceToViewfinder: Matrix,
  needsAdditionalMirror: Boolean = false,
): CameraFrameTransformIdentity {
  val requestKey = CameraFrameTransformIdentity()
  val viewfinderToBuffer = Matrix()
  check(surfaceToViewfinder.invert(viewfinderToBuffer))
  runOnMain {
    state.updatePreviewLayout(previewSize, contentScale, needsAdditionalMirror)
    state.updateSurfaceRequest(requestKey)
    state.updatePreviewTransformation(
      requestIdentity = requestKey,
      sensorToBuffer = previewSensorToBuffer,
      cropRect = previewCropRect,
      rotationDegrees = previewRotationDegrees,
      isMirrored = previewMirrored,
    )
    state.updateViewfinderToBuffer(
      requestIdentity = requestKey,
      previewSize = previewSize,
      contentScale = contentScale,
      viewfinderToBuffer = viewfinderToBuffer,
    )
  }
  return requestKey
}

private fun createTransformOnAnalysisThread(
  state: CameraPreviewState,
  frame: CameraFrame,
): Matrix? {
  val executor = Executors.newSingleThreadExecutor()
  return try {
    executor.submit<Matrix?> { state.createTransformToPreview(frame) }
      .get(5, TimeUnit.SECONDS)
  } finally {
    executor.shutdownNow()
  }
}

private fun fakeFrame(
  requestKey: CameraFrameTransformIdentity,
  cropRect: Rect = Rect(0, 0, 400, 200),
  rotationDegrees: Int = 0,
  sensorToBuffer: Matrix = Matrix(),
): CameraFrame {
  return CameraFrame(
    image = FakeImageProxy(
      cropRect = cropRect,
      rotationDegrees = rotationDegrees,
      sensorToBuffer = sensorToBuffer,
    ),
    transformIdentity = requestKey,
  )
}

private fun contentScaleTransform(
  sourceSize: Size,
  destinationSize: Size,
  contentScale: ContentScale,
): Matrix {
  val scale = contentScale.computeScaleFactor(sourceSize, destinationSize)
  val offsetX = (destinationSize.width - sourceSize.width * scale.scaleX) / 2f
  val offsetY = (destinationSize.height - sourceSize.height * scale.scaleY) / 2f
  return affineMatrix(scale.scaleX, 0f, offsetX, 0f, scale.scaleY, offsetY)
}

private fun horizontalMirrorTransform(width: Float): Matrix {
  return affineMatrix(-1f, 0f, width, 0f, 1f, 0f)
}

/** 创建 x' = ax + cy + tx、y' = bx + dy + ty 的仿射矩阵。 */
private fun affineMatrix(
  a: Float,
  c: Float,
  tx: Float,
  b: Float,
  d: Float,
  ty: Float,
): Matrix = Matrix().apply {
  setValues(
    floatArrayOf(
      a, c, tx,
      b, d, ty,
      0f, 0f, 1f,
    ),
  )
}

private fun assertMaps(
  matrix: Matrix,
  sourceX: Float,
  sourceY: Float,
  expectedX: Float,
  expectedY: Float,
) {
  val point = floatArrayOf(sourceX, sourceY)
  matrix.mapPoints(point)
  assertThat(point[0]).isWithin(0.001f).of(expectedX)
  assertThat(point[1]).isWithin(0.001f).of(expectedY)
}

private fun runOnMain(block: () -> Unit) {
  InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}

private data class ScaleCase(
  val contentScale: ContentScale,
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
)

private data class MirrorCase(
  val name: String,
  val cameraXTransform: Matrix,
  val cameraXMirrored: Boolean,
  val needsAdditionalMirror: Boolean,
  val expectedX: Float,
)

private data class RotationCase(
  val rotationDegrees: Int,
  val previewSize: IntSize,
  val surfaceToViewfinder: Matrix,
  val expectedX: Float,
  val expectedY: Float,
)

/** CameraX 变换测试使用的轻量 ImageProxy Fake。 */
@SuppressLint("RestrictedApi")
@ExperimentalGetImage
private class FakeImageProxy(
  cropRect: Rect = Rect(0, 0, 400, 200),
  rotationDegrees: Int = 0,
  sensorToBuffer: Matrix = Matrix(),
  private val closeError: Throwable? = null,
) : ImageProxy {
  private var _currentCropRect = Rect(cropRect)
  var isClosed: Boolean = false
    private set

  private val _imageInfo: ImageInfo = ImmutableImageInfo.create(
    TagBundle.emptyBundle(),
    0L,
    rotationDegrees,
    Matrix(sensorToBuffer),
    FlashState.UNKNOWN,
  )

  override fun close() {
    isClosed = true
    closeError?.also { throw it }
  }

  override fun getCropRect(): Rect = Rect(_currentCropRect)

  override fun setCropRect(rect: Rect?) {
    _currentCropRect = rect?.let(::Rect) ?: Rect(0, 0, width, height)
  }

  override fun getFormat(): Int = ImageFormat.YUV_420_888

  override fun getHeight(): Int = 600

  override fun getWidth(): Int = 800

  override fun getPlanes(): Array<ImageProxy.PlaneProxy> = emptyArray()

  override fun getImageInfo(): ImageInfo = _imageInfo

  override fun getImage(): Image? = null
}
