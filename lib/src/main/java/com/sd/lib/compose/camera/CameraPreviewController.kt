package com.sd.lib.compose.camera

import android.content.Context
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 管理一次 CameraPreview 的 CameraX 绑定和帧回调生命周期。
 *
 * 与官方示例的 Controller 一样，这个类型只负责 CameraX，不持有任何 Compose UI 状态。
 */
internal class CameraPreviewController(
  private val context: Context,
  private val lifecycleOwner: LifecycleOwner,
  private val cameraId: String?,
  private val targetRotation: Int,
  private val frameFormat: CameraFrameFormat,
  private val onSurfaceRequest: (
    SurfaceRequest,
    CameraFrameTransformIdentity,
    (Exception) -> Unit,
  ) -> Unit,
  private val transformIdentityProvider: () -> CameraFrameTransformIdentity?,
  private val onFrame: ((CameraFrame) -> Unit)?,
  private val onError: (Throwable) -> Unit,
  private val onClosed: () -> Unit,
) : AutoCloseable {
  private val _mainExecutor = ContextCompat.getMainExecutor(context)
  private val _frameDispatcher = onFrame?.let { callback ->
    CameraFrameDispatcher(transformIdentityProvider, callback)
  }
  private val _analysisExecutor: ExecutorService? = _frameDispatcher?.let {
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, CAMERA_ANALYSIS_THREAD_NAME)
    }
  }

  private var _cameraProvider: ProcessCameraProvider? = null
  private var _preview: Preview? = null
  private var _imageAnalysis: ImageAnalysis? = null
  private var _cameraState: LiveData<CameraState>? = null

  @Volatile
  private var _closed = false

  private val _cameraStateObserver = Observer<CameraState> { state ->
    state.toCameraPreviewExceptionOrNull()?.also { error ->
      if (state.requiresCameraSessionClose()) {
        failAndClose(error)
      } else if (!_closed) {
        onError(error)
      }
    }
  }
  private val _lifecycleObserver = LifecycleEventObserver { _, event ->
    if (event == Lifecycle.Event.ON_DESTROY) close()
  }

  fun start() {
    if (abortIfLifecycleDestroyed()) return
    val providerFuture = try {
      ProcessCameraProvider.getInstance(context)
    } catch (error: Exception) {
      failAndClose(error)
      return
    }
    try {
      providerFuture.addListener(
        {
          if (!_closed && !abortIfLifecycleDestroyed()) {
            val provider = try {
              providerFuture.get()
            } catch (error: Exception) {
              failAndClose(error)
              return@addListener
            }
            bind(provider)
          }
        },
        _mainExecutor,
      )
    } catch (error: Exception) {
      failAndClose(error)
    }
  }

  private fun bind(provider: ProcessCameraProvider) {
    val failure = try {
      bindUnchecked(provider)
      null
    } catch (error: Exception) {
      error
    }
    failure?.also(::failAndClose)
  }

  private fun bindUnchecked(provider: ProcessCameraProvider) {
    if (_closed || abortIfLifecycleDestroyed()) return
    _cameraProvider = provider
    val cameraSelector = provider.resolveCameraSelector(cameraId)
    if (cameraSelector == null) {
      failAndClose(cameraSelectionException(cameraId))
      return
    }

    val previewUseCase = Preview.Builder()
      .setTargetRotation(targetRotation)
      .build()
      .also { useCase ->
        useCase.setSurfaceProvider(_mainExecutor) { request ->
          if (_closed) {
            request.willNotProvideSurface()
          } else {
            val failure = try {
              val requestIdentity = CameraFrameTransformIdentity()
              onSurfaceRequest(request, requestIdentity) { error ->
                failSurfaceRequest(request, error)
              }
              null
            } catch (error: Exception) {
              error
            }
            failure?.also { error -> failSurfaceRequest(request, error) }
          }
        }
      }
    val analysisUseCase = onFrame?.let {
      ImageAnalysis.Builder()
        .setTargetRotation(targetRotation)
        .setOutputImageFormat(frameFormat.toImageAnalysisOutputFormat())
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also { useCase ->
          useCase.setAnalyzer(checkNotNull(_analysisExecutor), ::dispatchFrame)
        }
    }
    _preview = previewUseCase
    _imageAnalysis = analysisUseCase
    val boundCamera = if (analysisUseCase == null) {
      provider.bindToLifecycle(
        lifecycleOwner,
        cameraSelector,
        previewUseCase,
      )
    } else {
      provider.bindToLifecycle(
        lifecycleOwner,
        cameraSelector,
        previewUseCase,
        analysisUseCase,
      )
    }

    val boundCameraState = boundCamera.cameraInfo.cameraState
    _cameraState = boundCameraState
    if (_closed || abortIfLifecycleDestroyed()) return
    lifecycleOwner.lifecycle.addObserver(_lifecycleObserver)
    if (!_closed) {
      // 终止错误即使发生在 Lifecycle STOPPED 期间也不能丢失
      boundCameraState.observeForever(_cameraStateObserver)
    }
  }

  /** CameraX 会静默忽略向已销毁 LifecycleOwner 的绑定，因此必须在绑定前主动失败。 */
  private fun abortIfLifecycleDestroyed(): Boolean {
    if (lifecycleOwner.lifecycle.currentState != Lifecycle.State.DESTROYED) return false
    failAndClose(
      IllegalStateException("CameraPreview cannot bind to a destroyed LifecycleOwner."),
    )
    return true
  }

  private fun dispatchFrame(imageProxy: ImageProxy) {
    try {
      checkNotNull(_frameDispatcher).dispatch(imageProxy)
    } catch (error: Exception) {
      // onError 只负责入主线程消息队列，因此不会把用户回调异常带回分析线程。
      onError(error)
    }
  }

  private fun failSurfaceRequest(request: SurfaceRequest, error: Exception) {
    try {
      request.willNotProvideSurface()
    } catch (completionError: Exception) {
      if (completionError !== error) error.addSuppressed(completionError)
    }
    failAndClose(error)
  }

  private fun failAndClose(error: Throwable) {
    if (_closed) return
    onError(error)
    close()
  }

  /** 只解绑本组件创建的用例，避免影响同一进程中的其他 CameraX 使用方。 */
  override fun close() {
    if (_closed) return
    _closed = true
    _frameDispatcher?.close()
    val cameraState = _cameraState
    val imageAnalysis = _imageAnalysis
    val boundUseCases = listOfNotNull(_preview, imageAnalysis)
    val provider = _cameraProvider
    _cameraState = null
    _imageAnalysis = null
    _preview = null
    _cameraProvider = null

    runCameraCleanupActions(
      actions = listOf(
        { lifecycleOwner.lifecycle.removeObserver(_lifecycleObserver) },
        {
          cameraState?.removeObserver(_cameraStateObserver)
          Unit
        },
        {
          imageAnalysis?.clearAnalyzer()
          Unit
        },
        {
          if (boundUseCases.isNotEmpty()) provider?.unbind(*boundUseCases.toTypedArray())
          Unit
        },
        onClosed,
      ),
      finalAction = { _analysisExecutor?.shutdown() },
    )?.also(onError)
  }
}

internal const val CAMERA_ANALYSIS_THREAD_NAME = "CameraPreview-Analysis"

/** 执行全部普通清理；即使发生异常，也始终执行最后的资源释放。 */
internal fun runCameraCleanupActions(
  actions: List<() -> Unit>,
  finalAction: () -> Unit,
): Exception? {
  var firstFailure: Exception? = null

  fun runAction(action: () -> Unit) {
    try {
      action()
    } catch (error: Exception) {
      val previousFailure = firstFailure
      if (previousFailure == null) {
        firstFailure = error
      } else if (previousFailure !== error) {
        previousFailure.addSuppressed(error)
      }
    }
  }

  try {
    actions.forEach(::runAction)
  } finally {
    runAction(finalAction)
  }
  return firstFailure
}

/** 关闭后，CameraX 已排队但尚未执行的帧只会被释放。 */
internal class CameraFrameDispatcher(
  private val transformIdentityProvider: () -> CameraFrameTransformIdentity?,
  private val onFrame: (CameraFrame) -> Unit,
) : AutoCloseable {
  private val _lock = Any()
  private var _closed = false

  fun dispatch(imageProxy: ImageProxy) {
    var dispatchFailure: Throwable? = null
    try {
      val frame = synchronized(_lock) {
        if (_closed) return
        CameraFrame(imageProxy, transformIdentityProvider())
      }
      onFrame(frame)
    } catch (error: Throwable) {
      dispatchFailure = error
      throw error
    } finally {
      closeAfterDispatch(imageProxy, dispatchFailure)
    }
  }

  private fun closeAfterDispatch(imageProxy: ImageProxy, dispatchFailure: Throwable?) {
    try {
      imageProxy.close()
    } catch (closeFailure: Throwable) {
      if (dispatchFailure == null) throw closeFailure
      if (dispatchFailure is Error || closeFailure !is Error) {
        if (dispatchFailure !== closeFailure) dispatchFailure.addSuppressed(closeFailure)
      } else {
        closeFailure.addSuppressed(dispatchFailure)
        throw closeFailure
      }
    }
  }

  override fun close() {
    synchronized(_lock) { _closed = true }
  }
}

/** 根据 CameraX 实际默认镜像计算是否需要额外水平翻转 */
internal fun CameraMirrorMode.needsAdditionalMirror(cameraXMirrored: Boolean): Boolean {
  val targetMirrored = when (this) {
    CameraMirrorMode.AUTO -> cameraXMirrored
    CameraMirrorMode.ON -> true
    CameraMirrorMode.OFF -> false
  }
  return cameraXMirrored != targetMirrored
}

/** cameraId 为空时选择 CameraX 当前提供的第一个摄像头。 */
private fun ProcessCameraProvider.resolveCameraSelector(cameraId: String?): CameraSelector? {
  val selector = cameraId?.let(::cameraSelectorById) ?: CameraSelector.Builder().build()
  return selector.takeIf(::hasCamera)
}

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
private fun cameraSelectorById(cameraId: String): CameraSelector {
  return CameraSelector.Builder()
    .addCameraFilter { cameraInfos ->
      cameraInfos.filter { cameraInfo ->
        Camera2CameraInfo.from(cameraInfo).cameraId == cameraId
      }
    }
    .build()
}
