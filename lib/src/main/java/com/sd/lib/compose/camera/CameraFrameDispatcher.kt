package com.sd.lib.compose.camera

import android.graphics.ImageFormat

/** 单线程发布最新 NV21 帧，并在处理完成或被替换时归还回调缓冲区。 */
internal class CameraFrameDispatcher(
  private val onFrame: (CameraFrame.Preview) -> Unit,
  private val onError: (Throwable) -> Unit,
  analysisCoordinator: CameraAnalysisCoordinator? = null,
  private val beforeFrameStart: (() -> Unit)? = null,
  private val beforeFrameCallback: (() -> Unit)? = null,
) : AutoCloseable {
  private val _offerLock = Any()
  private val _closeLock = Any()
  private val _analysisCoordinator = analysisCoordinator ?: CameraAnalysisCoordinator()
  private val _ownsAnalysisCoordinator = analysisCoordinator == null
  private val _callbackGate = FrameCallbackGate()
  private var _acceptingFrames = true
  private var _closeCompleted = false

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
    synchronized(_offerLock) {
      if (!_acceptingFrames) {
        shouldDiscard = true
      } else {
        val frame = PendingCameraFrame(
          data = data,
          width = width,
          height = height,
          rotationDegrees = rotationDegrees,
          transformIdentity = transformIdentity,
          dispatchGeneration = _callbackGate.currentGeneration(),
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
      _callbackGate.runIfCurrent(frame.dispatchGeneration) {
        beforeFrameCallback?.invoke()
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
    try {
      synchronized(_offerLock) {
        _callbackGate.advanceGeneration()
        _analysisCoordinator.discardPending(this)
      }
    } finally {
      _callbackGate.awaitIdle()
    }
  }

  fun requestClose() {
    synchronized(_offerLock) {
      if (_acceptingFrames) {
        _acceptingFrames = false
        _callbackGate.closeAdmission()
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
