package com.sd.lib.compose.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import java.util.concurrent.atomic.AtomicReference

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
  private var _requestFocusAction: (() -> Unit)? = null

  /** 当前会话的原始帧分辨率，会话未运行时为 [IntSize.Zero] */
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
    _requestFocusAction?.invoke()
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
   * 把 [CameraFrame] 坐标映射到 Compose 预览区域。
   * 原始帧矩阵包含显示旋转、[ContentScale] 和目标镜像；采样帧矩阵只包含目标镜像。
   */
  @AnyThread
  fun createTransformToPreview(frame: CameraFrame): Matrix? {
    val snapshot = _snapshot.get()
    if (!frame.transformToken.matches(snapshot.transformIdentity)) return null
    val geometry = snapshot.geometry ?: return null
    val matrix = when (frame) {
      is CameraFrame.Preview -> {
        val rawFrame = snapshot.session?.rawFrame ?: return null
        createRawFrameMatrix(frame, rawFrame, geometry) ?: return null
      }
      is CameraFrame.PreviewSampled -> {
        if (frame.rotationDegrees != 0 || frame.dataSize != geometry.previewSize) return null
        Matrix()
      }
    }
    if (snapshot.isMirrored) matrix.postScale(-1f, 1f, geometry.previewSize.width / 2f, 0f)
    return matrix
  }

  internal val retryGeneration: Int get() = _retryGeneration.intValue

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
  internal fun attachRequestFocusAction(action: () -> Unit) {
    _requestFocusAction = action
  }

  @MainThread
  internal fun detachRequestFocusAction(action: () -> Unit) {
    if (_requestFocusAction === action) _requestFocusAction = null
  }

  @MainThread
  internal fun reset() {
    _takeScreenshotAction = null
    _requestFocusAction = null
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
    val transformIdentity = when {
      geometry == null -> null
      isChanged -> CameraFrameTransformIdentity()
      else -> current.transformIdentity
    }
    _snapshot.set(next.copy(geometry = geometry, transformIdentity = transformIdentity))
  }
}

/** 一次已经上屏的相机会话 */
internal class PreviewSession(
  /** 预览内容旋转到显示方向后的尺寸 */
  val contentSize: IntSize,
  val isPreviewMirrored: Boolean,
  val resolution: IntSize,
  /** 原始帧的缓冲区布局，未启用原始帧处理时为 `null` */
  val rawFrame: RawFrameLayout?,
)

/** 原始帧缓冲区中与预览同视野的区域，以及把它转到显示方向所需的角度 */
internal data class RawFrameLayout(
  val bufferSize: IntSize,
  val cropRect: IntRect,
  val rotationDegrees: Int,
)

private data class PreviewSnapshot(
  val session: PreviewSession? = null,
  val transformIdentity: CameraFrameTransformIdentity? = null,
  val previewSize: IntSize = IntSize.Zero,
  val contentScale: ContentScale = ContentScale.Crop,
  val isMirrored: Boolean = false,
  val geometry: PreviewGeometry? = null,
)

/** 预览内容在 Compose 区域中的位置，内容始终居中 */
internal data class PreviewGeometry(
  val previewSize: IntSize,
  val contentSize: Size,
  val offsetX: Float,
  val offsetY: Float,
  val scaleX: Float,
  val scaleY: Float,
)

internal fun calculatePreviewGeometry(
  contentSize: IntSize,
  previewSize: IntSize,
  contentScale: ContentScale,
): PreviewGeometry? {
  if (contentSize.width <= 0 || contentSize.height <= 0 || previewSize.width <= 0 || previewSize.height <= 0) {
    return null
  }
  val scale = contentScale.computeScaleFactor(
    srcSize = Size(contentSize.width.toFloat(), contentSize.height.toFloat()),
    dstSize = Size(previewSize.width.toFloat(), previewSize.height.toFloat()),
  )
  val width = contentSize.width * scale.scaleX
  val height = contentSize.height * scale.scaleY
  if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
  return PreviewGeometry(
    previewSize = previewSize,
    contentSize = Size(width, height),
    offsetX = (previewSize.width - width) / 2f,
    offsetY = (previewSize.height - height) / 2f,
    scaleX = scale.scaleX,
    scaleY = scale.scaleY,
  )
}

/** 原始帧坐标依次经过裁剪、旋转、缩放和居中，映射到 Compose 预览区域 */
private fun createRawFrameMatrix(
  frame: CameraFrame.Preview,
  rawFrame: RawFrameLayout,
  geometry: PreviewGeometry,
): Matrix? {
  if (frame.width != rawFrame.bufferSize.width || frame.height != rawFrame.bufferSize.height) return null
  if (normalizeRotation(frame.rotationDegrees) != rawFrame.rotationDegrees) return null
  val crop = rawFrame.cropRect
  val orientedSize = crop.size.rotate(rawFrame.rotationDegrees)
  return createRotationMatrix(crop.width, crop.height, rawFrame.rotationDegrees).apply {
    preTranslate(-crop.left.toFloat(), -crop.top.toFloat())
    postScale(
      geometry.contentSize.width / orientedSize.width,
      geometry.contentSize.height / orientedSize.height,
    )
    postTranslate(geometry.offsetX, geometry.offsetY)
  }
}

/** 把 [width] × [height] 的区域顺时针旋转 [rotationDegrees]，结果仍从原点开始 */
private fun createRotationMatrix(width: Int, height: Int, rotationDegrees: Int): Matrix {
  val (translateX, translateY) = when (rotationDegrees) {
    90 -> height to 0
    180 -> width to height
    270 -> 0 to width
    else -> 0 to 0
  }
  return Matrix().apply {
    setRotate(rotationDegrees.toFloat())
    postTranslate(translateX.toFloat(), translateY.toFloat())
  }
}

/** 把预览控件截图绘制到 Compose 预览区域并按需水平翻转，[source] 总会被回收或直接转交 */
internal fun renderPreviewBitmap(source: Bitmap, geometry: PreviewGeometry, shouldMirror: Boolean): Bitmap {
  val size = geometry.previewSize
  val isDirect = !shouldMirror &&
    source.width == size.width && source.height == size.height &&
    geometry.offsetX == 0f && geometry.offsetY == 0f
  if (isDirect) return source
  return try {
    Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).also { output ->
      Canvas(output).apply {
        if (shouldMirror) scale(-1f, 1f, size.width / 2f, 0f)
        val destination = RectF(
          geometry.offsetX,
          geometry.offsetY,
          geometry.offsetX + geometry.contentSize.width,
          geometry.offsetY + geometry.contentSize.height,
        )
        drawBitmap(source, null, destination, Paint(Paint.FILTER_BITMAP_FLAG))
      }
    }
  } finally {
    source.recycle()
  }
}

internal fun IntSize.rotate(rotationDegrees: Int): IntSize {
  return if (rotationDegrees % 180 == 0) this else IntSize(height, width)
}

internal fun normalizeRotation(rotationDegrees: Int): Int {
  val normalized = ((rotationDegrees % 360) + 360) % 360
  require(normalized % 90 == 0) { "rotationDegrees must be a multiple of 90." }
  return normalized
}
