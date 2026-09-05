@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraPreviewIntegrationTest {
  private val _composeRule = createAndroidComposeRule<CameraPreviewTestActivity>()

  @get:Rule
  val rules: TestRule = RuleChain
    .outerRule(GrantPermissionRule.grant(Manifest.permission.CAMERA))
    .around(_composeRule)

  @Test
  fun cameraPreview_publishesNv21FrameAndBitmapWithExpectedRotation() {
    assumeCameraAvailable()
    val cameraInfo = Camera.CameraInfo().also { Camera.getCameraInfo(0, it) }
    val expectedRotation = calculateCameraFrameRotation(cameraInfo, Surface.ROTATION_0)
    val frameReceived = CountDownLatch(1)
    val frameResult = AtomicReference<FrameResult?>()
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        displayRotation = Surface.ROTATION_0,
        onError = error::set,
        frameProcessor = FrameProcessor.Preview { frame ->
          if (frameResult.get() == null) {
            frame.toBitmap()?.also { bitmap ->
              frameResult.compareAndSet(
                null,
                FrameResult(
                  width = frame.width,
                  height = frame.height,
                  bitmapWidth = bitmap.width,
                  bitmapHeight = bitmap.height,
                  rotationDegrees = frame.rotationDegrees,
                  threadName = Thread.currentThread().name,
                ),
              )
              bitmap.recycle()
              frameReceived.countDown()
            }
          }
        },
      )
    }

    assertThat(frameReceived.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    assertThat(error.get()).isNull()
    val result = checkNotNull(frameResult.get())
    assertThat(result.width).isGreaterThan(0)
    assertThat(result.height).isGreaterThan(0)
    assertThat(result.bitmapWidth).isEqualTo(result.width)
    assertThat(result.bitmapHeight).isEqualTo(result.height)
    assertThat(result.rotationDegrees).isEqualTo(expectedRotation)
    assertThat(result.threadName).isEqualTo(CAMERA_ANALYSIS_THREAD_NAME)
  }

  @Test
  fun cameraPreview_sampledFrameMatchesPreviewCoordinates() {
    assumeCameraAvailable()
    val state = CameraPreviewState()
    val frameReceived = CountDownLatch(1)
    val frameResult = AtomicReference<SampledFrameResult?>()
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        onError = error::set,
        frameProcessor = FrameProcessor.PreviewSampled(intervalMillis = 100) { frame ->
          if (state.createTransformToPreview(frame) != null && frameResult.get() == null) {
            frameResult.compareAndSet(
              null,
              SampledFrameResult(
                width = frame.data.width,
                height = frame.data.height,
                rotationDegrees = frame.rotationDegrees,
                threadName = Thread.currentThread().name,
              ),
            )
            frameReceived.countDown()
          }
        },
      )
    }

    assertThat(frameReceived.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    assertThat(error.get()).isNull()
    val result = checkNotNull(frameResult.get())
    assertThat(result.width).isGreaterThan(0)
    assertThat(result.height).isEqualTo(result.width)
    assertThat(result.rotationDegrees).isEqualTo(0)
    assertThat(result.threadName).isEqualTo(CAMERA_ANALYSIS_THREAD_NAME)
  }

  @Test
  fun cameraPreview_takeScreenshotReturnsOwnedPreviewBitmapAndDetachesWithPreview() {
    assumeCameraAvailable()
    val showPreview = mutableStateOf(true)
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      if (showPreview.value) {
        CameraPreview(
          modifier = Modifier.size(240.dp),
          state = state,
          onError = error::set,
        )
      }
    }
    waitForPreview(state, error)

    lateinit var bitmap: Bitmap
    _composeRule.runOnIdle {
      bitmap = checkNotNull(state.takeScreenshot(CameraMirrorMode.OFF))
    }

    assertThat(error.get()).isNull()
    assertThat(bitmap.width).isGreaterThan(0)
    assertThat(bitmap.height).isEqualTo(bitmap.width)
    assertThat(bitmap.isRecycled).isFalse()
    bitmap.recycle()

    _composeRule.runOnIdle { showPreview.value = false }
    _composeRule.waitForIdle()
    _composeRule.runOnIdle { assertThat(state.takeScreenshot()).isNull() }
  }

  @Test
  fun cameraPreview_autoOnlyFocusesOnFirstFrameAndExplicitRequest() {
    val autoFocusCameraId = findCameraIdUsingFocusMode(Camera.Parameters.FOCUS_MODE_AUTO)
    assumeTrue(autoFocusCameraId != null)
    val initialCameraThreads = activeCameraThreads()
    val initialAnalysisThreads = activeAnalysisThreads()
    val state = CameraPreviewState()
    val showPreview = mutableStateOf(true)
    val autoFocusOperations = CopyOnWriteArrayList<RecordingCameraAutoFocusOperations>()
    val noPeriodicFocusWindowObserved = CountDownLatch(1)
    val error = AtomicReference<Throwable?>()
    val autoFocusOperationsFactory = CameraAutoFocusOperationsFactory {
      RecordingCameraAutoFocusOperations(state::isPreviewFrameAvailable).also { operations ->
        autoFocusOperations += operations
      }
    }

    _composeRule.setContent {
      CompositionLocalProvider(LocalCameraAutoFocusOperationsFactory provides autoFocusOperationsFactory) {
        if (showPreview.value) {
          CameraPreview(
            modifier = Modifier.size(240.dp),
            state = state,
            cameraId = checkNotNull(autoFocusCameraId),
            onError = error::set,
            frameProcessor = FrameProcessor.Preview {
              val firstAutoFocusAtMillis = autoFocusOperations.singleOrNull()?.firstAutoFocusAtMillis?.get() ?: 0L
              if (
                firstAutoFocusAtMillis > 0L &&
                SystemClock.elapsedRealtime() - firstAutoFocusAtMillis >= NO_PERIODIC_FOCUS_WINDOW_MILLIS
              ) {
                noPeriodicFocusWindowObserved.countDown()
              }
            },
          )
        }
      }
    }
    waitForPreview(state, error)
    _composeRule.waitUntil(timeoutMillis = FRAME_TIMEOUT_SECONDS * 1_000) {
      error.get() != null || autoFocusOperations.singleOrNull()?.autoFocusCount?.get() == 1
    }

    assertThat(error.get()).isNull()
    val operations = autoFocusOperations.single()
    assertThat(noPeriodicFocusWindowObserved.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    assertThat(operations.calls).containsExactly(
      AutoFocusOperation.CANCEL,
      AutoFocusOperation.AUTO_FOCUS,
    ).inOrder()
    assertThat(operations.previewFrameAvailableAtAutoFocus).containsExactly(true)

    _composeRule.runOnIdle { state.requestFocus() }
    _composeRule.waitUntil(timeoutMillis = FRAME_TIMEOUT_SECONDS * 1_000) {
      error.get() != null || operations.autoFocusCount.get() == 2
    }

    assertThat(error.get()).isNull()
    assertThat(operations.calls).containsExactly(
      AutoFocusOperation.CANCEL,
      AutoFocusOperation.AUTO_FOCUS,
      AutoFocusOperation.CANCEL,
      AutoFocusOperation.AUTO_FOCUS,
    ).inOrder()
    assertThat(operations.threadNames).containsExactly(
      CAMERA_OPERATION_THREAD_NAME,
      CAMERA_OPERATION_THREAD_NAME,
      CAMERA_OPERATION_THREAD_NAME,
      CAMERA_OPERATION_THREAD_NAME,
    )
    assertThat(operations.previewFrameAvailableAtAutoFocus).containsExactly(true, true)

    val previousSessionIdentity = checkNotNull(state.currentSessionIdentity())
    _composeRule.runOnIdle { state.retry() }
    _composeRule.waitUntil(timeoutMillis = FRAME_TIMEOUT_SECONDS * 1_000) {
      val currentSessionIdentity = state.currentSessionIdentity()
      error.get() != null || (
        autoFocusOperations.size == 2 &&
          autoFocusOperations[1].autoFocusCount.get() == 1 &&
          currentSessionIdentity != null &&
          currentSessionIdentity !== previousSessionIdentity &&
          state.createCurrentTextureViewTransform(currentSessionIdentity) != null
      )
    }

    assertThat(error.get()).isNull()
    val retriedOperations = autoFocusOperations[1]
    _composeRule.runOnIdle { state.requestFocus() }
    _composeRule.waitUntil(timeoutMillis = FRAME_TIMEOUT_SECONDS * 1_000) {
      error.get() != null || retriedOperations.autoFocusCount.get() == 2
    }

    assertThat(error.get()).isNull()
    assertThat(operations.autoFocusCount.get()).isEqualTo(2)
    assertThat(retriedOperations.calls).containsExactly(
      AutoFocusOperation.CANCEL,
      AutoFocusOperation.AUTO_FOCUS,
      AutoFocusOperation.CANCEL,
      AutoFocusOperation.AUTO_FOCUS,
    ).inOrder()
    assertThat(retriedOperations.previewFrameAvailableAtAutoFocus).containsExactly(true, true)

    _composeRule.runOnIdle { showPreview.value = false }
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      state.previewResolution.value == IntSize.Zero &&
        (activeCameraThreads() - initialCameraThreads).isEmpty() &&
        (activeAnalysisThreads() - initialAnalysisThreads).isEmpty()
    }
    _composeRule.runOnIdle { state.requestFocus() }

    assertThat(operations.autoFocusCount.get()).isEqualTo(2)
    assertThat(retriedOperations.autoFocusCount.get()).isEqualTo(2)
  }

  @Test
  fun cameraPreview_continuousFocusIgnoresExplicitRequest() {
    val continuousFocusCameraId = findCameraIdUsingFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)
      ?: findCameraIdUsingFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)
    assumeTrue(continuousFocusCameraId != null)
    val initialCameraThreads = activeCameraThreads()
    val initialAnalysisThreads = activeAnalysisThreads()
    val state = CameraPreviewState()
    val showPreview = mutableStateOf(true)
    val autoFocusOperations = CopyOnWriteArrayList<RecordingCameraAutoFocusOperations>()
    val explicitRequestAtMillis = AtomicLong()
    val requestObservationWindowCompleted = CountDownLatch(1)
    val error = AtomicReference<Throwable?>()
    val autoFocusOperationsFactory = CameraAutoFocusOperationsFactory {
      RecordingCameraAutoFocusOperations(state::isPreviewFrameAvailable).also { operations ->
        autoFocusOperations += operations
      }
    }

    _composeRule.setContent {
      CompositionLocalProvider(LocalCameraAutoFocusOperationsFactory provides autoFocusOperationsFactory) {
        if (showPreview.value) {
          CameraPreview(
            modifier = Modifier.size(240.dp),
            state = state,
            cameraId = checkNotNull(continuousFocusCameraId),
            onError = error::set,
            frameProcessor = FrameProcessor.Preview {
              val requestAtMillis = explicitRequestAtMillis.get()
              if (
                requestAtMillis > 0L &&
                SystemClock.elapsedRealtime() - requestAtMillis >= FOCUS_REQUEST_OBSERVATION_WINDOW_MILLIS
              ) {
                requestObservationWindowCompleted.countDown()
              }
            },
          )
        }
      }
    }
    waitForPreview(state, error)

    _composeRule.runOnIdle {
      explicitRequestAtMillis.set(SystemClock.elapsedRealtime())
      state.requestFocus()
    }

    assertThat(requestObservationWindowCompleted.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    assertThat(error.get()).isNull()
    assertThat(autoFocusOperations).isEmpty()

    _composeRule.runOnIdle { showPreview.value = false }
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      state.previewResolution.value == IntSize.Zero &&
        (activeCameraThreads() - initialCameraThreads).isEmpty() &&
        (activeAnalysisThreads() - initialAnalysisThreads).isEmpty()
    }
  }

  @Test
  fun withoutFrameProcessor_usesCameraThreadWithoutAnalysisThread() {
    assumeCameraAvailable()
    val initialCameraThreads = activeCameraThreads()
    val initialAnalysisThreads = activeAnalysisThreads()
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        onError = error::set,
      )
    }

    waitForPreview(state, error)

    assertThat(error.get()).isNull()
    assertThat(state.previewResolution.value).isNotEqualTo(IntSize.Zero)
    assertThat(activeCameraThreads() - initialCameraThreads).isNotEmpty()
    assertThat(activeAnalysisThreads() - initialAnalysisThreads).isEmpty()
  }

  @Test
  fun cameraPreview_appliesContentTransformToPreviewSizedTextureView() {
    assumeCameraAvailable()
    val cameraInfo = Camera.CameraInfo().also { Camera.getCameraInfo(0, it) }
    val rotationDegrees = calculateCameraFrameRotation(cameraInfo, Surface.ROTATION_0)
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        displayRotation = Surface.ROTATION_0,
        onError = error::set,
      )
    }
    waitForPreview(state, error)

    _composeRule.runOnIdle {
      val textureView = checkNotNull(findTextureView(_composeRule.activity.window.decorView))
      val previewSize = IntSize(textureView.width, textureView.height)
      val geometry = checkNotNull(
        calculatePreviewGeometry(
          bufferSize = state.previewResolution.value,
          rotationDegrees = rotationDegrees,
          previewSize = previewSize,
          contentScale = ContentScale.Crop,
        ),
      )
      val expectedValues = FloatArray(9).also(createTextureViewTransform(geometry)::getValues)
      val actualValues = FloatArray(9).also(textureView.getTransform(Matrix())::getValues)

      assertThat(error.get()).isNull()
      assertThat(textureView.width).isEqualTo(textureView.height)
      assertThat(textureView.isOpaque).isFalse()
      actualValues.indices.forEach { index ->
        assertThat(actualValues[index]).isWithin(0.01f).of(expectedValues[index])
      }
    }
  }

  @Test
  fun frameProcessorCallbackChange_doesNotRestartSession() {
    assumeCameraAvailable()
    val firstFrame = CountDownLatch(1)
    val updatedFrame = CountDownLatch(1)
    val callback = mutableStateOf<(CameraFrame.Preview) -> Unit>({ firstFrame.countDown() })
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        onError = error::set,
        frameProcessor = FrameProcessor.Preview(callback.value),
      )
    }
    assertThat(firstFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    val sessionIdentity = checkNotNull(state.currentSessionIdentity())

    _composeRule.runOnIdle { callback.value = { updatedFrame.countDown() } }
    _composeRule.waitForIdle()

    assertThat(updatedFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    assertThat(error.get()).isNull()
    assertThat(state.currentSessionIdentity()).isSameInstanceAs(sessionIdentity)
  }

  @Test
  fun localContextChange_keepsAttachedTextureViewSession() {
    assumeCameraAvailable()
    val baseContext = _composeRule.activity
    val previewContext = mutableStateOf<Context>(baseContext)
    val contextChanged = AtomicBoolean()
    val framesAfterContextChange = CountDownLatch(5)
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      val currentContext = previewContext.value
      CompositionLocalProvider(LocalContext provides currentContext) {
        CameraPreview(
          modifier = Modifier.size(240.dp),
          state = state,
          onError = error::set,
          frameProcessor = FrameProcessor.Preview { frame ->
            if (contextChanged.get() && state.isFrameTransformCurrent(frame.transformToken)) {
              framesAfterContextChange.countDown()
            }
          },
        )
      }
      SideEffect { contextChanged.set(currentContext !== baseContext) }
    }
    waitForPreview(state, error)
    val sessionIdentity = checkNotNull(state.currentSessionIdentity())

    _composeRule.runOnIdle { previewContext.value = ContextWrapper(baseContext) }
    _composeRule.waitForIdle()

    assertThat(framesAfterContextChange.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    assertThat(error.get()).isNull()
    assertThat(state.currentSessionIdentity()).isSameInstanceAs(sessionIdentity)
  }

  @Test
  fun sampledFrameProcessorChange_updatesIntervalAndCallbackWithoutRestartingSession() {
    assumeCameraAvailable()
    val oldFrameCount = AtomicInteger()
    val updatedFrame = CountDownLatch(1)
    val processor = mutableStateOf<FrameProcessor>(
      FrameProcessor.PreviewSampled(intervalMillis = 60_000) { oldFrameCount.incrementAndGet() },
    )
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        onError = error::set,
        frameProcessor = processor.value,
      )
    }
    waitForPreview(state, error)
    val sessionIdentity = checkNotNull(state.currentSessionIdentity())

    _composeRule.runOnIdle {
      processor.value = FrameProcessor.PreviewSampled(intervalMillis = 1) { updatedFrame.countDown() }
    }
    _composeRule.waitForIdle()

    assertThat(updatedFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    assertThat(oldFrameCount.get()).isEqualTo(0)
    assertThat(error.get()).isNull()
    assertThat(state.currentSessionIdentity()).isSameInstanceAs(sessionIdentity)
  }

  @Test
  fun frameProcessorModeChange_restartsSession() {
    assumeCameraAvailable()
    val processor = mutableStateOf<FrameProcessor>(FrameProcessor.None)
    val frameReceived = CountDownLatch(1)
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        onError = error::set,
        frameProcessor = processor.value,
      )
    }
    waitForPreview(state, error)
    val initialSessionIdentity = checkNotNull(state.currentSessionIdentity())

    _composeRule.runOnIdle {
      processor.value = FrameProcessor.Preview { frameReceived.countDown() }
    }
    _composeRule.waitForIdle()

    assertThat(frameReceived.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    assertThat(error.get()).isNull()
    assertThat(state.currentSessionIdentity()).isNotSameInstanceAs(initialSessionIdentity)
  }

  @Test
  fun retry_serializesAnalysisCallbacksAcrossControllerGenerations() {
    assumeCameraAvailable()
    val initialCameraThreads = activeCameraThreads()
    val firstCallbackStarted = CountDownLatch(1)
    val releaseFirstCallback = CountDownLatch(1)
    val nextCallbackStarted = CountDownLatch(1)
    val callbackCount = AtomicInteger()
    val activeCallbacks = AtomicInteger()
    val overlappingCallbacks = AtomicBoolean()
    val callbackThreads = CopyOnWriteArrayList<Thread>()
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        onError = error::set,
        frameProcessor = FrameProcessor.Preview {
          callbackThreads += Thread.currentThread()
          if (activeCallbacks.incrementAndGet() > 1) overlappingCallbacks.set(true)
          try {
            if (callbackCount.incrementAndGet() == 1) {
              firstCallbackStarted.countDown()
              check(releaseFirstCallback.await(FRAME_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS))
            } else {
              nextCallbackStarted.countDown()
            }
          } finally {
            activeCallbacks.decrementAndGet()
          }
        },
      )
    }

    lateinit var initialSessionIdentity: CameraFrameTransformIdentity
    try {
      assertThat(firstCallbackStarted.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
      _composeRule.runOnIdle {
        initialSessionIdentity = checkNotNull(state.currentSessionIdentity())
        state.retry()
      }
      assertThat(error.get()).isNull()
      assertThat(nextCallbackStarted.await(300, TimeUnit.MILLISECONDS)).isFalse()
    } finally {
      releaseFirstCallback.countDown()
    }

    _composeRule.waitUntil(timeoutMillis = FRAME_TIMEOUT_SECONDS * 1_000) {
      val currentSessionIdentity = state.currentSessionIdentity()
      error.get() != null || (
        currentSessionIdentity != null &&
          currentSessionIdentity !== initialSessionIdentity &&
          state.isPreviewFrameAvailable()
      )
    }

    assertThat(nextCallbackStarted.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    assertThat(callbackCount.get()).isAtLeast(2)
    assertThat(overlappingCallbacks.get()).isFalse()
    assertThat(callbackThreads.distinct()).hasSize(1)
    assertThat(activeCameraThreads() - initialCameraThreads).hasSize(1)
    assertThat(error.get()).isNull()
  }

  @Test
  fun mirrorModeChange_updatesTransformWithoutRestartingSession() {
    assumeCameraAvailable()
    val mirrorMode = mutableStateOf(CameraMirrorMode.AUTO)
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        mirrorMode = mirrorMode.value,
        onError = error::set,
      )
    }
    waitForPreview(state, error)

    lateinit var initialSessionIdentity: CameraFrameTransformIdentity
    lateinit var initialTransformIdentity: CameraFrameTransformIdentity
    _composeRule.runOnIdle {
      initialSessionIdentity = checkNotNull(state.currentSessionIdentity())
      initialTransformIdentity = checkNotNull(state.currentTransformIdentity())
      mirrorMode.value = CameraMirrorMode.ON
    }
    _composeRule.waitForIdle()
    _composeRule.runOnIdle { mirrorMode.value = CameraMirrorMode.OFF }
    _composeRule.waitForIdle()

    _composeRule.runOnIdle {
      assertThat(error.get()).isNull()
      assertThat(state.currentSessionIdentity()).isSameInstanceAs(initialSessionIdentity)
      assertThat(state.currentTransformIdentity()).isNotSameInstanceAs(initialTransformIdentity)
    }
  }

  @Test
  fun layoutChange_publishesFrameWithNewValidTransform() {
    assumeCameraAvailable()
    val previewSize = mutableStateOf(240.dp)
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()
    val firstToken = AtomicReference<CameraFrameTransformToken?>()
    val firstFrame = CountDownLatch(1)
    val changedFrame = CountDownLatch(1)

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(previewSize.value),
        state = state,
        onError = error::set,
        frameProcessor = FrameProcessor.Preview { frame ->
          if (state.createTransformToPreview(frame) != null) {
            val token = frame.transformToken
            val initialToken = firstToken.get()
            if (initialToken == null) {
              if (firstToken.compareAndSet(null, token)) firstFrame.countDown()
            } else if (!initialToken.isSameTransform(token)) {
              changedFrame.countDown()
            }
          }
        },
      )
    }

    assertWithMessage("布局变化前未收到有效帧坐标变换")
      .that(firstFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS))
      .isTrue()
    _composeRule.runOnIdle { previewSize.value = 280.dp }
    _composeRule.waitForIdle()

    assertWithMessage("布局变化后未收到新的有效帧坐标变换")
      .that(changedFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS))
      .isTrue()
    assertThat(error.get()).isNull()
  }

  @Test
  fun layoutChange_lifecycleRestartSelectsPreviewSizeFromLatestLayout() {
    assumeCameraAvailable()
    val cameraInfo = Camera.CameraInfo().also { Camera.getCameraInfo(0, it) }
    val rotationDegrees = calculateCameraFrameRotation(cameraInfo, Surface.ROTATION_0)
    val supportedPreviewSizes = supportedPreviewSizes(0)
    val normalizedRotation = normalizeRotation(rotationDegrees)
    val isQuarterTurn = normalizedRotation == 90 || normalizedRotation == 270
    val initialLayoutSize = if (isQuarterTurn) IntSize(180, 320) else IntSize(320, 180)
    val latestLayoutSize = if (isQuarterTurn) IntSize(240, 320) else IntSize(320, 240)
    val initialExpected = choosePreviewSize(supportedPreviewSizes, initialLayoutSize, rotationDegrees)
    val latestExpected = choosePreviewSize(supportedPreviewSizes, latestLayoutSize, rotationDegrees)
    assumeTrue("Camera must support different preview sizes for the tested aspect ratios.", initialExpected != latestExpected)

    val lifecycleOwner = FakeLifecycleOwner()
    val layoutSize = mutableStateOf(initialLayoutSize)
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()
    _composeRule.runOnUiThread { lifecycleOwner.start() }

    _composeRule.setContent {
      val size = layoutSize.value
      CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
        CameraPreview(
          modifier = Modifier.size(size.width.dp, size.height.dp),
          state = state,
          displayRotation = Surface.ROTATION_0,
          onError = error::set,
        )
      }
    }
    waitForPreview(state, error)

    lateinit var initialSessionIdentity: CameraFrameTransformIdentity
    _composeRule.runOnIdle {
      initialSessionIdentity = checkNotNull(state.currentSessionIdentity())
      assertThat(state.previewResolution.value).isEqualTo(initialExpected)
      layoutSize.value = latestLayoutSize
    }
    _composeRule.waitForIdle()
    _composeRule.runOnIdle {
      assertThat(state.currentSessionIdentity()).isSameInstanceAs(initialSessionIdentity)
      assertThat(state.previewResolution.value).isEqualTo(initialExpected)
    }

    _composeRule.runOnUiThread { lifecycleOwner.stop() }
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      error.get() != null || state.previewResolution.value == IntSize.Zero
    }
    _composeRule.runOnUiThread { lifecycleOwner.start() }
    _composeRule.waitUntil(timeoutMillis = FRAME_TIMEOUT_SECONDS * 1_000) {
      val currentSessionIdentity = state.currentSessionIdentity()
      error.get() != null || (
        currentSessionIdentity != null &&
          currentSessionIdentity !== initialSessionIdentity &&
          state.createCurrentTextureViewTransform(currentSessionIdentity) != null
      )
    }

    assertThat(error.get()).isNull()
    assertThat(state.previewResolution.value).isEqualTo(latestExpected)
  }

  @Test
  fun missingCameraId_reportsErrorWithoutFallback() {
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        cameraId = "missing-camera-id",
        onError = { failure -> error.compareAndSet(null, failure) },
      )
    }
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) { error.get() != null }

    assertThat(error.get()).isInstanceOf(CameraPreviewException::class.java)
    assertThat((error.get() as CameraPreviewException).reason)
      .isEqualTo(CameraPreviewException.Reason.CAMERA_NOT_FOUND)
    assertThat(state.failure.value).isSameInstanceAs(error.get())
    assertThat(state.previewResolution.value).isEqualTo(IntSize.Zero)
  }

  @Test
  fun retry_republishesSameDeviceEnumerationFailure() {
    val state = CameraPreviewState()
    val devicesState = CameraDevicesState()
    val failure = IllegalStateException("enumeration")
    val errorCount = AtomicInteger()
    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        devicesState = devicesState,
        onError = { error -> if (error === failure) errorCount.incrementAndGet() },
      )
    }

    _composeRule.runOnIdle { devicesState.publishError(failure) }
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      state.failure.value === failure && errorCount.get() == 1
    }

    _composeRule.runOnIdle { state.retry() }
    _composeRule.waitForIdle()
    _composeRule.runOnIdle { assertThat(state.failure.value).isNull() }
    _composeRule.runOnIdle { devicesState.publishError(failure) }
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      state.failure.value === failure && errorCount.get() == 2
    }
  }

  @Test
  fun deviceRefreshFailure_afterSuccessfulEnumerationUpdatesAndClearsPreviewFailure() {
    val state = CameraPreviewState()
    val devicesState = CameraDevicesState()
    val failure = IllegalStateException("enumeration")
    val errorCount = AtomicInteger()
    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(0.dp),
        state = state,
        devicesState = devicesState,
        onError = { error -> if (error === failure) errorCount.incrementAndGet() },
      )
    }

    _composeRule.runOnIdle { devicesState.publishDevices(emptyList()) }
    _composeRule.waitForIdle()
    _composeRule.runOnIdle { devicesState.publishError(failure) }
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      state.failure.value === failure && errorCount.get() == 1
    }

    _composeRule.runOnIdle { devicesState.publishDevices(emptyList()) }
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      state.failure.value == null
    }

    assertThat(devicesState.error.value).isNull()
    assertThat(errorCount.get()).isEqualTo(1)
  }

  @Test
  fun deviceRefreshFailure_displayRotationChangePreservesPreviewFailure() {
    val state = CameraPreviewState()
    val devicesState = CameraDevicesState()
    val displayRotation = mutableStateOf(Surface.ROTATION_0)
    val failure = IllegalStateException("enumeration")
    val errorCount = AtomicInteger()
    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(0.dp),
        state = state,
        devicesState = devicesState,
        displayRotation = displayRotation.value,
        onError = { error -> if (error === failure) errorCount.incrementAndGet() },
      )
    }

    _composeRule.runOnIdle { devicesState.publishDevices(emptyList()) }
    _composeRule.waitForIdle()
    _composeRule.runOnIdle { devicesState.publishError(failure) }
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      state.failure.value === failure && errorCount.get() == 1
    }

    _composeRule.runOnIdle { displayRotation.value = Surface.ROTATION_90 }
    _composeRule.waitForIdle()

    assertThat(state.failure.value).isSameInstanceAs(failure)
    assertThat(devicesState.error.value).isSameInstanceAs(failure)
    assertThat(errorCount.get()).isEqualTo(1)
  }

  @Test
  fun exactCameraId_startsSelectedPreview() {
    assumeCameraAvailable()
    val cameraId = (Camera.getNumberOfCameras() - 1).toString()
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        cameraId = cameraId,
        onError = error::set,
      )
    }

    waitForPreview(state, error)

    assertThat(error.get()).isNull()
    assertThat(state.previewResolution.value).isNotEqualTo(IntSize.Zero)
  }

  @Test
  fun retry_restartsPreviewWithNewTransform() {
    assumeCameraAvailable()
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()
    val firstToken = AtomicReference<CameraFrameTransformToken?>()
    val firstFrame = CountDownLatch(1)
    val retryStarted = AtomicBoolean()
    val retriedFrame = CountDownLatch(1)

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        onError = error::set,
        frameProcessor = FrameProcessor.Preview { frame ->
          val token = frame.transformToken
          if (state.isFrameTransformCurrent(token)) {
            val initialToken = firstToken.get()
            if (initialToken == null) {
              if (firstToken.compareAndSet(null, token)) firstFrame.countDown()
            } else if (retryStarted.get() && !initialToken.isSameTransform(token)) {
              retriedFrame.countDown()
            }
          }
        },
      )
    }

    assertThat(firstFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    _composeRule.runOnIdle {
      retryStarted.set(true)
      state.retry()
    }
    _composeRule.waitForIdle()

    assertThat(retriedFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    assertThat(error.get()).isNull()
  }

  @Test
  fun rapidLifecycleRestart_reopensSessionWithoutError() {
    assumeCameraAvailable()
    val lifecycleOwner = FakeLifecycleOwner()
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()
    _composeRule.runOnUiThread { lifecycleOwner.start() }

    _composeRule.setContent {
      CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
        CameraPreview(
          modifier = Modifier.size(240.dp),
          state = state,
          onError = error::set,
        )
      }
    }
    waitForPreview(state, error)
    var previousSessionIdentity = checkNotNull(state.currentSessionIdentity())

    repeat(3) {
      _composeRule.runOnUiThread {
        lifecycleOwner.stop()
        lifecycleOwner.start()
      }
      _composeRule.waitUntil(timeoutMillis = FRAME_TIMEOUT_SECONDS * 1_000) {
        val currentSessionIdentity = state.currentSessionIdentity()
        error.get() != null || (
          currentSessionIdentity != null &&
            currentSessionIdentity !== previousSessionIdentity &&
            state.createCurrentTextureViewTransform(currentSessionIdentity) != null
        )
      }
      previousSessionIdentity = checkNotNull(state.currentSessionIdentity())
    }

    assertThat(error.get()).isNull()
    assertThat(state.previewResolution.value).isNotEqualTo(IntSize.Zero)
  }

  @Test
  fun destroyedLifecycleOwnerHandoff_reusesWorkersWithoutCameraOpenFailure() {
    assumeCameraAvailable()
    val initialCameraThreads = activeCameraThreads()
    val initialLifecycleOwner = FakeLifecycleOwner()
    val lifecycleOwner = mutableStateOf(initialLifecycleOwner)
    val firstCallbackStarted = CountDownLatch(1)
    val releaseFirstCallback = CountDownLatch(1)
    val nextCallbackStarted = CountDownLatch(1)
    val callbackCount = AtomicInteger()
    val activeCallbacks = AtomicInteger()
    val overlappingCallbacks = AtomicBoolean()
    val callbackThreads = CopyOnWriteArrayList<Thread>()
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()
    _composeRule.runOnUiThread { initialLifecycleOwner.start() }

    _composeRule.setContent {
      CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner.value) {
        CameraPreview(
          modifier = Modifier.size(240.dp),
          state = state,
          onError = { failure -> error.compareAndSet(null, failure) },
          frameProcessor = FrameProcessor.Preview {
            callbackThreads += Thread.currentThread()
            if (activeCallbacks.incrementAndGet() > 1) overlappingCallbacks.set(true)
            try {
              if (callbackCount.incrementAndGet() == 1) {
                firstCallbackStarted.countDown()
                check(releaseFirstCallback.await(FRAME_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS))
              } else {
                nextCallbackStarted.countDown()
              }
            } finally {
              activeCallbacks.decrementAndGet()
            }
          },
        )
      }
    }
    waitForPreview(state, error)
    assertThat(firstCallbackStarted.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    var previousSessionIdentity = checkNotNull(state.currentSessionIdentity())

    val handoffLifecycleOwner = FakeLifecycleOwner()
    try {
      _composeRule.runOnUiThread {
        handoffLifecycleOwner.start()
        initialLifecycleOwner.destroy()
        lifecycleOwner.value = handoffLifecycleOwner
      }
      assertThat(nextCallbackStarted.await(300, TimeUnit.MILLISECONDS)).isFalse()
      assertThat(error.get()).isNull()
    } finally {
      releaseFirstCallback.countDown()
    }
    _composeRule.waitUntil(timeoutMillis = FRAME_TIMEOUT_SECONDS * 1_000) {
      val currentSessionIdentity = state.currentSessionIdentity()
      error.get() != null || (
        currentSessionIdentity != null &&
          currentSessionIdentity !== previousSessionIdentity &&
          state.createCurrentTextureViewTransform(currentSessionIdentity) != null
      )
    }
    assertThat(nextCallbackStarted.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    previousSessionIdentity = checkNotNull(state.currentSessionIdentity())

    repeat(4) {
      val nextLifecycleOwner = FakeLifecycleOwner()
      _composeRule.runOnUiThread {
        nextLifecycleOwner.start()
        lifecycleOwner.value = nextLifecycleOwner
      }
      _composeRule.waitUntil(timeoutMillis = FRAME_TIMEOUT_SECONDS * 1_000) {
        val currentSessionIdentity = state.currentSessionIdentity()
        error.get() != null || (
          currentSessionIdentity != null &&
            currentSessionIdentity !== previousSessionIdentity &&
            state.createCurrentTextureViewTransform(currentSessionIdentity) != null
        )
      }
      previousSessionIdentity = checkNotNull(state.currentSessionIdentity())
    }

    assertThat(error.get()).isNull()
    assertThat(state.previewResolution.value).isNotEqualTo(IntSize.Zero)
    assertThat(overlappingCallbacks.get()).isFalse()
    assertThat(callbackThreads.distinct()).hasSize(1)
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      (activeCameraThreads() - initialCameraThreads).size == 1
    }
  }

  @Test
  fun surfaceDestroyed_transfersReleaseToCameraThread() {
    val initialCameraThreads = activeCameraThreads()
    val lifecycleOwner = FakeLifecycleOwner()
    val releaseCompleted = CountDownLatch(1)
    val releaseThreadName = AtomicReference<String?>()
    val receivedError = AtomicReference<Throwable?>()
    val surfaceTexture = RecordingSurfaceTexture {
      releaseThreadName.set(Thread.currentThread().name)
      releaseCompleted.countDown()
    }
    lateinit var controller: CameraPreviewController
    lateinit var runtime: CameraPreviewRuntime

    _composeRule.runOnUiThread {
      val textureView = CameraTextureView(_composeRule.activity)
      runtime = CameraPreviewRuntime()
      controller = CameraPreviewController(
        runtimeLease = runtime.acquire(),
        lifecycleOwner = lifecycleOwner,
        textureView = textureView,
        cameraId = null,
        displayRotation = Surface.ROTATION_0,
        previewViewSizeProvider = { IntSize(240, 240) },
        transformIdentityProvider = { null },
        onSessionStarted = { _, _, _, _ -> error("Camera session must not start.") },
        onPreviewFrameAvailable = { error("Preview frame must not be published.") },
        frameProcessor = ActiveFrameProcessor.None,
        captureSampledFrame = { _, _ -> null },
        onSessionFailure = receivedError::set,
        onError = receivedError::set,
        onSessionClosed = {},
      )
      controller.start()
      val listener = checkNotNull(textureView.surfaceTextureListener)
      listener.onSurfaceTextureAvailable(surfaceTexture, 240, 240)

      assertThat(listener.onSurfaceTextureDestroyed(surfaceTexture)).isFalse()
    }

    assertThat(releaseCompleted.await(CLEANUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue()
    assertThat(releaseThreadName.get()).isEqualTo(CAMERA_OPERATION_THREAD_NAME)
    assertThat(receivedError.get()).isNull()

    _composeRule.runOnUiThread {
      controller.close()
      runtime.close()
    }
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      (activeCameraThreads() - initialCameraThreads).isEmpty()
    }
  }

  @Test
  fun close_removesSurfaceListenerAfterCameraThreadStops() {
    val initialCameraThreads = activeCameraThreads()
    val lifecycleOwner = FakeLifecycleOwner()
    val receivedError = AtomicReference<Throwable?>()
    lateinit var textureView: CameraTextureView

    _composeRule.runOnUiThread {
      textureView = CameraTextureView(_composeRule.activity)
      val runtime = CameraPreviewRuntime()
      val controller = CameraPreviewController(
        runtimeLease = runtime.acquire(),
        lifecycleOwner = lifecycleOwner,
        textureView = textureView,
        cameraId = null,
        displayRotation = Surface.ROTATION_0,
        previewViewSizeProvider = { IntSize(240, 240) },
        transformIdentityProvider = { null },
        onSessionStarted = { _, _, _, _ -> error("Camera session must not start.") },
        onPreviewFrameAvailable = { error("Preview frame must not be published.") },
        frameProcessor = ActiveFrameProcessor.None,
        captureSampledFrame = { _, _ -> null },
        onSessionFailure = receivedError::set,
        onError = receivedError::set,
        onSessionClosed = {},
      )
      controller.start()
      val listener = checkNotNull(textureView.surfaceTextureListener)

      controller.close()
      runtime.close()

      assertThat(textureView.surfaceTextureListener).isSameInstanceAs(listener)
    }

    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      (activeCameraThreads() - initialCameraThreads).isEmpty()
    }
    _composeRule.runOnUiThread { assertThat(textureView.surfaceTextureListener).isNull() }
    assertThat(receivedError.get()).isNull()
  }

  @Test
  fun close_afterCameraThreadTerminatesUsesFallbackCleanup() {
    val initialCameraThreads = activeCameraThreads()
    val lifecycleOwner = FakeLifecycleOwner()
    val receivedErrors = CopyOnWriteArrayList<Throwable>()
    lateinit var runtime: CameraPreviewRuntime
    lateinit var controller: CameraPreviewController
    lateinit var cameraThread: Thread
    lateinit var textureView: CameraTextureView

    _composeRule.runOnUiThread {
      textureView = CameraTextureView(_composeRule.activity)
      runtime = CameraPreviewRuntime()
      val runtimeLease = runtime.acquire()
      cameraThread = runtimeLease.cameraHandler.looper.thread
      controller = CameraPreviewController(
        runtimeLease = runtimeLease,
        lifecycleOwner = lifecycleOwner,
        textureView = textureView,
        cameraId = null,
        displayRotation = Surface.ROTATION_0,
        previewViewSizeProvider = { IntSize(240, 240) },
        transformIdentityProvider = { null },
        onSessionStarted = { _, _, _, _ -> error("Camera session must not start.") },
        onPreviewFrameAvailable = { error("Preview frame must not be published.") },
        frameProcessor = ActiveFrameProcessor.None,
        captureSampledFrame = { _, _ -> null },
        onSessionFailure = receivedErrors::add,
        onError = receivedErrors::add,
        onSessionClosed = {},
      )
      controller.start()
      runtimeLease.cameraHandler.looper.quitSafely()
      runtime.close()
    }
    cameraThread.join(CLEANUP_TIMEOUT_MILLIS)
    assertThat(cameraThread.isAlive).isFalse()

    _composeRule.runOnUiThread { controller.close() }
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      textureView.surfaceTextureListener == null
    }

    assertThat(activeCameraThreads() - initialCameraThreads).isEmpty()
    assertThat(receivedErrors).hasSize(1)
    assertThat(receivedErrors.single()).isInstanceOf(IllegalStateException::class.java)
    assertThat(receivedErrors.single()).hasMessageThat().contains("camera operation thread")
  }

  @Test
  fun destroyedLifecycleOwner_releasesSessionAndWorkerThreads() {
    assumeCameraAvailable()
    val initialCameraThreads = activeCameraThreads()
    val initialAnalysisThreads = activeAnalysisThreads()
    val lifecycleOwner = FakeLifecycleOwner()
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()
    val frameReceived = CountDownLatch(1)
    _composeRule.runOnUiThread { lifecycleOwner.start() }

    _composeRule.setContent {
      CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
        CameraPreview(
          modifier = Modifier.size(240.dp),
          state = state,
          onError = error::set,
          frameProcessor = FrameProcessor.Preview { frameReceived.countDown() },
        )
      }
    }

    assertThat(frameReceived.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    _composeRule.runOnUiThread { lifecycleOwner.destroy() }
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      state.previewResolution.value == IntSize.Zero &&
        (activeCameraThreads() - initialCameraThreads).isEmpty() &&
        (activeAnalysisThreads() - initialAnalysisThreads).isEmpty()
    }

    assertThat(error.get()).isNull()
    assertThat(state.previewResolution.value).isEqualTo(IntSize.Zero)
  }

  @Test
  fun destroyedLifecycleOwner_afterControllerLeavesForZeroSize_releasesWorkerThreads() {
    assumeCameraAvailable()
    val initialCameraThreads = activeCameraThreads()
    val initialAnalysisThreads = activeAnalysisThreads()
    val lifecycleOwner = FakeLifecycleOwner()
    val hasPreviewSize = mutableStateOf(true)
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()
    val frameReceived = CountDownLatch(1)
    _composeRule.runOnUiThread { lifecycleOwner.start() }

    _composeRule.setContent {
      CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
        CameraPreview(
          modifier = Modifier.size(if (hasPreviewSize.value) 240.dp else 0.dp),
          state = state,
          onError = error::set,
          frameProcessor = FrameProcessor.Preview { frameReceived.countDown() },
        )
      }
    }

    assertThat(frameReceived.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    _composeRule.runOnIdle { hasPreviewSize.value = false }
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      state.previewResolution.value == IntSize.Zero
    }
    assertThat(activeCameraThreads() - initialCameraThreads).isNotEmpty()
    assertThat(activeAnalysisThreads() - initialAnalysisThreads).isNotEmpty()

    _composeRule.runOnUiThread { lifecycleOwner.destroy() }
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      (activeCameraThreads() - initialCameraThreads).isEmpty() &&
        (activeAnalysisThreads() - initialAnalysisThreads).isEmpty()
    }

    assertThat(error.get()).isNull()
  }

  private fun assumeCameraAvailable() {
    assumeTrue(Camera.getNumberOfCameras() > 0)
  }

  private fun findCameraIdUsingFocusMode(focusMode: String): String? {
    repeat(Camera.getNumberOfCameras()) { cameraId ->
      val camera = try {
        Camera.open(cameraId)
      } catch (_: Exception) {
        return@repeat
      }
      val selectedFocusMode = try {
        chooseFocusMode(camera.parameters.supportedFocusModes)
      } catch (_: Exception) {
        null
      } finally {
        camera.release()
      }
      if (selectedFocusMode == focusMode) return cameraId.toString()
    }
    return null
  }

  private fun supportedPreviewSizes(cameraId: Int): List<IntSize> {
    val camera = Camera.open(cameraId)
    return try {
      camera.parameters.supportedPreviewSizes.map { size -> IntSize(size.width, size.height) }
    } finally {
      camera.release()
    }
  }

  private fun waitForPreview(state: CameraPreviewState, error: AtomicReference<Throwable?>) {
    _composeRule.waitUntil(timeoutMillis = FRAME_TIMEOUT_SECONDS * 1_000) {
      val sessionIdentity = state.currentSessionIdentity()
      error.get() != null || (
        state.previewResolution.value != IntSize.Zero &&
          sessionIdentity != null &&
          state.createCurrentTextureViewTransform(sessionIdentity) != null
      )
    }
  }

  private fun activeAnalysisThreads(): Set<Thread> {
    return activeThreads(CAMERA_ANALYSIS_THREAD_NAME)
  }

  private fun activeCameraThreads(): Set<Thread> {
    return activeThreads(CAMERA_OPERATION_THREAD_NAME)
  }

  private fun activeThreads(name: String): Set<Thread> {
    return Thread.getAllStackTraces().keys.filterTo(linkedSetOf()) { thread ->
      thread.isAlive && thread.name == name
    }
  }

  private fun findTextureView(view: View): TextureView? {
    if (view is TextureView) return view
    if (view !is ViewGroup) return null
    repeat(view.childCount) { index ->
      findTextureView(view.getChildAt(index))?.also { return it }
    }
    return null
  }

  private data class FrameResult(
    val width: Int,
    val height: Int,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val rotationDegrees: Int,
    val threadName: String,
  )

  private data class SampledFrameResult(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val threadName: String,
  )

  private companion object {
    const val FRAME_TIMEOUT_SECONDS = 15L
    const val CLEANUP_TIMEOUT_MILLIS = 5_000L
    const val NO_PERIODIC_FOCUS_WINDOW_MILLIS = 2_500L
    const val FOCUS_REQUEST_OBSERVATION_WINDOW_MILLIS = 500L
  }
}

private class RecordingSurfaceTexture(
  private val onRelease: () -> Unit,
) : SurfaceTexture(0) {
  override fun release() {
    super.release()
    onRelease()
  }
}

private enum class AutoFocusOperation {
  CANCEL,
  AUTO_FOCUS,
}

private class RecordingCameraAutoFocusOperations(
  private val isPreviewFrameAvailable: () -> Boolean,
) : CameraAutoFocusOperations {
  val calls = CopyOnWriteArrayList<AutoFocusOperation>()
  val threadNames = CopyOnWriteArrayList<String>()
  val previewFrameAvailableAtAutoFocus = CopyOnWriteArrayList<Boolean>()
  val autoFocusCount = AtomicInteger()
  val firstAutoFocusAtMillis = AtomicLong()

  override fun cancelAutoFocus() {
    calls += AutoFocusOperation.CANCEL
    threadNames += Thread.currentThread().name
  }

  override fun autoFocus(onComplete: () -> Unit) {
    calls += AutoFocusOperation.AUTO_FOCUS
    threadNames += Thread.currentThread().name
    previewFrameAvailableAtAutoFocus += isPreviewFrameAvailable()
    autoFocusCount.incrementAndGet()
    firstAutoFocusAtMillis.compareAndSet(0L, SystemClock.elapsedRealtime())
    onComplete()
  }
}

private class FakeLifecycleOwner : LifecycleOwner {
  private val _registry = LifecycleRegistry(this)

  override val lifecycle: Lifecycle get() = _registry

  fun start() {
    if (_registry.currentState == Lifecycle.State.INITIALIZED) {
      _registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }
    _registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
  }

  fun stop() {
    _registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
  }

  fun destroy() {
    _registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
  }
}
