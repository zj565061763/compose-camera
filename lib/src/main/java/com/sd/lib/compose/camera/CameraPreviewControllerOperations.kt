package com.sd.lib.compose.camera

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

internal fun postStopThenRelease(
  post: (Runnable) -> Boolean,
  stop: () -> Unit,
  release: () -> Unit,
): Boolean {
  val task = Runnable {
    try {
      stop()
    } finally {
      release()
    }
  }
  return post(task).also { posted ->
    if (!posted) release()
  }
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

internal fun awaitThreadTerminationUninterruptibly(thread: Thread) {
  var interrupted = false
  while (thread.isAlive) {
    try {
      thread.join()
    } catch (_: InterruptedException) {
      interrupted = true
    }
  }
  if (interrupted) Thread.currentThread().interrupt()
}

/** 尝试全部清理操作，汇总普通异常并在完成后重新抛出致命错误。 */
internal fun runCleanupActions(
  actions: List<() -> Unit>,
  finalAction: () -> Unit,
): Exception? {
  var failure: Throwable? = null

  fun runAction(action: () -> Unit) {
    try {
      action()
    } catch (error: Throwable) {
      failure = mergeFailures(failure, error)
    }
  }

  actions.forEach(::runAction)
  runAction(finalAction)
  return when (val result = failure) {
    null -> null
    is Exception -> result
    else -> throw result
  }
}
