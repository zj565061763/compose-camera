@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.Manifest
import android.hardware.Camera
import android.view.Surface
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
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        onFrame = { frame ->
          if (frameResult.get() == null) {
            val bitmap = frame.toBitmap() ?: return@CameraPreview
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
  fun withoutFrameCallback_startsPreviewWithoutAnalysisThread() {
    assumeCameraAvailable()
    val initialAnalysisThreadCount = activeAnalysisThreadCount()
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
    assertThat(activeAnalysisThreadCount()).isAtMost(initialAnalysisThreadCount)
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
    assertThat(state.previewResolution.value).isEqualTo(IntSize.Zero)
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
    val retriedFrame = CountDownLatch(1)

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        onError = error::set,
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

    assertThat(firstFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    _composeRule.runOnIdle { state.retry() }
    _composeRule.waitForIdle()

    assertThat(retriedFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    assertThat(error.get()).isNull()
  }

  @Test
  fun destroyedLifecycleOwner_releasesSessionAndAnalysisThread() {
    assumeCameraAvailable()
    val initialAnalysisThreadCount = activeAnalysisThreadCount()
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
          onFrame = { frameReceived.countDown() },
        )
      }
    }

    assertThat(frameReceived.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    _composeRule.runOnUiThread { lifecycleOwner.destroy() }
    _composeRule.waitUntil(timeoutMillis = CLEANUP_TIMEOUT_MILLIS) {
      state.previewResolution.value == IntSize.Zero && activeAnalysisThreadCount() <= initialAnalysisThreadCount
    }

    assertThat(error.get()).isNull()
    assertThat(state.previewResolution.value).isEqualTo(IntSize.Zero)
  }

  private fun assumeCameraAvailable() {
    assumeTrue(Camera.getNumberOfCameras() > 0)
  }

  private fun waitForPreview(state: CameraPreviewState, error: AtomicReference<Throwable?>) {
    _composeRule.waitUntil(timeoutMillis = FRAME_TIMEOUT_SECONDS * 1_000) {
      error.get() != null || state.previewResolution.value != IntSize.Zero
    }
  }

  private fun activeAnalysisThreadCount(): Int {
    return Thread.getAllStackTraces().keys.count { thread ->
      thread.isAlive && thread.name == CAMERA_ANALYSIS_THREAD_NAME
    }
  }

  private data class FrameResult(
    val width: Int,
    val height: Int,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val rotationDegrees: Int,
    val threadName: String,
  )

  private companion object {
    const val FRAME_TIMEOUT_SECONDS = 15L
    const val CLEANUP_TIMEOUT_MILLIS = 5_000L
  }
}

private class FakeLifecycleOwner : LifecycleOwner {
  private val _registry = LifecycleRegistry(this)

  override val lifecycle: Lifecycle get() = _registry

  fun start() {
    _registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    _registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
  }

  fun destroy() {
    _registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
  }
}
