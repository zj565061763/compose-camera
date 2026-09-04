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

    val failure = runCleanupActions(
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
  fun cleanupActions_runAllActionsAndRethrowFatalFailure() {
    val calls = mutableListOf<Int>()
    val ordinaryFailure = IllegalStateException("ordinary")
    val fatalFailure = AssertionError("fatal")

    val thrown = assertThrows(AssertionError::class.java) {
      runCleanupActions(
        actions = listOf(
          {
            calls += 1
            throw ordinaryFailure
          },
          {
            calls += 2
            throw fatalFailure
          },
          { calls += 3 },
        ),
        finalAction = { calls += 4 },
      )
    }

    assertThat(calls).containsExactly(1, 2, 3, 4).inOrder()
    assertThat(thrown).isSameInstanceAs(fatalFailure)
    assertThat(fatalFailure.suppressed.asList()).containsExactly(ordinaryFailure)
  }

  @Test
  fun postStopThenRelease_stopsBeforeReleasingSurface() {
    val calls = mutableListOf<String>()

    val posted = postStopThenRelease(
      post = { task ->
        task.run()
        true
      },
      stop = { calls += "stop" },
      release = { calls += "release" },
    )

    assertThat(posted).isTrue()
    assertThat(calls).containsExactly("stop", "release").inOrder()
  }

  @Test
  fun postStopThenRelease_rejectionTransfersReleaseWithoutStopping() {
    val calls = mutableListOf<String>()

    val posted = postStopThenRelease(
      post = { false },
      stop = { calls += "stop" },
      release = { calls += "release" },
    )

    assertThat(posted).isFalse()
    assertThat(calls).containsExactly("release")
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

  @Test
  fun throwAfterCleanup_runsAllActionsAndPrefersFatalFailure() {
    val initialFailure = IllegalStateException("initial")
    val ordinaryCleanupFailure = IllegalArgumentException("ordinary cleanup")
    val fatalCleanupFailure = AssertionError("fatal cleanup")
    val calls = mutableListOf<Int>()

    val thrown = assertThrows(AssertionError::class.java) {
      throwAfterCleanup(
        initialFailure,
        listOf(
          {
            calls += 1
            throw ordinaryCleanupFailure
          },
          {
            calls += 2
            throw fatalCleanupFailure
          },
          { calls += 3 },
        ),
      )
    }

    assertThat(calls).containsExactly(1, 2, 3).inOrder()
    assertThat(thrown).isSameInstanceAs(fatalCleanupFailure)
    assertThat(fatalCleanupFailure.suppressed.asList()).containsExactly(initialFailure)
    assertThat(initialFailure.suppressed.asList()).containsExactly(ordinaryCleanupFailure)
  }
}
