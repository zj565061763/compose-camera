package com.sd.lib.compose.camera

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.SurfaceRequest
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executor
import androidx.compose.ui.graphics.Matrix as ComposeMatrix

/**
 * 基于 CameraX 的 Compose 摄像头预览。
 *
 * 帧回调：
 *
 * - [onFrame] 非空时在独立分析线程同步调用，底层使用 `STRATEGY_KEEP_ONLY_LATEST`；为空时不创建 `ImageAnalysis` 和分析线程。
 * - [frameFormat] 默认使用无需逐帧转换的 YUV；选择 RGBA 会增加格式转换开销。
 * - [CameraFrame.image] 只在回调期间有效。需要交给其他线程时应先复制所需数据，不能保存 `ImageProxy` 引用；回调返回或抛出异常后会自动关闭该帧。
 * - 组件释放会丢弃尚未开始的帧回调，但不会中断已经执行的回调。
 *
 * 摄像头选择与镜像：
 *
 * - [cameraId] 非空时按 Camera2 cameraId 精确选择；为 `null` 时使用 CameraX 当前提供的第一个摄像头。
 * - [mirrorMode] 只影响预览显示和 [CameraPreviewState.createTransformToPreview] 的坐标结果，不会修改 [CameraFrame.image] 的像素内容。
 *
 * 错误与恢复：
 *
 * - 初始化、设备枚举、CameraX 运行期、帧回调及清理异常都会按发生顺序投递到主线程的 [onError]。
 * - 用户 [onError] 抛出的异常位于所有库内部异常边界之外，不会被重新分类为设备错误。
 * - CameraX 不可恢复错误会释放失效 Controller；外部条件解决后可调用 [CameraPreviewState.retry] 重新绑定。
 *
 * 布局与设备状态：
 *
 * - [modifier] 必须让组件获得非零宽高；零尺寸表示当前无需预览，不会创建相机会话，也不会因尺寸本身报告错误。
 * - [devicesState] 可与同一界面的设备选择 UI 或其他预览共享，复用设备快照和手动刷新入口。
 *
 * 权限：调用方必须在组合本组件前取得 `android.permission.CAMERA` 权限。
 */
@Composable
fun CameraPreview(
  modifier: Modifier = Modifier,
  state: CameraPreviewState = rememberCameraPreviewState(),
  devicesState: CameraDevicesState = rememberCameraDevicesState(),
  cameraId: String? = null,
  mirrorMode: CameraMirrorMode = CameraMirrorMode.AUTO,
  contentScale: ContentScale = ContentScale.Crop,
  frameFormat: CameraFrameFormat = CameraFrameFormat.YUV_420_888,
  onError: (Throwable) -> Unit = {},
  onFrame: ((CameraFrame) -> Unit)? = null,
) {
  cameraId?.also { require(it.isNotBlank()) { "cameraId must not be blank." } }
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val view = LocalView.current

  val currentOnFrame by rememberUpdatedState(onFrame)
  val currentOnError by rememberUpdatedState(onError)
  val currentMirrorMode by rememberUpdatedState(mirrorMode)
  val currentContentScale by rememberUpdatedState(contentScale)
  val errorDispatcher = remember { MainThreadErrorDispatcher { error -> currentOnError(error) } }
  val hasFrameCallback = onFrame != null
  val effectiveFrameFormat = frameFormat.takeIf { hasFrameCallback }

  var previewSize by remember { mutableStateOf(IntSize.Zero) }
  var previewSurface by remember { mutableStateOf<PreviewSurface?>(null) }
  var cameraXMirrored by remember { mutableStateOf<Boolean?>(null) }

  val cameraDevices by devicesState.devices
  val hasLoadedCameraDevices by devicesState.hasLoadedDevices
  val cameraDeviceKey = cameraDevices.cameraDeviceKey(cameraId)
  val needsAdditionalMirror = cameraXMirrored?.let { defaultMirrored -> mirrorMode.needsAdditionalMirror(defaultMirrored) } ?: false
  val targetRotation = rememberDisplayRotation(context, view)
  val hasValidPreviewSize = previewSize.isValidPreviewSize()

  val retryGeneration = state.retryGeneration
  LaunchedEffect(devicesState, retryGeneration) {
    if (retryGeneration != 0) devicesState.refresh()
  }

  SideEffect {
    state.updatePreviewLayout(previewSize, contentScale, needsAdditionalMirror)
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
    context,
    lifecycleOwner,
    state,
    cameraId,
    targetRotation,
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
        context = context.applicationContext,
        lifecycleOwner = lifecycleOwner,
        cameraId = cameraId,
        targetRotation = targetRotation,
        frameFormat = frameFormat,
        onSurfaceRequest = { request, requestIdentity, onFailure ->
          previewSurface = null
          cameraXMirrored = null
          state.updateSurfaceRequest(requestIdentity)
          val publicationGate = SurfaceRequestPublicationGate()
          request.addRequestCancellationListener(DirectExecutor) {
            publicationGate.cancel()
            ContextCompat.getMainExecutor(context).execute {
              if (previewSurface?.request === request) previewSurface = null
              state.clearSurfaceRequest(requestIdentity)
            }
          }
          request.setTransformationInfoListener(ContextCompat.getMainExecutor(context)) { transformationInfo ->
            try {
              request.clearTransformationInfoListener()
              val published = publicationGate.publishIfActive {
                val current = state.updatePreviewTransformation(
                  requestIdentity = requestIdentity,
                  sensorToBuffer = transformationInfo.sensorToBufferTransform,
                  cropRect = transformationInfo.cropRect,
                  rotationDegrees = transformationInfo.rotationDegrees,
                  isMirrored = transformationInfo.isMirroring,
                )
                if (current) {
                  val newAdditionalMirror = currentMirrorMode.needsAdditionalMirror(transformationInfo.isMirroring)
                  // 同步轮换坐标 identity，消除镜像状态等待下一次重组的亚帧窗口。
                  state.updatePreviewLayout(previewSize, currentContentScale, newAdditionalMirror)
                  cameraXMirrored = transformationInfo.isMirroring
                  previewSurface = PreviewSurface(request, requestIdentity)
                  val resolution = request.resolution
                  state.updatePreviewResolution(requestIdentity, IntSize(resolution.width, resolution.height))
                }
                current
              }
              if (!published) {
                state.clearSurfaceRequest(requestIdentity)
                request.willNotProvideSurface()
              }
            } catch (error: Exception) {
              onFailure(error)
            }
          }
        },
        transformIdentityProvider = state::currentTransformIdentity,
        onFrame = if (hasFrameCallback) {
          { frame -> currentOnFrame?.invoke(frame) }
        } else {
          null
        },
        onError = errorDispatcher::dispatch,
        onClosed = {
          previewSurface = null
          cameraXMirrored = null
          state.clearSurfaceRequest()
        },
      )
      controller.start()
      onDispose {
        controller.close()
      }
    }
  }

  CameraPreviewViewfinder(
    modifier = modifier,
    previewSurface = previewSurface,
    previewSize = previewSize,
    contentScale = contentScale,
    needsAdditionalMirror = needsAdditionalMirror,
    state = state,
    onSizeChanged = { size ->
      previewSize = size
      state.updatePreviewLayout(size, contentScale, needsAdditionalMirror)
    },
  )
}

/** 与官方 CameraXPreview 相同：渲染层只消费 SurfaceRequest，不参与相机绑定。 */
@Composable
private fun CameraPreviewViewfinder(
  modifier: Modifier,
  previewSurface: PreviewSurface?,
  previewSize: IntSize,
  contentScale: ContentScale,
  needsAdditionalMirror: Boolean,
  state: CameraPreviewState,
  onSizeChanged: (IntSize) -> Unit,
) {
  Box(modifier = modifier.onSizeChanged(onSizeChanged)) {
    previewSurface?.also { preview ->
      val coordinateTransformer = remember(preview, state, previewSize, contentScale) {
        CameraPreviewCoordinateTransformer { viewfinderToBuffer ->
          state.updateViewfinderToBuffer(
            requestIdentity = preview.requestIdentity,
            previewSize = previewSize,
            contentScale = contentScale,
            viewfinderToBuffer = viewfinderToBuffer.toAndroidMatrix(),
          )
        }
      }
      CameraXViewfinder(
        surfaceRequest = preview.request,
        modifier = Modifier
          .matchParentSize()
          .graphicsLayer { scaleX = if (needsAdditionalMirror) -1f else 1f },
        coordinateTransformer = coordinateTransformer,
        contentScale = contentScale,
      )
    }
  }
}

/** 监听当前 View 所在显示器的旋转，包括不会触发 Configuration 变化的 180° 旋转。 */
@Composable
private fun rememberDisplayRotation(context: Context, view: View): Int {
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

/** 退出组合后丢弃已经排入主线程队列的设备错误。 */
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

private data class PreviewSurface(
  val request: SurfaceRequest,
  val requestIdentity: CameraFrameTransformIdentity,
)

/** 取消与 TransformationInfo 发布使用同一把锁，避免已取消请求重新写入预览状态。 */
internal class SurfaceRequestPublicationGate {
  private val _lock = Any()
  private var _isCancelled = false

  fun cancel() {
    synchronized(_lock) { _isCancelled = true }
  }

  fun publishIfActive(action: () -> Boolean): Boolean {
    return synchronized(_lock) { if (_isCancelled) false else action() }
  }
}

/** 相机会话只关心预览是否已有有效尺寸，不关心后续的具体尺寸变化。 */
internal fun IntSize.isValidPreviewSize(): Boolean = width > 0 && height > 0

private class CameraPreviewCoordinateTransformer(
  private val onTransformChanged: (ComposeMatrix) -> Unit,
) : MutableCoordinateTransformer {
  override var transformMatrix: ComposeMatrix = ComposeMatrix()
    set(value) {
      field = value
      onTransformChanged(value)
    }
}

internal fun ComposeMatrix.toAndroidMatrix(): android.graphics.Matrix {
  return android.graphics.Matrix().apply {
    setValues(
      floatArrayOf(
        this@toAndroidMatrix[0, 0],
        this@toAndroidMatrix[1, 0],
        this@toAndroidMatrix[3, 0],
        this@toAndroidMatrix[0, 1],
        this@toAndroidMatrix[1, 1],
        this@toAndroidMatrix[3, 1],
        this@toAndroidMatrix[0, 3],
        this@toAndroidMatrix[1, 3],
        this@toAndroidMatrix[3, 3],
      ),
    )
  }
}

private object DirectExecutor : Executor {
  override fun execute(command: Runnable) = command.run()
}

/** cameraId 为空时返回首项，否则只在目标存在时返回它，用作会话重建 key。 */
internal fun List<CameraDeviceInfo>.cameraDeviceKey(cameraId: String?): String? {
  return if (cameraId == null) {
    firstOrNull()?.cameraId
  } else {
    firstOrNull { it.cameraId == cameraId }?.cameraId
  }
}
