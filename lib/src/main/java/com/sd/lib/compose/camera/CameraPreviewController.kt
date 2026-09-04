@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.annotation.MainThread
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.util.WeakHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import kotlin.math.abs

/** 管理一次 [CameraPreview] 会话和帧分发 */
internal class CameraPreviewController(
  runtime: CameraPreviewRuntime,
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
) : AutoCloseable {
  private val _mainHandler = Handler(Looper.getMainLooper())
  private val _runtimeLease = runtime.acquire()
  private val _cameraHandler = _runtimeLease.cameraHandler
  private val _analysisCoordinator = _runtimeLease.analysisCoordinator
  private val _previewFrameDispatcher = (frameProcessor as? ActiveFrameProcessor.Preview)?.let { processor ->
    CameraFrameDispatcher(processor.onFrame, onError, analysisCoordinator = _analysisCoordinator)
  }
  private val _sampledFrameDispatcher = (frameProcessor as? ActiveFrameProcessor.PreviewSampled)?.let { processor ->
    PreviewSampledFrameDispatcher(
      mainHandler = _mainHandler,
      intervalMillis = processor.intervalMillis,
      captureFrame = captureSampledFrame,
      onFrame = processor.onFrame,
      onError = onError,
      analysisCoordinator = _analysisCoordinator,
    )
  }
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
        cameraSurfaceTexture?.also(SURFACE_TEXTURE_RELEASE_COORDINATOR::releaseAfterUse)
      },
    )?.also(onError)
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
    if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
      _runtimeLease.closeRuntime()
    }
    _shouldRun = false
    _requestGeneration++
    lifecycleOwner.lifecycle.removeObserver(_lifecycleObserver)
    runCameraCleanupActions(
      actions = buildList {
        _previewFrameDispatcher?.also { dispatcher -> add(dispatcher::close) }
        _sampledFrameDispatcher?.also { dispatcher -> add(dispatcher::close) }
      },
      finalAction = {
        val closeTask = Runnable {
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
              _runtimeLease.close()
            }
          }
        }
        if (!_cameraHandler.post(closeTask)) {
          try {
            onError(IllegalStateException("The camera operation thread is not available."))
          } finally {
            _runtimeLease.close()
          }
        }
      },
    )?.also(onError)
  }
}

private const val CAMERA_CALLBACK_BUFFER_COUNT = 3
private const val MAX_PREVIEW_PIXELS = 1280 * 960
private const val PREVIEW_QUALITY_AREA_DIVISOR = 4
private const val AUTO_FOCUS_TIMEOUT_MILLIS = 3_000L
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
