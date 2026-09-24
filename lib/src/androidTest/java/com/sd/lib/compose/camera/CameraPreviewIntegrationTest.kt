package com.sd.lib.compose.camera

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.CameraInfo
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class CameraPreviewIntegrationTest {
  private val _composeRule = createAndroidComposeRule<CameraPreviewTestActivity>()

  @get:Rule
  val rules: TestRule = RuleChain
    .outerRule(GrantPermissionRule.grant(Manifest.permission.CAMERA))
    .around(_composeRule)

  @Test
  fun previewFrame_publishesNv21FrameWithRotationAndTransform() {
    val cameraInfo = cameraInfos().firstOrNull()
    assumeTrue(cameraInfo != null)
    val state = CameraPreviewState()
    val result = AtomicReference<FrameResult?>()
    val frameReceived = CountDownLatch(1)
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        displayRotation = Surface.ROTATION_0,
        onError = error::set,
        frameProcessor = FrameProcessor.Preview { frame ->
          if (result.get() == null && state.createTransformToPreview(frame) != null) {
            val bitmap = frame.toBitmap()
            result.set(
              FrameResult(
                size = IntSize(frame.width, frame.height),
                dataSize = frame.data.size,
                rotationDegrees = frame.rotationDegrees,
                bitmapSize = bitmap?.let { IntSize(it.width, it.height) },
                threadName = Thread.currentThread().name,
              ),
            )
            bitmap?.recycle()
            frameReceived.countDown()
          }
        },
      )
    }

    assertThat(frameReceived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    assertThat(error.get()).isNull()
    val frame = checkNotNull(result.get())
    assertThat(frame.size.width % 2).isEqualTo(0)
    assertThat(frame.size.height % 2).isEqualTo(0)
    assertThat(frame.dataSize).isEqualTo(frame.size.width * frame.size.height * 3 / 2)
    assertThat(frame.rotationDegrees).isEqualTo(checkNotNull(cameraInfo).getSensorRotationDegrees(Surface.ROTATION_0))
    assertThat(frame.bitmapSize).isEqualTo(frame.size)
    assertThat(frame.threadName).isEqualTo(CAMERA_ANALYSIS_THREAD_NAME)
    _composeRule.runOnIdle { assertThat(state.previewResolution.value).isEqualTo(frame.size) }
  }

  @Test
  fun sampledFrame_matchesPreviewSizeAndCoordinates() {
    assumeCameraAvailable()
    val state = CameraPreviewState()
    val result = AtomicReference<Pair<IntSize, String>?>()
    val frameReceived = CountDownLatch(1)
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(240.dp),
        state = state,
        onError = error::set,
        frameProcessor = FrameProcessor.PreviewSampled(intervalMillis = 100) { frame ->
          if (result.get() == null && frame.rotationDegrees == 0 && state.createTransformToPreview(frame) != null) {
            result.set(IntSize(frame.data.width, frame.data.height) to Thread.currentThread().name)
            frameReceived.countDown()
          }
        },
      )
    }

    assertThat(frameReceived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    assertThat(error.get()).isNull()
    val (size, threadName) = checkNotNull(result.get())
    assertThat(size).isEqualTo(dpSize(240))
    assertThat(threadName).isEqualTo(CAMERA_ANALYSIS_THREAD_NAME)
  }

  @Test
  fun takeScreenshot_returnsPreviewSizedBitmapWithRequestedMirror() {
    assumeCameraAvailable()
    val state = CameraPreviewState()
    var isShown by mutableStateOf(true)
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      if (isShown) CameraPreview(modifier = Modifier.size(240.dp), state = state, onError = error::set)
    }
    waitForSession(state, error)

    _composeRule.runOnIdle {
      val mirrored = checkNotNull(state.takeScreenshot(CameraMirrorMode.ON))
      val unmirrored = checkNotNull(state.takeScreenshot(CameraMirrorMode.OFF))
      assertThat(IntSize(mirrored.width, mirrored.height)).isEqualTo(dpSize(240))
      assertThat(mirroredDifference(mirrored, unmirrored)).isLessThan(16.0)
      mirrored.recycle()
      unmirrored.recycle()
    }
    isShown = false
    _composeRule.runOnIdle { assertThat(state.takeScreenshot()).isNull() }
  }

  @Test
  fun contentScaleFit_placesPreviewInsideTransparentLetterbox() {
    assumeCameraAvailable()
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(width = 300.dp, height = 60.dp),
        state = state,
        contentScale = ContentScale.Fit,
        onError = error::set,
      )
    }
    waitForSession(state, error)

    _composeRule.runOnIdle {
      val previewView = checkNotNull(findPreviewView(_composeRule.activity.window.decorView))
      val contentSize = checkNotNull(state.displayedSession.value).contentSize
      val previewSize = dpSize(300, 60)
      // 预览控件保持相机内容比例，并在宽度方向居中留边
      assertThat(previewView.height).isEqualTo(previewSize.height)
      assertThat(previewView.width.toFloat() / previewView.height)
        .isWithin(0.02f).of(contentSize.width.toFloat() / contentSize.height)
      assertThat(previewView.width).isLessThan(previewSize.width)

      val screenshot = checkNotNull(state.takeScreenshot())
      assertThat(Color.alpha(screenshot.getPixel(0, screenshot.height / 2))).isEqualTo(0)
      assertThat(Color.alpha(screenshot.getPixel(screenshot.width / 2, screenshot.height / 2))).isEqualTo(255)
      screenshot.recycle()
    }
  }

  @Test
  fun missingCameraId_reportsNotFoundWithoutFallback() {
    assumeCameraAvailable()
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(modifier = Modifier.size(240.dp), state = state, cameraId = "missing-camera", onError = error::set)
    }

    _composeRule.waitUntil(TIMEOUT_MILLIS) { error.get() != null }
    _composeRule.runOnIdle {
      val failure = state.failure.value as CameraPreviewException
      assertThat(failure).isSameInstanceAs(error.get())
      assertThat(failure.reason).isEqualTo(CameraPreviewException.Reason.CAMERA_NOT_FOUND)
      assertThat(state.displayedSession.value).isNull()
    }
  }

  @Test
  fun exactCameraId_startsEveryAvailableCamera() {
    val cameraIds = cameraInfos().map { it.cameraIdString }
    assumeTrue(cameraIds.isNotEmpty())
    val state = CameraPreviewState()
    var cameraId by mutableStateOf<String?>(null)
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(modifier = Modifier.size(240.dp), state = state, cameraId = cameraId, onError = error::set)
    }
    cameraIds.forEach { id ->
      val previousSession = _composeRule.runOnIdle {
        cameraId = id
        state.displayedSession.value
      }
      _composeRule.waitUntil(TIMEOUT_MILLIS) {
        error.get() != null || (state.currentTransformIdentity() != null && state.displayedSession.value !== previousSession)
      }
      assertThat(error.get()).isNull()
      _composeRule.runOnIdle { assertThat(state.previewResolution.value).isNotEqualTo(IntSize.Zero) }
    }
  }

  @Test
  fun devicesState_listsCamerasInPlatformOrder() {
    assumeCameraAvailable()
    lateinit var devicesState: CameraDevicesState
    _composeRule.setContent { devicesState = rememberCameraDevicesState() }

    _composeRule.waitUntil(TIMEOUT_MILLIS) { devicesState.hasLoadedDevices.value }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val platformIds = (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.toList()
    val deviceIds = _composeRule.runOnIdle { devicesState.devices.value.map { it.cameraId } }
    assertThat(deviceIds).containsExactlyElementsIn(cameraInfos().map { it.cameraIdString })
    assertThat(deviceIds).isEqualTo(platformIds.filter { it in deviceIds })
  }

  @Test
  fun retry_restartsSessionWithNewTransform() {
    assumeCameraAvailable()
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(modifier = Modifier.size(240.dp), state = state, onError = error::set)
    }
    waitForSession(state, error)
    val (session, token) = _composeRule.runOnIdle {
      val token = CameraFrameTransformToken(state.currentTransformIdentity())
      val session = state.displayedSession.value
      state.retry()
      session to token
    }

    _composeRule.waitUntil(TIMEOUT_MILLIS) {
      state.currentTransformIdentity() != null && state.displayedSession.value !== session
    }
    assertThat(error.get()).isNull()
    _composeRule.runOnIdle { assertThat(state.isFrameTransformCurrent(token)).isFalse() }
  }

  @Test
  fun layoutMirrorAndCallbackChanges_updateTransformWithoutRestartingSession() {
    assumeCameraAvailable()
    val state = CameraPreviewState()
    var size by mutableStateOf(240)
    var mirrorMode by mutableStateOf(CameraMirrorMode.AUTO)
    var processor by mutableStateOf(FrameProcessor.Preview {})
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(
        modifier = Modifier.size(size.dp),
        state = state,
        mirrorMode = mirrorMode,
        onError = error::set,
        frameProcessor = processor,
      )
    }
    waitForSession(state, error)
    val session = checkNotNull(_composeRule.runOnIdle { state.displayedSession.value })
    // 选择与当前显示状态相反的镜像模式，确保目标镜像确实变化
    val flippedMirrorMode = if (session.isPreviewMirrored) CameraMirrorMode.OFF else CameraMirrorMode.ON

    // 布局和镜像变化使旧 token 失效，回调实例变化不影响坐标变换
    val changes = listOf<Triple<String, Boolean, () -> Unit>>(
      Triple("size", false) { size = 200 },
      Triple("mirror", false) { mirrorMode = flippedMirrorMode },
      Triple("callback", true) { processor = FrameProcessor.Preview {} },
    )
    changes.forEach { (name, keepsToken, change) ->
      val token = _composeRule.runOnIdle {
        CameraFrameTransformToken(state.currentTransformIdentity()).also { change() }
      }
      _composeRule.runOnIdle {
        assertWithMessage(name).that(state.displayedSession.value).isSameInstanceAs(session)
        assertWithMessage(name).that(state.isFrameTransformCurrent(token)).isEqualTo(keepsToken)
      }
    }
    assertThat(error.get()).isNull()
  }

  @Test
  fun frameProcessorModeChange_restartsSession() {
    assumeCameraAvailable()
    val state = CameraPreviewState()
    var processor by mutableStateOf<FrameProcessor>(FrameProcessor.None)
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CameraPreview(modifier = Modifier.size(240.dp), state = state, onError = error::set, frameProcessor = processor)
    }
    waitForSession(state, error)
    val session = _composeRule.runOnIdle {
      state.displayedSession.value.also { processor = FrameProcessor.Preview {} }
    }

    _composeRule.waitUntil(TIMEOUT_MILLIS) {
      state.currentTransformIdentity() != null && state.displayedSession.value !== session
    }
    _composeRule.runOnIdle { assertThat(state.displayedSession.value?.rawFrame).isNotNull() }
  }

  @Test
  fun lifecycleStop_clearsSessionAndStartResumesPreview() {
    assumeCameraAvailable()
    val state = CameraPreviewState()
    val lifecycleOwner = _composeRule.runOnIdle { FakeLifecycleOwner().also { it.start() } }
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
        CameraPreview(modifier = Modifier.size(240.dp), state = state, onError = error::set)
      }
    }
    waitForSession(state, error)

    _composeRule.runOnIdle { lifecycleOwner.stop() }
    _composeRule.waitUntil(TIMEOUT_MILLIS) {
      state.currentTransformIdentity() == null && state.previewResolution.value == IntSize.Zero
    }

    _composeRule.runOnIdle { lifecycleOwner.start() }
    waitForSession(state, error)
    _composeRule.runOnIdle { lifecycleOwner.destroy() }
  }

  @Test
  fun leavingComposition_resetsStateAndStopsAnalysisThread() {
    assumeCameraAvailable()
    val state = CameraPreviewState()
    var isShown by mutableStateOf(true)
    val frameReceived = CountDownLatch(1)
    val error = AtomicReference<Throwable?>()

    _composeRule.setContent {
      if (isShown) {
        CameraPreview(
          modifier = Modifier.size(240.dp),
          state = state,
          onError = error::set,
          frameProcessor = FrameProcessor.Preview { frameReceived.countDown() },
        )
      }
    }
    assertThat(frameReceived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()

    isShown = false
    _composeRule.waitUntil(TIMEOUT_MILLIS) {
      Thread.getAllStackTraces().keys.none { it.isAlive && it.name == CAMERA_ANALYSIS_THREAD_NAME }
    }
    _composeRule.runOnIdle {
      assertThat(state.currentTransformIdentity()).isNull()
      assertThat(state.displayedSession.value).isNull()
    }
    assertThat(error.get()).isNull()
  }

  private fun waitForSession(state: CameraPreviewState, error: AtomicReference<Throwable?>) {
    _composeRule.waitUntil(TIMEOUT_MILLIS) { error.get() != null || state.currentTransformIdentity() != null }
    assertThat(error.get()).isNull()
  }

  private fun assumeCameraAvailable() {
    assumeTrue(cameraInfos().isNotEmpty())
  }

  private fun cameraInfos(): List<CameraInfo> {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return ProcessCameraProvider.getInstance(context).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).availableCameraInfos
  }

  private fun dpSize(width: Int, height: Int = width): IntSize {
    val density = _composeRule.activity.resources.displayMetrics.density
    return IntSize((width * density).roundToInt(), (height * density).roundToInt())
  }

  /** 比较 [mirrored] 与水平翻转后的 [unmirrored]，返回采样点的平均通道差 */
  private fun mirroredDifference(mirrored: Bitmap, unmirrored: Bitmap): Double {
    var total = 0L
    var count = 0
    for (y in 0 until mirrored.height step 8) {
      for (x in 0 until mirrored.width step 8) {
        val a = mirrored.getPixel(x, y)
        val b = unmirrored.getPixel(mirrored.width - 1 - x, y)
        total += abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
        count += 3
      }
    }
    return total.toDouble() / count
  }

  private fun findPreviewView(view: View): PreviewView? {
    if (view is PreviewView) return view
    if (view !is ViewGroup) return null
    repeat(view.childCount) { index -> findPreviewView(view.getChildAt(index))?.also { return it } }
    return null
  }

  private data class FrameResult(
    val size: IntSize,
    val dataSize: Int,
    val rotationDegrees: Int,
    val bitmapSize: IntSize?,
    val threadName: String,
  )

  private companion object {
    const val TIMEOUT_SECONDS = 10L
    const val TIMEOUT_MILLIS = TIMEOUT_SECONDS * 1_000
  }
}

private class FakeLifecycleOwner : LifecycleOwner {
  private val _registry = LifecycleRegistry(this)

  override val lifecycle: Lifecycle get() = _registry

  fun start() {
    _registry.currentState = Lifecycle.State.STARTED
  }

  fun stop() {
    _registry.currentState = Lifecycle.State.CREATED
  }

  fun destroy() {
    _registry.currentState = Lifecycle.State.DESTROYED
  }
}
