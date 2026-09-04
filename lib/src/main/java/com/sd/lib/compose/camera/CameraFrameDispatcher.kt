package com.sd.lib.compose.camera

import android.graphics.ImageFormat

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
      // 取得当前 generation 的执行权后，此回调可以在停止过程中继续完成。
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

internal fun mergeFrameFailures(failure: Throwable?, nextFailure: Throwable): Throwable {
  return when {
    failure == null -> nextFailure
    failure is Error || nextFailure !is Error -> failure.also {
      if (failure !== nextFailure) failure.addSuppressed(nextFailure)
    }
    else -> nextFailure.also { nextFailure.addSuppressed(failure) }
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
