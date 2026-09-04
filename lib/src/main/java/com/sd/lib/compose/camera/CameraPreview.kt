package com.sd.lib.compose.camera

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicReference

/**
 * Compose 摄像头预览。
 *
 * [frameProcessor] 控制是否在 `CameraPreview-Analysis` 单线程同步接收最新帧。
 *
 * [cameraId] 是不透明的摄像头标识；为 `null` 时选择设备列表中的第一项。
 * [mirrorMode] 只影响预览和坐标矩阵，不修改帧数据。
 * [displayRotation] 为 `null` 时监听当前 View 所在显示器，也可以传入 `Surface.ROTATION_*` 覆盖系统方向。
 * `onError` 报告全部普通异常；需要重新枚举设备或重建相机会话的当前故障同时发布到 [CameraPreviewState.failure]。
 * 调用方必须在组合本组件前取得 `android.permission.CAMERA` 权限。
 */
@Composable
fun CameraPreview(
  modifier: Modifier = Modifier,
  state: CameraPreviewState = rememberCameraPreviewState(),
  devicesState: CameraDevicesState = rememberCameraDevicesState(),
  cameraId: String? = null,
  mirrorMode: CameraMirrorMode = CameraMirrorMode.AUTO,
  contentScale: ContentScale = ContentScale.Crop,
  displayRotation: Int? = null,
  onError: (Throwable) -> Unit = {},
  frameProcessor: FrameProcessor = FrameProcessor.None,
) {
  cameraId?.also { require(it.isNotBlank()) { "cameraId must not be blank." } }
  displayRotation?.also { rotation ->
    require(rotation in Surface.ROTATION_0..Surface.ROTATION_270) {
      "displayRotation must be a Surface.ROTATION_* value."
    }
  }
  val lifecycleOwner = LocalLifecycleOwner.current
  val autoFocusOperationsFactory = LocalCameraAutoFocusOperationsFactory.current
  var textureView by remember { mutableStateOf<TextureView?>(null) }

  val currentFrameProcessor by rememberUpdatedState(frameProcessor)
  val currentOnError by rememberUpdatedState(onError)
  val currentMirrorMode by rememberUpdatedState(mirrorMode)
  val errorDispatcher = remember { MainThreadErrorDispatcher { error -> currentOnError(error) } }
  val analysisCoordinator = remember { CameraAnalysisCoordinator() }
  val frameProcessorMode = frameProcessor.mode
  var previewSize by remember { mutableStateOf(IntSize.Zero) }
  // 布局变化不重建 Controller，发布最新有效尺寸供下次开会话读取
  val latestPreviewViewSize = remember { AtomicReference(IntSize.Zero) }
  var activePreviewMirrored by remember { mutableStateOf<Boolean?>(null) }

  val cameraDevices by devicesState.devices
  val hasLoadedCameraDevices by devicesState.hasLoadedDevices
  val selectedDevice = cameraDevices.selectedCameraDevice(cameraId)
  val cameraDeviceKey = selectedDevice?.cameraId
  val cameraPreviewMirrored = activePreviewMirrored ?: (selectedDevice?.lens == CameraLens.FRONT)
  val targetMirrored = mirrorMode.isMirrored(cameraPreviewMirrored)
  val effectiveDisplayRotation = displayRotation ?: rememberDisplayRotation()
  val hasValidPreviewSize = previewSize.width > 0 && previewSize.height > 0

  val retryGeneration = state.retryGeneration
  LaunchedEffect(devicesState, retryGeneration) {
    if (retryGeneration != 0) devicesState.refresh()
  }

  SideEffect {
    state.updatePreviewLayout(previewSize, contentScale, targetMirrored)
  }

  DisposableEffect(state) {
    onDispose { state.reset() }
  }

  DisposableEffect(analysisCoordinator) {
    onDispose { analysisCoordinator.close() }
  }

  val currentTextureView = textureView
  val attemptIdentity = remember(
    lifecycleOwner,
    autoFocusOperationsFactory,
    state,
    devicesState,
    cameraId,
    effectiveDisplayRotation,
    frameProcessorMode,
    retryGeneration,
    hasLoadedCameraDevices,
    cameraDeviceKey,
  ) { CameraPreviewAttemptIdentity() }
  val failureDispatcher = remember(state, attemptIdentity) {
    MainThreadErrorDispatcher { error -> state.reportFailure(attemptIdentity, error) }
  }
  // 设备枚举故障跨普通相机会话重建保留，只在设备状态或 retry 变化时失效
  val cameraDevicesAttemptIdentity = remember(state, devicesState, retryGeneration) {
    CameraDevicesAttemptIdentity()
  }
  val cameraDevicesRefreshDispatcher = remember(state, devicesState, cameraDevicesAttemptIdentity) {
    MainThreadCameraDevicesRefreshDispatcher { event ->
      when (event) {
        CameraDevicesRefreshEvent.Success -> {
          state.clearCameraDevicesFailure(cameraDevicesAttemptIdentity, devicesState)
        }
        is CameraDevicesRefreshEvent.Failure -> {
          state.reportCameraDevicesFailure(cameraDevicesAttemptIdentity, devicesState, event.error)
        }
      }
    }
  }
  val currentCameraDevicesRefreshDispatcher by rememberUpdatedState(cameraDevicesRefreshDispatcher)

  DisposableEffect(state, cameraDevicesAttemptIdentity) {
    state.beginCameraDevicesAttempt(cameraDevicesAttemptIdentity)
    onDispose { state.endCameraDevicesAttempt(cameraDevicesAttemptIdentity) }
  }

  DisposableEffect(state, attemptIdentity) {
    state.beginAttempt(attemptIdentity)
    onDispose { state.endAttempt(attemptIdentity) }
  }

  DisposableEffect(state, devicesState, errorDispatcher) {
    val errorSubscription = MainThreadErrorSubscription(errorDispatcher)
    val refreshListener: (CameraDevicesRefreshEvent) -> Unit = { event ->
      currentCameraDevicesRefreshDispatcher.dispatch(event)
      if (event is CameraDevicesRefreshEvent.Failure) errorSubscription.dispatch(event.error)
    }
    devicesState.addRefreshListener(refreshListener)?.also(refreshListener)
    onDispose {
      errorSubscription.close()
      devicesState.removeRefreshListener(refreshListener)
    }
  }

  DisposableEffect(
    currentTextureView,
    hasValidPreviewSize,
    attemptIdentity,
  ) {
    if (currentTextureView == null || !hasValidPreviewSize || !hasLoadedCameraDevices) {
      onDispose { }
    } else {
      val failureSubscription = MainThreadErrorSubscription(failureDispatcher)
      val controller = CameraPreviewController(
        lifecycleOwner = lifecycleOwner,
        textureView = currentTextureView,
        cameraId = cameraId,
        displayRotation = effectiveDisplayRotation,
        previewViewSizeProvider = latestPreviewViewSize::get,
        transformIdentityProvider = state::currentTransformIdentity,
        onSessionStarted = { sessionIdentity, bufferSize, rotationDegrees, isPreviewMirrored ->
          val started = state.startSession(
            attemptIdentity = attemptIdentity,
            sessionIdentity = sessionIdentity,
            bufferSize = bufferSize,
            rotationDegrees = rotationDegrees,
            isPreviewMirrored = isPreviewMirrored,
            isMirrored = currentMirrorMode.isMirrored(isPreviewMirrored),
          )
          if (started) activePreviewMirrored = isPreviewMirrored
          started
        },
        onPreviewFrameAvailable = { sessionIdentity ->
          state.markPreviewFrameAvailable(sessionIdentity)?.also(currentTextureView::setTransform)
        },
        frameProcessor = when (frameProcessorMode) {
          FrameProcessorMode.NONE -> ActiveFrameProcessor.None
          FrameProcessorMode.PREVIEW -> ActiveFrameProcessor.Preview { frame ->
            (currentFrameProcessor as? FrameProcessor.Preview)?.onFrame?.invoke(frame)
          }
          FrameProcessorMode.PREVIEW_SAMPLED -> ActiveFrameProcessor.PreviewSampled(
            intervalMillis = {
              (currentFrameProcessor as? FrameProcessor.PreviewSampled)?.intervalMillis ?: Long.MAX_VALUE
            },
            onFrame = { frame ->
              (currentFrameProcessor as? FrameProcessor.PreviewSampled)?.onFrame?.invoke(frame)
            },
          )
        },
        captureSampledFrame = { sessionIdentity, isPreviewMirrored ->
          capturePreviewSampledFrame(
            state = state,
            sessionIdentity = sessionIdentity,
            isPreviewMirrored = isPreviewMirrored,
            captureBitmap = { width, height -> captureTextureViewBitmap(currentTextureView, width, height) },
          )
        },
        onSessionFailure = failureSubscription::dispatch,
        onError = errorDispatcher::dispatch,
        onSessionClosed = { sessionIdentity ->
          activePreviewMirrored = null
          state.clearSession(sessionIdentity)
        },
        autoFocusOperationsFactory = autoFocusOperationsFactory,
        analysisCoordinator = analysisCoordinator,
      )
      controller.start()
      val requestFocusAction: () -> Unit = controller::requestFocus
      state.attachRequestFocusAction(requestFocusAction)
      onDispose {
        state.detachRequestFocusAction(requestFocusAction)
        failureSubscription.close()
        controller.close()
      }
    }
  }

  DisposableEffect(state, currentTextureView, errorDispatcher) {
    if (currentTextureView == null) {
      onDispose { }
    } else {
      val takeScreenshotAction: (CameraMirrorMode) -> Bitmap? = { screenshotMirrorMode ->
        try {
          capturePreviewScreenshot(
            state = state,
            mirrorMode = screenshotMirrorMode,
            captureBitmap = { width, height -> captureTextureViewBitmap(currentTextureView, width, height) },
          )
        } catch (error: Exception) {
          errorDispatcher.dispatch(error)
          null
        }
      }
      state.attachTakeScreenshotAction(takeScreenshotAction)
      onDispose { state.detachTakeScreenshotAction(takeScreenshotAction) }
    }
  }

  CameraPreviewTextureView(
    modifier = modifier,
    state = state,
    previewSize = previewSize,
    contentScale = contentScale,
    targetMirrored = targetMirrored,
    onTextureViewCreated = { view -> textureView = view },
    onSizeChanged = { size ->
      if (size.width > 0 && size.height > 0) latestPreviewViewSize.set(size)
      previewSize = size
      state.updatePreviewLayout(size, contentScale, targetMirrored)
    },
  )
}

@Composable
private fun CameraPreviewTextureView(
  modifier: Modifier,
  state: CameraPreviewState,
  previewSize: IntSize,
  contentScale: ContentScale,
  targetMirrored: Boolean,
  onTextureViewCreated: (TextureView) -> Unit,
  onSizeChanged: (IntSize) -> Unit,
) {
  val previewResolution = state.previewResolution.value
  val previewTransformRevision = state.previewTransformRevision
  val textureTransform = remember(
    state,
    previewResolution,
    previewTransformRevision,
    previewSize,
    contentScale,
    targetMirrored,
  ) {
    state.calculateCurrentTextureViewTransform(previewSize, contentScale, targetMirrored)
  }
  AndroidView(
    factory = { context ->
      TextureView(context).also { view ->
        view.isOpaque = false
        onTextureViewCreated(view)
      }
    },
    update = { view -> textureTransform?.also(view::setTransform) },
    modifier = modifier
      .clipToBounds()
      .onSizeChanged(onSizeChanged),
  )
}

private const val TEXTURE_VIEW_COPY_MARKER = 0x01010203

@MainThread
internal fun captureTextureViewBitmap(textureView: TextureView, width: Int, height: Int): Bitmap? {
  if (!textureView.isAvailable || width <= 0 || height <= 0) return null
  val bitmap = Bitmap.createBitmap(
    textureView.resources.displayMetrics,
    width,
    height,
    Bitmap.Config.ARGB_8888,
  )
  return copyIntoBitmapOrThrow(bitmap) { destination -> textureView.getBitmap(destination) }
}

internal fun copyIntoBitmapOrThrow(
  bitmap: Bitmap,
  copyInto: (Bitmap) -> Unit,
): Bitmap {
  val markerX = bitmap.width / 2
  val markerY = bitmap.height / 2
  bitmap.setPixel(markerX, markerY, TEXTURE_VIEW_COPY_MARKER)
  val storedMarker = bitmap.getPixel(markerX, markerY)
  var succeeded = false
  return try {
    copyInto(bitmap)
    check(bitmap.getPixel(markerX, markerY) != storedMarker) {
      "TextureView failed to copy its content into the bitmap."
    }
    succeeded = true
    bitmap
  } finally {
    if (!succeeded) bitmap.recycle()
  }
}

@MainThread
internal fun capturePreviewSampledFrame(
  state: CameraPreviewState,
  sessionIdentity: CameraFrameTransformIdentity,
  isPreviewMirrored: Boolean,
  captureBitmap: (Int, Int) -> Bitmap?,
): CameraFrame.PreviewSampled? {
  val request = state.createPreviewSampleRequest(sessionIdentity, isPreviewMirrored) ?: return null
  val source = captureBitmap(request.captureSize.width, request.captureSize.height) ?: return null
  var sourceTransferred = false
  return try {
    state.createSampledFrame(source, request).also { frame ->
      sourceTransferred = frame?.data === source
    }
  } finally {
    if (!sourceTransferred) source.recycle()
  }
}

@MainThread
internal fun capturePreviewScreenshot(
  state: CameraPreviewState,
  mirrorMode: CameraMirrorMode,
  captureBitmap: (Int, Int) -> Bitmap?,
): Bitmap? {
  val request = state.createScreenshotRequest(mirrorMode) ?: return null
  val source = captureBitmap(request.captureSize.width, request.captureSize.height) ?: return null
  var sourceTransferred = false
  return try {
    state.createPreviewBitmap(source, request).also { bitmap ->
      sourceTransferred = bitmap === source
    }
  } finally {
    if (!sourceTransferred) source.recycle()
  }
}

/** 监听当前 View 所在显示器的旋转，包括不会触发 Configuration 变化的 180° 旋转。 */
@Composable
private fun rememberDisplayRotation(): Int {
  val context = LocalContext.current
  val view = LocalView.current
  var rotation by remember(view) {
    mutableIntStateOf(view.display?.rotation ?: Surface.ROTATION_0)
  }

  DisposableEffect(context, view) {
    val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    fun updateRotation(displayId: Int? = null) {
      val display = view.display ?: return
      if (displayId == null || display.displayId == displayId) rotation = display.rotation
    }

    val displayListener = object : DisplayManager.DisplayListener {
      override fun onDisplayAdded(displayId: Int) = updateRotation(displayId)
      override fun onDisplayChanged(displayId: Int) = updateRotation(displayId)
      override fun onDisplayRemoved(displayId: Int) = Unit
    }
    val attachStateListener = object : View.OnAttachStateChangeListener {
      override fun onViewAttachedToWindow(view: View) = updateRotation()
      override fun onViewDetachedFromWindow(view: View) = Unit
    }

    displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    view.addOnAttachStateChangeListener(attachStateListener)
    updateRotation()
    onDispose {
      view.removeOnAttachStateChangeListener(attachStateListener)
      displayManager.unregisterDisplayListener(displayListener)
    }
  }
  return rotation
}

/** 始终排入主线程队列，使用户回调位于所有库内部 try/catch 之外。 */
internal class MainThreadErrorDispatcher(
  private val onError: (Throwable) -> Unit,
) {
  private val _handler = Handler(Looper.getMainLooper())

  fun dispatch(error: Throwable) {
    _handler.post { onError(error) }
  }

  fun dispatchWhile(error: Throwable, isActive: () -> Boolean) {
    _handler.post {
      if (isActive()) onError(error)
    }
  }
}

/** 始终按完成顺序把设备刷新事件排入主线程队列 */
internal class MainThreadCameraDevicesRefreshDispatcher(
  private val onEvent: (CameraDevicesRefreshEvent) -> Unit,
) {
  private val _handler = Handler(Looper.getMainLooper())

  fun dispatch(event: CameraDevicesRefreshEvent) {
    _handler.post { onEvent(event) }
  }
}

/** 退出组合后丢弃已经排入主线程队列的设备错误 */
internal class MainThreadErrorSubscription(
  private val dispatcher: MainThreadErrorDispatcher,
) : AutoCloseable {
  private var _isActive = true

  fun dispatch(error: Throwable) {
    dispatcher.dispatchWhile(error) { _isActive }
  }

  override fun close() {
    _isActive = false
  }
}

private fun List<CameraDeviceInfo>.selectedCameraDevice(cameraId: String?): CameraDeviceInfo? {
  return if (cameraId == null) firstOrNull() else firstOrNull { device -> device.cameraId == cameraId }
}
