package com.sd.lib.compose.camera

import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicBoolean

internal const val CAMERA_OPERATION_THREAD_NAME = "CameraPreview-Camera"

/** 在单个 CameraPreview 生命周期内复用相机和分析执行资源 */
internal class CameraPreviewRuntime : AutoCloseable {
  private val _lock = Any()
  private val _analysisCoordinator = CameraAnalysisCoordinator()
  private var _cameraThread: HandlerThread? = null
  private var _cameraHandler: Handler? = null
  private var _leaseCount = 0
  private var _closeRequested = false
  private var _resourcesClosed = false

  fun acquire(): CameraPreviewRuntimeLease {
    return synchronized(_lock) {
      check(!_closeRequested) { "CameraPreviewRuntime is closed." }
      val handler = _cameraHandler ?: createCameraHandler()
      _leaseCount++
      CameraPreviewRuntimeLease(this, handler, _analysisCoordinator)
    }
  }

  private fun createCameraHandler(): Handler {
    val thread = HandlerThread(CAMERA_OPERATION_THREAD_NAME).also { it.start() }
    _cameraThread = thread
    return Handler(thread.looper).also { _cameraHandler = it }
  }

  internal fun release() {
    val shouldCloseResources = synchronized(_lock) {
      check(_leaseCount > 0) { "CameraPreviewRuntime lease count is already zero." }
      _leaseCount--
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
    try {
      _analysisCoordinator.close()
    } finally {
      _cameraThread?.quitSafely()
    }
  }
}

internal class CameraPreviewRuntimeLease(
  private val runtime: CameraPreviewRuntime,
  val cameraHandler: Handler,
  val analysisCoordinator: CameraAnalysisCoordinator,
) : AutoCloseable {
  private val _closed = AtomicBoolean()

  fun closeRuntime() {
    runtime.close()
  }

  override fun close() {
    if (_closed.compareAndSet(false, true)) runtime.release()
  }
}
