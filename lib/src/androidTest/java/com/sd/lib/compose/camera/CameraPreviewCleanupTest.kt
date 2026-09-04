@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.graphics.SurfaceTexture
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class CameraPreviewCleanupTest {
  @Test
  fun cleanupActions_runAllActionsAndAggregateFailures() {
    val calls = mutableListOf<Int>()
    val firstFailure = IllegalStateException("first")
    val secondFailure = IllegalArgumentException("second")

    val failure = runCameraCleanupActions(
      actions = listOf(
        {
          calls += 1
          throw firstFailure
        },
        { calls += 2 },
        {
          calls += 3
          throw secondFailure
        },
      ),
      finalAction = { calls += 4 },
    )

    assertThat(calls).containsExactly(1, 2, 3, 4).inOrder()
    assertThat(failure).isSameInstanceAs(firstFailure)
    assertThat(firstFailure.suppressed.asList()).containsExactly(secondFailure)
  }

  @Test
  fun postStopThenRelease_stopsBeforeReleasingSurface() {
    val calls = mutableListOf<String>()

    postStopThenRelease(
      post = { task ->
        task.run()
        true
      },
      stop = { calls += "stop" },
      release = { calls += "release" },
    )

    assertThat(calls).containsExactly("stop", "release").inOrder()
  }

  @Test
  fun postStopThenRelease_stopFailureStillReleasesSurface() {
    val failure = IllegalStateException("stop")
    var released = false

    val thrown = assertThrows(IllegalStateException::class.java) {
      postStopThenRelease(
        post = { task ->
          task.run()
          true
        },
        stop = { throw failure },
        release = { released = true },
      )
    }

    assertThat(thrown).isSameInstanceAs(failure)
    assertThat(released).isTrue()
  }

  @Test
  fun surfaceTextureReleaseCoordinator_waitsForExistingCameraUser() {
    val surfaceTexture = SurfaceTexture(0)
    val released = AtomicInteger()
    val coordinator = SurfaceTextureReleaseCoordinator { released.incrementAndGet() }
    coordinator.retain(surfaceTexture)

    coordinator.requestRelease(surfaceTexture)
    coordinator.requestRelease(surfaceTexture)

    assertThat(released.get()).isEqualTo(0)

    coordinator.releaseAfterUse(surfaceTexture)

    assertThat(released.get()).isEqualTo(1)
    coordinator.requestRelease(surfaceTexture)
    assertThat(released.get()).isEqualTo(1)
    assertThrows(IllegalStateException::class.java) { coordinator.retain(surfaceTexture) }
    surfaceTexture.release()
  }
}
