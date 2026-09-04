package com.sd.lib.compose.camera

import android.os.Handler
import android.os.Looper

private const val AUTO_FOCUS_TIMEOUT_MILLIS = 3_000L

/** 消费当前会话首个有效预览更新，并把首帧和显式对焦请求投递到相机线程 */
internal class CameraPreviewAutoFocusCoordinator(
  private val post: (Runnable) -> Boolean,
  private val currentSessionIdentity: () -> CameraFrameTransformIdentity?,
  private val isClosed: () -> Boolean,
  private val requestAutoFocus: () -> Unit,
  private val onPreviewFrameAvailable: (CameraFrameTransformIdentity) -> Unit,
  private val onError: (Throwable) -> Unit,
) {
  private val _lock = Any()
  private var _firstPreviewFrameSessionIdentity: CameraFrameTransformIdentity? = null

  fun armFirstPreviewFrame(sessionIdentity: CameraFrameTransformIdentity) {
    synchronized(_lock) {
      _firstPreviewFrameSessionIdentity = sessionIdentity
    }
  }

  fun clearFirstPreviewFrame() {
    synchronized(_lock) {
      _firstPreviewFrameSessionIdentity = null
    }
  }

  fun onSurfaceTextureUpdated(isActive: Boolean, isCurrentSurface: Boolean) {
    val sessionIdentity = synchronized(_lock) {
      val pendingIdentity = _firstPreviewFrameSessionIdentity
      if (
        pendingIdentity != null && isActive && isCurrentSurface && !isClosed() &&
        currentSessionIdentity() === pendingIdentity
      ) {
        _firstPreviewFrameSessionIdentity = null
        pendingIdentity
      } else {
        null
      }
    } ?: return
    onPreviewFrameAvailable(sessionIdentity)
    request(sessionIdentity)
  }

  fun requestCurrentSession() {
    currentSessionIdentity()?.also(::request)
  }

  private fun request(sessionIdentity: CameraFrameTransformIdentity) {
    if (isClosed()) return
    val request = Runnable {
      if (!isClosed() && currentSessionIdentity() === sessionIdentity) requestAutoFocus()
    }
    if (!post(request) && !isClosed()) {
      onError(IllegalStateException("The camera thread is not available for autofocus."))
    }
  }
}

/** 串行执行单次自动对焦请求，忙碌期间只保留一次待处理请求 */
internal class OneShotAutoFocus(
  private val handler: Handler,
  private val timeoutMillis: Long = AUTO_FOCUS_TIMEOUT_MILLIS,
  private val focus: (onComplete: () -> Unit) -> Unit,
  private val onError: (Throwable) -> Unit,
) : AutoCloseable {
  private var _closed = false
  private var _focusing = false
  private var _pending = false
  private var _generation = 0L
  private var _timeoutTask: Runnable? = null

  init {
    require(timeoutMillis > 0) { "timeoutMillis must be positive." }
  }

  fun request() {
    if (_closed) return
    if (_focusing) {
      _pending = true
      return
    }
    startFocus()
  }

  private fun startFocus() {
    _focusing = true
    val generation = ++_generation
    val timeoutTask = Runnable { finish(generation) }.also { _timeoutTask = it }
    try {
      focus { finishOnHandler(generation) }
    } catch (error: Exception) {
      try {
        onError(error)
      } finally {
        finish(generation)
      }
      return
    }
    if (_closed || !_focusing || _generation != generation) return
    if (!handler.postDelayed(timeoutTask, timeoutMillis)) {
      try {
        onError(IllegalStateException("The camera thread is not available for autofocus timeout."))
      } finally {
        finish(generation)
      }
    }
  }

  private fun finishOnHandler(generation: Long) {
    if (Looper.myLooper() === handler.looper) {
      finish(generation)
    } else {
      handler.post { finish(generation) }
    }
  }

  private fun finish(generation: Long) {
    if (_closed || !_focusing || _generation != generation) return
    _timeoutTask?.also(handler::removeCallbacks)
    _timeoutTask = null
    _focusing = false
    if (_pending) {
      _pending = false
      startFocus()
    }
  }

  override fun close() {
    if (_closed) return
    _closed = true
    _generation++
    _focusing = false
    _pending = false
    _timeoutTask?.also(handler::removeCallbacks)
    _timeoutTask = null
  }
}
