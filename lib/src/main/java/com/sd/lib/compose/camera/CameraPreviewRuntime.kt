package com.sd.lib.compose.camera

import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicBoolean

internal const val CAMERA_OPERATION_THREAD_NAME = "CameraPreview-Camera"

/** 在同一个组合预览内复用 Runtime，并在当前 Lifecycle 销毁后按需创建新实例。 */
internal class CameraPreviewRuntimeStore(
  private val runtimeFactory: () -> CameraPreviewRuntime = { CameraPreviewRuntime() },
) : AutoCloseable {
  private val _lock = Any()
  private var _runtime: CameraPreviewRuntime? = null
  private var _closed = false

  fun acquire(): CameraPreviewRuntimeLease {
    return synchronized(_lock) {
      check(!_closed) { "CameraPreviewRuntimeStore is closed." }
      _runtime?.tryAcquireForRuntimeStore()?.also { return@synchronized it }

      val runtime = runtimeFactory()
      try {
        runtime.acquire().also { _runtime = runtime }
      } catch (error: Throwable) {
        throwAfterCleanup(error, listOf(runtime::close))
      }
    }
  }

  fun closeCurrentRuntime() {
    synchronized(_lock) {
      if (!_closed) _runtime?.close()
    }
  }

  override fun close() {
    val runtime = synchronized(_lock) {
      if (_closed) return
      _closed = true
      _runtime.also { _runtime = null }
    }
    runtime?.close()
  }
}

/** 在单个 CameraPreview 生命周期内复用相机和分析执行资源 */
internal class CameraPreviewRuntime(
  private val cameraHandlerFactory: (HandlerThread) -> Handler = { thread -> Handler(thread.looper) },
) : AutoCloseable {
  private val _lock = Any()
  private val _analysisCoordinator = CameraAnalysisCoordinator()
  private var _cameraThread: HandlerThread? = null
  private var _cameraHandler: Handler? = null
  private var _leaseCount = 0
  private var _closeRequested = false
  private var _invalidated = false
  private var _resourcesClosed = false

  internal fun tryAcquireForRuntimeStore(): CameraPreviewRuntimeLease? {
    return synchronized(_lock) {
      if (_resourcesClosed || _invalidated) {
        null
      } else {
        _closeRequested = false
        acquireLocked()
      }
    }
  }

  fun acquire(): CameraPreviewRuntimeLease {
    return synchronized(_lock) {
      check(!_closeRequested) { "CameraPreviewRuntime is closed." }
      acquireLocked()
    }
  }

  private fun acquireLocked(): CameraPreviewRuntimeLease {
    val handler = _cameraHandler ?: createCameraHandler()
    _leaseCount++
    return CameraPreviewRuntimeLease(this, handler, _analysisCoordinator)
  }

  private fun createCameraHandler(): Handler {
    val thread = HandlerThread(CAMERA_OPERATION_THREAD_NAME)
    try {
      thread.start()
      return cameraHandlerFactory(thread).also { handler ->
        _cameraThread = thread
        _cameraHandler = handler
      }
    } catch (error: Throwable) {
      throwAfterCleanup(
        error,
        listOf { if (thread.isAlive) thread.quitSafely() },
      )
    }
  }

  internal fun release() {
    val shouldCloseResources = synchronized(_lock) {
      check(_leaseCount > 0) { "CameraPreviewRuntime lease count is already zero." }
      _leaseCount--
      markResourcesClosedIfReady()
    }
    if (shouldCloseResources) closeResources()
  }

  internal fun invalidate() {
    val shouldCloseResources = synchronized(_lock) {
      if (_invalidated) return
      _invalidated = true
      _closeRequested = true
      markResourcesClosedIfReady()
    }
    if (shouldCloseResources) closeResources()
  }

  override fun close() {
    val shouldCloseResources = synchronized(_lock) {
      if (_closeRequested) return
      _closeRequested = true
      markResourcesClosedIfReady()
    }
    if (shouldCloseResources) closeResources()
  }

  private fun markResourcesClosedIfReady(): Boolean {
    if (!_closeRequested || _leaseCount != 0 || _resourcesClosed) return false
    _resourcesClosed = true
    return true
  }

  private fun closeResources() {
    runCleanupActions(
      actions = listOf(_analysisCoordinator::close),
      finalAction = { _cameraThread?.quitSafely() },
    )?.also { throw it }
  }
}

internal class CameraPreviewRuntimeLease(
  private val runtime: CameraPreviewRuntime,
  val cameraHandler: Handler,
  val analysisCoordinator: CameraAnalysisCoordinator,
) : AutoCloseable {
  private val _closed = AtomicBoolean()

  fun invalidateRuntime() {
    runtime.invalidate()
  }

  override fun close() {
    if (_closed.compareAndSet(false, true)) runtime.release()
  }
}
