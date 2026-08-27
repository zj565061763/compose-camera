@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.Manifest
import android.hardware.Camera
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
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
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraPreviewIntegrationTest {
  private val _permissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)
  private val _activityRule = ActivityScenarioRule(CameraPreviewTestActivity::class.java)

  @get:Rule
  val ruleChain: RuleChain = RuleChain.outerRule(_permissionRule).around(_activityRule)

  @Test
  fun cameraPreview_publishesNv21FrameAndBitmap() {
    assumeTrue(Camera.getNumberOfCameras() > 0)
    val frameReceived = CountDownLatch(1)
    val frameResult = AtomicReference<FrameResult?>()
    val error = AtomicReference<Throwable?>()

    _activityRule.scenario.onActivity { activity ->
      activity.setContent {
        CameraPreview(
          modifier = Modifier.size(240.dp),
          onError = error::set,
          onFrame = { frame ->
            if (frameResult.get() == null) {
              val bitmap = frame.toBitmap() ?: return@CameraPreview
              frameResult.compareAndSet(
                null,
                FrameResult(
                  width = frame.width,
                  height = frame.height,
                  format = frame.format,
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
    }

    assertThat(frameReceived.await(15, TimeUnit.SECONDS)).isTrue()
    assertThat(error.get()).isNull()
    val result = checkNotNull(frameResult.get())
    assertThat(result.width).isGreaterThan(0)
    assertThat(result.height).isGreaterThan(0)
    assertThat(result.format).isEqualTo(CameraFrameFormat.NV21)
    assertThat(result.bitmapWidth).isEqualTo(result.width)
    assertThat(result.bitmapHeight).isEqualTo(result.height)
    assertThat(result.rotationDegrees).isAnyOf(0, 90, 180, 270)
    assertThat(result.threadName).isEqualTo(CAMERA_ANALYSIS_THREAD_NAME)
  }

  private data class FrameResult(
    val width: Int,
    val height: Int,
    val format: CameraFrameFormat,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val rotationDegrees: Int,
    val threadName: String,
  )
}
