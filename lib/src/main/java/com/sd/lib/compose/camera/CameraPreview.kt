package com.sd.lib.compose.camera

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Compose 摄像头预览。
 *
 * [onFrame] 非空时在 `CameraPreview-Analysis` 单线程同步接收最新帧；
 * [CameraFrame.data] 只在回调期间有效，异步处理前必须复制数据或调用 [CameraFrame.toBitmap]。
 * [frameFormat] 控制回调数据格式，JPEG 编码在分析线程完成。
 *
 * [cameraId] 是不透明的摄像头标识；为 `null` 时选择设备列表中的第一项。
 * [mirrorMode] 只影响预览和坐标矩阵，不修改帧数据。
 * [displayRotation] 为 `null` 时监听当前 View 所在显示器，也可以传入 `Surface.ROTATION_*` 覆盖系统方向。
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
  frameFormat: CameraFrameFormat = CameraFrameFormat.NV21,
  onError: (Throwable) -> Unit = {},
  onFrame: ((CameraFrame) -> Unit)? = null,
) {
  cameraId?.also { require(it.isNotBlank()) { "cameraId must not be blank." } }
  displayRotation?.also { rotation ->
    require(rotation in Surface.ROTATION_0..Surface.ROTATION_270) {
      "displayRotation must be a Surface.ROTATION_* value."
    }
  }
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val textureView = remember(context) { TextureView(context) }

  val currentOnFrame by rememberUpdatedState(onFrame)
  val currentOnError by rememberUpdatedState(onError)
  val errorDispatcher = remember { MainThreadErrorDispatcher { error -> currentOnError(error) } }
  val hasFrameCallback = onFrame != null
  val effectiveFrameFormat = frameFormat.takeIf { hasFrameCallback }
  var previewSize by remember { mutableStateOf(IntSize.Zero) }
  var activePreviewMirrored by remember { mutableStateOf<Boolean?>(null) }

  val cameraDevices by devicesState.devices
  val hasLoadedCameraDevices by devicesState.hasLoadedDevices
  val selectedDevice = cameraDevices.selectedCameraDevice(cameraId)
  val cameraDeviceKey = selectedDevice?.cameraId
  val cameraPreviewMirrored = activePreviewMirrored ?: (selectedDevice?.lens == CameraLens.FRONT)
  val targetMirrored = mirrorMode.isMirrored(cameraPreviewMirrored)
  val needsAdditionalMirror = targetMirrored != cameraPreviewMirrored
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

  DisposableEffect(devicesState, errorDispatcher) {
    val errorSubscription = MainThreadErrorSubscription(errorDispatcher)
    val cameraErrorListener: (Throwable) -> Unit = errorSubscription::dispatch
    devicesState.addErrorListener(cameraErrorListener)?.also(cameraErrorListener)
    onDispose {
      errorSubscription.close()
      devicesState.removeErrorListener(cameraErrorListener)
    }
  }

  DisposableEffect(
    lifecycleOwner,
    textureView,
    state,
    cameraId,
    effectiveDisplayRotation,
    hasValidPreviewSize,
    hasFrameCallback,
    effectiveFrameFormat,
    retryGeneration,
    hasLoadedCameraDevices,
    cameraDeviceKey,
  ) {
    if (!hasValidPreviewSize || !hasLoadedCameraDevices) {
      onDispose { }
    } else {
      val controller = CameraPreviewController(
        lifecycleOwner = lifecycleOwner,
        textureView = textureView,
        cameraId = cameraId,
        displayRotation = effectiveDisplayRotation,
        previewViewSize = previewSize,
        frameFormat = frameFormat,
        transformIdentityProvider = state::currentTransformIdentity,
        onSessionStarted = { sessionIdentity, bufferSize, rotationDegrees, isPreviewMirrored ->
          activePreviewMirrored = isPreviewMirrored
          state.startSession(sessionIdentity, bufferSize, rotationDegrees)
        },
        onFrame = if (hasFrameCallback) {
          { frame -> currentOnFrame?.invoke(frame) }
        } else {
          null
        },
        onError = errorDispatcher::dispatch,
        onSessionClosed = { sessionIdentity ->
          activePreviewMirrored = null
          state.clearSession(sessionIdentity)
        },
      )
      controller.start()
      onDispose { controller.close() }
    }
  }

  CameraPreviewTextureView(
    modifier = modifier,
    textureView = textureView,
    state = state,
    contentScale = contentScale,
    needsAdditionalMirror = needsAdditionalMirror,
    onSizeChanged = { size ->
      previewSize = size
      state.updatePreviewLayout(size, contentScale, targetMirrored)
    },
  )
}

@Composable
private fun CameraPreviewTextureView(
  modifier: Modifier,
  textureView: TextureView,
  state: CameraPreviewState,
  contentScale: ContentScale,
  needsAdditionalMirror: Boolean,
  onSizeChanged: (IntSize) -> Unit,
) {
  state.previewResolution.value
  Layout(
    modifier = modifier
      .clipToBounds()
      .onSizeChanged(onSizeChanged),
    content = {
      AndroidView(
        factory = { textureView },
        modifier = Modifier.graphicsLayer {
          scaleX = if (needsAdditionalMirror) -1f else 1f
        },
      )
    },
  ) { measurables, constraints ->
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
    val geometry = state.calculateCurrentPreviewGeometry(IntSize(width, height), contentScale)
    val contentSize = geometry?.contentSize ?: IntSize(width, height)
    val placeable = measurables.single().measure(
      Constraints.fixed(
        width = contentSize.width.coerceAtLeast(1),
        height = contentSize.height.coerceAtLeast(1),
      ),
    )
    layout(width, height) {
      val x = geometry?.offsetX?.toInt() ?: 0
      val y = geometry?.offsetY?.toInt() ?: 0
      placeable.place(x, y)
    }
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

private fun CameraMirrorMode.isMirrored(isFrontFacing: Boolean): Boolean {
  return when (this) {
    CameraMirrorMode.AUTO -> isFrontFacing
    CameraMirrorMode.ON -> true
    CameraMirrorMode.OFF -> false
  }
}
