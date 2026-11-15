package com.sd.lib.compose.camera

import android.content.Context
import android.util.Rational
import android.util.Size
import androidx.annotation.MainThread
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.ResolutionInfo
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
import java.util.concurrent.ExecutionException

internal data class CameraSessionConfig(
  val cameraId: String?,
  val displayRotation: Int,
  val frameMode: FrameProcessorMode,
  val previewViewSize: IntSize,
)

/**
 * 把一次预览配置绑定到 CameraX，关闭后忽略所有迟到回调。
 *
 * 除原始帧和采样帧回调在分析线程执行外，其余操作都在主线程执行。
 */
internal class CameraSession(
  private val context: Context,
  private val lifecycleOwner: LifecycleOwner,
  private val previewView: PreviewView,
  private val state: CameraPreviewState,
  private val config: CameraSessionConfig,
  private val frameProcessor: () -> FrameProcessor,
  private val analysisExecutor: CameraAnalysisExecutor,
  private val onFailure: (Throwable) -> Unit,
  private val onError: (Throwable) -> Unit,
) : CameraSessionController, AutoCloseable {
  private val _mainExecutor = ContextCompat.getMainExecutor(context)
  private val _streamObserver = Observer(::onStreamStateChanged)
  private val _cameraStateObserver = Observer(::onCameraStateChanged)

  private var _closed = false
  private var _provider: ProcessCameraProvider? = null
  private var _camera: Camera? = null
  private var _preview: Preview? = null
  private var _analysis: ImageAnalysis? = null

  private var _rawAnalyzer: RawFrameAnalyzer? = null
  private var _sampler: PreviewFrameSampler? = null
  private var _focusController: CameraFocusController? = null

  @Volatile
  private var _activeSession: PreviewSession? = null
  private var _hasSeenIdleStream = false
  private var _reportedError: CameraState.StateError? = null

  @MainThread
  fun start() {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener(
      {
        if (_closed) return@addListener
        val provider = try {
          future.get()
        } catch (error: ExecutionException) {
          fail(error.cause ?: error)
          return@addListener
        } catch (error: Exception) {
          fail(error)
          return@addListener
        }
        bind(provider)
      },
      _mainExecutor,
    )
  }

  @MainThread
  override fun requestFocus() {
    if (!_closed) _focusController?.requestFocus()
  }

  @MainThread
  fun onFrameProcessorUpdated() {
    if (!_closed) _sampler?.onProcessorUpdated()
  }

  /** 只解绑本会话创建的用例，不影响进程内其他 CameraX 使用方 */
  @MainThread
  override fun close() {
    if (_closed) return
    _closed = true
    stopActiveSession()
    _focusController?.close()
    _sampler?.close()
    _rawAnalyzer?.close()
    previewView.previewStreamState.removeObserver(_streamObserver)
    _camera?.cameraInfo?.cameraState?.removeObserver(_cameraStateObserver)
    _analysis?.clearAnalyzer()
    _preview?.surfaceProvider = null
    _provider?.unbind(*listOfNotNull(_preview, _analysis).toTypedArray())
  }

  private fun bind(provider: ProcessCameraProvider) {
    // CameraX 会静默忽略已销毁 LifecycleOwner 的绑定
    if (_closed || lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) return
    val (cameraInfo, resolvedCameraId) = provider.platformOrderedCameraInfos(context).findCamera(config.cameraId)
      ?: run {
        fail(cameraSelectionException(config.cameraId))
        return
      }

    var preview: Preview? = null
    var analysis: ImageAnalysis? = null
    var rawAnalyzer: RawFrameAnalyzer? = null
    var sampler: PreviewFrameSampler? = null
    val camera = try {
      val rotation = config.displayRotation
      val aspectRatio = chooseAspectRatio(config.previewViewSize, cameraInfo.getSensorRotationDegrees(rotation))
      preview = Preview.Builder()
        .setTargetRotation(rotation)
        .setResolutionSelector(resolutionSelector(aspectRatio, maxSize = null))
        .build()
        .also { it.surfaceProvider = previewView.surfaceProvider }

      when (config.frameMode) {
        FrameProcessorMode.None -> Unit
        FrameProcessorMode.Preview -> {
          val analyzer = RawFrameAnalyzer(
            frameProcessor = frameProcessor,
            currentFrameTransform = { state.currentFrameTransform(_activeSession) },
            onError = onError,
          ).also { rawAnalyzer = it }
          analysis = ImageAnalysis.Builder()
            .setTargetRotation(rotation)
            .setResolutionSelector(resolutionSelector(aspectRatio, maxAnalysisSize(aspectRatio)))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer) }
        }
        FrameProcessorMode.PreviewSampled -> {
          sampler = PreviewFrameSampler(
            frameProcessor = frameProcessor,
            captureFrame = { state.capturePreview(CameraMirrorMode.OFF, previewView::getBitmap) },
            analysisExecutor = analysisExecutor,
            onError = onError,
          )
        }
      }

      // FIT 忽略外部宽高比，只把预览和分析帧裁剪到两者共同的最大视野
      val viewPort = ViewPort.Builder(Rational(1, 1), rotation).setScaleType(ViewPort.FIT).build()
      val useCases = UseCaseGroup.Builder()
        .setViewPort(viewPort)
        .addUseCase(preview)
        .also { builder -> analysis?.also(builder::addUseCase) }
        .build()

      provider.bindToLifecycle(lifecycleOwner, cameraInfo.cameraSelector, useCases)
    } catch (error: Exception) {
      sampler?.close()
      rawAnalyzer?.close()
      analysis?.clearAnalyzer()
      preview?.setSurfaceProvider(null)
      fail(cameraOpenException(resolvedCameraId, error))
      return
    }

    _provider = provider
    _camera = camera
    _preview = preview
    _analysis = analysis
    _rawAnalyzer = rawAnalyzer
    _sampler = sampler
    _focusController = CameraFocusController(
      operations = CameraXFocusOperations(camera, _mainExecutor),
      onError = onError,
    )
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
    // 重新绑定时 PreviewView 可能仍保留上一个会话的 STREAMING，需先经过非 STREAMING 状态
    if (!_hasSeenIdleStream || _activeSession != null) return
    val session = createPreviewSession() ?: return
    _activeSession = session
    state.startSession(session)
    _focusController?.onSessionStarted()
    _sampler?.start()
  }

  private fun createPreviewSession(): PreviewSession? {
    val camera = _camera ?: return null
    val previewInfo = _preview?.resolutionInfo ?: return null
    val analysisInfo = _analysis?.let { it.resolutionInfo ?: return null }
    return PreviewSession(
      contentSize = previewInfo.cropSize().rotate(previewInfo.rotationDegrees),
      isPreviewMirrored = camera.cameraInfo.resolveCameraLensOrNull() == CameraLens.FRONT,
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
    val session = _activeSession
    _activeSession = null
    _focusController?.onSessionStopped()
    _sampler?.stop()
    session?.also(state::clearSession)
  }

  private fun onCameraStateChanged(cameraState: CameraState) {
    if (_closed) return
    val error = cameraState.error
    if (error != null && error != _reportedError) {
      fail(cameraStateException(cameraState.type, error))
    }
    _reportedError = error
  }

  private fun fail(error: Throwable) {
    onFailure(error)
    onError(error)
  }
}

internal fun resolutionSelector(aspectRatio: Int, maxSize: Size?): ResolutionSelector {
  return ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
    .also { builder ->
      maxSize?.also { size ->
        builder.setResolutionStrategy(
          ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
        )
        builder.setResolutionFilter { supportedSizes, _ ->
          filterAnalysisSizes(supportedSizes, size)
        }
      }
    }
    .build()
}

/** 原始帧需要逐帧转换为 NV21，限制分辨率以控制拷贝开销 */
internal fun maxAnalysisSize(aspectRatio: Int): Size {
  return if (aspectRatio == AspectRatio.RATIO_16_9) Size(1280, 720) else Size(1280, 960)
}

internal fun filterAnalysisSizes(supportedSizes: List<Size>, maxSize: Size): List<Size> {
  val withinLimit = supportedSizes.filter { it.fitsWithin(maxSize) }
  if (withinLimit.isNotEmpty()) return withinLimit
  return supportedSizes.sortedBy { it.width.toLong() * it.height }
}

private fun Size.fitsWithin(maxSize: Size): Boolean {
  val maxLongSide = maxOf(maxSize.width, maxSize.height)
  val maxShortSide = minOf(maxSize.width, maxSize.height)
  return maxOf(width, height) <= maxLongSide && minOf(width, height) <= maxShortSide
}

private fun List<CameraInfo>.findCamera(cameraId: String?): Pair<CameraInfo, String>? {
  for (info in this) {
    val id = info.cameraIdOrNull() ?: continue
    if (cameraId == null || id == cameraId) {
      return info to id
    }
  }
  return null
}

private fun ResolutionInfo.cropSize(): IntSize {
  return IntSize(cropRect.width(), cropRect.height())
}

private fun Size.toIntSize(): IntSize {
  return IntSize(width, height)
}
