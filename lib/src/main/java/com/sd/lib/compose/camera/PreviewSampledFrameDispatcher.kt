package com.sd.lib.compose.camera

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference

/** 由预览帧节拍触发截图，并在分析线程同步发布最新采样帧。 */
internal class PreviewSampledFrameDispatcher(
  private val mainHandler: Handler,
  private val intervalMillis: () -> Long,
  private val captureFrame: (CameraFrameTransformIdentity, Boolean) -> CameraFrame.PreviewSampled?,
  private val onFrame: (CameraFrame.PreviewSampled) -> Unit,
  private val onError: (Throwable) -> Unit,
  private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
  analysisCoordinator: CameraAnalysisCoordinator? = null,
  private val beforeFrameCallback: (() -> Unit)? = null,
) : AutoCloseable {
  private val _lock = Any()
  private val _closeLock = Any()
  private val _analysisCoordinator = analysisCoordinator ?: CameraAnalysisCoordinator()
  private val _ownsAnalysisCoordinator = analysisCoordinator == null
  private val _callbackGate = FrameCallbackGate()
  // coordinator 只调度票据，主线程截图开始时才取得这里的最新请求。
  private val _pending = AtomicReference<PendingSampledFrame?>()
  private var _started = false
  private var _closed = false
  private var _lastSampleTimeMillis = 0L
  private var _runGeneration = 0L
  private var _closeCompleted = false

  fun start() {
    synchronized(_lock) {
      if (_started || _closed) return
      _started = true
      _runGeneration = _callbackGate.currentGeneration()
      _lastSampleTimeMillis = elapsedRealtimeMillis()
    }
  }

  fun offer(sessionIdentity: CameraFrameTransformIdentity, isPreviewMirrored: Boolean) {
    synchronized(_lock) {
      if (!_started || _closed) return
      val now = elapsedRealtimeMillis()
      val currentIntervalMillis = intervalMillis()
      if (currentIntervalMillis <= 0 || now - _lastSampleTimeMillis < currentIntervalMillis) return
      _lastSampleTimeMillis = now
      val pending = PendingSampledFrame(sessionIdentity, isPreviewMirrored)
      _pending.set(pending)
      val runGeneration = _runGeneration
      _analysisCoordinator.offer(
        owner = this,
        process = { process(runGeneration) },
        discard = { failure ->
          _pending.compareAndSet(pending, null)
          reportFrameFailure(failure, onError)
        },
      )
    }
  }

  private fun process(runGeneration: Long) {
    if (!hasPending(runGeneration)) return
    val frame = try {
      captureSampledFrameOnHandlerThread(mainHandler) {
        takePending(runGeneration)?.let { pending ->
          captureFrame(pending.sessionIdentity, pending.isPreviewMirrored)
        }
      }
    } catch (error: Throwable) {
      reportFrameFailure(error, onError)
      return
    } ?: return

    var failure: Throwable? = null
    try {
      _callbackGate.runIfCurrent(runGeneration) {
        beforeFrameCallback?.invoke()
        onFrame(frame)
      }
    } catch (error: Throwable) {
      failure = error
    } finally {
      failure = recycleSampledFrame(frame, failure)
    }
    reportFrameFailure(failure, onError)
  }

  private fun hasPending(runGeneration: Long): Boolean {
    return synchronized(_lock) {
      _started && !_closed && _runGeneration == runGeneration && _pending.get() != null
    }
  }

  private fun takePending(runGeneration: Long): PendingSampledFrame? {
    return synchronized(_lock) {
      if (_started && !_closed && _runGeneration == runGeneration) _pending.getAndSet(null) else null
    }
  }

  fun requestStop() {
    synchronized(_lock) {
      if (_started && !_closed) {
        _started = false
        _pending.set(null)
        _runGeneration = _callbackGate.advanceGeneration()
      }
    }
  }

  fun stop() {
    requestStop()
    try {
      _analysisCoordinator.discardPending(this)
    } finally {
      _callbackGate.awaitIdle()
    }
  }

  fun requestClose() {
    synchronized(_lock) {
      if (!_closed) {
        _closed = true
        _started = false
        _pending.set(null)
        _runGeneration = _callbackGate.closeAdmission()
      }
    }
  }

  override fun close() {
    requestClose()
    synchronized(_closeLock) {
      if (_closeCompleted) return
      try {
        _callbackGate.awaitIdle()
        _analysisCoordinator.discardPending(this)
      } finally {
        try {
          if (_ownsAnalysisCoordinator) _analysisCoordinator.close()
        } finally {
          _closeCompleted = true
        }
      }
    }
  }
}

/** 中断时取消未执行的截图；已经执行时等待并回收结果。 */
private fun captureSampledFrameOnHandlerThread(
  handler: Handler,
  captureFrame: () -> CameraFrame.PreviewSampled?,
): CameraFrame.PreviewSampled? {
  if (Looper.myLooper() === handler.looper) return captureFrame()
  val task = CancellableHandlerFutureTask(captureFrame)
  check(handler.post(task)) { "The target handler thread is not available." }
  return try {
    task.get()
  } catch (error: ExecutionException) {
    throw error.cause ?: error
  } catch (error: InterruptedException) {
    var failure: Throwable = error
    try {
      if (task.cancelBeforeStart()) {
        handler.removeCallbacks(task)
      } else {
        awaitHandlerTaskUninterruptibly(task)?.also { frame ->
          failure = recycleSampledFrame(frame, failure) ?: failure
        }
      }
    } catch (cleanupFailure: Throwable) {
      failure = mergeFailures(failure, cleanupFailure)
    } finally {
      Thread.currentThread().interrupt()
    }
    throw failure
  }
}

/** 只允许在 action 开始前取消，避免丢失执行中的结果。 */
private class CancellableHandlerFutureTask<T>(action: () -> T) : FutureTask<T>(action) {
  private val _startLock = Any()
  private var _started = false
  private var _cancelledBeforeStart = false

  override fun run() {
    val shouldRun = synchronized(_startLock) {
      if (_cancelledBeforeStart) {
        false
      } else {
        _started = true
        true
      }
    }
    if (shouldRun) super.run()
  }

  fun cancelBeforeStart(): Boolean {
    return synchronized(_startLock) {
      if (_started) {
        false
      } else {
        cancel(false).also { cancelled ->
          if (cancelled) _cancelledBeforeStart = true
        }
      }
    }
  }
}

private fun <T> awaitHandlerTaskUninterruptibly(task: FutureTask<T>): T {
  while (true) {
    try {
      return task.get()
    } catch (_: InterruptedException) {
      // 完成结果所有权交接后再统一恢复 interrupt 状态
    } catch (error: ExecutionException) {
      throw error.cause ?: error
    }
  }
}

private fun recycleSampledFrame(
  frame: CameraFrame.PreviewSampled,
  failure: Throwable? = null,
): Throwable? {
  return try {
    frame.data.recycle()
    failure
  } catch (recycleFailure: Throwable) {
    mergeFailures(failure, recycleFailure)
  }
}

private data class PendingSampledFrame(
  val sessionIdentity: CameraFrameTransformIdentity,
  val isPreviewMirrored: Boolean,
)
