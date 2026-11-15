package com.sd.lib.compose.camera

import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringResult
import androidx.test.annotation.UiThreadTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CameraFocusControllerTest {
  @Test
  @UiThreadTest
  fun autoFocusOnly_triggersOnSessionStartAndExplicitRequestWhileActive() {
    val operations = FakeCameraFocusOperations(isAutoFocusOnly = true)
    val errors = mutableListOf<Throwable>()
    val state = CameraPreviewState()
    val controller = CameraFocusController(operations, errors::add)
    val sessionController = object : CameraSessionController {
      override fun requestFocus() {
        controller.requestFocus()
      }
    }
    state.attachSessionController(sessionController)

    // 会话尚未开始上屏时忽略对焦请求
    state.requestFocus()
    assertThat(operations.triggerCount).isEqualTo(0)

    controller.onSessionStarted()
    assertThat(operations.triggerCount).isEqualTo(1)

    state.requestFocus()
    assertThat(operations.triggerCount).isEqualTo(2)

    controller.onSessionStopped()
    state.requestFocus()
    assertThat(operations.triggerCount).isEqualTo(2)
    assertThat(errors).isEmpty()
  }

  @Test
  @UiThreadTest
  fun continuousOrUnsupportedFocus_ignoresStartAndExplicitRequest() {
    val operations = FakeCameraFocusOperations(isAutoFocusOnly = false)
    val controller = CameraFocusController(operations) {}

    controller.onSessionStarted()
    controller.requestFocus()

    assertThat(operations.triggerCount).isEqualTo(0)
  }

  @Test
  @UiThreadTest
  fun focusFailure_reportsWhileOpenAndIgnoresAfterClose() {
    val operations = FakeCameraFocusOperations(isAutoFocusOnly = true)
    val errors = mutableListOf<Throwable>()
    val controller = CameraFocusController(operations, errors::add)

    controller.onSessionStarted()
    // 被新请求取消返回 null 时不报告错误
    operations.completeNext(null)
    assertThat(errors).isEmpty()

    controller.requestFocus()
    val failure = IllegalStateException("focus failed")
    operations.completeNext(failure)
    assertThat(errors).containsExactly(failure)

    controller.requestFocus()
    controller.close()
    operations.completeNext(IllegalStateException("late failure"))
    assertThat(errors).containsExactly(failure)
  }

  @Test
  fun cameraXFocusOperations_forwardsSyncAndListenerExceptionsWithoutCrashing() {
    val syncError = IllegalArgumentException("unsupported metering")
    val syncResults = mutableListOf<Throwable?>()
    val syncOperations = CameraXFocusOperations(
      camera = fakeCamera(isSupported = true) { throw syncError },
      mainExecutor = Executor(Runnable::run),
    )
    syncOperations.triggerCenterFocus(syncResults::add)
    assertThat(syncResults).containsExactly(syncError)

    val interruptedResults = mutableListOf<Throwable?>()
    val interruptedError = InterruptedException("interrupted")
    val interruptedOperations = CameraXFocusOperations(
      camera = fakeCamera(isSupported = true) { immediateThrowingFuture(interruptedError) },
      mainExecutor = Executor(Runnable::run),
    )
    try {
      interruptedOperations.triggerCenterFocus(interruptedResults::add)
      assertThat(Thread.currentThread().isInterrupted).isTrue()
    } finally {
      Thread.interrupted()
    }
    assertThat(interruptedResults).containsExactly(interruptedError)

    val unsupportedResults = mutableListOf<Throwable?>()
    val unsupportedOperations = CameraXFocusOperations(
      camera = fakeCamera(isSupported = false) { throw AssertionError("Should not start focus") },
      mainExecutor = Executor(Runnable::run),
    )
    unsupportedOperations.triggerCenterFocus(unsupportedResults::add)
    assertThat(unsupportedResults).isEmpty()
  }
}

private class FakeCameraFocusOperations(
  override val isAutoFocusOnly: Boolean,
) : CameraFocusOperations {
  private val _callbacks = ArrayDeque<(Throwable?) -> Unit>()

  var triggerCount = 0
    private set

  override fun triggerCenterFocus(onResult: (Throwable?) -> Unit) {
    triggerCount++
    _callbacks.addLast(onResult)
  }

  fun completeNext(error: Throwable?) {
    _callbacks.removeFirst().invoke(error)
  }
}

private fun fakeCamera(
  isSupported: Boolean,
  startFocus: () -> ListenableFuture<FocusMeteringResult>,
): Camera {
  val classLoader = Camera::class.java.classLoader
  val cameraInfo = Proxy.newProxyInstance(
    classLoader,
    arrayOf(CameraInfo::class.java),
  ) { _, method, _ ->
    when (method.name) {
      "isFocusMeteringSupported" -> isSupported
      else -> throw UnsupportedOperationException(method.name)
    }
  } as CameraInfo
  val cameraControl = Proxy.newProxyInstance(
    classLoader,
    arrayOf(CameraControl::class.java),
  ) { _, method, _ ->
    when (method.name) {
      "startFocusAndMetering" -> startFocus()
      else -> throw UnsupportedOperationException(method.name)
    }
  } as CameraControl
  return Proxy.newProxyInstance(
    classLoader,
    arrayOf(Camera::class.java),
  ) { _, method, _ ->
    when (method.name) {
      "getCameraInfo" -> cameraInfo
      "getCameraControl" -> cameraControl
      else -> throw UnsupportedOperationException(method.name)
    }
  } as Camera
}

private fun immediateThrowingFuture(error: Exception): ListenableFuture<FocusMeteringResult> {
  return object : ListenableFuture<FocusMeteringResult> {
    override fun addListener(listener: Runnable, executor: Executor) {
      executor.execute(listener)
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
      return false
    }

    override fun isCancelled(): Boolean {
      return false
    }

    override fun isDone(): Boolean {
      return true
    }

    override fun get(): FocusMeteringResult {
      throw error
    }

    override fun get(timeout: Long, unit: TimeUnit): FocusMeteringResult {
      throw error
    }
  }
}
