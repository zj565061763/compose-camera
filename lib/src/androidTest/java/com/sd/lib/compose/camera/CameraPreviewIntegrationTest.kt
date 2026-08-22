package com.sd.lib.compose.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.os.Looper
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
@ExperimentalCamera2Interop
class CameraPreviewIntegrationTest {
  private val _composeRule = createAndroidComposeRule<CameraPreviewTestActivity>()

  @get:Rule
  val rules: TestRule = RuleChain
    .outerRule(GrantPermissionRule.grant(Manifest.permission.CAMERA))
    .around(_composeRule)

  @Test
  fun frameFormat_defaultsToYuvAndCanSwitchToRgba() {
    assumeFrontOrBackCameraAvailable()

    val requestedFormat = mutableStateOf(CameraFrameFormat.YUV_420_888)
    val error = AtomicReference<Throwable?>(null)
    val yuvPlaneCount = AtomicInteger(-1)
    val rgbaPlaneCount = AtomicInteger(-1)
    val yuvFrame = CountDownLatch(1)
    val rgbaFrame = CountDownLatch(1)

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        frameFormat = requestedFormat.value,
        onError = { throwable ->
          error.compareAndSet(null, throwable)
          yuvFrame.countDown()
          rgbaFrame.countDown()
        },
        onFrame = { frame ->
          when (frame.image.format) {
            ImageFormat.YUV_420_888 -> {
              if (yuvPlaneCount.compareAndSet(-1, frame.image.planes.size)) {
                yuvFrame.countDown()
              }
            }

            PixelFormat.RGBA_8888 -> {
              if (rgbaPlaneCount.compareAndSet(-1, frame.image.planes.size)) {
                rgbaFrame.countDown()
              }
            }
          }
        },
      )
    }

    assertWithMessage("未收到默认 YUV 帧")
      .that(yuvFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS))
      .isTrue()
    assertThat(error.get()).isNull()
    assertThat(yuvPlaneCount.get()).isEqualTo(3)

    _composeRule.runOnIdle {
      requestedFormat.value = CameraFrameFormat.RGBA_8888
    }
    // 等待参数变化完成重组和旧会话释放后，再开始等待新格式的帧。
    _composeRule.waitForIdle()

    assertWithMessage("切换格式后未收到 RGBA 帧")
      .that(rgbaFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS))
      .isTrue()
    assertThat(error.get()).isNull()
    assertThat(rgbaPlaneCount.get()).isEqualTo(1)
  }

  @Test
  fun withoutFrameCallback_startsPreviewWithoutAnalysisThread() {
    assumeFrontOrBackCameraAvailable()
    val initialAnalysisThreadCount = activeAnalysisThreadCount()
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>(null)

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        onError = error::set,
      )
    }

    _composeRule.waitUntil(timeoutMillis = FRAME_TIMEOUT_SECONDS * 1_000) {
      error.get() != null || state.previewResolution.value != IntSize.Zero
    }

    assertThat(error.get()).isNull()
    assertThat(state.previewResolution.value).isNotEqualTo(IntSize.Zero)
    assertThat(activeAnalysisThreadCount()).isAtMost(initialAnalysisThreadCount)
  }

  @Test
  fun withoutFrameCallback_changingFrameFormatKeepsCurrentSession() {
    assumeFrontOrBackCameraAvailable()
    val requestedFormat = mutableStateOf(CameraFrameFormat.YUV_420_888)
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>(null)

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        frameFormat = requestedFormat.value,
        onError = error::set,
      )
    }

    _composeRule.waitUntil(timeoutMillis = FRAME_TIMEOUT_SECONDS * 1_000) {
      error.get() != null || state.previewResolution.value != IntSize.Zero
    }
    assertThat(error.get()).isNull()

    lateinit var initialTransformIdentity: CameraFrameTransformIdentity
    _composeRule.runOnIdle {
      initialTransformIdentity = checkNotNull(state.currentTransformIdentity())
      requestedFormat.value = CameraFrameFormat.RGBA_8888
    }
    _composeRule.waitForIdle()

    _composeRule.runOnIdle {
      assertThat(error.get()).isNull()
      assertThat(state.previewResolution.value).isNotEqualTo(IntSize.Zero)
      assertThat(state.currentTransformIdentity()).isSameInstanceAs(initialTransformIdentity)
    }
  }

  @Test
  fun devicesInitializationError_isForwardedWithoutStartingSession() {
    val devicesState = CameraDevicesState()
    val expectedError = IllegalStateException("provider initialization failed")
    val actualError = AtomicReference<Throwable?>(null)
    val reportedOnMainThread = AtomicReference<Boolean?>(null)
    val deliveryCount = AtomicInteger(0)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      devicesState.publishError(expectedError)
    }

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        devicesState = devicesState,
        onError = { error ->
          actualError.compareAndSet(null, error)
          reportedOnMainThread.compareAndSet(null, Looper.myLooper() == Looper.getMainLooper())
          deliveryCount.incrementAndGet()
        },
      )
    }

    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      actualError.get() != null
    }

    assertThat(actualError.get()).isSameInstanceAs(expectedError)
    assertThat(reportedOnMainThread.get()).isTrue()
    assertThat(deliveryCount.get()).isEqualTo(1)
  }

  @Test
  fun recoveredDevicesError_isNotForwardedToLaterPreview() {
    val devicesState = CameraDevicesState()
    val deliveryCount = AtomicInteger(0)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      devicesState.publishError(IllegalStateException("transient provider failure"))
      devicesState.publishDevices(emptyList())
    }

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(0.dp),
        devicesState = devicesState,
        onError = { deliveryCount.incrementAndGet() },
      )
    }
    _composeRule.waitForIdle()

    assertThat(deliveryCount.get()).isEqualTo(0)
  }

  @Test
  fun immediateDisposal_releasesAnalysisThreadWithoutLateError() {
    assumeFrontOrBackCameraAvailable()
    val initialAnalysisThreadCount = activeAnalysisThreadCount()
    val showPreview = mutableStateOf(true)
    val error = AtomicReference<Throwable?>(null)

    _composeRule.setContent {
      if (showPreview.value) {
        CameraPreview(
          modifier = Modifier.size(240.dp),
          onError = error::set,
          onFrame = {},
        )
      }
    }

    _composeRule.runOnIdle { showPreview.value = false }
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      activeAnalysisThreadCount() <= initialAnalysisThreadCount
    }

    assertThat(error.get()).isNull()
    assertThat(activeAnalysisThreadCount()).isAtMost(initialAnalysisThreadCount)
  }

  @Test
  fun frameCallbackFailure_afterSessionDisposal_isStillReported() {
    assumeFrontOrBackCameraAvailable()
    val initialAnalysisThreadCount = activeAnalysisThreadCount()
    val showPreview = mutableStateOf(true)
    val state = CameraPreviewState()
    val frameStarted = CountDownLatch(1)
    val releaseFrame = CountDownLatch(1)
    val errorReported = CountDownLatch(1)
    val callbackError = IllegalStateException("frame callback failed after disposal")
    val actualError = AtomicReference<Throwable?>(null)

    _composeRule.setContent {
      if (showPreview.value) {
        CameraPreview(
          modifier = Modifier.size(240.dp),
          state = state,
          onError = { error ->
            actualError.compareAndSet(null, error)
            errorReported.countDown()
          },
          onFrame = {
            frameStarted.countDown()
            if (!releaseFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
              throw IllegalStateException("timed out waiting to release frame callback")
            }
            throw callbackError
          },
        )
      }
    }

    try {
      assertWithMessage("释放会话前未进入帧回调")
        .that(frameStarted.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .isTrue()
      _composeRule.runOnIdle { showPreview.value = false }
      _composeRule.waitForIdle()
      assertThat(state.previewResolution.value).isEqualTo(IntSize.Zero)
    } finally {
      releaseFrame.countDown()
    }

    assertWithMessage("会话释放后丢失了已开始帧回调的异常")
      .that(errorReported.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS))
      .isTrue()
    assertThat(actualError.get()).isSameInstanceAs(callbackError)
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      activeAnalysisThreadCount() <= initialAnalysisThreadCount
    }
  }

  @Test
  fun lifecycleDestroyedWhileComposed_releasesAnalysisThread() {
    assumeFrontOrBackCameraAvailable()
    val initialAnalysisThreadCount = activeAnalysisThreadCount()
    val lifecycleOwner = FakeLifecycleOwner()
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>(null)
    val frameReceived = CountDownLatch(1)
    _composeRule.runOnUiThread { lifecycleOwner.start() }

    _composeRule.setContent {
      CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
        CameraPreview(
          modifier = Modifier.size(240.dp),
          state = state,
          onError = { throwable ->
            error.compareAndSet(null, throwable)
            frameReceived.countDown()
          },
          onFrame = { frameReceived.countDown() },
        )
      }
    }

    assertWithMessage("LifecycleOwner 销毁前未收到分析帧")
      .that(frameReceived.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS))
      .isTrue()
    assertThat(error.get()).isNull()
    assertThat(activeAnalysisThreadCount()).isGreaterThan(initialAnalysisThreadCount)

    _composeRule.runOnUiThread { lifecycleOwner.destroy() }
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      activeAnalysisThreadCount() <= initialAnalysisThreadCount &&
        state.previewResolution.value == IntSize.Zero
    }

    assertThat(error.get()).isNull()
    assertThat(activeAnalysisThreadCount()).isAtMost(initialAnalysisThreadCount)
    assertThat(state.previewResolution.value).isEqualTo(IntSize.Zero)
  }

  @Test
  fun destroyedLifecycleOwner_reportsErrorWithoutStartingPreview() {
    val initialAnalysisThreadCount = activeAnalysisThreadCount()
    val lifecycleOwner = FakeLifecycleOwner()
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>(null)
    _composeRule.runOnUiThread { lifecycleOwner.destroy() }

    _composeRule.setContent {
      CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
        CameraPreview(
          modifier = Modifier.size(240.dp),
          state = state,
          onError = { throwable -> error.compareAndSet(null, throwable) },
          onFrame = {},
        )
      }
    }

    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      error.get() != null
    }

    assertThat(error.get()).isInstanceOf(IllegalStateException::class.java)
    assertThat(error.get()).hasMessageThat().contains("destroyed LifecycleOwner")
    assertThat(state.previewResolution.value).isEqualTo(IntSize.Zero)
    assertThat(activeAnalysisThreadCount()).isAtMost(initialAnalysisThreadCount)
  }

  @Test
  fun exactCameraId_startsPreview() {
    val cameraId = availableCameraIds().firstOrNull()
    assumeTrue(cameraId != null)
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>(null)

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        cameraId = checkNotNull(cameraId),
        onError = error::set,
      )
    }

    _composeRule.waitUntil(timeoutMillis = FRAME_TIMEOUT_SECONDS * 1_000) {
      error.get() != null || state.previewResolution.value != IntSize.Zero
    }

    assertThat(error.get()).isNull()
    assertThat(state.previewResolution.value).isNotEqualTo(IntSize.Zero)
  }

  @Test
  fun missingCameraId_reportsErrorWithoutFallback() {
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>(null)

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        cameraId = "missing-camera-id",
        onError = { throwable -> error.compareAndSet(null, throwable) },
      )
    }

    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      error.get() != null
    }

    assertThat(error.get()).isInstanceOf(CameraPreviewException::class.java)
    assertThat((error.get() as CameraPreviewException).reason)
      .isEqualTo(CameraPreviewException.Reason.CAMERA_NOT_FOUND)
    assertThat(state.previewResolution.value).isEqualTo(IntSize.Zero)
  }

  @Test
  fun changingCameraId_restartsPreviewWithNewTransform() {
    val cameraIds = availableCameraIds()
    assumeTrue(cameraIds.size >= 2)
    val cameraId = mutableStateOf(cameraIds[0])
    val error = AtomicReference<Throwable?>(null)
    val firstToken = AtomicReference<CameraFrameTransformToken?>(null)
    val firstFrame = CountDownLatch(1)
    val switchedFrame = CountDownLatch(1)

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        cameraId = cameraId.value,
        onError = { throwable ->
          error.compareAndSet(null, throwable)
          firstFrame.countDown()
          switchedFrame.countDown()
        },
        onFrame = { frame ->
          val token = frame.transformToken
          val initialToken = firstToken.get()
          if (initialToken == null) {
            if (firstToken.compareAndSet(null, token)) firstFrame.countDown()
          } else if (!initialToken.isSameTransform(token)) {
            switchedFrame.countDown()
          }
        },
      )
    }

    assertWithMessage("切换前未收到分析帧")
      .that(firstFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS))
      .isTrue()
    assertThat(error.get()).isNull()

    _composeRule.runOnIdle {
      cameraId.value = cameraIds[1]
    }
    _composeRule.waitForIdle()

    assertWithMessage("切换 cameraId 后未收到新预览的分析帧")
      .that(switchedFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS))
      .isTrue()
    assertThat(error.get()).isNull()
  }

  @Test
  fun layoutChange_republishesFrameTransform() {
    assumeFrontOrBackCameraAvailable()
    val state = CameraPreviewState()
    val previewSize = mutableStateOf(240.dp)
    val error = AtomicReference<Throwable?>(null)
    val firstToken = AtomicReference<CameraFrameTransformToken?>(null)
    val firstFrame = CountDownLatch(1)
    val changedFrame = CountDownLatch(1)

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(previewSize.value),
        state = state,
        onError = { throwable ->
          error.compareAndSet(null, throwable)
          firstFrame.countDown()
          changedFrame.countDown()
        },
        onFrame = { frame ->
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

    assertWithMessage("布局变化前未发布有效帧坐标变换")
      .that(firstFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS))
      .isTrue()
    assertThat(error.get()).isNull()

    _composeRule.runOnIdle { previewSize.value = 280.dp }
    _composeRule.waitForIdle()

    assertWithMessage("布局变化后未重新发布有效帧坐标变换")
      .that(changedFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS))
      .isTrue()
    assertThat(error.get()).isNull()
  }

  @Test
  fun retry_restartsPreviewWithNewTransform() {
    assumeFrontOrBackCameraAvailable()
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>(null)
    val firstToken = AtomicReference<CameraFrameTransformToken?>(null)
    val firstFrame = CountDownLatch(1)
    val retriedFrame = CountDownLatch(1)

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        onError = { throwable ->
          error.compareAndSet(null, throwable)
          firstFrame.countDown()
          retriedFrame.countDown()
        },
        onFrame = { frame ->
          val token = frame.transformToken
          val initialToken = firstToken.get()
          if (initialToken == null) {
            if (firstToken.compareAndSet(null, token)) firstFrame.countDown()
          } else if (!initialToken.isSameTransform(token)) {
            retriedFrame.countDown()
          }
        },
      )
    }

    assertWithMessage("retry 前未收到分析帧")
      .that(firstFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS))
      .isTrue()
    assertThat(error.get()).isNull()

    _composeRule.runOnIdle { state.retry() }
    _composeRule.waitForIdle()

    assertWithMessage("retry 后未收到新会话的分析帧")
      .that(retriedFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS))
      .isTrue()
    assertThat(error.get()).isNull()
  }

  @Test
  fun cameraDevicesState_publishesCameraXCameraIds() {
    val expectedCameraIds = availableCameraIds()
    lateinit var devicesState: CameraDevicesState

    _composeRule.setContent {
      devicesState = rememberCameraDevicesState()
    }

    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      !devicesState.isLoading.value
    }

    assertThat(devicesState.error.value).isNull()
    assertThat(devicesState.devices.value.map(CameraDeviceInfo::cameraId))
      .containsExactlyElementsIn(expectedCameraIds)
      .inOrder()
  }

  @Test
  fun externalCameraDevicesState_canDrivePreview() {
    assumeFrontOrBackCameraAvailable()
    val previewState = CameraPreviewState()
    val error = AtomicReference<Throwable?>(null)
    lateinit var devicesState: CameraDevicesState

    _composeRule.setContent {
      devicesState = rememberCameraDevicesState()
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = previewState,
        devicesState = devicesState,
        onError = error::set,
      )
    }

    _composeRule.waitUntil(timeoutMillis = FRAME_TIMEOUT_SECONDS * 1_000) {
      error.get() != null || previewState.previewResolution.value != IntSize.Zero
    }

    assertThat(error.get()).isNull()
    assertThat(devicesState.isLoading.value).isFalse()
    assertThat(devicesState.devices.value).isNotEmpty()
    assertThat(previewState.previewResolution.value).isNotEqualTo(IntSize.Zero)
  }

  @Test
  fun cameraFeatureCheck_acceptsFrontOrBackButNotExternalOnly() {
    assertThat(
      hasFrontOrBackCameraFeature(setOf(PackageManager.FEATURE_CAMERA)::contains),
    ).isTrue()
    assertThat(
      hasFrontOrBackCameraFeature(setOf(PackageManager.FEATURE_CAMERA_FRONT)::contains),
    ).isTrue()
    assertThat(
      hasFrontOrBackCameraFeature(
        setOf(
          PackageManager.FEATURE_CAMERA_ANY,
          PackageManager.FEATURE_CAMERA_EXTERNAL,
        )::contains,
      ),
    ).isFalse()
  }

  private fun assumeFrontOrBackCameraAvailable() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val packageManager = context.packageManager
    assumeTrue(
      hasFrontOrBackCameraFeature { feature ->
        packageManager.hasSystemFeature(feature)
      },
    )
  }

  private fun activeAnalysisThreadCount(): Int {
    return Thread.getAllStackTraces().keys.count { thread ->
      thread.isAlive && thread.name == CAMERA_ANALYSIS_THREAD_NAME
    }
  }

  private fun availableCameraIds(): List<String> {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return ProcessCameraProvider.getInstance(context)
      .get(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .availableCameraInfos
      .map { cameraInfo -> Camera2CameraInfo.from(cameraInfo).cameraId }
      .distinct()
  }

  private companion object {
    const val FRAME_TIMEOUT_SECONDS = 30L
    const val CLEANUP_TIMEOUT_MILLIS = 5_000L
  }
}

private fun hasFrontOrBackCameraFeature(
  hasSystemFeature: (String) -> Boolean,
): Boolean {
  return hasSystemFeature(PackageManager.FEATURE_CAMERA) ||
    hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
}

private class FakeLifecycleOwner : LifecycleOwner {
  private val _registry = LifecycleRegistry(this)

  override val lifecycle: Lifecycle get() = _registry

  fun start() {
    _registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    _registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
  }

  fun destroy() {
    if (_registry.currentState == Lifecycle.State.INITIALIZED) {
      _registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }
    _registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
  }
}
