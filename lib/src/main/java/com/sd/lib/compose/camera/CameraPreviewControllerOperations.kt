package com.sd.lib.compose.camera

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

internal fun postStopThenRelease(
  post: (Runnable) -> Boolean,
  stop: () -> Unit,
  release: () -> Unit,
) {
  val task = Runnable {
    try {
      stop()
    } finally {
      release()
    }
  }
  if (!post(task)) release()
}

internal fun reportNullPreviewCallbackAndStop(
  onError: (Throwable) -> Unit,
  stopSession: () -> Unit,
) {
  try {
    onError(nullPreviewCallbackBufferException())
  } finally {
    stopSession()
  }
}

internal fun <T> callOnHandlerThread(handler: Handler, action: () -> T): T {
  if (Looper.myLooper() === handler.looper) return action()
  val task = FutureTask(action)
  check(handler.post(task)) { "The target handler thread is not available." }
  return try {
    task.get()
  } catch (error: ExecutionException) {
    throw error.cause ?: error
  } catch (error: InterruptedException) {
    Thread.currentThread().interrupt()
    throw error
  }
}

/** 执行全部普通清理；即使发生异常，也始终执行最后的资源释放。 */
internal fun runCameraCleanupActions(
  actions: List<() -> Unit>,
  finalAction: () -> Unit,
): Exception? {
  var firstFailure: Exception? = null

  fun runAction(action: () -> Unit) {
    try {
      action()
    } catch (error: Exception) {
      val previousFailure = firstFailure
      if (previousFailure == null) {
        firstFailure = error
      } else if (previousFailure !== error) {
        previousFailure.addSuppressed(error)
      }
    }
  }

  try {
    actions.forEach(::runAction)
  } finally {
    runAction(finalAction)
  }
  return firstFailure
}
