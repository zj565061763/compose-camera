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
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** 管理一次 [CameraPreview] 会话和帧分发 */
internal class CameraPreviewController(
  private val lifecycleOwner: LifecycleOwner,
  private val textureView: TextureView,
  private val cameraId: String?,
  private val displayRotation: Int,
  private val previewViewSize: IntSize,
  private val transformIdentityProvider: () -> CameraFrameTransformIdentity?,
  private val onSessionStarted: (
    CameraFrameTransformIdentity,
    IntSize,
    Int,
    Boolean,
  ) -> Unit,
  frameProcessor: ActiveFrameProcessor,
  private val captureSampledFrame: (
    CameraFrameTransformIdentity,
    Boolean,
  ) -> CameraFrame.PreviewSampled?,
  private val onError: (Throwable) -> Unit,
  private val onSessionClosed: (CameraFrameTransformIdentity?) -> Unit,
) : AutoCloseable {
  private val _mainHandler = Handler(Looper.getMainLooper())
  private val _previewFrameDispatcher = (frameProcessor as? ActiveFrameProcessor.Preview)?.let { processor ->
    CameraFrameDispatcher(processor.onFrame, onError)
  }
  private val _sampledFrameDispatcher = (frameProcessor as? ActiveFrameProcessor.PreviewSampled)?.let { processor ->
    PreviewSampledFrameDispatcher(
      mainHandler = _mainHandler,
      intervalMillis = processor.intervalMillis,
      captureFrame = captureSampledFrame,
      onFrame = processor.onFrame,
      onError = onError,
    )
  }
  private val _cameraThread = HandlerThread(CAMERA_OPERATION_THREAD_NAME).also { it.start() }
  private val _cameraHandler = Handler(_cameraThread.looper)
  private var _camera: Camera? = null
  private var _periodicAutoFocus: PeriodicAutoFocus? = null
  @Volatile
  private var _sessionIdentity: CameraFrameTransformIdentity? = null
  private var _surfaceTexture: SurfaceTexture? = textureView.surfaceTexture.takeIf { textureView.isAvailable }
  private var _started = false
  @Volatile
  private var _shouldRun = false
  @Volatile
  private var _requestGeneration = 0L
  private var _hasCameraSessionPermit = false
  @Volatile
  private var _closed = false

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
        surface.release()
      }
      return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
  }

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
      release = { surfaceTextureToRelease?.release() },
    )
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
      if (isCurrentStartRequest(generation)) onError(cameraOpenException(resolvedCameraId, error))
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
    val previewSize = choosePreviewSize(
      sizes = parameters.supportedPreviewSizes.map { size -> IntSize(size.width, size.height) },
      previewViewSize = previewViewSize,
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
    camera.setPreviewTexture(surfaceTexture)
    camera.setErrorCallback { errorCode, source ->
      if (!_closed && _camera === source) {
        if (isCurrentStartRequest(generation)) onError(cameraRuntimeException(errorCode))
        stopCamera()
      }
    }

    val sessionIdentity = CameraFrameTransformIdentity().also { _sessionIdentity = it }
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
        true
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
    camera.startPreview()
    if (configuredFocusMode == Camera.Parameters.FOCUS_MODE_AUTO) startPeriodicAutoFocus(camera, generation)
  }

  private fun startPeriodicAutoFocus(camera: Camera, generation: Long) {
    _periodicAutoFocus = PeriodicAutoFocus(
      handler = _cameraHandler,
      focus = {
        if (_camera === camera && isCurrentStartRequest(generation)) {
          camera.cancelAutoFocus()
          camera.autoFocus(null)
        }
      },
      onError = onError,
    ).also { it.start() }
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
        source.addCallbackBuffer(data)
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
      onError(error)
      close()
    }
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
    _periodicAutoFocus?.close()
    _periodicAutoFocus = null
    _camera = null
    _sessionIdentity = null
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
      finalAction = ::releaseCameraSessionPermit,
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

  override fun close() {
    if (_closed) return
    _closed = true
    _shouldRun = false
    _requestGeneration++
    lifecycleOwner.lifecycle.removeObserver(_lifecycleObserver)
    if (textureView.surfaceTextureListener === _surfaceTextureListener) {
      textureView.surfaceTextureListener = null
    }
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
            _cameraThread.quitSafely()
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
private const val AUTO_FOCUS_INTERVAL_MILLIS = 2_000L
private const val CAMERA_SESSION_PERMIT_POLL_MILLIS = 100L
// 避免重建时新会话在旧会话异步释放前打开相机
private val CAMERA_SESSION_PERMIT = Semaphore(1, true)

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

/** 在不支持连续对焦时定期触发单次自动对焦 */
internal class PeriodicAutoFocus(
  private val handler: Handler,
  private val intervalMillis: Long = AUTO_FOCUS_INTERVAL_MILLIS,
  private val focus: () -> Unit,
  private val onError: (Throwable) -> Unit,
) : AutoCloseable {
  private var _started = false
  private var _closed = false
  private val _task = object : Runnable {
    override fun run() {
      if (_closed) return
      try {
        focus()
      } catch (error: Exception) {
        onError(error)
      }
      if (!_closed) handler.postDelayed(this, intervalMillis)
    }
  }

  fun start() {
    if (_started || _closed) return
    _started = true
    handler.post(_task)
  }

  override fun close() {
    if (_closed) return
    _closed = true
    handler.removeCallbacks(_task)
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

/** 单线程发布最新 NV21 帧，并在处理完成或被替换时归还回调缓冲区。 */
internal class CameraFrameDispatcher(
  private val onFrame: (CameraFrame.Preview) -> Unit,
  private val onError: (Throwable) -> Unit,
) : AutoCloseable {
  private val _lock = Any()
  private val _executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, CAMERA_ANALYSIS_THREAD_NAME)
  }
  private var _processing = false
  private var _pending: PendingCameraFrame? = null
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
    var replaced: PendingCameraFrame? = null
    var shouldSchedule = false
    synchronized(_lock) {
      if (_closed) {
        replaced = frame
      } else if (_processing) {
        replaced = _pending
        _pending = frame
      } else {
        _processing = true
        _pending = frame
        shouldSchedule = true
      }
    }
    replaced?.also { dropped -> reportOrThrow(releaseFrameBuffer(dropped)) }
    if (shouldSchedule) _executor.execute(::drain)
  }

  private fun drain() {
    while (true) {
      val pending = synchronized(_lock) {
        val next = _pending
        _pending = null
        if (next == null) _processing = false
        next
      } ?: return

      var failure: Throwable? = null
      try {
        onFrame(
          CameraFrame.Preview(
            data = pending.data,
            width = pending.width,
            height = pending.height,
            rotationDegrees = pending.rotationDegrees,
            transformIdentity = pending.transformIdentity,
          ),
        )
      } catch (error: Throwable) {
        failure = error
      } finally {
        failure = releaseFrameBuffer(pending, failure)
      }
      reportOrThrow(failure)
    }
  }

  fun discardPending() {
    val pending = synchronized(_lock) { _pending.also { _pending = null } }
    pending?.also { reportOrThrow(releaseFrameBuffer(it)) }
  }

  override fun close() {
    val pending = synchronized(_lock) {
      if (_closed) return
      _closed = true
      _pending.also { _pending = null }
    }
    try {
      pending?.also { reportOrThrow(releaseFrameBuffer(it)) }
    } finally {
      _executor.shutdown()
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
) : AutoCloseable {
  private val _lock = Any()
  private val _executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, CAMERA_ANALYSIS_THREAD_NAME)
  }
  private var _started = false
  private var _closed = false
  private var _lastSampleTimeMillis = 0L
  private var _runGeneration = 0L
  private var _capturePosted = false
  private var _processing = false
  private var _pending: PendingSampledFrame? = null

  fun start() {
    synchronized(_lock) {
      if (_started || _closed) return
      _started = true
      _runGeneration++
      _lastSampleTimeMillis = elapsedRealtimeMillis()
    }
  }

  fun offer(sessionIdentity: CameraFrameTransformIdentity, isPreviewMirrored: Boolean) {
    var shouldPostCapture = false
    synchronized(_lock) {
      if (!_started || _closed) return
      val now = elapsedRealtimeMillis()
      val currentIntervalMillis = intervalMillis()
      if (currentIntervalMillis <= 0 || now - _lastSampleTimeMillis < currentIntervalMillis) return
      _lastSampleTimeMillis = now
      _pending = PendingSampledFrame(sessionIdentity, isPreviewMirrored)
      if (!_processing && !_capturePosted) {
        _capturePosted = true
        shouldPostCapture = true
      }
    }
    if (shouldPostCapture && !mainHandler.post(::capturePending)) {
      synchronized(_lock) { _capturePosted = false }
      onError(IllegalStateException("The main thread is not available for preview sampling."))
    }
  }

  private fun capturePending() {
    val capture = synchronized(_lock) {
      _capturePosted = false
      if (!_started || _closed || _processing) return
      val pending = _pending ?: return
      _pending = null
      _processing = true
      ActiveSampledCapture(pending, _runGeneration)
    }

    val frame = try {
      captureFrame(capture.pending.sessionIdentity, capture.pending.isPreviewMirrored)
    } catch (error: Throwable) {
      finishProcessing()
      reportFrameFailure(error, onError)
      return
    }
    if (frame == null) {
      finishProcessing()
      return
    }
    try {
      _executor.execute { process(frame, capture.runGeneration) }
    } catch (error: Throwable) {
      val failure = recycleSampledFrame(frame, error)
      finishProcessing()
      reportFrameFailure(failure, onError)
    }
  }

  private fun process(frame: CameraFrame.PreviewSampled, runGeneration: Long) {
    val shouldProcess = synchronized(_lock) {
      _started && !_closed && _runGeneration == runGeneration
    }
    if (!shouldProcess) {
      val failure = recycleSampledFrame(frame)
      finishProcessing()
      reportFrameFailure(failure, onError)
      return
    }

    var failure: Throwable? = null
    try {
      onFrame(frame)
    } catch (error: Throwable) {
      failure = error
    } finally {
      failure = recycleSampledFrame(frame, failure)
      finishProcessing()
    }
    reportFrameFailure(failure, onError)
  }

  private fun finishProcessing() {
    var shouldPostCapture = false
    synchronized(_lock) {
      _processing = false
      if (_started && !_closed && _pending != null && !_capturePosted) {
        _capturePosted = true
        shouldPostCapture = true
      }
    }
    if (shouldPostCapture && !mainHandler.post(::capturePending)) {
      synchronized(_lock) { _capturePosted = false }
      onError(IllegalStateException("The main thread is not available for preview sampling."))
    }
  }

  fun stop() {
    synchronized(_lock) {
      _started = false
      _pending = null
    }
  }

  override fun close() {
    synchronized(_lock) {
      if (_closed) return
      _closed = true
      _started = false
      _pending = null
    }
    _executor.shutdown()
  }
}

private fun reportFrameFailure(failure: Throwable?, onError: (Throwable) -> Unit) {
  when (failure) {
    null -> Unit
    is Exception -> onError(failure)
    else -> throw failure
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

private data class ActiveSampledCapture(
  val pending: PendingSampledFrame,
  val runGeneration: Long,
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
  val preferredSizes = sizes.filter { size -> size.width.toLong() * size.height <= MAX_PREVIEW_PIXELS }
    .ifEmpty { sizes }
  return preferredSizes.minWithOrNull(
    compareBy<IntSize> { size ->
      val orientedWidth = if (isQuarterTurn) size.height else size.width
      val orientedHeight = if (isQuarterTurn) size.width else size.height
      abs(orientedWidth.toFloat() / orientedHeight - targetAspectRatio)
    }.thenByDescending { size -> size.width.toLong() * size.height },
  ) ?: sizes.first()
}

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
