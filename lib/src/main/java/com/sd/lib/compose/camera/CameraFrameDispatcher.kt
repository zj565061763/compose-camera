package com.sd.lib.compose.camera

import android.graphics.ImageFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference

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

/** 单线程发布最新 NV21 帧，并在处理完成或被替换时归还回调缓冲区。 */
internal class CameraFrameDispatcher(
  private val onFrame: (CameraFrame.Preview) -> Unit,
  private val onError: (Throwable) -> Unit,
  analysisCoordinator: CameraAnalysisCoordinator? = null,
  private val beforeFrameStart: (() -> Unit)? = null,
) : AutoCloseable {
  private val _lock = Any()
  private val _analysisCoordinator = analysisCoordinator ?: CameraAnalysisCoordinator()
  private val _ownsAnalysisCoordinator = analysisCoordinator == null
  private var _dispatchGeneration = 0L
  private var _closed = false

  fun offer(
    data: ByteArray,
    width: Int,
    height: Int,
    rotationDegrees: Int,
    transformIdentity: CameraFrameTransformIdentity?,
    returnBuffer: (ByteArray) -> Unit,
  ) {
    if (!isValidNv21Data(data, width, height)) {
      reportOrThrow(releaseFrameBuffer(data, returnBuffer))
      return
    }
    var shouldDiscard = false
    synchronized(_lock) {
      if (_closed) {
        shouldDiscard = true
      } else {
        val frame = PendingCameraFrame(
          data = data,
          width = width,
          height = height,
          rotationDegrees = rotationDegrees,
          transformIdentity = transformIdentity,
          dispatchGeneration = _dispatchGeneration,
          returnBuffer = returnBuffer,
        )
        _analysisCoordinator.offer(
          owner = this,
          process = { process(frame) },
          discard = { failure -> reportOrThrow(releaseFrameBuffer(frame, failure)) },
        )
      }
    }
    if (shouldDiscard) reportOrThrow(releaseFrameBuffer(data, returnBuffer))
  }

  private fun process(frame: PendingCameraFrame) {
    var failure: Throwable? = null
    try {
      beforeFrameStart?.invoke()
      val callbackFrame = CameraFrame.Preview(
        data = frame.data,
        width = frame.width,
        height = frame.height,
        rotationDegrees = frame.rotationDegrees,
        transformIdentity = frame.transformIdentity,
      )
      // 取得当前 generation 的执行权后，此回调可以在停止过程中继续完成
      if (synchronized(_lock) { !_closed && frame.dispatchGeneration == _dispatchGeneration }) {
        onFrame(callbackFrame)
      }
    } catch (error: Throwable) {
      failure = error
    } finally {
      // 用户回调遗留的中断状态不能影响相机缓冲区归还
      Thread.interrupted()
      failure = releaseFrameBuffer(frame, failure)
    }
    reportOrThrow(failure)
  }

  fun discardPending() {
    synchronized(_lock) {
      _dispatchGeneration++
      _analysisCoordinator.discardPending(this)
    }
  }

  override fun close() {
    var shouldClose = false
    synchronized(_lock) {
      if (!_closed) {
        _closed = true
        _dispatchGeneration++
        shouldClose = true
      }
    }
    if (!shouldClose) return
    try {
      _analysisCoordinator.discardPending(this)
    } finally {
      if (_ownsAnalysisCoordinator) _analysisCoordinator.close()
    }
  }

  private fun reportOrThrow(failure: Throwable?) {
    reportFrameFailure(failure, onError)
  }
}

/** 由预览帧节拍触发截图，并在分析线程同步发布最新采样帧。 */
internal class PreviewSampledFrameDispatcher(
  private val mainHandler: Handler,
  private val intervalMillis: () -> Long,
  private val captureFrame: (CameraFrameTransformIdentity, Boolean) -> CameraFrame.PreviewSampled?,
  private val onFrame: (CameraFrame.PreviewSampled) -> Unit,
  private val onError: (Throwable) -> Unit,
  private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
  analysisCoordinator: CameraAnalysisCoordinator? = null,
) : AutoCloseable {
  private val _lock = Any()
  private val _analysisCoordinator = analysisCoordinator ?: CameraAnalysisCoordinator()
  private val _ownsAnalysisCoordinator = analysisCoordinator == null
  // coordinator 只调度票据，主线程截图开始时才取得这里的最新请求
  private val _pending = AtomicReference<PendingSampledFrame?>()
  private var _started = false
  private var _closed = false
  private var _lastSampleTimeMillis = 0L
  private var _runGeneration = 0L

  fun start() {
    synchronized(_lock) {
      if (_started || _closed) return
      _started = true
      _runGeneration++
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
      // 取得当前 generation 的执行权后，此回调可以在停止过程中继续完成
      if (isCurrent(runGeneration)) onFrame(frame)
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

  private fun isCurrent(runGeneration: Long): Boolean {
    return synchronized(_lock) {
      _started && !_closed && _runGeneration == runGeneration
    }
  }

  fun stop() {
    synchronized(_lock) {
      _started = false
      _pending.set(null)
      _analysisCoordinator.discardPending(this)
    }
  }

  override fun close() {
    var shouldClose = false
    synchronized(_lock) {
      if (!_closed) {
        _closed = true
        _started = false
        _pending.set(null)
        shouldClose = true
      }
    }
    if (!shouldClose) return
    try {
      _analysisCoordinator.discardPending(this)
    } finally {
      if (_ownsAnalysisCoordinator) _analysisCoordinator.close()
    }
  }
}

internal fun returnStalePreviewCallbackBuffer(
  data: ByteArray?,
  returnBuffer: (ByteArray) -> Unit,
  onError: (Throwable) -> Unit,
) {
  data?.also { buffer ->
    reportFrameFailure(releaseFrameBuffer(buffer, returnBuffer), onError)
  }
}

internal fun reportFrameFailure(failure: Throwable?, onError: (Throwable) -> Unit) {
  when (failure) {
    null -> Unit
    is Exception -> onError(failure)
    else -> throw failure
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
      failure = mergeFrameFailures(failure, cleanupFailure)
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

private fun mergeFrameFailures(failure: Throwable?, nextFailure: Throwable): Throwable {
  return when {
    failure == null -> nextFailure
    failure is Error || nextFailure !is Error -> failure.also {
      if (failure !== nextFailure) failure.addSuppressed(nextFailure)
    }
    else -> nextFailure.also { nextFailure.addSuppressed(failure) }
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
    mergeFrameFailures(failure, recycleFailure)
  }
}

private fun releaseFrameBuffer(frame: PendingCameraFrame, failure: Throwable? = null): Throwable? {
  return releaseFrameBuffer(frame.data, frame.returnBuffer, failure)
}

internal fun releaseFrameBuffer(
  data: ByteArray,
  returnBuffer: (ByteArray) -> Unit,
  failure: Throwable? = null,
): Throwable? {
  return try {
    returnBuffer(data)
    failure
  } catch (returnFailure: Throwable) {
    mergeFrameFailures(failure, returnFailure)
  }
}

private fun isValidNv21Data(data: ByteArray, width: Int, height: Int): Boolean {
  val requiredSize = nv21BufferSize(width, height) ?: return false
  return data.size >= requiredSize
}

internal fun checkNv21PreviewFormat(previewFormat: Int) {
  check(previewFormat == ImageFormat.NV21) {
    "The camera applied preview format $previewFormat instead of NV21."
  }
}

internal fun nv21BufferSize(width: Int, height: Int): Int? {
  if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) return null
  val pixelCount = width.toLong() * height
  if (pixelCount > Int.MAX_VALUE * 2L / 3L) return null
  return (pixelCount * 3 / 2).toInt()
}

private data class PendingCameraFrame(
  val data: ByteArray,
  val width: Int,
  val height: Int,
  val rotationDegrees: Int,
  val transformIdentity: CameraFrameTransformIdentity?,
  val dispatchGeneration: Long,
  val returnBuffer: (ByteArray) -> Unit,
)

private data class PendingSampledFrame(
  val sessionIdentity: CameraFrameTransformIdentity,
  val isPreviewMirrored: Boolean,
)
