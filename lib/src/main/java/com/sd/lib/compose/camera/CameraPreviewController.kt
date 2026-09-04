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

/** 管理一次 [CameraPreview] 会话和帧分发 */
internal class CameraPreviewController(
  runtimeLease: CameraPreviewRuntimeLease,
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
  private val _runtimeLease = runtimeLease
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
  @Volatile
  private var _sampledSurfaceFrameSession: SampledSurfaceFrameSession? = null
  private var _surfaceTexture: SurfaceTexture? = textureView.surfaceTexture.takeIf { textureView.isAvailable }
  private var _started = false
  @Volatile
  private var _shouldRun = false
  @Volatile
  private var _requestGeneration = 0L
  @Volatile
  private var _closed = false
  private var _cameraThreadFailureReported = false
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
      val isActive = !_closed && _shouldRun
      val isCurrentSurface = _cameraSurfaceTexture === surface
      _autoFocusCoordinator.onSurfaceTextureUpdated(
        isActive = isActive,
        isCurrentSurface = isCurrentSurface,
      )
      if (!isActive || !isCurrentSurface) return
      val sampledSession = _sampledSurfaceFrameSession ?: return
      if (_sessionIdentity !== sampledSession.sessionIdentity) return
      _sampledFrameDispatcher?.offer(sampledSession.sessionIdentity, sampledSession.isPreviewMirrored)
    }
  }

  @MainThread
  fun start() {
    if (_started || _closed) return
    _started = true
    try {
      if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
        failAndClose(IllegalStateException("CameraPreview cannot use a destroyed LifecycleOwner."))
        return
      }
      lifecycleOwner.lifecycle.addObserver(_lifecycleObserver)
      if (_closed) return
      textureView.surfaceTextureListener = _surfaceTextureListener
      _surfaceTexture = textureView.surfaceTexture.takeIf { textureView.isAvailable }
      updateCameraRequest()
    } catch (error: Throwable) {
      throwAfterCleanup(error, listOf(::close))
    }
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

    if (!shouldRun) requestFrameDispatchersStop()
    _shouldRun = shouldRun
    val generation = ++_requestGeneration
    if (shouldRun) {
      if (!_cameraHandler.post { startCameraIfReady(generation, checkNotNull(surfaceTexture)) }) {
        failAfterCameraThreadFailure()
      }
    } else {
      postStopCamera(surfaceTextureToRelease)
    }
  }

  private fun postStopCamera(surfaceTextureToRelease: SurfaceTexture? = null) {
    val posted = postStopThenRelease(
      post = _cameraHandler::post,
      stop = ::stopCamera,
      release = { surfaceTextureToRelease?.also(::requestSurfaceTextureRelease) },
    )
    if (!posted && !_closed) failAfterCameraThreadFailure()
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
      throwAfterCleanup(error, listOf(::stopCamera))
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
    if (_previewFrameDispatcher != null) parameters.previewFormat = ImageFormat.NV21
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
    if (_previewFrameDispatcher != null) checkNv21PreviewFormat(configuredParameters.previewFormat)
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
    _previewFrameDispatcher?.start()
    configureFrameCallback(
      camera = camera,
      bufferSize = bufferSize,
      rotationDegrees = frameRotationDegrees,
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
    // 先清空旧生产者尚未消费的更新，再启用新会话首帧门控，避免漏掉同步提交的首帧。
    _autoFocusCoordinator.armFirstPreviewFrame(sessionIdentity)
    _sampledSurfaceFrameSession = _sampledFrameDispatcher?.let {
      SampledSurfaceFrameSession(
        sessionIdentity = sessionIdentity,
        isPreviewMirrored = cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT,
      )
    }
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
    generation: Long,
  ) {
    val previewDispatcher = _previewFrameDispatcher ?: return
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
      previewDispatcher.offer(
        data = data,
        width = bufferSize.width,
        height = bufferSize.height,
        rotationDegrees = rotationDegrees,
        transformIdentity = transformIdentityProvider(),
        returnBuffer = { buffer -> returnCallbackBuffer(source, buffer) },
      )
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

  @MainThread
  private fun failAfterCameraThreadFailure() {
    reportCameraThreadFailure(isSessionFailure = true)
    close()
  }

  @MainThread
  private fun reportCameraThreadFailure(isSessionFailure: Boolean = false) {
    if (_cameraThreadFailureReported) return
    _cameraThreadFailureReported = true
    _runtimeLease.invalidateRuntime()
    val error = cameraOperationThreadUnavailableException()
    if (isSessionFailure) reportSessionFailure(error) else onError(error)
  }

  private fun requestFrameDispatchersStop() {
    _previewFrameDispatcher?.requestStop()
    _sampledFrameDispatcher?.requestStop()
  }

  private fun stopCamera() {
    checkCameraOperationThread()
    stopCameraSession()
  }

  private fun stopCameraAfterCameraThreadTermination() {
    check(!_cameraHandler.looper.thread.isAlive) {
      "The camera operation thread must stop before fallback cleanup."
    }
    stopCameraSession()
  }

  private fun stopCameraSession() {
    requestFrameDispatchersStop()
    val camera = _camera
    val oneShotAutoFocus = _oneShotAutoFocus
    val sessionIdentity = _sessionIdentity
    val cameraSurfaceTexture = _cameraSurfaceTexture
    _oneShotAutoFocus = null
    _camera = null
    _sessionIdentity = null
    _sampledSurfaceFrameSession = null
    _autoFocusCoordinator.clearFirstPreviewFrame()
    _cameraSurfaceTexture = null
    runCleanupActions(
      actions = buildList {
        oneShotAutoFocus?.also { add(it::close) }
        _previewFrameDispatcher?.also { add(it::stop) }
        _sampledFrameDispatcher?.also { add(it::stop) }
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

  private fun closeFrameDispatchers(finalAction: () -> Unit): Exception? {
    return runCleanupActions(
      actions = buildList {
        _previewFrameDispatcher?.also { dispatcher -> add(dispatcher::close) }
        _sampledFrameDispatcher?.also { dispatcher -> add(dispatcher::close) }
      },
      finalAction = finalAction,
    )
  }

  @MainThread
  private fun detachSurfaceTextureListener() {
    if (textureView.surfaceTextureListener === _surfaceTextureListener) {
      textureView.surfaceTextureListener = null
    }
    _surfaceTexture = null
  }

  private fun finishClose(stopCameraAction: () -> Unit) {
    runCleanupActions(
      actions = listOf(
        { closeFrameDispatchers(stopCameraAction)?.also(onError) },
        { callOnMainThread(::detachSurfaceTextureListener) },
      ),
      finalAction = _runtimeLease::close,
    )?.also(onError)
  }

  @MainThread
  private fun scheduleClose() {
    val closeTask = Runnable { finishClose(::stopCamera) }
    if (_cameraHandler.post(closeTask)) return

    reportCameraThreadFailure()
    val cameraThread = _cameraHandler.looper.thread
    try {
      Thread(
        {
          awaitThreadTerminationUninterruptibly(cameraThread)
          finishClose(::stopCameraAfterCameraThreadTermination)
        },
        CAMERA_OPERATION_CLEANUP_THREAD_NAME,
      ).start()
    } catch (error: Exception) {
      var failure = error
      try {
        runCleanupActions(
          actions = listOf(::detachSurfaceTextureListener),
          finalAction = _runtimeLease::close,
        )?.also { cleanupFailure -> failure = mergeFailures(failure, cleanupFailure) }
      } catch (cleanupFailure: Error) {
        throw mergeFailures(failure, cleanupFailure)
      }
      onError(failure)
    } catch (error: Error) {
      throwAfterCleanup(
        error,
        listOf(::detachSurfaceTextureListener, _runtimeLease::close),
      )
    }
  }

  @MainThread
  override fun close() {
    if (_closed) return
    _closed = true
    _shouldRun = false
    _requestGeneration++
    _previewFrameDispatcher?.requestClose()
    _sampledFrameDispatcher?.requestClose()
    runCleanupActions(
      actions = listOf { lifecycleOwner.lifecycle.removeObserver(_lifecycleObserver) },
      finalAction = ::scheduleClose,
    )?.also(onError)
  }
}

private data class SampledSurfaceFrameSession(
  val sessionIdentity: CameraFrameTransformIdentity,
  val isPreviewMirrored: Boolean,
)

private const val CAMERA_CALLBACK_BUFFER_COUNT = 3
private const val CAMERA_OPERATION_CLEANUP_THREAD_NAME = "CameraPreview-Camera-Cleanup"
private val SURFACE_TEXTURE_RELEASE_COORDINATOR = SurfaceTextureReleaseCoordinator()

private fun cameraOperationThreadUnavailableException(): IllegalStateException {
  return IllegalStateException("The camera operation thread is not available.")
}
