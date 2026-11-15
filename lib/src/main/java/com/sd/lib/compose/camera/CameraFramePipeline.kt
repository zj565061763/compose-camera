package com.sd.lib.compose.camera

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

internal const val CAMERA_ANALYSIS_THREAD_NAME = "CameraPreview-Analysis"

/** 在首个任务到达时懒创建 `CameraPreview-Analysis` 单线程，关闭后拒绝新任务 */
internal class CameraAnalysisExecutor : Executor, AutoCloseable {
  private var _executor: ExecutorService? = null
  private var _closed = false

  @Synchronized
  fun submit(command: Runnable): Boolean {
    if (_closed) return false
    return try {
      val executor = _executor ?: Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, CAMERA_ANALYSIS_THREAD_NAME)
      }.also { _executor = it }
      executor.execute(command)
      true
    } catch (_: RejectedExecutionException) {
      false
    }
  }

  override fun execute(command: Runnable) {
    if (!submit(command)) {
      throw RejectedExecutionException("CameraAnalysisExecutor is closed.")
    }
  }

  @Synchronized
  override fun close() {
    _closed = true
    _executor?.shutdown()
  }
}

/** 在分析线程将 `YUV_420_888` 原始帧转换为 NV21 并复用缓冲区，回调结束后关闭 [ImageProxy] */
internal class RawFrameAnalyzer(
  private val frameProcessor: () -> FrameProcessor,
  private val currentFrameTransform: () -> FrameTransformSnapshot,
  private val onError: (Throwable) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {
  @Volatile
  private var _closed = false

  // 仅在分析单线程读写；原始帧数据只在同步回调期间有效，因此安全复用
  private var _nv21Buffer: ByteArray? = null

  override fun analyze(image: ImageProxy) {
    image.use {
      if (_closed) return
      try {
        if (frameProcessor() !is FrameProcessor.Preview) return
        val before = currentFrameTransform()
        val data = image.toNv21(_nv21Buffer)?.also { _nv21Buffer = it } ?: return
        if (_closed) return
        val after = currentFrameTransform()
        if (before.session !== after.session) return
        val processor = frameProcessor() as? FrameProcessor.Preview ?: return
        val frame = CameraFrame.Preview(
          data = data,
          width = image.width,
          height = image.height,
          rotationDegrees = image.imageInfo.rotationDegrees,
          transformIdentity = before.transformIdentity,
        )
        processor.onFrame(frame)
      } catch (error: Exception) {
        if (!_closed) onError(error)
      } finally {
        Thread.interrupted()
      }
    }
  }

  override fun close() {
    _closed = true
  }
}

/**
 * 在主线程按间隔截取预览区域，并交由分析线程同步回调。
 *
 * 上一帧仍在回调时跳过本次采样，只保留最新画面；采样间隔在组合期间变化时动态重排下一次触发时间。
 */
internal class PreviewFrameSampler(
  private val frameProcessor: () -> FrameProcessor,
  private val captureFrame: () -> CameraFrame.PreviewSampled?,
  private val analysisExecutor: CameraAnalysisExecutor,
  private val onError: (Throwable) -> Unit,
  private val mainHandler: Handler = Handler(Looper.getMainLooper()),
  private val uptimeMillis: () -> Long = SystemClock::uptimeMillis,
) : AutoCloseable {
  private val _isDelivering = AtomicBoolean(false)
  private val _sampleTask = Runnable(::onSampleTick)

  @Volatile
  private var _closed = false
  @Volatile
  private var _running = false
  private var _scheduledIntervalMillis = 0L
  private var _lastTickUptimeMillis = 0L

  @MainThread
  fun start() {
    if (_closed || _running) return
    val processor = frameProcessor() as? FrameProcessor.PreviewSampled ?: return
    _running = true
    _lastTickUptimeMillis = uptimeMillis()
    scheduleNext(processor.intervalMillis)
  }

  @MainThread
  fun stop() {
    if (!_running) return
    _running = false
    _scheduledIntervalMillis = 0L
    mainHandler.removeCallbacks(_sampleTask)
  }

  @MainThread
  fun onProcessorUpdated() {
    if (_closed || !_running) return
    val processor = frameProcessor() as? FrameProcessor.PreviewSampled ?: return
    if (processor.intervalMillis == _scheduledIntervalMillis) return
    val elapsed = (uptimeMillis() - _lastTickUptimeMillis).coerceAtLeast(0L)
    val delayMillis = (processor.intervalMillis - elapsed).coerceAtLeast(0L)
    mainHandler.removeCallbacks(_sampleTask)
    _scheduledIntervalMillis = processor.intervalMillis
    mainHandler.postDelayed(_sampleTask, delayMillis)
  }

  @MainThread
  override fun close() {
    if (_closed) return
    _closed = true
    stop()
  }

  private fun onSampleTick() {
    if (_closed || !_running) return
    val processor = frameProcessor() as? FrameProcessor.PreviewSampled ?: return
    _lastTickUptimeMillis = uptimeMillis()
    if (_isDelivering.compareAndSet(false, true)) {
      val frame = try {
        captureFrame()
      } catch (error: Exception) {
        onError(error)
        null
      }
      if (frame == null) {
        _isDelivering.set(false)
      } else if (!analysisExecutor.submit { deliverFrame(frame) }) {
        frame.data.recycle()
        _isDelivering.set(false)
      }
    }
    if (!_closed && _running) {
      scheduleNext(processor.intervalMillis)
    }
  }

  private fun scheduleNext(intervalMillis: Long) {
    _scheduledIntervalMillis = intervalMillis
    mainHandler.postDelayed(_sampleTask, intervalMillis)
  }

  private fun deliverFrame(frame: CameraFrame.PreviewSampled) {
    try {
      if (!_closed && _running) {
        (frameProcessor() as? FrameProcessor.PreviewSampled)?.onFrame?.invoke(frame)
      }
    } catch (error: Exception) {
      if (!_closed) onError(error)
    } finally {
      Thread.interrupted()
      try {
        frame.data.recycle()
      } finally {
        _isDelivering.set(false)
      }
    }
  }
}
