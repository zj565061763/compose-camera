package com.sd.lib.compose.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ResolutionInfo
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

internal data class CameraSessionConfig(
  val cameraId: String?,
  val displayRotation: Int,
  val frameMode: FrameProcessorMode,
  val previewViewSize: IntSize,
)

/**
 * 把一次预览配置绑定到 CameraX，关闭后不再产生任何回调。
 *
 * 除原始帧和采样帧回调在分析线程执行外，其余操作都在主线程执行。
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
internal class CameraSession(
  private val context: Context,
  private val lifecycleOwner: LifecycleOwner,
  private val previewView: PreviewView,
  private val state: CameraPreviewState,
  private val config: CameraSessionConfig,
  private val frameProcessor: () -> FrameProcessor,
  private val analysisExecutor: Executor,
  private val onFailure: (Throwable) -> Unit,
  private val onError: (Throwable) -> Unit,
) : AutoCloseable {
  private val _mainExecutor = ContextCompat.getMainExecutor(context)
  private val _mainHandler = Handler(Looper.getMainLooper())
  private val _isSampling = AtomicBoolean()
  private val _streamObserver = Observer<PreviewView.StreamState>(::onStreamStateChanged)
  private val _cameraStateObserver = Observer<CameraState>(::onCameraStateChanged)
  private val _sampleTask = Runnable(::sampleFrame)

  @Volatile
  private var _closed = false
  private var _provider: ProcessCameraProvider? = null
  private var _camera: Camera? = null
  private var _preview: Preview? = null
  private var _analysis: ImageAnalysis? = null
  private var _activeSession: PreviewSession? = null
  private var _hasSeenIdleStream = false
  private var _reportedError: CameraState.StateError? = null
  private var _isAutoFocusOnly = false

  // 只在分析线程读写，原始帧数据只保证在回调期间有效，因此可以复用
  private var _nv21Buffer: ByteArray? = null

  fun start() {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener(
      {
        if (!_closed) {
          val provider = try {
            future.get()
          } catch (error: ExecutionException) {
            fail(error.cause ?: error)
            return@addListener
          }
          bind(provider)
        }
      },
      _mainExecutor,
    )
  }

  fun requestFocus() {
    if (!_closed && _isAutoFocusOnly && _activeSession != null) focus()
  }

  private fun bind(provider: ProcessCameraProvider) {
    // CameraX 会静默忽略已销毁 LifecycleOwner 的绑定
    if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) return
    val cameraInfo = provider.platformOrderedCameraInfos(context).findCamera(config.cameraId)
    if (cameraInfo == null) {
      fail(cameraSelectionException(config.cameraId))
      return
    }

    val rotation = config.displayRotation
    val aspectRatio = chooseAspectRatio(config.previewViewSize, cameraInfo.getSensorRotationDegrees(rotation))
    val preview = Preview.Builder()
      .setTargetRotation(rotation)
      .setResolutionSelector(resolutionSelector(aspectRatio, maxSize = null))
      .build()
    preview.setSurfaceProvider(previewView.surfaceProvider)
    val analysis = if (config.frameMode == FrameProcessorMode.PREVIEW) {
      ImageAnalysis.Builder()
        .setTargetRotation(rotation)
        .setResolutionSelector(resolutionSelector(aspectRatio, maxAnalysisSize(aspectRatio)))
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also { it.setAnalyzer(analysisExecutor, ::analyze) }
    } else {
      null
    }
    // FIT 忽略宽高比，只把预览和分析帧裁剪到两者共同的视野
    val viewPort = ViewPort.Builder(Rational(1, 1), rotation).setScaleType(ViewPort.FIT).build()
    val useCases = UseCaseGroup.Builder()
      .setViewPort(viewPort)
      .addUseCase(preview)
      .also { builder -> analysis?.also(builder::addUseCase) }
      .build()

    val camera = try {
      provider.bindToLifecycle(lifecycleOwner, cameraInfo.cameraSelector, useCases)
    } catch (error: Exception) {
      analysis?.clearAnalyzer()
      fail(cameraOpenException(cameraInfo.cameraIdString, error))
      return
    }
    _provider = provider
    _camera = camera
    _preview = preview
    _analysis = analysis
    _isAutoFocusOnly = cameraInfo.isAutoFocusOnly()
    camera.cameraInfo.cameraState.observeForever(_cameraStateObserver)
    previewView.previewStreamState.observeForever(_streamObserver)
  }

  private fun onStreamStateChanged(streamState: PreviewView.StreamState) {
    if (_closed) return
    if (streamState != PreviewView.StreamState.STREAMING) {
      _hasSeenIdleStream = true
      stopActiveSession()
      return
    }
    // 重新绑定时 PreviewView 可能仍保留上一个会话的 STREAMING
    if (!_hasSeenIdleStream || _activeSession != null) return
    val session = createPreviewSession() ?: return
    _activeSession = session
    state.startSession(session)
    if (_isAutoFocusOnly) focus()
    scheduleSample()
  }

  private fun createPreviewSession(): PreviewSession? {
    val camera = _camera ?: return null
    val previewInfo = _preview?.resolutionInfo ?: return null
    val analysisInfo = _analysis?.resolutionInfo
    return PreviewSession(
      contentSize = previewInfo.cropSize.rotate(previewInfo.rotationDegrees),
      isPreviewMirrored = camera.cameraInfo.lensFacing == CameraSelector.LENS_FACING_FRONT,
      resolution = (analysisInfo ?: previewInfo).resolution.toIntSize(),
      rawFrame = analysisInfo?.let { info ->
        RawFrameLayout(
          bufferSize = info.resolution.toIntSize(),
          cropRect = info.cropRect.let { IntRect(it.left, it.top, it.right, it.bottom) },
          rotationDegrees = info.rotationDegrees,
        )
      },
    )
  }

  private fun stopActiveSession() {
    _mainHandler.removeCallbacks(_sampleTask)
    _activeSession?.also(state::clearSession)
    _activeSession = null
  }

  private fun onCameraStateChanged(cameraState: CameraState) {
    if (_closed) return
    val error = cameraState.error
    if (error != null && error != _reportedError) fail(cameraStateException(cameraState.type, error))
    _reportedError = error
  }

  private fun fail(error: Throwable) {
    onFailure(error)
    onError(error)
  }

  private fun focus() {
    val camera = _camera ?: return
    val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).disableAutoCancel().build()
    val future = camera.cameraControl.startFocusAndMetering(action)
    future.addListener(
      {
        try {
          future.get()
        } catch (error: ExecutionException) {
          // 新的对焦请求会取消进行中的请求，这不是错误
          val cause = error.cause ?: error
          if (!_closed && cause !is CameraControl.OperationCanceledException) onError(cause)
        } catch (_: CancellationException) {
        }
      },
      _mainExecutor,
    )
  }

  private fun analyze(image: ImageProxy) {
    image.use {
      if (_closed) return
      try {
        val processor = frameProcessor() as? FrameProcessor.Preview ?: return
        val data = image.toNv21(_nv21Buffer)?.also { _nv21Buffer = it } ?: return
        val frame = CameraFrame.Preview(
          data = data,
          width = image.width,
          height = image.height,
          rotationDegrees = image.imageInfo.rotationDegrees,
          transformIdentity = state.currentTransformIdentity(),
        )
        processor.onFrame(frame)
      } catch (error: Exception) {
        onError(error)
      }
    }
  }

  private fun scheduleSample() {
    if (config.frameMode != FrameProcessorMode.PREVIEW_SAMPLED) return
    val processor = frameProcessor() as? FrameProcessor.PreviewSampled ?: return
    _mainHandler.postDelayed(_sampleTask, processor.intervalMillis)
  }

  private fun sampleFrame() {
    if (_closed || _activeSession == null) return
    // 上一帧仍在处理时跳过本次采样，只处理最新画面
    if (_isSampling.compareAndSet(false, true)) {
      val frame = try {
        state.capturePreview(CameraMirrorMode.OFF, previewView::getBitmap)
      } catch (error: Exception) {
        onError(error)
        null
      }
      if (frame == null) {
        _isSampling.set(false)
      } else {
        analysisExecutor.execute { deliverSampledFrame(frame) }
      }
    }
    scheduleSample()
  }

  private fun deliverSampledFrame(frame: CameraFrame.PreviewSampled) {
    try {
      if (!_closed) (frameProcessor() as? FrameProcessor.PreviewSampled)?.onFrame?.invoke(frame)
    } catch (error: Exception) {
      onError(error)
    } finally {
      frame.data.recycle()
      _isSampling.set(false)
    }
  }

  /** 只解绑本会话创建的用例，不影响进程内其他 CameraX 使用方 */
  override fun close() {
    if (_closed) return
    _closed = true
    stopActiveSession()
    previewView.previewStreamState.removeObserver(_streamObserver)
    _camera?.cameraInfo?.cameraState?.removeObserver(_cameraStateObserver)
    _analysis?.clearAnalyzer()
    _provider?.unbind(*listOfNotNull(_preview, _analysis).toTypedArray())
  }
}

/** 选择最接近预览区域比例的相机输出比例，比例相同时保留 4:3 的完整视野 */
internal fun chooseAspectRatio(previewViewSize: IntSize, sensorRotationDegrees: Int): Int {
  if (previewViewSize.width <= 0 || previewViewSize.height <= 0) return AspectRatio.RATIO_4_3
  val viewRatio = previewViewSize.width.toFloat() / previewViewSize.height
  // 相机输出比例按传感器横向描述，四分之一圈旋转后宽高互换
  fun orientedRatio(ratio: Float) = if (sensorRotationDegrees % 180 == 0) ratio else 1f / ratio
  val ratio16By9 = abs(orientedRatio(16f / 9f) - viewRatio)
  val ratio4By3 = abs(orientedRatio(4f / 3f) - viewRatio)
  return if (ratio16By9 < ratio4By3) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
}

private fun resolutionSelector(aspectRatio: Int, maxSize: Size?): ResolutionSelector {
  return ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
    .also { builder ->
      maxSize?.also { size ->
        builder.setResolutionStrategy(
          ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
        )
      }
    }
    .build()
}

/** 原始帧需要逐帧转换为 NV21，限制分辨率以控制拷贝开销 */
private fun maxAnalysisSize(aspectRatio: Int): Size {
  return if (aspectRatio == AspectRatio.RATIO_16_9) Size(1280, 720) else Size(1280, 960)
}

internal val CameraInfo.cameraIdString: String
  @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
  get() = Camera2CameraInfo.from(this).cameraId

private fun List<CameraInfo>.findCamera(cameraId: String?): CameraInfo? {
  return if (cameraId == null) firstOrNull() else firstOrNull { it.cameraIdString == cameraId }
}

/** 只支持单次自动对焦时需要由库主动触发对焦 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
private fun CameraInfo.isAutoFocusOnly(): Boolean {
  val modes = Camera2CameraInfo.from(this)
    .getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return false
  return CameraCharacteristics.CONTROL_AF_MODE_AUTO in modes &&
    CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE !in modes &&
    CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO !in modes
}

private val ResolutionInfo.cropSize: IntSize
  get() = IntSize(cropRect.width(), cropRect.height())

private fun Size.toIntSize(): IntSize = IntSize(width, height)
