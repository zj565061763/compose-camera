package com.sd.lib.compose.camera

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraTextureViewTest {
  @get:Rule
  val composeRule = createAndroidComposeRule<CameraPreviewTestActivity>()

  @Test
  fun firstFrame_ignoresResizeAndOpacityUpdatesThenAcceptsSingleProducerFrame() {
    lateinit var view: CameraTextureView
    val state = CameraPreviewState()
    val publishedFrames = mutableListOf<Long>()
    var updateCount = 0
    var focusRequests = 0
    val coordinator = CameraPreviewAutoFocusCoordinator(
      post = { task ->
        task.run()
        true
      },
      currentSessionIdentity = state::currentSessionIdentity,
      isClosed = { false },
      requestAutoFocus = { focusRequests++ },
      onPreviewFrameAvailable = { identity ->
        state.markPreviewFrameAvailable(identity)?.also(view::setTransform)
        publishedFrames += view.frameNumber
      },
      onError = { throw AssertionError(it) },
    )
    composeRule.setContent {
      AndroidView(
        factory = { context ->
          CameraTextureView(context).also { textureView ->
            view = textureView
            textureView.surfaceTextureListener = object : EmptySurfaceTextureListener() {
              override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                updateCount++
                coordinator.onSurfaceTextureUpdated(
                  isActive = true,
                  isCurrentSurface = view.surfaceTexture === surface,
                  frameNumber = view.frameNumber,
                )
              }
            }
          }
        },
        modifier = Modifier.size(120.dp),
      )
    }

    composeRule.waitUntil(5_000) { view.isAvailable }
    composeRule.runOnIdle {
      state.updatePreviewLayout(IntSize(view.width, view.height), ContentScale.Fit, isMirrored = false)
      val identity = CameraFrameTransformIdentity()
      state.startSession(identity, IntSize(160, 120), 0, isPreviewMirrored = false, isMirrored = false)
      view.getBitmap(1, 1)?.recycle()
      coordinator.armFirstPreviewFrame(identity, view.frameNumber)
      publishFrame(view, Color.GREEN)
    }
    composeRule.waitUntil(5_000) { state.isPreviewFrameAvailable() }

    var previousFrameNumber = 0L
    composeRule.runOnIdle {
      view.getBitmap(1, 1)?.recycle()
      previousFrameNumber = view.frameNumber
      val previousTimestamp = checkNotNull(view.surfaceTexture).timestamp
      val oldTransform = view.getTransform(Matrix()).values()
      val identity = CameraFrameTransformIdentity()
      state.startSession(identity, IntSize(160, 120), 90, isPreviewMirrored = false, isMirrored = true)
      coordinator.armFirstPreviewFrame(identity, previousFrameNumber)
      val previousUpdateCount = updateCount

      view.layout(view.left, view.top, view.right + 1, view.bottom)
      state.updatePreviewLayout(IntSize(view.width, view.height), ContentScale.Fit, isMirrored = true)
      view.getBitmap(1, 1)?.recycle()
      view.isOpaque = false
      view.getBitmap(1, 1)?.recycle()

      assertThat(updateCount).isGreaterThan(previousUpdateCount)
      assertThat(view.frameNumber).isEqualTo(previousFrameNumber)
      assertThat(checkNotNull(view.surfaceTexture).timestamp).isEqualTo(previousTimestamp)
      assertThat(view.getTransform(Matrix()).values()).isEqualTo(oldTransform)
      assertThat(state.isPreviewFrameAvailable()).isFalse()
      assertThat(state.currentTransformIdentity()).isNull()
      assertThat(state.createScreenshotRequest(CameraMirrorMode.OFF)).isNull()
      assertThat(publishedFrames).containsExactly(previousFrameNumber)
      assertThat(focusRequests).isEqualTo(1)

      publishFrame(view, Color.BLUE)
    }
    composeRule.waitUntil(5_000) { state.isPreviewFrameAvailable() }
    composeRule.runOnIdle {
      assertThat(view.frameNumber).isEqualTo(previousFrameNumber + 1)
      assertThat(publishedFrames).containsExactly(previousFrameNumber, previousFrameNumber + 1).inOrder()
      assertThat(focusRequests).isEqualTo(2)
      assertThat(state.currentTransformIdentity()).isNotNull()
      assertThat(state.createScreenshotRequest(CameraMirrorMode.OFF)).isNotNull()
      val expectedTransform = checkNotNull(state.createCurrentTextureViewTransform(checkNotNull(state.currentSessionIdentity())))
      assertThat(view.getTransform(Matrix()).values()).isEqualTo(expectedTransform.values())
    }
  }

  @Test
  fun reattach_createsNewSurfaceAndTracksItsFirstFrame() {
    lateinit var container: FrameLayout
    lateinit var view: CameraTextureView
    lateinit var previousSurface: SurfaceTexture
    val availableSurfaces = mutableListOf<SurfaceTexture>()
    val destroyedSurfaces = mutableListOf<SurfaceTexture>()
    composeRule.setContent {
      AndroidView(
        factory = { context ->
          FrameLayout(context).also { frameLayout ->
            container = frameLayout
            view = CameraTextureView(context).also { textureView ->
              textureView.surfaceTextureListener = object : EmptySurfaceTextureListener() {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                  availableSurfaces += surface
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                  destroyedSurfaces += surface
                  return true
                }
              }
            }
            frameLayout.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
          }
        },
        modifier = Modifier.size(120.dp),
      )
    }
    composeRule.waitUntil(5_000) { view.isAvailable }
    composeRule.runOnIdle {
      assertThat(availableSurfaces).hasSize(1)
      publishFrame(view, Color.GREEN)
    }
    composeRule.waitUntil(5_000) { view.frameNumber == 1L }
    composeRule.runOnIdle {
      previousSurface = checkNotNull(view.surfaceTexture)
      container.removeView(view)
      assertThat(destroyedSurfaces).containsExactly(previousSurface)
      assertThat(view.isAvailable).isFalse()

      container.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    composeRule.waitUntil(5_000) { view.isAvailable }
    composeRule.runOnIdle {
      assertThat(view.isAvailable).isTrue()
      assertThat(view.surfaceTexture).isNotSameInstanceAs(previousSurface)
      assertThat(availableSurfaces).containsExactly(previousSurface, view.surfaceTexture).inOrder()
      assertThat(view.frameNumber).isEqualTo(0)
      publishFrame(view, Color.BLUE)
    }
    composeRule.waitUntil(5_000) { view.frameNumber == 1L }
  }

  private fun publishFrame(view: CameraTextureView, color: Int) {
    val surface = Surface(checkNotNull(view.surfaceTexture))
    try {
      val canvas = surface.lockCanvas(null)
      canvas.drawColor(color)
      surface.unlockCanvasAndPost(canvas)
    } finally {
      surface.release()
    }
  }

  private fun Matrix.values(): FloatArray = FloatArray(9).also(::getValues)
}

private open class EmptySurfaceTextureListener : TextureView.SurfaceTextureListener {
  override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = Unit
  override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
  override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
  override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
