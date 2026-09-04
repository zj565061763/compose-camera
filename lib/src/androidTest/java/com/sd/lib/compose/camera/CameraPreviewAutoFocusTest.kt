@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.hardware.Camera
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CameraPreviewAutoFocusTest {
  @Test
  fun requestFocus_usesAttachedActionAndDetachesSafely() {
    val state = CameraPreviewState()
    val requests = AtomicInteger()
    val action: () -> Unit = { requests.incrementAndGet() }
    val otherAction: () -> Unit = {}
    state.attachRequestFocusAction(action)

    state.requestFocus()
    state.detachRequestFocusAction(otherAction)
    state.requestFocus()

    assertThat(requests.get()).isEqualTo(2)
    state.detachRequestFocusAction(action)
    state.requestFocus()
    state.attachRequestFocusAction(action)
    state.reset()
    state.requestFocus()
    assertThat(requests.get()).isEqualTo(2)
  }

  @Test
  fun autoFocusCoordinator_firstValidFrameAndExplicitRequestTargetCurrentSession() {
    val sessionIdentity = CameraFrameTransformIdentity()
    val currentSessionIdentity = AtomicReference<CameraFrameTransformIdentity?>(sessionIdentity)
    val postedTasks = mutableListOf<Runnable>()
    val previewFrames = mutableListOf<CameraFrameTransformIdentity>()
    val focusRequests = AtomicInteger()
    val errors = mutableListOf<Throwable>()
    val coordinator = CameraPreviewAutoFocusCoordinator(
      post = { task ->
        postedTasks += task
        true
      },
      currentSessionIdentity = currentSessionIdentity::get,
      isClosed = { false },
      requestAutoFocus = { focusRequests.incrementAndGet() },
      onPreviewFrameAvailable = { identity -> previewFrames += identity },
      onError = { error -> errors += error },
    )

    coordinator.armFirstPreviewFrame(sessionIdentity)
    coordinator.onSurfaceTextureUpdated(isActive = false, isCurrentSurface = true)
    coordinator.onSurfaceTextureUpdated(isActive = true, isCurrentSurface = false)
    coordinator.onSurfaceTextureUpdated(isActive = true, isCurrentSurface = true)
    coordinator.onSurfaceTextureUpdated(isActive = true, isCurrentSurface = true)

    assertThat(previewFrames).containsExactly(sessionIdentity)
    assertThat(postedTasks).hasSize(1)
    assertThat(focusRequests.get()).isEqualTo(0)
    postedTasks.removeAt(0).run()
    assertThat(focusRequests.get()).isEqualTo(1)

    coordinator.requestCurrentSession()
    assertThat(postedTasks).hasSize(1)
    postedTasks.removeAt(0).run()

    assertThat(focusRequests.get()).isEqualTo(2)
    assertThat(errors).isEmpty()
  }

  @Test
  fun autoFocusCoordinator_discardsClearedFrameAndStaleOrClosedRequests() {
    val firstSessionIdentity = CameraFrameTransformIdentity()
    val secondSessionIdentity = CameraFrameTransformIdentity()
    val currentSessionIdentity = AtomicReference<CameraFrameTransformIdentity?>(null)
    val closed = AtomicBoolean()
    val postedTasks = mutableListOf<Runnable>()
    val previewFrames = mutableListOf<CameraFrameTransformIdentity>()
    val focusRequests = AtomicInteger()
    val coordinator = CameraPreviewAutoFocusCoordinator(
      post = { task ->
        postedTasks += task
        true
      },
      currentSessionIdentity = currentSessionIdentity::get,
      isClosed = closed::get,
      requestAutoFocus = { focusRequests.incrementAndGet() },
      onPreviewFrameAvailable = { identity -> previewFrames += identity },
      onError = {},
    )

    coordinator.requestCurrentSession()
    assertThat(postedTasks).isEmpty()
    currentSessionIdentity.set(firstSessionIdentity)

    coordinator.armFirstPreviewFrame(firstSessionIdentity)
    coordinator.clearFirstPreviewFrame()
    coordinator.onSurfaceTextureUpdated(isActive = true, isCurrentSurface = true)
    assertThat(previewFrames).isEmpty()
    assertThat(postedTasks).isEmpty()

    coordinator.armFirstPreviewFrame(firstSessionIdentity)
    coordinator.onSurfaceTextureUpdated(isActive = true, isCurrentSurface = true)
    currentSessionIdentity.set(secondSessionIdentity)
    postedTasks.removeAt(0).run()
    assertThat(focusRequests.get()).isEqualTo(0)

    coordinator.requestCurrentSession()
    closed.set(true)
    postedTasks.removeAt(0).run()
    coordinator.requestCurrentSession()

    assertThat(previewFrames).containsExactly(firstSessionIdentity)
    assertThat(focusRequests.get()).isEqualTo(0)
    assertThat(postedTasks).isEmpty()
  }

  @Test
  fun autoFocusCoordinator_postFailureReportsOnlyWhileOpen() {
    val sessionIdentity = CameraFrameTransformIdentity()
    val closed = AtomicBoolean()
    val errors = mutableListOf<Throwable>()
    val coordinator = CameraPreviewAutoFocusCoordinator(
      post = { false },
      currentSessionIdentity = { sessionIdentity },
      isClosed = closed::get,
      requestAutoFocus = {},
      onPreviewFrameAvailable = {},
      onError = { error -> errors += error },
    )

    coordinator.requestCurrentSession()
    closed.set(true)
    coordinator.requestCurrentSession()

    assertThat(errors).hasSize(1)
    assertThat(errors.single()).isInstanceOf(IllegalStateException::class.java)
    assertThat(errors.single()).hasMessageThat().contains("not available for autofocus")
  }

  @Test
  fun oneShotAutoFocus_coalescesRequestsWhileFocusing() {
    val completions = mutableListOf<() -> Unit>()
    val attempts = AtomicInteger()
    val error = AtomicReference<Throwable?>()
    lateinit var autoFocus: OneShotAutoFocus

    runOnMainSync {
      autoFocus = OneShotAutoFocus(
        handler = Handler(Looper.getMainLooper()),
        focus = { onComplete ->
          attempts.incrementAndGet()
          completions += onComplete
        },
        onError = error::set,
      )

      autoFocus.request()
      autoFocus.request()
      autoFocus.request()

      assertThat(attempts.get()).isEqualTo(1)
      completions.removeAt(0).invoke()
      assertThat(attempts.get()).isEqualTo(2)
      completions.removeAt(0).invoke()
      autoFocus.close()
    }

    assertThat(attempts.get()).isEqualTo(2)
    assertThat(error.get()).isNull()
  }

  @Test
  fun oneShotAutoFocus_timeoutStartsPendingRequest() {
    val attempts = AtomicInteger()
    val pendingRequestStarted = CountDownLatch(1)
    lateinit var autoFocus: OneShotAutoFocus

    runOnMainSync {
      autoFocus = OneShotAutoFocus(
        handler = Handler(Looper.getMainLooper()),
        timeoutMillis = 10,
        focus = {
          if (attempts.incrementAndGet() == 2) pendingRequestStarted.countDown()
        },
        onError = {},
      )
      autoFocus.request()
      autoFocus.request()
    }

    assertThat(pendingRequestStarted.await(5, TimeUnit.SECONDS)).isTrue()
    runOnMainSync(autoFocus::close)
    assertThat(attempts.get()).isEqualTo(2)
  }

  @Test
  fun oneShotAutoFocus_lateCompletionDoesNotFinishNewRequest() {
    val completions = mutableListOf<() -> Unit>()
    val attempts = AtomicInteger()
    val secondRequestStarted = CountDownLatch(1)
    lateinit var autoFocus: OneShotAutoFocus

    runOnMainSync {
      autoFocus = OneShotAutoFocus(
        handler = Handler(Looper.getMainLooper()),
        timeoutMillis = 1_000,
        focus = { onComplete ->
          completions += onComplete
          if (attempts.incrementAndGet() == 2) secondRequestStarted.countDown()
        },
        onError = {},
      )
      autoFocus.request()
      autoFocus.request()
    }

    assertThat(secondRequestStarted.await(5, TimeUnit.SECONDS)).isTrue()
    runOnMainSync {
      completions[0].invoke()
      autoFocus.request()
      assertThat(attempts.get()).isEqualTo(2)
      completions[1].invoke()
      assertThat(attempts.get()).isEqualTo(3)
      completions[2].invoke()
      autoFocus.close()
    }

    assertThat(attempts.get()).isEqualTo(3)
  }

  @Test
  fun oneShotAutoFocus_canRetryAfterOrdinaryFailure() {
    val attempts = AtomicInteger()
    val error = AtomicReference<Throwable?>()
    val firstFailure = IllegalStateException("focus")

    runOnMainSync {
      val autoFocus = OneShotAutoFocus(
        handler = Handler(Looper.getMainLooper()),
        focus = { onComplete ->
          if (attempts.incrementAndGet() == 1) throw firstFailure
          onComplete()
        },
        onError = error::set,
      )
      autoFocus.request()
      autoFocus.request()
      autoFocus.close()
    }

    assertThat(error.get()).isSameInstanceAs(firstFailure)
    assertThat(attempts.get()).isEqualTo(2)
  }

  @Test
  fun oneShotAutoFocus_closeDiscardsPendingRequestAndCompletion() {
    val attempts = AtomicInteger()
    lateinit var completion: () -> Unit

    runOnMainSync {
      val autoFocus = OneShotAutoFocus(
        handler = Handler(Looper.getMainLooper()),
        focus = { onComplete ->
          attempts.incrementAndGet()
          completion = onComplete
        },
        onError = {},
      )
      autoFocus.request()
      autoFocus.request()
      autoFocus.close()
      completion()
      autoFocus.request()
    }

    assertThat(attempts.get()).isEqualTo(1)
  }

  @Test
  fun chooseFocusMode_prefersContinuousModeAndFallsBackToAuto() {
    assertThat(
      chooseFocusMode(
        listOf(
          Camera.Parameters.FOCUS_MODE_AUTO,
          Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO,
          Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE,
        ),
      ),
    ).isEqualTo(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)
    assertThat(chooseFocusMode(listOf(Camera.Parameters.FOCUS_MODE_AUTO)))
      .isEqualTo(Camera.Parameters.FOCUS_MODE_AUTO)
    assertThat(chooseFocusMode(listOf(Camera.Parameters.FOCUS_MODE_FIXED))).isNull()
  }
}

private fun runOnMainSync(action: () -> Unit) {
  InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
}
