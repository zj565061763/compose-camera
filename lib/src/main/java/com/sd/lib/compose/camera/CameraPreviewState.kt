package com.sd.lib.compose.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import java.util.concurrent.atomic.AtomicReference

/** 面向活动相机会话的控制接口，新增缩放、补光灯或点按对焦等能力时统一在此扩展 */
internal interface CameraSessionController {
  @MainThread
  fun requestFocus()
}

/** 创建并记住当前预览的状态 */
@Composable
fun rememberCameraPreviewState(): CameraPreviewState {
  return remember { CameraPreviewState() }
}

/** 每个正在组合的 [CameraPreview] 必须使用独立实例 */
@Stable
class CameraPreviewState internal constructor() {
  // 分析线程无锁读取，只在主线程整体替换
  private val _snapshot = AtomicReference(PreviewSnapshot())
  private val _previewResolution = mutableStateOf(IntSize.Zero)
  private val _failure = mutableStateOf<Throwable?>(null)
  private val _displayedSession = mutableStateOf<PreviewSession?>(null)
  private val _retryGeneration = mutableIntStateOf(0)

  private var _sessionFailure: Throwable? = null
  private var _devicesFailure: Throwable? = null

  private var _takeScreenshotAction: ((CameraMirrorMode) -> Bitmap?)? = null
  private var _sessionController: CameraSessionController? = null

  /** 当前会话的帧分辨率（启用原始帧处理时为原始帧分辨率，否则为预览流分辨率），会话未运行时为 [IntSize.Zero] */
  val previewResolution: State<IntSize> = _previewResolution

  /** 需要重新枚举设备或重建相机会话的当前故障，其他普通异常只通过 [CameraPreview] 的 `onError` 报告 */
  val failure: State<Throwable?> = _failure

  /**
   * 截取当前预览区域，并按照 [mirrorMode] 决定返回图片的镜像状态。
   *
   * 返回的图片已经应用显示旋转和 `ContentScale`，不包含预览上层内容，由调用方负责回收。
   * 预览尚未产生有效帧、已经离开组合或发生普通截图异常时返回 `null`；截图异常同时通过 [CameraPreview] 的 `onError` 报告。
   */
  @MainThread
  fun takeScreenshot(mirrorMode: CameraMirrorMode = CameraMirrorMode.AUTO): Bitmap? {
    return _takeScreenshotAction?.invoke(mirrorMode)
  }

  /**
   * 请求当前预览执行一次自动对焦
   *
   * 使用连续对焦、设备不支持单次自动对焦、预览未运行或已离开组合时不执行操作。
   */
  @MainThread
  fun requestFocus() {
    _sessionController?.requestFocus()
  }

  /** 在外部条件恢复后关闭并重新创建当前相机会话 */
  @MainThread
  fun retry() {
    _sessionFailure = null
    _devicesFailure = null
    updateFailure()
    _retryGeneration.intValue++
  }

  /** 判断异步结果是否仍属于当前预览变换 */
  @AnyThread
  fun isFrameTransformCurrent(token: CameraFrameTransformToken): Boolean {
    return token.matches(_snapshot.get().transformIdentity)
  }

  /**
   * 把 [CameraFrame] 坐标映射到 Compose 预览区域，
   * 原始帧矩阵包含裁剪、显示旋转、[ContentScale] 和目标镜像，采样帧矩阵只包含目标镜像。
   */
  @AnyThread
  fun createTransformToPreview(frame: CameraFrame): Matrix? {
    val snapshot = _snapshot.get()
    if (!frame.transformToken.matches(snapshot.transformIdentity)) return null
    val geometry = snapshot.geometry ?: return null
    val matrix = when (frame) {
      is CameraFrame.Preview -> {
        val rawFrame = snapshot.session?.rawFrame ?: return null
        createRawFrameToPreviewMatrix(frame, rawFrame, geometry) ?: return null
      }
      is CameraFrame.PreviewSampled -> {
        if (frame.rotationDegrees != 0 || frame.dataSize != geometry.previewSize) return null
        Matrix()
      }
    }
    if (snapshot.isMirrored) {
      applyHorizontalMirror(matrix, geometry.previewSize.width)
    }
    return matrix
  }

  internal val retryGeneration: Int
    get() {
      return _retryGeneration.intValue
    }

  /** 最近一次开始显示的会话，停止后保留，使旧画面在新会话首帧前维持原布局 */
  internal val displayedSession: State<PreviewSession?> = _displayedSession

  @AnyThread
  internal fun currentTransformIdentity(): CameraFrameTransformIdentity? {
    return _snapshot.get().transformIdentity
  }

  @MainThread
  internal fun updatePreviewLayout(previewSize: IntSize, contentScale: ContentScale, isMirrored: Boolean) {
    publish { it.copy(previewSize = previewSize, contentScale = contentScale, isMirrored = isMirrored) }
  }

  /** 预览首帧已经上屏，开始发布坐标变换 */
  @MainThread
  internal fun startSession(session: PreviewSession) {
    publish { it.copy(session = session) }
    _displayedSession.value = session
    _previewResolution.value = session.resolution
    _sessionFailure = null
    updateFailure()
  }

  @MainThread
  internal fun clearSession(session: PreviewSession) {
    if (_snapshot.get().session !== session) return
    publish { it.copy(session = null) }
    _previewResolution.value = IntSize.Zero
  }

  @MainThread
  internal fun reportSessionFailure(error: Throwable) {
    _sessionFailure = error
    updateFailure()
  }

  @MainThread
  internal fun updateDevicesFailure(error: Throwable?) {
    _devicesFailure = error
    updateFailure()
  }

  /**
   * 截取当前预览区域并生成采样帧
   *
   * [capture] 返回预览控件显示的画面，其中包含平台镜像；[mirrorMode] 决定输出图片的镜像状态。
   */
  @MainThread
  internal fun capturePreview(mirrorMode: CameraMirrorMode, capture: () -> Bitmap?): CameraFrame.PreviewSampled? {
    val snapshot = _snapshot.get()
    val transformIdentity = snapshot.transformIdentity ?: return null
    val session = snapshot.session ?: return null
    val geometry = snapshot.geometry ?: return null
    val source = capture() ?: return null
    val shouldMirror = mirrorMode.isMirrored(session.isPreviewMirrored) != session.isPreviewMirrored
    return CameraFrame.PreviewSampled(
      data = renderPreviewBitmap(source, geometry, shouldMirror),
      rotationDegrees = 0,
      transformIdentity = transformIdentity,
    )
  }

  @MainThread
  internal fun attachTakeScreenshotAction(action: (CameraMirrorMode) -> Bitmap?) {
    _takeScreenshotAction = action
  }

  @MainThread
  internal fun detachTakeScreenshotAction(action: (CameraMirrorMode) -> Bitmap?) {
    if (_takeScreenshotAction === action) _takeScreenshotAction = null
  }

  @MainThread
  internal fun attachSessionController(controller: CameraSessionController) {
    _sessionController = controller
  }

  @MainThread
  internal fun detachSessionController(controller: CameraSessionController) {
    if (_sessionController === controller) _sessionController = null
  }

  @MainThread
  internal fun reset() {
    _takeScreenshotAction = null
    _sessionController = null
    _snapshot.set(PreviewSnapshot())
    _displayedSession.value = null
    _previewResolution.value = IntSize.Zero
    _sessionFailure = null
    _devicesFailure = null
    updateFailure()
    _retryGeneration.intValue = 0
  }

  private fun updateFailure() {
    _failure.value = _sessionFailure ?: _devicesFailure
  }

  /** 会话、布局、缩放或镜像任一变化都会轮换 transform identity，使旧 token 失效 */
  private fun publish(update: (PreviewSnapshot) -> PreviewSnapshot) {
    val current = _snapshot.get()
    val next = update(current)
    val geometry = next.session?.let { session ->
      calculatePreviewGeometry(session.contentSize, next.previewSize, next.contentScale)
    }
    val isChanged = next.session !== current.session ||
      geometry != current.geometry ||
      next.contentScale != current.contentScale ||
      next.isMirrored != current.isMirrored
    val hasValidRawFrame = next.session?.rawFrame?.let { raw ->
      raw.bufferSize.width > 0 && raw.bufferSize.height > 0 &&
        raw.cropRect.width > 0 && raw.cropRect.height > 0
    } != false
    val transformIdentity = when {
      geometry == null || !hasValidRawFrame -> null
      isChanged -> CameraFrameTransformIdentity()
      else -> current.transformIdentity
    }
    _snapshot.set(next.copy(geometry = geometry, transformIdentity = transformIdentity))
  }
}

private data class PreviewSnapshot(
  val session: PreviewSession? = null,
  val transformIdentity: CameraFrameTransformIdentity? = null,
  val previewSize: IntSize = IntSize.Zero,
  val contentScale: ContentScale = ContentScale.Crop,
  val isMirrored: Boolean = false,
  val geometry: PreviewGeometry? = null,
)
