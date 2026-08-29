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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** 创建并记住当前预览的状态 */
@Composable
fun rememberCameraPreviewState(): CameraPreviewState {
  return remember { CameraPreviewState() }
}

/** 每个正在组合的 [CameraPreview] 必须使用独立实例 */
@Stable
class CameraPreviewState internal constructor() {
  private val _transformConfig = AtomicReference(PreviewTransformConfig())
  private val _previewResolution = mutableStateOf(IntSize.Zero)
  private val _failure = mutableStateOf<Throwable?>(null)
  private val _previewTransformRevision = mutableIntStateOf(0)
  private val _retryGeneration = mutableIntStateOf(0)
  private var _attemptIdentity: CameraPreviewAttemptIdentity? = null
  private var _failureClearingSessionIdentity: CameraFrameTransformIdentity? = null
  private var _takeScreenshotAction: ((CameraMirrorMode) -> Bitmap?)? = null

  /** 当前会话使用的原始帧分辨率，会话未运行时为 [IntSize.Zero]。 */
  val previewResolution: State<IntSize> = _previewResolution

  /** 阻止当前预览启动或继续运行的错误，非致命异常只通过 [CameraPreview] 的 `onError` 报告。 */
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

  /** 在外部条件恢复后关闭并重新创建当前相机会话 */
  @MainThread
  fun retry() {
    _attemptIdentity = null
    _failureClearingSessionIdentity = null
    _failure.value = null
    _retryGeneration.intValue++
  }

  internal val retryGeneration: Int get() = _retryGeneration.intValue
  internal val previewTransformRevision: Int get() = _previewTransformRevision.intValue

  /** 判断异步结果是否仍属于当前预览变换 */
  @AnyThread
  fun isFrameTransformCurrent(token: CameraFrameTransformToken): Boolean {
    return token.matches(_transformConfig.get().transformIdentity)
  }

  @AnyThread
  internal fun currentTransformIdentity(): CameraFrameTransformIdentity? {
    return _transformConfig.get().transformIdentity
  }

  @AnyThread
  internal fun currentSessionIdentity(): CameraFrameTransformIdentity? {
    return _transformConfig.get().sessionIdentity
  }

  /**
   * 把 [CameraFrame] 坐标映射到 Compose 预览区域。
   * 原始帧矩阵包含显示旋转、[ContentScale] 和目标镜像；采样帧矩阵只包含目标镜像。
   */
  @AnyThread
  fun createTransformToPreview(frame: CameraFrame): Matrix? {
    val config = _transformConfig.get()
    if (!frame.transformToken.matches(config.transformIdentity)) return null
    return when (frame) {
      is CameraFrame.Preview -> createPreviewFrameTransform(frame, config)
      is CameraFrame.PreviewSampled -> createSampledFrameTransform(frame, config)
    }
  }

  private fun createPreviewFrameTransform(frame: CameraFrame.Preview, config: PreviewTransformConfig): Matrix? {
    if (frame.width != config.bufferSize.width || frame.height != config.bufferSize.height) return null
    if (normalizeRotation(frame.rotationDegrees) != config.rotationDegrees) return null
    val geometry = config.geometry ?: return null
    val matrix = createBufferToPreviewMatrix(
      bufferSize = config.bufferSize,
      rotationDegrees = config.rotationDegrees,
      geometry = geometry,
    )
    if (!config.isMirrored) return matrix

    val mirror = Matrix().apply {
      setValues(
        floatArrayOf(
          -1f, 0f, geometry.previewSize.width.toFloat(),
          0f, 1f, 0f,
          0f, 0f, 1f,
        ),
      )
    }
    return Matrix().apply { setConcat(mirror, matrix) }
  }

  private fun createSampledFrameTransform(
    frame: CameraFrame.PreviewSampled,
    config: PreviewTransformConfig,
  ): Matrix? {
    val geometry = config.geometry ?: return null
    if (frame.rotationDegrees != 0) return null
    if (frame.data.width != config.previewSize.width || frame.data.height != config.previewSize.height) return null
    if (!config.isMirrored) return Matrix()
    return Matrix().apply {
      setValues(
        floatArrayOf(
          -1f, 0f, geometry.previewSize.width.toFloat(),
          0f, 1f, 0f,
          0f, 0f, 1f,
        ),
      )
    }
  }

  @MainThread
  internal fun createPreviewSampleRequest(
    sessionIdentity: CameraFrameTransformIdentity,
    isPreviewMirrored: Boolean,
  ): PreviewBitmapRequest? {
    val config = _transformConfig.get()
    if (
      config.sessionIdentity !== sessionIdentity ||
      config.isPreviewMirrored != isPreviewMirrored ||
      !config.isPreviewFrameAvailable
    ) return null
    return createPreviewBitmapRequest(
      config = config,
      sessionIdentity = sessionIdentity,
      shouldMirror = config.isPreviewMirrored,
    )
  }

  @MainThread
  internal fun createScreenshotRequest(mirrorMode: CameraMirrorMode): PreviewBitmapRequest? {
    val config = _transformConfig.get()
    val sessionIdentity = config.sessionIdentity ?: return null
    if (!config.isPreviewFrameAvailable) return null
    val targetMirrored = mirrorMode.isMirrored(config.isPreviewMirrored)
    return createPreviewBitmapRequest(
      config = config,
      sessionIdentity = sessionIdentity,
      shouldMirror = config.isPreviewMirrored != targetMirrored,
    )
  }

  private fun createPreviewBitmapRequest(
    config: PreviewTransformConfig,
    sessionIdentity: CameraFrameTransformIdentity,
    shouldMirror: Boolean,
  ): PreviewBitmapRequest? {
    val transformIdentity = config.transformIdentity ?: return null
    val geometry = config.geometry ?: return null
    val normalizedRotation = normalizeRotation(config.rotationDegrees)
    val isQuarterTurn = normalizedRotation == 90 || normalizedRotation == 270
    val orientedWidth = if (isQuarterTurn) config.bufferSize.height else config.bufferSize.width
    val orientedHeight = if (isQuarterTurn) config.bufferSize.width else config.bufferSize.height
    // getBitmap 不应用 TextureView 内容矩阵，按内容比例截图后再显式绘制到预览区域
    val captureScale = minOf(
      1f,
      orientedWidth / geometry.contentSize.width,
      orientedHeight / geometry.contentSize.height,
    )
    val captureSize = IntSize(
      (geometry.contentSize.width * captureScale).roundToInt().coerceAtLeast(1),
      (geometry.contentSize.height * captureScale).roundToInt().coerceAtLeast(1),
    )
    return PreviewBitmapRequest(
      sessionIdentity = sessionIdentity,
      transformIdentity = transformIdentity,
      captureSize = captureSize,
      previewSize = geometry.previewSize,
      contentBounds = Rect(
        geometry.offsetX,
        geometry.offsetY,
        geometry.offsetX + geometry.contentSize.width,
        geometry.offsetY + geometry.contentSize.height,
      ),
      shouldMirror = shouldMirror,
    )
  }

  @MainThread
  internal fun createSampledFrame(
    source: Bitmap,
    request: PreviewBitmapRequest,
  ): CameraFrame.PreviewSampled? {
    val output = createPreviewBitmap(source, request) ?: return null
    return CameraFrame.PreviewSampled(
      data = output,
      rotationDegrees = 0,
      transformIdentity = request.transformIdentity,
    )
  }

  @MainThread
  internal fun createPreviewBitmap(
    source: Bitmap,
    request: PreviewBitmapRequest,
  ): Bitmap? {
    val config = _transformConfig.get()
    if (
      config.sessionIdentity !== request.sessionIdentity ||
      config.transformIdentity !== request.transformIdentity ||
      IntSize(source.width, source.height) != request.captureSize
    ) return null

    val previewBounds = Rect(0f, 0f, request.previewSize.width.toFloat(), request.previewSize.height.toFloat())
    val isDirect = !request.shouldMirror && request.captureSize == request.previewSize &&
      request.contentBounds == previewBounds
    val output = if (isDirect) {
      source
    } else {
      Bitmap.createBitmap(request.previewSize.width, request.previewSize.height, Bitmap.Config.ARGB_8888).also { bitmap ->
        try {
          Canvas(bitmap).apply {
            if (request.shouldMirror) {
              scale(-1f, 1f, request.previewSize.width / 2f, request.previewSize.height / 2f)
            }
            val destination = request.contentBounds
            drawBitmap(
              source,
              null,
              RectF(destination.left, destination.top, destination.right, destination.bottom),
              Paint(Paint.FILTER_BITMAP_FLAG),
            )
          }
        } catch (error: Throwable) {
          bitmap.recycle()
          throw error
        }
      }
    }
    return output
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
  internal fun updatePreviewLayout(
    previewSize: IntSize,
    contentScale: ContentScale,
    isMirrored: Boolean,
  ) {
    val current = _transformConfig.get()
    val geometry = calculatePreviewGeometry(
      bufferSize = current.bufferSize,
      rotationDegrees = current.rotationDegrees,
      previewSize = previewSize,
      contentScale = contentScale,
    )
    val transformChanged = current.geometry != geometry || current.contentScale != contentScale || current.isMirrored != isMirrored
    _transformConfig.set(
      current.copy(
        previewSize = previewSize,
        contentScale = contentScale,
        geometry = geometry,
        isMirrored = isMirrored,
        transformIdentity = if (transformChanged && current.sessionIdentity != null) {
          CameraFrameTransformIdentity()
        } else {
          current.transformIdentity
        },
      ),
    )
    if (transformChanged) _previewTransformRevision.intValue++
  }

  @MainThread
  internal fun beginAttempt(attemptIdentity: CameraPreviewAttemptIdentity) {
    _attemptIdentity = attemptIdentity
    _failureClearingSessionIdentity = null
    _failure.value = null
  }

  @MainThread
  internal fun reportFailure(
    attemptIdentity: CameraPreviewAttemptIdentity,
    error: Throwable,
  ) {
    if (_attemptIdentity !== attemptIdentity) return
    _failureClearingSessionIdentity = null
    _failure.value = error
  }

  @MainThread
  internal fun endAttempt(attemptIdentity: CameraPreviewAttemptIdentity) {
    if (_attemptIdentity !== attemptIdentity) return
    _attemptIdentity = null
    _failureClearingSessionIdentity = null
    _failure.value = null
  }

  @MainThread
  internal fun startSession(
    attemptIdentity: CameraPreviewAttemptIdentity,
    sessionIdentity: CameraFrameTransformIdentity,
    bufferSize: IntSize,
    rotationDegrees: Int,
    isPreviewMirrored: Boolean = false,
    isMirrored: Boolean,
  ): Boolean {
    if (_attemptIdentity !== attemptIdentity) return false
    startSession(
      sessionIdentity = sessionIdentity,
      bufferSize = bufferSize,
      rotationDegrees = rotationDegrees,
      isPreviewMirrored = isPreviewMirrored,
      isMirrored = isMirrored,
    )
    return true
  }

  @MainThread
  internal fun startSession(
    sessionIdentity: CameraFrameTransformIdentity,
    bufferSize: IntSize,
    rotationDegrees: Int,
    isPreviewMirrored: Boolean = false,
    isMirrored: Boolean,
  ) {
    val current = _transformConfig.get()
    val normalizedRotation = normalizeRotation(rotationDegrees)
    _transformConfig.set(
      current.copy(
        sessionIdentity = sessionIdentity,
        transformIdentity = sessionIdentity,
        bufferSize = bufferSize,
        rotationDegrees = normalizedRotation,
        isPreviewMirrored = isPreviewMirrored,
        isMirrored = isMirrored,
        isPreviewFrameAvailable = false,
        geometry = calculatePreviewGeometry(
          bufferSize = bufferSize,
          rotationDegrees = normalizedRotation,
          previewSize = current.previewSize,
          contentScale = current.contentScale,
        ),
      ),
    )
    _failureClearingSessionIdentity = sessionIdentity
    _previewTransformRevision.intValue++
    _previewResolution.value = bufferSize
  }

  @MainThread
  internal fun clearSession(sessionIdentity: CameraFrameTransformIdentity? = null) {
    val current = _transformConfig.get()
    if (sessionIdentity != null && current.sessionIdentity !== sessionIdentity) return
    _failureClearingSessionIdentity = null
    _transformConfig.set(
      current.copy(
        sessionIdentity = null,
        transformIdentity = null,
        bufferSize = IntSize.Zero,
        rotationDegrees = 0,
        isPreviewMirrored = false,
        isPreviewFrameAvailable = false,
        geometry = null,
      ),
    )
    _previewTransformRevision.intValue++
    _previewResolution.value = IntSize.Zero
  }

  @MainThread
  internal fun createCurrentTextureViewTransform(
    sessionIdentity: CameraFrameTransformIdentity,
  ): Matrix? {
    val current = _transformConfig.get()
    if (current.sessionIdentity !== sessionIdentity || !current.isPreviewFrameAvailable) return null
    return current.geometry?.let { geometry ->
      createTextureViewTransform(geometry, current.isMirrored != current.isPreviewMirrored)
    }
  }

  @MainThread
  internal fun markPreviewFrameAvailable(
    sessionIdentity: CameraFrameTransformIdentity,
  ): Matrix? {
    val current = _transformConfig.get()
    if (current.sessionIdentity !== sessionIdentity || current.isPreviewFrameAvailable) return null
    _transformConfig.set(current.copy(isPreviewFrameAvailable = true))
    if (_failureClearingSessionIdentity === sessionIdentity) {
      _failureClearingSessionIdentity = null
      _failure.value = null
    }
    _previewTransformRevision.intValue++
    return current.geometry?.let { geometry ->
      createTextureViewTransform(geometry, current.isMirrored != current.isPreviewMirrored)
    }
  }

  @MainThread
  internal fun calculateCurrentTextureViewTransform(
    previewSize: IntSize,
    contentScale: ContentScale,
    isMirrored: Boolean,
  ): Matrix? {
    val current = _transformConfig.get()
    if (!current.isPreviewFrameAvailable) return null
    val geometry = calculatePreviewGeometry(
      bufferSize = current.bufferSize,
      rotationDegrees = current.rotationDegrees,
      previewSize = previewSize,
      contentScale = contentScale,
    ) ?: return null
    return createTextureViewTransform(geometry, isMirrored != current.isPreviewMirrored)
  }

  @MainThread
  internal fun reset() {
    _takeScreenshotAction = null
    _attemptIdentity = null
    _failureClearingSessionIdentity = null
    _failure.value = null
    _transformConfig.set(PreviewTransformConfig())
    _previewTransformRevision.intValue++
    _previewResolution.value = IntSize.Zero
    _retryGeneration.intValue = 0
  }
}

internal class CameraPreviewAttemptIdentity

private data class PreviewTransformConfig(
  val sessionIdentity: CameraFrameTransformIdentity? = null,
  val transformIdentity: CameraFrameTransformIdentity? = null,
  val bufferSize: IntSize = IntSize.Zero,
  val rotationDegrees: Int = 0,
  val previewSize: IntSize = IntSize.Zero,
  val contentScale: ContentScale = ContentScale.Crop,
  val geometry: PreviewGeometry? = null,
  val isPreviewMirrored: Boolean = false,
  val isMirrored: Boolean = false,
  val isPreviewFrameAvailable: Boolean = false,
)

internal data class PreviewBitmapRequest(
  val sessionIdentity: CameraFrameTransformIdentity,
  val transformIdentity: CameraFrameTransformIdentity,
  val captureSize: IntSize,
  val previewSize: IntSize,
  val contentBounds: Rect,
  val shouldMirror: Boolean,
)

internal data class PreviewGeometry(
  val previewSize: IntSize,
  val contentSize: Size,
  val offsetX: Float,
  val offsetY: Float,
  val scaleX: Float,
  val scaleY: Float,
)

internal fun createTextureViewTransform(
  geometry: PreviewGeometry,
  shouldMirror: Boolean = false,
): Matrix {
  val previewWidth = geometry.previewSize.width.toFloat()
  val previewHeight = geometry.previewSize.height.toFloat()
  val contentTransform = Matrix().apply {
    setValues(
      floatArrayOf(
        geometry.contentSize.width / previewWidth, 0f, geometry.offsetX,
        0f, geometry.contentSize.height / previewHeight, geometry.offsetY,
        0f, 0f, 1f,
      ),
    )
  }
  if (!shouldMirror) return contentTransform
  val mirror = Matrix().apply {
    setValues(
      floatArrayOf(
        -1f, 0f, previewWidth,
        0f, 1f, 0f,
        0f, 0f, 1f,
      ),
    )
  }
  return Matrix().apply { setConcat(mirror, contentTransform) }
}

internal fun calculatePreviewGeometry(
  bufferSize: IntSize,
  rotationDegrees: Int,
  previewSize: IntSize,
  contentScale: ContentScale,
): PreviewGeometry? {
  if (
    bufferSize.width <= 0 || bufferSize.height <= 0 ||
    previewSize.width <= 0 || previewSize.height <= 0
  ) {
    return null
  }
  val normalizedRotation = normalizeRotation(rotationDegrees)
  val isQuarterTurn = normalizedRotation == 90 || normalizedRotation == 270
  val orientedWidth = if (isQuarterTurn) bufferSize.height else bufferSize.width
  val orientedHeight = if (isQuarterTurn) bufferSize.width else bufferSize.height
  val scale = contentScale.computeScaleFactor(
    srcSize = Size(orientedWidth.toFloat(), orientedHeight.toFloat()),
    dstSize = Size(previewSize.width.toFloat(), previewSize.height.toFloat()),
  )
  val contentWidth = orientedWidth * scale.scaleX
  val contentHeight = orientedHeight * scale.scaleY
  if (!contentWidth.isFinite() || !contentHeight.isFinite() || contentWidth <= 0f || contentHeight <= 0f) return null
  return PreviewGeometry(
    previewSize = previewSize,
    contentSize = Size(contentWidth, contentHeight),
    offsetX = (previewSize.width - contentWidth) / 2f,
    offsetY = (previewSize.height - contentHeight) / 2f,
    scaleX = contentWidth / orientedWidth,
    scaleY = contentHeight / orientedHeight,
  )
}

private fun createBufferToPreviewMatrix(
  bufferSize: IntSize,
  rotationDegrees: Int,
  geometry: PreviewGeometry,
): Matrix {
  val width = bufferSize.width.toFloat()
  val height = bufferSize.height.toFloat()
  val scaleX = geometry.scaleX
  val scaleY = geometry.scaleY
  val offsetX = geometry.offsetX
  val offsetY = geometry.offsetY
  val values = when (rotationDegrees) {
    0 -> floatArrayOf(
      scaleX, 0f, offsetX,
      0f, scaleY, offsetY,
      0f, 0f, 1f,
    )
    90 -> floatArrayOf(
      0f, -scaleX, scaleX * height + offsetX,
      scaleY, 0f, offsetY,
      0f, 0f, 1f,
    )
    180 -> floatArrayOf(
      -scaleX, 0f, scaleX * width + offsetX,
      0f, -scaleY, scaleY * height + offsetY,
      0f, 0f, 1f,
    )
    270 -> floatArrayOf(
      0f, scaleX, offsetX,
      -scaleY, 0f, scaleY * width + offsetY,
      0f, 0f, 1f,
    )
    else -> error("Unsupported rotation: $rotationDegrees")
  }
  return Matrix().apply { setValues(values) }
}

internal fun normalizeRotation(rotationDegrees: Int): Int {
  val normalized = ((rotationDegrees % 360) + 360) % 360
  require(normalized % 90 == 0) { "rotationDegrees must be a multiple of 90." }
  return normalized
}
