@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.Manifest
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
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
class CameraPreviewLayoutTest {
  // 使用系统绘制时钟，避免 Compose 测试时钟在绘制前推进多次重组。
  private val _activityRule = ActivityScenarioRule(CameraPreviewTestActivity::class.java)

  @get:Rule
  val rules: TestRule = RuleChain
    .outerRule(GrantPermissionRule.grant(Manifest.permission.CAMERA))
    .around(_activityRule)

  @Test
  fun layoutChange_cropAppliesTransformBeforeFirstDraw() {
    assertLayoutTransformOnFirstDraw(ContentScale.Crop, CameraMirrorMode.OFF)
  }

  @Test
  fun layoutChange_fitWithMirrorAppliesTransformBeforeFirstDraw() {
    assertLayoutTransformOnFirstDraw(ContentScale.Fit, CameraMirrorMode.ON)
  }

  private fun assertLayoutTransformOnFirstDraw(contentScale: ContentScale, mirrorMode: CameraMirrorMode) {
    assumeTrue(Camera.getNumberOfCameras() > 0)
    val width = mutableStateOf(120.dp)
    val state = CameraPreviewState()
    val error = AtomicReference<Throwable?>()
    val firstFrame = CountDownLatch(1)
    val layoutFrame = CountDownLatch(1)
    val result = AtomicReference<LayoutTransformResult?>()

    _activityRule.scenario.onActivity { activity ->
      activity.setContent {
        Box {
          CameraPreview(
            modifier = Modifier.size(width.value, 120.dp),
            state = state,
            displayRotation = Surface.ROTATION_0,
            contentScale = contentScale,
            mirrorMode = mirrorMode,
            onError = error::set,
            frameProcessor = FrameProcessor.Preview { frame ->
              if (state.isFrameTransformCurrent(frame.transformToken)) firstFrame.countDown()
            },
          )
        }
      }
    }
    val firstFrameReceived = firstFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    assertThat(error.get()).isNull()
    assertThat(firstFrameReceived).isTrue()

    lateinit var view: CameraTextureView
    lateinit var originalListener: TextureView.SurfaceTextureListener
    lateinit var listener: TextureView.SurfaceTextureListener
    lateinit var initialSessionIdentity: CameraFrameTransformIdentity
    _activityRule.scenario.onActivity { activity ->
      view = checkNotNull(findTextureView(activity.window.decorView))
      originalListener = checkNotNull(view.surfaceTextureListener)
      initialSessionIdentity = checkNotNull(state.currentSessionIdentity())
      val initialWidth = view.width
      listener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
          originalListener.onSurfaceTextureAvailable(surface, width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
          originalListener.onSurfaceTextureSizeChanged(surface, width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
          return originalListener.onSurfaceTextureDestroyed(surface)
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
          originalListener.onSurfaceTextureUpdated(surface)
          if (view.width == initialWidth || result.get() != null) return
          result.set(
            LayoutTransformResult(
              viewSize = IntSize(view.width, view.height),
              stateSize = state.createScreenshotRequest(CameraMirrorMode.AUTO)?.previewSize,
              sessionIdentity = state.currentSessionIdentity(),
              expectedTransform = state.createCurrentTextureViewTransform(initialSessionIdentity)?.values(),
              actualTransform = view.getTransform(Matrix()).values(),
            ),
          )
          layoutFrame.countDown()
        }
      }
      view.surfaceTextureListener = listener
    }

    try {
      _activityRule.scenario.onActivity { width.value = 240.dp }
      val layoutFrameReceived = layoutFrame.await(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      assertThat(error.get()).isNull()
      assertThat(layoutFrameReceived).isTrue()
      val observation = checkNotNull(result.get())
      assertThat(observation.sessionIdentity).isSameInstanceAs(initialSessionIdentity)
      assertThat(observation.stateSize).isEqualTo(observation.viewSize)
      assertThat(observation.actualTransform).usingTolerance(0.001)
        .containsExactly(checkNotNull(observation.expectedTransform)).inOrder()
    } finally {
      _activityRule.scenario.onActivity {
        if (view.surfaceTextureListener === listener) view.surfaceTextureListener = originalListener
      }
    }
  }

  private fun findTextureView(view: View): CameraTextureView? {
    if (view is CameraTextureView) return view
    if (view !is ViewGroup) return null
    repeat(view.childCount) { index ->
      findTextureView(view.getChildAt(index))?.also { return it }
    }
    return null
  }

  private fun Matrix.values(): FloatArray = FloatArray(9).also(::getValues)

  private data class LayoutTransformResult(
    val viewSize: IntSize,
    val stateSize: IntSize?,
    val sessionIdentity: CameraFrameTransformIdentity?,
    val expectedTransform: FloatArray?,
    val actualTransform: FloatArray,
  )

  private companion object {
    const val FRAME_TIMEOUT_SECONDS = 15L
  }
}
