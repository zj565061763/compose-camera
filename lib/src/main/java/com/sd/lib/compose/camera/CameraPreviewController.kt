@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.view.Surface
import android.view.TextureView
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/** 管理一次 [CameraPreview] 会话和帧分发 */
internal class CameraPreviewController(
  private val lifecycleOwner: LifecycleOwner,
  private val textureView: TextureView,
  private val cameraId: String?,
  private val displayRotation: Int,
  private val previewViewSize: IntSize,
  frameFormat: CameraFrameFormat,
  private val transformIdentityProvider: () -> CameraFrameTransformIdentity?,
  private val onSessionStarted: (
    CameraFrameTransformIdentity,
    IntSize,
    Int,
    Boolean,
  ) -> Unit,
  private val onFrame: ((CameraFrame) -> Unit)?,
  private val onError: (Throwable) -> Unit,
  private val onSessionClosed: (CameraFrameTransformIdentity?) -> Unit,
) : AutoCloseable {
  private val _frameDispatcher = onFrame?.let { callback ->
    CameraFrameDispatcher(frameFormat, callback, onError)
  }
  @Volatile
  private var _camera: Camera? = null
  private var _sessionIdentity: CameraFrameTransformIdentity? = null
  private var _surfaceAvailable = textureView.isAvailable
  private var _started = false
  @Volatile
  private var _closed = false

  private val _lifecycleObserver = LifecycleEventObserver { _, event ->
    when (event) {
      Lifecycle.Event.ON_START -> startCameraIfReady()
      Lifecycle.Event.ON_STOP -> stopCamera()
      Lifecycle.Event.ON_DESTROY -> close()
      else -> Unit
    }
  }
  private val _surfaceTextureListener = object : TextureView.SurfaceTextureListener {
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
      _surfaceAvailable = true
      startCameraIfReady()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
      _surfaceAvailable = false
      stopCamera()
      return true
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
    _surfaceAvailable = textureView.isAvailable
    startCameraIfReady()
  }

  private fun startCameraIfReady() {
    if (
      _closed || _camera != null || !_surfaceAvailable ||
      !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    ) {
      return
    }

    val numberOfCameras = try {
      Camera.getNumberOfCameras()
    } catch (error: Exception) {
      failAndClose(error)
      return
    }
    val resolvedCameraId = if (cameraId == null) {
      0.takeIf { numberOfCameras > 0 }
    } else {
      (0 until numberOfCameras).firstOrNull { id -> id.toString() == cameraId }
    }
    if (resolvedCameraId == null) {
      failAndClose(cameraSelectionException(cameraId))
      return
    }

    val failure = try {
      openCamera(resolvedCameraId)
      null
    } catch (error: Exception) {
      cameraOpenException(resolvedCameraId, error)
    }
    failure?.also { error ->
      onError(error)
      stopCamera()
    }
  }

  private fun openCamera(resolvedCameraId: Int) {
    val cameraInfo = Camera.CameraInfo().also { Camera.getCameraInfo(resolvedCameraId, it) }
    val rotationDegrees = calculateCameraDisplayOrientation(cameraInfo, displayRotation)
    val camera = Camera.open(resolvedCameraId).also { _camera = it }
    val parameters = camera.parameters
    parameters.previewFormat = ImageFormat.NV21
    val previewSize = choosePreviewSize(
      sizes = parameters.supportedPreviewSizes.map { size -> IntSize(size.width, size.height) },
      previewViewSize = previewViewSize,
      rotationDegrees = rotationDegrees,
    )
    parameters.setPreviewSize(previewSize.width, previewSize.height)
    chooseFocusMode(parameters.supportedFocusModes)?.also { mode -> parameters.focusMode = mode }
    camera.parameters = parameters

    val configuredSize = camera.parameters.previewSize
    val bufferSize = IntSize(configuredSize.width, configuredSize.height)
    val surfaceTexture = checkNotNull(textureView.surfaceTexture) { "TextureView surface is not available." }
    camera.setDisplayOrientation(rotationDegrees)
    camera.setPreviewTexture(surfaceTexture)
    camera.setErrorCallback { errorCode, source ->
      if (!_closed && _camera === source) {
        onError(cameraRuntimeException(errorCode))
        stopCamera()
      }
    }

    val sessionIdentity = CameraFrameTransformIdentity().also { _sessionIdentity = it }
    onSessionStarted(
      sessionIdentity,
      bufferSize,
      rotationDegrees,
      cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT,
    )
    configureFrameCallback(camera, bufferSize, rotationDegrees)
    camera.startPreview()
  }

  private fun configureFrameCallback(camera: Camera, bufferSize: IntSize, rotationDegrees: Int) {
    val dispatcher = _frameDispatcher ?: return
    val bufferBytes = checkNotNull(nv21BufferSize(bufferSize.width, bufferSize.height)) {
      "The camera reported an invalid NV21 preview size: $bufferSize."
    }
    repeat(CAMERA_CALLBACK_BUFFER_COUNT) { camera.addCallbackBuffer(ByteArray(bufferBytes)) }
    camera.setPreviewCallbackWithBuffer { data, source ->
      if (_closed || _camera !== source) return@setPreviewCallbackWithBuffer
      dispatcher.offer(
        data = data,
        width = bufferSize.width,
        height = bufferSize.height,
        rotationDegrees = rotationDegrees,
        transformIdentity = transformIdentityProvider(),
        returnBuffer = { buffer ->
          if (!_closed && _camera === source) source.addCallbackBuffer(buffer)
        },
      )
    }
  }

  private fun failAndClose(error: Throwable) {
    if (_closed) return
    onError(error)
    close()
  }

  private fun stopCamera() {
    val camera = _camera ?: return
    val sessionIdentity = _sessionIdentity
    _camera = null
    _sessionIdentity = null
    _frameDispatcher?.discardPending()
    runCameraCleanupActions(
      actions = listOf(
        { camera.setPreviewCallbackWithBuffer(null) },
        { camera.setErrorCallback(null) },
        { camera.stopPreview() },
        { camera.release() },
        { onSessionClosed(sessionIdentity) },
      ),
      finalAction = {},
    )?.also(onError)
  }

  override fun close() {
    if (_closed) return
    _closed = true
    lifecycleOwner.lifecycle.removeObserver(_lifecycleObserver)
    if (textureView.surfaceTextureListener === _surfaceTextureListener) {
      textureView.surfaceTextureListener = null
    }
    stopCamera()
    _frameDispatcher?.close()
  }
}

internal const val CAMERA_ANALYSIS_THREAD_NAME = "CameraPreview-Analysis"
private const val CAMERA_CALLBACK_BUFFER_COUNT = 3
private const val MAX_PREVIEW_PIXELS = 1280 * 960

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

/** 单线程处理最新帧，并在处理完成或被替换时归还相机回调缓冲区。 */
internal class CameraFrameDispatcher(
  private val frameFormat: CameraFrameFormat,
  private val onFrame: (CameraFrame) -> Unit,
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
    if (!isValidFrameData(data, width, height)) {
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
        val frameData = when (frameFormat) {
          CameraFrameFormat.NV21 -> pending.data
          CameraFrameFormat.JPEG -> nv21ToJpeg(pending.data, pending.width, pending.height)
        }
        frameData?.also { data ->
          onFrame(
            CameraFrame(
              data = data,
              format = frameFormat,
              width = pending.width,
              height = pending.height,
              rotationDegrees = pending.rotationDegrees,
              transformIdentity = pending.transformIdentity,
            ),
          )
        }
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
    when (failure) {
      null -> Unit
      is Exception -> onError(failure)
      else -> throw failure
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

private fun isValidFrameData(data: ByteArray, width: Int, height: Int): Boolean {
  val requiredSize = nv21BufferSize(width, height) ?: return false
  return data.size >= requiredSize
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
  val displayDegrees = when (displayRotation) {
    Surface.ROTATION_0 -> 0
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
  }
  return if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
    val result = (cameraInfo.orientation + displayDegrees) % 360
    (360 - result) % 360
  } else {
    (cameraInfo.orientation - displayDegrees + 360) % 360
  }
}

private fun chooseFocusMode(supportedModes: List<String>?): String? {
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
