package com.sd.lib.compose.camera

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal const val CAMERA_ANALYSIS_THREAD_NAME = "CameraPreview-Analysis"

/** 在单个 CameraPreview 生命周期内串行执行分析，只保留最新待处理任务。 */
internal class CameraAnalysisCoordinator(
  private val executorFactory: () -> ExecutorService = ::createCameraAnalysisExecutor,
) : AutoCloseable {
  private val _lock = Any()
  private var _processing = false
  private var _pending: PendingCameraAnalysis? = null
  private var _executor: ExecutorService? = null
  private var _closed = false

  fun offer(
    owner: Any,
    process: () -> Unit,
    discard: (Throwable?) -> Unit,
  ) {
    val analysis = PendingCameraAnalysis(owner, process, discard)
    var replaced: PendingCameraAnalysis? = null
    var toSchedule: PendingCameraAnalysis? = null
    synchronized(_lock) {
      if (_closed) {
        replaced = analysis
      } else if (_processing) {
        replaced = _pending
        _pending = analysis
      } else {
        _processing = true
        toSchedule = analysis
      }
    }
    replaced?.also { dropped -> dropped.discard(null) }
    toSchedule?.also(::schedule)
  }

  fun discardPending(owner: Any) {
    val pending = synchronized(_lock) {
      _pending?.takeIf { analysis -> analysis.owner === owner }?.also { _pending = null }
    }
    pending?.also { analysis -> analysis.discard(null) }
  }

  private fun schedule(initial: PendingCameraAnalysis) {
    var analysis: PendingCameraAnalysis? = initial
    var failure: Throwable? = null
    while (analysis != null) {
      val current = analysis
      var skippedBecauseClosed = false
      val schedulingFailure = try {
        val executor = synchronized(_lock) {
          if (_closed) {
            skippedBecauseClosed = true
            null
          } else {
            _executor ?: executorFactory().also { _executor = it }
          }
        }
        executor?.execute { process(current) }
        null
      } catch (error: Throwable) {
        error
      }
      if (!skippedBecauseClosed && schedulingFailure == null) break

      try {
        val discardFailure = schedulingFailure?.takeUnless { synchronized(_lock) { _closed } }
        current.discard(discardFailure)
      } catch (error: Throwable) {
        failure = mergeFrameFailures(failure, error)
      }
      analysis = takeNext()
    }
    failure?.also { throw it }
  }

  private fun process(initial: PendingCameraAnalysis) {
    var analysis = initial
    var failure: Throwable? = null
    while (true) {
      try {
        analysis.process()
      } catch (error: Throwable) {
        failure = mergeFrameFailures(failure, error)
      }
      // 共享协调中的任务需要隔离用户回调留下的 interrupt 状态
      Thread.interrupted()

      val next = takeNext() ?: break
      if (failure != null) {
        try {
          schedule(next)
        } catch (error: Throwable) {
          failure = mergeFrameFailures(failure, error)
        }
        break
      }
      analysis = next
    }
    failure?.also { throw it }
  }

  private fun takeNext(): PendingCameraAnalysis? {
    return synchronized(_lock) {
      _pending.also { next ->
        _pending = null
        if (next == null) _processing = false
      }
    }
  }

  override fun close() {
    val executor: ExecutorService?
    val pending = synchronized(_lock) {
      if (_closed) return
      _closed = true
      executor = _executor
      _pending.also { _pending = null }
    }
    var failure: Throwable? = null
    try {
      pending?.also { analysis -> analysis.discard(null) }
    } catch (error: Throwable) {
      failure = error
    }
    try {
      executor?.shutdown()
    } catch (error: Throwable) {
      failure = mergeFrameFailures(failure, error)
    }
    failure?.also { throw it }
  }
}

private class PendingCameraAnalysis(
  val owner: Any,
  val process: () -> Unit,
  val discard: (Throwable?) -> Unit,
)

private fun createCameraAnalysisExecutor(): ExecutorService {
  return Executors.newSingleThreadExecutor { runnable -> Thread(runnable, CAMERA_ANALYSIS_THREAD_NAME) }
}
