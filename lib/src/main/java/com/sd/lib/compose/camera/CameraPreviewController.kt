@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.annotation.MainThread
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** 管理一次 [CameraPreview] 会话和帧分发 */
internal class CameraPreviewController(
  private val lifecycleOwner: LifecycleOwner,
  private val textureView: TextureView,
  private val cameraId: String?,
  private val displayRotation: Int,
  private val previewViewSizeProvider: () -> IntSize,
  private val transformIdentityProvider: () -> CameraFrameTransformIdentity?,
  private val onSessionStarted: (
    CameraFrameTransformIdentity,
    IntSize,
    Int,
    Boolean,
  ) -> Boolean,
  private val onPreviewFrameAvailable: (CameraFrameTransformIdentity) -> Unit,
  frameProcessor: ActiveFrameProcessor,
  private val captureSampledFrame: (
    CameraFrameTransformIdentity,
    Boolean,
  ) -> CameraFrame.PreviewSampled?,
  private val onSessionFailure: (Throwable) -> Unit,
  private val onError: (Throwable) -> Unit,
  private val onSessionClosed: (CameraFrameTransformIdentity?) -> Unit,
  private val autoFocusOperationsFactory: CameraAutoFocusOperationsFactory = PlatformCameraAutoFocusOperationsFactory,
  private val analysisCoordinator: CameraAnalysisCoordinator = CameraAnalysisCoordinator(),
) : AutoCloseable {
  private val _mainHandler = Handler(Looper.getMainLooper())
  private val _previewFrameDispatcher = (frameProcessor as? ActiveFrameProcessor.Preview)?.let { processor ->
    CameraFrameDispatcher(processor.onFrame, onError, analysisCoordinator = analysisCoordinator)
  }
  private val _sampledFrameDispatcher = (frameProcessor as? ActiveFrameProcessor.PreviewSampled)?.let { processor ->
    PreviewSampledFrameDispatcher(
      mainHandler = _mainHandler,
      intervalMillis = processor.intervalMillis,
      captureFrame = captureSampledFrame,
      onFrame = processor.onFrame,
      onError = onError,
      analysisCoordinator = analysisCoordinator,
    )
  }
  private val _cameraThread = HandlerThread(CAMERA_OPERATION_THREAD_NAME).also { it.start() }
  private val _cameraHandler = Handler(_cameraThread.looper)
  private var _camera: Camera? = null
  private var _oneShotAutoFocus: OneShotAutoFocus? = null
  @Volatile
  private var _sessionIdentity: CameraFrameTransformIdentity? = null
  @Volatile
  private var _cameraSurfaceTexture: SurfaceTexture? = null
  private var _surfaceTexture: SurfaceTexture? = textureView.surfaceTexture.takeIf { textureView.isAvailable }
  private var _started = false
  @Volatile
  private var _shouldRun = false
  @Volatile
  private var _requestGeneration = 0L
  private var _hasCameraSessionPermit = false
  @Volatile
  private var _closed = false
  private val _autoFocusCoordinator = CameraPreviewAutoFocusCoordinator(
    post = _cameraHandler::post,
    currentSessionIdentity = { _sessionIdentity },
    isClosed = { _closed },
    requestAutoFocus = { _oneShotAutoFocus?.request() },
    onPreviewFrameAvailable = onPreviewFrameAvailable,
    onError = onError,
  )

  private val _lifecycleObserver = LifecycleEventObserver { _, event ->
    when (event) {
      Lifecycle.Event.ON_START, Lifecycle.Event.ON_STOP -> updateCameraRequest()
      Lifecycle.Event.ON_DESTROY -> close()
      else -> Unit
    }
  }
  private val _surfaceTextureListener = object : TextureView.SurfaceTextureListener {
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
      _surfaceTexture = surface
      updateCameraRequest()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
      if (_surfaceTexture === surface) {
        _surfaceTexture = null
        updateCameraRequest(surface)
      } else {
        postReleaseSurfaceTexture(surface)
      }
      return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
      _autoFocusCoordinator.onSurfaceTextureUpdated(
        isActive = !_closed && _shouldRun,
        isCurrentSurface = _cameraSurfaceTexture === surface,
      )
    }
  }

  @MainThread
  fun start() {
    if (_started || _closed) return
    _started = true
    if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
      failAndClose(IllegalStateException("CameraPreview cannot use a destroyed LifecycleOwner."))
      return
    }
    lifecycleOwner.lifecycle.addObserver(_lifecycleObserver)
    textureView.surfaceTextureListener = _surfaceTextureListener
    _surfaceTexture = textureView.surfaceTexture.takeIf { textureView.isAvailable }
    updateCameraRequest()
  }

  @MainThread
  fun requestFocus() {
    _autoFocusCoordinator.requestCurrentSession()
  }

  private fun updateCameraRequest(surfaceTextureToRelease: SurfaceTexture? = null) {
    if (_closed) {
      postStopCamera(surfaceTextureToRelease)
      return
    }
    val surfaceTexture = _surfaceTexture
    val shouldRun = _started && surfaceTexture != null &&
      lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    if (_shouldRun == shouldRun) {
      if (surfaceTextureToRelease != null) postStopCamera(surfaceTextureToRelease)
      return
    }

    _shouldRun = shouldRun
    val generation = ++_requestGeneration
    if (shouldRun) {
      _cameraHandler.post { startCameraIfReady(generation, checkNotNull(surfaceTexture)) }
    } else {
      postStopCamera(surfaceTextureToRelease)
    }
  }

  private fun postStopCamera(surfaceTextureToRelease: SurfaceTexture? = null) {
    postStopThenRelease(
      post = _cameraHandler::post,
      stop = ::stopCamera,
      release = { surfaceTextureToRelease?.also(::requestSurfaceTextureRelease) },
    )
  }

  private fun postReleaseSurfaceTexture(surfaceTexture: SurfaceTexture) {
    val release = Runnable { requestSurfaceTextureRelease(surfaceTexture) }
    if (!_cameraHandler.post(release)) release.run()
  }

  private fun requestSurfaceTextureRelease(surfaceTexture: SurfaceTexture) {
    try {
      SURFACE_TEXTURE_RELEASE_COORDINATOR.requestRelease(surfaceTexture)
    } catch (error: Exception) {
      onError(error)
    }
  }

  private fun startCameraIfReady(generation: Long, surfaceTexture: SurfaceTexture) {
    checkCameraOperationThread()
    if (!isCurrentStartRequest(generation) || _camera != null) return

    val numberOfCameras = try {
      Camera.getNumberOfCameras()
    } catch (error: Exception) {
      if (isCurrentStartRequest(generation)) failAndCloseFromCameraThread(error, generation)
      return
    }
    if (!isCurrentStartRequest(generation)) return
    val resolvedCameraId = if (cameraId == null) {
      0.takeIf { numberOfCameras > 0 }
    } else {
      (0 until numberOfCameras).firstOrNull { id -> id.toString() == cameraId }
    }
    if (resolvedCameraId == null) {
      failAndCloseFromCameraThread(cameraSelectionException(cameraId), generation)
      return
    }
    if (!acquireCameraSessionPermit(generation)) return
    if (!isCurrentStartRequest(generation)) {
      stopCamera()
      return
    }

    try {
      openCamera(resolvedCameraId, surfaceTexture, generation)
    } catch (error: Exception) {
      if (isCurrentStartRequest(generation)) reportSessionFailure(cameraOpenException(resolvedCameraId, error))
      stopCamera()
    } catch (error: Error) {
      try {
        stopCamera()
      } catch (cleanupFailure: Throwable) {
        if (cleanupFailure !== error) error.addSuppressed(cleanupFailure)
      }
      throw error
    }
  }

  private fun openCamera(resolvedCameraId: Int, surfaceTexture: SurfaceTexture, generation: Long) {
    val cameraInfo = Camera.CameraInfo().also { Camera.getCameraInfo(resolvedCameraId, it) }
    val displayOrientation = calculateCameraDisplayOrientation(cameraInfo, displayRotation)
    val frameRotationDegrees = calculateCameraFrameRotation(cameraInfo, displayRotation)
    val camera = Camera.open(resolvedCameraId).also { _camera = it }
    if (!isCurrentStartRequest(generation)) {
      stopCamera()
      return
    }
    val parameters = camera.parameters
    parameters.previewFormat = ImageFormat.NV21
    val currentPreviewViewSize = previewViewSizeProvider()
    val previewSize = choosePreviewSize(
      sizes = parameters.supportedPreviewSizes.map { size -> IntSize(size.width, size.height) },
      previewViewSize = currentPreviewViewSize,
      rotationDegrees = frameRotationDegrees,
    )
    parameters.setPreviewSize(previewSize.width, previewSize.height)
    val focusMode = chooseFocusMode(parameters.supportedFocusModes)
    focusMode?.also { mode -> parameters.focusMode = mode }
    camera.parameters = parameters

    val configuredParameters = camera.parameters
    if (_previewFrameDispatcher != null || _sampledFrameDispatcher != null) {
      checkNv21PreviewFormat(configuredParameters.previewFormat)
    }
    val configuredSize = configuredParameters.previewSize
    val configuredFocusMode = configuredParameters.focusMode
    val bufferSize = IntSize(configuredSize.width, configuredSize.height)
    camera.setDisplayOrientation(displayOrientation)
    SURFACE_TEXTURE_RELEASE_COORDINATOR.retain(surfaceTexture)
    _cameraSurfaceTexture = surfaceTexture
    camera.setPreviewTexture(surfaceTexture)
    camera.setErrorCallback { errorCode, source ->
      if (!_closed && _camera === source) {
        if (isCurrentStartRequest(generation)) reportSessionFailure(cameraRuntimeException(errorCode))
        stopCamera()
      }
    }

    val sessionIdentity = CameraFrameTransformIdentity().also { _sessionIdentity = it }
    if (configuredFocusMode == Camera.Parameters.FOCUS_MODE_AUTO) {
      configureOneShotAutoFocus(camera, sessionIdentity, generation)
    }
    val sessionPublished = callOnMainThread {
      if (!isCurrentStartRequest(generation) || _sessionIdentity !== sessionIdentity) {
        false
      } else {
        onSessionStarted(
          sessionIdentity,
          bufferSize,
          frameRotationDegrees,
          cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT,
        )
      }
    }
    if (!sessionPublished) {
      stopCamera()
      return
    }
    configureFrameCallback(
      camera = camera,
      bufferSize = bufferSize,
      rotationDegrees = frameRotationDegrees,
      sessionIdentity = sessionIdentity,
      isPreviewMirrored = cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT,
      generation = generation,
    )
    if (!isCurrentStartRequest(generation)) {
      stopCamera()
      return
    }
    _sampledFrameDispatcher?.start()
    drainPendingPreviewFrame(surfaceTexture)
    if (_camera !== camera || !isCurrentStartRequest(generation)) {
      stopCamera()
      return
    }
    // 先清空旧生产者尚未消费的更新，再启用新会话首帧门控，避免漏掉同步提交的首帧
    _autoFocusCoordinator.armFirstPreviewFrame(sessionIdentity)
    camera.startPreview()
    if (_camera !== camera || !isCurrentStartRequest(generation)) {
      stopCamera()
      return
    }
  }

  private fun drainPendingPreviewFrame(surfaceTexture: SurfaceTexture) {
    callOnMainThread {
      if (textureView.isAvailable && textureView.surfaceTexture === surfaceTexture) {
        textureView.getBitmap(1, 1)?.recycle()
      }
    }
  }

  private fun configureOneShotAutoFocus(
    camera: Camera,
    sessionIdentity: CameraFrameTransformIdentity,
    generation: Long,
  ) {
    val autoFocusOperations = autoFocusOperationsFactory.create(camera)
    _oneShotAutoFocus = OneShotAutoFocus(
      handler = _cameraHandler,
      focus = { onComplete ->
        if (
          _camera !== camera ||
          _sessionIdentity !== sessionIdentity ||
          !isCurrentStartRequest(generation)
        ) {
          onComplete()
        } else {
          autoFocusOperations.cancelAutoFocus()
          autoFocusOperations.autoFocus(onComplete)
        }
      },
      onError = onError,
    )
  }

  private fun configureFrameCallback(
    camera: Camera,
    bufferSize: IntSize,
    rotationDegrees: Int,
    sessionIdentity: CameraFrameTransformIdentity,
    isPreviewMirrored: Boolean,
    generation: Long,
  ) {
    val previewDispatcher = _previewFrameDispatcher
    val sampledDispatcher = _sampledFrameDispatcher
    if (previewDispatcher == null && sampledDispatcher == null) return
    val bufferBytes = checkNotNull(nv21BufferSize(bufferSize.width, bufferSize.height)) {
      "The camera reported an invalid NV21 preview size: $bufferSize."
    }
    repeat(CAMERA_CALLBACK_BUFFER_COUNT) { camera.addCallbackBuffer(ByteArray(bufferBytes)) }
    camera.setPreviewCallbackWithBuffer { data, source ->
      if (_closed || _camera !== source) return@setPreviewCallbackWithBuffer
      if (!isCurrentStartRequest(generation)) {
        returnStalePreviewCallbackBuffer(data, source::addCallbackBuffer, onError)
        return@setPreviewCallbackWithBuffer
      }
      if (data == null) {
        reportNullPreviewCallbackAndStop(::reportSessionFailure, ::stopCamera)
        return@setPreviewCallbackWithBuffer
      }
      if (previewDispatcher != null) {
        previewDispatcher.offer(
          data = data,
          width = bufferSize.width,
          height = bufferSize.height,
          rotationDegrees = rotationDegrees,
          transformIdentity = transformIdentityProvider(),
          returnBuffer = { buffer -> returnCallbackBuffer(source, buffer) },
        )
      } else {
        var failure: Throwable? = null
        try {
          sampledDispatcher?.offer(sessionIdentity, isPreviewMirrored)
        } catch (error: Throwable) {
          failure = error
        } finally {
          failure = releaseFrameBuffer(data, { buffer -> returnCallbackBuffer(source, buffer) }, failure)
        }
        reportFrameFailure(failure, onError)
      }
    }
  }

  private fun returnCallbackBuffer(source: Camera, buffer: ByteArray) {
    if (_closed) return
    callOnCameraThread {
      if (!_closed && _camera === source) source.addCallbackBuffer(buffer)
    }
  }

  private fun acquireCameraSessionPermit(generation: Long): Boolean {
    while (isCurrentStartRequest(generation)) {
      try {
        if (CAMERA_SESSION_PERMIT.tryAcquire(CAMERA_SESSION_PERMIT_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
          _hasCameraSessionPermit = true
          return true
        }
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return false
      }
    }
    return false
  }

  private fun isCurrentStartRequest(generation: Long): Boolean {
    return !_closed && _shouldRun && _requestGeneration == generation
  }

  private fun failAndCloseFromCameraThread(error: Throwable, generation: Long) {
    _mainHandler.post {
      if (!isCurrentStartRequest(generation)) return@post
      reportSessionFailure(error)
      close()
    }
  }

  private fun reportSessionFailure(error: Throwable) {
    onSessionFailure(error)
    onError(error)
  }

  private fun failAndClose(error: Throwable) {
    if (_closed) return
    onError(error)
    close()
  }

  private fun stopCamera() {
    checkCameraOperationThread()
    val camera = _camera
    val sessionIdentity = _sessionIdentity
    val cameraSurfaceTexture = _cameraSurfaceTexture
    _oneShotAutoFocus?.close()
    _oneShotAutoFocus = null
    _camera = null
    _sessionIdentity = null
    _autoFocusCoordinator.clearFirstPreviewFrame()
    _cameraSurfaceTexture = null
    _previewFrameDispatcher?.discardPending()
    _sampledFrameDispatcher?.stop()
    runCameraCleanupActions(
      actions = buildList {
        if (camera != null) {
          add { camera.setPreviewCallbackWithBuffer(null) }
          add { camera.setErrorCallback(null) }
          add { camera.stopPreview() }
          add { camera.release() }
        }
        if (sessionIdentity != null) add { callOnMainThread { onSessionClosed(sessionIdentity) } }
      },
      finalAction = {
        try {
          cameraSurfaceTexture?.also(SURFACE_TEXTURE_RELEASE_COORDINATOR::releaseAfterUse)
        } finally {
          releaseCameraSessionPermit()
        }
      },
    )?.also(onError)
  }

  private fun releaseCameraSessionPermit() {
    if (!_hasCameraSessionPermit) return
    _hasCameraSessionPermit = false
    CAMERA_SESSION_PERMIT.release()
  }

  private fun <T> callOnMainThread(action: () -> T): T {
    return callOnHandlerThread(_mainHandler, action)
  }

  private fun <T> callOnCameraThread(action: () -> T): T {
    return callOnHandlerThread(_cameraHandler, action)
  }

  private fun checkCameraOperationThread() {
    check(Looper.myLooper() === _cameraHandler.looper) {
      "Camera operations must run on $CAMERA_OPERATION_THREAD_NAME."
    }
  }

  @MainThread
  override fun close() {
    if (_closed) return
    _closed = true
    _shouldRun = false
    _requestGeneration++
    lifecycleOwner.lifecycle.removeObserver(_lifecycleObserver)
    runCameraCleanupActions(
      actions = buildList {
        _previewFrameDispatcher?.also { dispatcher -> add(dispatcher::close) }
        _sampledFrameDispatcher?.also { dispatcher -> add(dispatcher::close) }
      },
      finalAction = {
        _cameraHandler.post {
          try {
            stopCamera()
          } finally {
            try {
              callOnMainThread {
                if (textureView.surfaceTextureListener === _surfaceTextureListener) {
                  textureView.surfaceTextureListener = null
                }
                _surfaceTexture = null
              }
            } finally {
              _cameraThread.quitSafely()
            }
          }
        }
      },
    )?.also(onError)
  }
}

internal const val CAMERA_OPERATION_THREAD_NAME = "CameraPreview-Camera"
internal const val CAMERA_ANALYSIS_THREAD_NAME = "CameraPreview-Analysis"
private const val CAMERA_CALLBACK_BUFFER_COUNT = 3
private const val MAX_PREVIEW_PIXELS = 1280 * 960
private const val PREVIEW_QUALITY_AREA_DIVISOR = 4
private const val AUTO_FOCUS_TIMEOUT_MILLIS = 3_000L
private const val CAMERA_SESSION_PERMIT_POLL_MILLIS = 100L
// 避免重建时新会话在旧会话异步释放前打开相机
private val CAMERA_SESSION_PERMIT = Semaphore(1, true)
private val SURFACE_TEXTURE_RELEASE_COORDINATOR = SurfaceTextureReleaseCoordinator()

internal fun postStopThenRelease(
  post: (Runnable) -> Boolean,
  stop: () -> Unit,
  release: () -> Unit,
) {
  val task = Runnable {
    try {
      stop()
    } finally {
      release()
    }
  }
  if (!post(task)) release()
}

internal fun reportNullPreviewCallbackAndStop(
  onError: (Throwable) -> Unit,
  stopSession: () -> Unit,
) {
  try {
    onError(nullPreviewCallbackBufferException())
  } finally {
    stopSession()
  }
}

/** 延迟释放仍被相机会话使用的 SurfaceTexture。 */
internal class SurfaceTextureReleaseCoordinator(
  private val releaseSurfaceTexture: (SurfaceTexture) -> Unit = SurfaceTexture::release,
) {
  private val _lock = Any()
  private val _states = WeakHashMap<SurfaceTexture, SurfaceTextureReleaseState>()

  fun retain(surfaceTexture: SurfaceTexture) {
    synchronized(_lock) {
      val state = _states.getOrPut(surfaceTexture, ::SurfaceTextureReleaseState)
      check(!state.releaseRequested && !state.released) { "SurfaceTexture has already been destroyed." }
      state.useCount++
    }
  }

  fun requestRelease(surfaceTexture: SurfaceTexture) {
    val shouldRelease = synchronized(_lock) {
      val state = _states.getOrPut(surfaceTexture, ::SurfaceTextureReleaseState)
      if (state.releaseRequested || state.released) {
        false
      } else {
        state.releaseRequested = true
        if (state.useCount == 0) {
          state.released = true
          true
        } else {
          false
        }
      }
    }
    if (shouldRelease) releaseSurfaceTexture(surfaceTexture)
  }

  fun releaseAfterUse(surfaceTexture: SurfaceTexture) {
    val shouldRelease = synchronized(_lock) {
      val state = checkNotNull(_states[surfaceTexture]) { "SurfaceTexture is not retained." }
      check(state.useCount > 0) { "SurfaceTexture use count is already zero." }
      state.useCount--
      if (state.useCount == 0 && state.releaseRequested) {
        state.released = true
        true
      } else {
        if (state.useCount == 0) _states.remove(surfaceTexture)
        false
      }
    }
    if (shouldRelease) releaseSurfaceTexture(surfaceTexture)
  }
}

private class SurfaceTextureReleaseState {
  var useCount = 0
  var releaseRequested = false
  var released = false
}

private fun <T> callOnHandlerThread(handler: Handler, action: () -> T): T {
  if (Looper.myLooper() === handler.looper) return action()
  val task = FutureTask(action)
  check(handler.post(task)) { "The target handler thread is not available." }
  return try {
    task.get()
  } catch (error: ExecutionException) {
    throw error.cause ?: error
  } catch (error: InterruptedException) {
    Thread.currentThread().interrupt()
    throw error
  }
}

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

/** 在单个 CameraPreview 生命周期内串行协调分析，只保留最新待处理任务。 */
internal class CameraAnalysisCoordinator : AutoCloseable {
  private val _lock = Any()
  private var _processing = false
  private var _pending: PendingCameraAnalysis? = null
  private var _closed = false

  fun offer(
    owner: Any,
    execute: (Runnable) -> Unit,
    process: () -> Unit,
    discard: (Throwable?) -> Unit,
  ) {
    val analysis = PendingCameraAnalysis(owner, process, discard)
    var replaced: PendingCameraAnalysis? = null
    var schedulingFailure: Throwable? = null
    synchronized(_lock) {
      if (_closed) {
        replaced = analysis
      } else if (_processing) {
        replaced = _pending
        _pending = analysis
      } else {
        _processing = true
        _pending = analysis
        try {
          execute(Runnable(::drain))
        } catch (error: Throwable) {
          _processing = false
          _pending = null
          replaced = analysis
          schedulingFailure = error
        }
      }
    }
    replaced?.also { dropped -> dropped.discard(schedulingFailure) }
  }

  fun discardPending(owner: Any) {
    val pending = synchronized(_lock) {
      _pending?.takeIf { analysis -> analysis.owner === owner }?.also { _pending = null }
    }
    pending?.also { analysis -> analysis.discard(null) }
  }

  private fun drain() {
    while (true) {
      val analysis = synchronized(_lock) {
        val next = _pending
        _pending = null
        if (next == null) _processing = false
        next
      } ?: return
      try {
        analysis.process()
      } finally {
        // 共享 drain 中的任务需要隔离用户回调留下的 interrupt 状态
        Thread.interrupted()
      }
    }
  }

  override fun close() {
    val pending = synchronized(_lock) {
      if (_closed) return
      _closed = true
      _pending.also { _pending = null }
    }
    pending?.also { analysis -> analysis.discard(null) }
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
  private val analysisCoordinator: CameraAnalysisCoordinator = CameraAnalysisCoordinator(),
  private val executor: ExecutorService = createCameraAnalysisExecutor(),
  private val beforeFrameStart: (() -> Unit)? = null,
) : AutoCloseable {
  private val _lock = Any()
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
    val frame = PendingCameraFrame(
      data = data,
      width = width,
      height = height,
      rotationDegrees = rotationDegrees,
      transformIdentity = transformIdentity,
      returnBuffer = returnBuffer,
    )
    var shouldDiscard = false
    synchronized(_lock) {
      if (_closed) {
        shouldDiscard = true
      } else {
        analysisCoordinator.offer(
          owner = this,
          execute = executor::execute,
          process = { process(frame) },
          discard = { failure -> reportOrThrow(releaseFrameBuffer(frame, failure)) },
        )
      }
    }
    if (shouldDiscard) reportOrThrow(releaseFrameBuffer(frame))
  }

  private fun process(frame: PendingCameraFrame) {
    var failure: Throwable? = null
    try {
      beforeFrameStart?.invoke()
      // 与 close() 共用锁，将已出队帧的开始点线性化
      if (synchronized(_lock) { !_closed }) {
        onFrame(
          CameraFrame.Preview(
            data = frame.data,
            width = frame.width,
            height = frame.height,
            rotationDegrees = frame.rotationDegrees,
            transformIdentity = frame.transformIdentity,
          ),
        )
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
    analysisCoordinator.discardPending(this)
  }

  override fun close() {
    var shouldClose = false
    synchronized(_lock) {
      if (!_closed) {
        _closed = true
        shouldClose = true
      }
    }
    if (!shouldClose) return
    try {
      analysisCoordinator.discardPending(this)
    } finally {
      executor.shutdown()
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
  private val analysisCoordinator: CameraAnalysisCoordinator = CameraAnalysisCoordinator(),
  private val executor: ExecutorService = createCameraAnalysisExecutor(),
) : AutoCloseable {
  private val _lock = Any()
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
      analysisCoordinator.offer(
        owner = this,
        execute = executor::execute,
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
    }
    analysisCoordinator.discardPending(this)
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
      analysisCoordinator.discardPending(this)
    } finally {
      executor.shutdown()
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

private fun reportFrameFailure(failure: Throwable?, onError: (Throwable) -> Unit) {
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

private fun mergeFrameFailures(failure: Throwable, nextFailure: Throwable): Throwable {
  return when {
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
    when {
      failure == null -> recycleFailure
      failure is Error || recycleFailure !is Error -> failure.also {
        if (failure !== recycleFailure) failure.addSuppressed(recycleFailure)
      }
      else -> recycleFailure.also { recycleFailure.addSuppressed(failure) }
    }
  }
}

private fun releaseFrameBuffer(frame: PendingCameraFrame, failure: Throwable? = null): Throwable? {
  return releaseFrameBuffer(frame.data, frame.returnBuffer, failure)
}

private fun releaseFrameBuffer(
  data: ByteArray,
  returnBuffer: (ByteArray) -> Unit,
  failure: Throwable? = null,
): Throwable? {
  return try {
    returnBuffer(data)
    failure
  } catch (returnFailure: Throwable) {
    when {
      failure == null -> returnFailure
      failure is Error || returnFailure !is Error -> failure.also {
        if (failure !== returnFailure) failure.addSuppressed(returnFailure)
      }
      else -> returnFailure.also { returnFailure.addSuppressed(failure) }
    }
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
  val returnBuffer: (ByteArray) -> Unit,
)

private data class PendingSampledFrame(
  val sessionIdentity: CameraFrameTransformIdentity,
  val isPreviewMirrored: Boolean,
)

internal fun choosePreviewSize(
  sizes: List<IntSize>,
  previewViewSize: IntSize,
  rotationDegrees: Int,
): IntSize {
  require(sizes.isNotEmpty()) { "The camera did not report any preview size." }
  val targetAspectRatio = if (previewViewSize.width > 0 && previewViewSize.height > 0) {
    previewViewSize.width.toFloat() / previewViewSize.height
  } else {
    1f
  }
  val normalizedRotation = normalizeRotation(rotationDegrees)
  val isQuarterTurn = normalizedRotation == 90 || normalizedRotation == 270
  val aspectComparator = compareBy<IntSize> { size ->
    val orientedWidth = if (isQuarterTurn) size.height else size.width
    val orientedHeight = if (isQuarterTurn) size.width else size.height
    abs(orientedWidth.toFloat() / orientedHeight - targetAspectRatio)
  }
  val boundedSizes = sizes.filter { size -> previewArea(size) <= MAX_PREVIEW_PIXELS }
  return if (boundedSizes.isNotEmpty()) {
    val largestArea = boundedSizes.maxOf(::previewArea)
    boundedSizes
      .filter { size -> previewArea(size) >= largestArea / PREVIEW_QUALITY_AREA_DIVISOR }
      .minWithOrNull(aspectComparator.thenByDescending(::previewArea))
  } else {
    sizes.minByOrNull(::previewArea)
  } ?: sizes.first()
}

private fun previewArea(size: IntSize): Long = size.width.toLong() * size.height

internal fun calculateCameraDisplayOrientation(cameraInfo: Camera.CameraInfo, displayRotation: Int): Int {
  val displayDegrees = displayRotationDegrees(displayRotation)
  return if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
    val result = (cameraInfo.orientation + displayDegrees) % 360
    (360 - result) % 360
  } else {
    (cameraInfo.orientation - displayDegrees + 360) % 360
  }
}

internal fun calculateCameraFrameRotation(cameraInfo: Camera.CameraInfo, displayRotation: Int): Int {
  val displayDegrees = displayRotationDegrees(displayRotation)
  return if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
    (cameraInfo.orientation + displayDegrees) % 360
  } else {
    (cameraInfo.orientation - displayDegrees + 360) % 360
  }
}

private fun displayRotationDegrees(displayRotation: Int): Int {
  return when (displayRotation) {
    Surface.ROTATION_0 -> 0
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
  }
}

internal fun chooseFocusMode(supportedModes: List<String>?): String? {
  return when {
    supportedModes == null -> null
    Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in supportedModes -> {
      Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
    }
    Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO in supportedModes -> {
      Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
    }
    Camera.Parameters.FOCUS_MODE_AUTO in supportedModes -> Camera.Parameters.FOCUS_MODE_AUTO
    else -> null
  }
}
