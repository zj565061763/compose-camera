package com.sd.lib.compose.camera

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Compose 摄像头预览
 *
 * [frameProcessor] 控制是否在 `CameraPreview-Analysis` 单线程同步接收最新帧
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
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val currentOnError by rememberUpdatedState(onError)
  val currentFrameProcessor by rememberUpdatedState(frameProcessor)
  val errorDispatcher = remember { MainThreadErrorDispatcher { error -> currentOnError(error) } }
  val analysisExecutor = remember { CameraAnalysisExecutor() }
  var previewView by remember { mutableStateOf<PreviewView?>(null) }
  var previewSize by remember { mutableStateOf(IntSize.Zero) }
  val currentPreviewSize by rememberUpdatedState(previewSize)

  val devices by devicesState.devices
  val hasLoadedDevices by devicesState.hasLoadedDevices
  val devicesRefreshVersion by devicesState.refreshVersion
  val selectedDevice = if (cameraId == null) devices.firstOrNull() else devices.firstOrNull { it.cameraId == cameraId }
  val displayedSession by state.displayedSession
  // 会话上屏前按镜头方向预估平台镜像
  val isPreviewMirrored = displayedSession?.isPreviewMirrored ?: (selectedDevice?.lens == CameraLens.FRONT)
  val targetMirrored = mirrorMode.isMirrored(isPreviewMirrored)
  val effectiveDisplayRotation = displayRotation ?: rememberDisplayRotation()
  val retryGeneration = state.retryGeneration
  val hasValidPreviewSize = previewSize.width > 0 && previewSize.height > 0

  SideEffect {
    state.updatePreviewLayout(previewSize, contentScale, targetMirrored)
  }

  DisposableEffect(state) {
    onDispose { state.reset() }
  }

  DisposableEffect(errorDispatcher, analysisExecutor) {
    onDispose {
      errorDispatcher.close()
      analysisExecutor.close()
    }
  }

  LaunchedEffect(devicesState, retryGeneration) {
    if (retryGeneration != 0) devicesState.refresh()
  }

  LaunchedEffect(state, devicesState, devicesRefreshVersion) {
    val error = devicesState.error.value
    state.updateDevicesFailure(error)
    error?.also(errorDispatcher::dispatch)
  }

  val currentPreviewView = previewView
  DisposableEffect(
    currentPreviewView,
    hasValidPreviewSize,
    hasLoadedDevices,
    lifecycleOwner,
    state,
    cameraId,
    selectedDevice?.cameraId,
    effectiveDisplayRotation,
    frameProcessor.mode,
    retryGeneration,
  ) {
    if (currentPreviewView == null || !hasValidPreviewSize || !hasLoadedDevices) {
      return@DisposableEffect onDispose { }
    }
    val session = CameraSession(
      context = context,
      lifecycleOwner = lifecycleOwner,
      previewView = currentPreviewView,
      state = state,
      config = CameraSessionConfig(
        cameraId = cameraId,
        displayRotation = effectiveDisplayRotation,
        frameMode = frameProcessor.mode,
        previewViewSize = currentPreviewSize,
      ),
      frameProcessor = { currentFrameProcessor },
      analysisExecutor = analysisExecutor,
      // retry() 之后旧会话的迟到故障不再写入状态
      onFailure = { error -> if (state.retryGeneration == retryGeneration) state.reportSessionFailure(error) },
      onError = errorDispatcher::dispatch,
    )
    session.start()
    val requestFocus: () -> Unit = session::requestFocus
    state.attachRequestFocusAction(requestFocus)
    onDispose {
      state.detachRequestFocusAction(requestFocus)
      session.close()
    }
  }

  DisposableEffect(state, currentPreviewView) {
    if (currentPreviewView == null) return@DisposableEffect onDispose { }
    val takeScreenshot: (CameraMirrorMode) -> Bitmap? = { screenshotMirrorMode ->
      try {
        state.capturePreview(screenshotMirrorMode, currentPreviewView::getBitmap)?.data
      } catch (error: Exception) {
        errorDispatcher.dispatch(error)
        null
      }
    }
    state.attachTakeScreenshotAction(takeScreenshot)
    onDispose { state.detachTakeScreenshotAction(takeScreenshot) }
  }

  Box(
    modifier = modifier
      .clipToBounds()
      .onSizeChanged { size ->
        previewSize = size
        // 在当次布局同步更新坐标快照，不等下一次重组
        state.updatePreviewLayout(size, contentScale, targetMirrored)
      },
  ) {
    AndroidView(
      factory = { viewContext ->
        PreviewView(viewContext).apply {
          // TextureView 实现才支持自定义旋转、截图和父布局裁剪
          implementationMode = PreviewView.ImplementationMode.COMPATIBLE
          scaleType = PreviewView.ScaleType.FILL_CENTER
        }.also { previewView = it }
      },
      modifier = Modifier.previewContent(
        contentSize = displayedSession?.contentSize,
        contentScale = contentScale,
        isFlipped = targetMirrored != isPreviewMirrored,
      ),
    )
  }
}

/**
 * 按 [contentScale] 把预览控件放到内容区域
 *
 * 控件保持相机内容的宽高比，因此 `PreviewView` 不会再裁剪或留边；非等比缩放和额外镜像通过图层完成。
 */
private fun Modifier.previewContent(
  contentSize: IntSize?,
  contentScale: ContentScale,
  isFlipped: Boolean,
): Modifier = layout { measurable, constraints ->
  val flipScaleX = if (isFlipped) -1f else 1f
  val geometry = if (constraints.hasBoundedWidth && constraints.hasBoundedHeight && contentSize != null) {
    calculatePreviewGeometry(contentSize, IntSize(constraints.maxWidth, constraints.maxHeight), contentScale)
  } else {
    null
  }
  if (geometry == null || contentSize == null) {
    val fillConstraints = if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
      Constraints.fixed(constraints.maxWidth, constraints.maxHeight)
    } else {
      constraints
    }
    val placeable = measurable.measure(fillConstraints)
    layout(placeable.width, placeable.height) {
      placeable.placeWithLayer(0, 0) { scaleX = flipScaleX }
    }
  } else {
    val placeable = measurable.measure(
      Constraints.fixed(
        width = (contentSize.width * geometry.scaleX).roundToInt().coerceAtLeast(1),
        height = (contentSize.height * geometry.scaleX).roundToInt().coerceAtLeast(1),
      ),
    )
    layout(constraints.maxWidth, constraints.maxHeight) {
      placeable.placeWithLayer(geometry.offsetX.roundToInt(), geometry.offsetY.roundToInt()) {
        transformOrigin = TransformOrigin(0.5f, 0f)
        scaleX = flipScaleX
        scaleY = geometry.scaleY / geometry.scaleX
      }
    }
  }
}

/** 监听当前 View 所在显示器的旋转，包括不会触发 Configuration 变化的 180° 旋转 */
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

/** 始终排入主线程队列，使用户回调位于库内部 try/catch 之外；关闭后丢弃尚未送达的错误 */
internal class MainThreadErrorDispatcher(
  private val onError: (Throwable) -> Unit,
) : AutoCloseable {
  private val _handler = Handler(Looper.getMainLooper())

  @Volatile
  private var _closed = false

  fun dispatch(error: Throwable) {
    _handler.post { if (!_closed) onError(error) }
  }

  override fun close() {
    _closed = true
  }
}

internal const val CAMERA_ANALYSIS_THREAD_NAME = "CameraPreview-Analysis"

/** 在首个任务到达时创建 `CameraPreview-Analysis` 单线程，关闭后丢弃新任务 */
internal class CameraAnalysisExecutor : Executor, AutoCloseable {
  private var _executor: ExecutorService? = null
  private var _closed = false

  @Synchronized
  override fun execute(command: Runnable) {
    if (_closed) return
    val executor = _executor ?: Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, CAMERA_ANALYSIS_THREAD_NAME)
    }.also { _executor = it }
    executor.execute(command)
  }

  @Synchronized
  override fun close() {
    _closed = true
    _executor?.shutdown()
  }
}
