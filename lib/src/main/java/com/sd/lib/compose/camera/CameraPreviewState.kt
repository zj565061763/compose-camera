package com.sd.lib.compose.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
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
  private val _retryGeneration = mutableIntStateOf(0)

  /** 当前会话使用的原始帧分辨率，会话未运行时为 [IntSize.Zero]。 */
  val previewResolution: State<IntSize> = _previewResolution

  /** 在外部条件恢复后关闭并重新创建当前相机会话 */
  @MainThread
  fun retry() {
    _retryGeneration.intValue++
  }

  internal val retryGeneration: Int get() = _retryGeneration.intValue

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
          -1f, 0f, geometry.offsetX * 2 + geometry.contentSize.width,
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
          -1f, 0f, geometry.offsetX * 2 + geometry.contentSize.width,
          0f, 1f, 0f,
          0f, 0f, 1f,
        ),
      )
    }
  }

  @MainThread
  internal fun createSampledFrame(
    source: Bitmap,
    sessionIdentity: CameraFrameTransformIdentity,
    isPreviewMirrored: Boolean,
  ): CameraFrame.PreviewSampled? {
    val config = _transformConfig.get()
    if (config.sessionIdentity !== sessionIdentity) return null
    val transformIdentity = config.transformIdentity ?: return null
    val geometry = config.geometry ?: return null
    val sourceSize = IntSize(source.width, source.height)
    val sourceOffsetX: Float
    val sourceOffsetY: Float
    when (sourceSize) {
      config.previewSize -> {
        sourceOffsetX = 0f
        sourceOffsetY = 0f
      }
      geometry.contentSize -> {
        sourceOffsetX = geometry.offsetX
        sourceOffsetY = geometry.offsetY
      }
      else -> return null
    }

    val output = Bitmap.createBitmap(config.previewSize.width, config.previewSize.height, Bitmap.Config.ARGB_8888)
    Canvas(output).apply {
      if (isPreviewMirrored) scale(-1f, 1f, sourceOffsetX + source.width / 2f, sourceOffsetY + source.height / 2f)
      drawBitmap(source, sourceOffsetX, sourceOffsetY, Paint(Paint.FILTER_BITMAP_FLAG))
    }
    return CameraFrame.PreviewSampled(
      data = output,
      rotationDegrees = 0,
      transformIdentity = transformIdentity,
    )
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
  }

  @MainThread
  internal fun startSession(
    sessionIdentity: CameraFrameTransformIdentity,
    bufferSize: IntSize,
    rotationDegrees: Int,
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
        isMirrored = isMirrored,
        geometry = calculatePreviewGeometry(
          bufferSize = bufferSize,
          rotationDegrees = normalizedRotation,
          previewSize = current.previewSize,
          contentScale = current.contentScale,
        ),
      ),
    )
    _previewResolution.value = bufferSize
  }

  @MainThread
  internal fun clearSession(sessionIdentity: CameraFrameTransformIdentity? = null) {
    val current = _transformConfig.get()
    if (sessionIdentity != null && current.sessionIdentity !== sessionIdentity) return
    _transformConfig.set(
      current.copy(
        sessionIdentity = null,
        transformIdentity = null,
        bufferSize = IntSize.Zero,
        rotationDegrees = 0,
        geometry = null,
      ),
    )
    _previewResolution.value = IntSize.Zero
  }

  @MainThread
  internal fun calculateCurrentPreviewGeometry(
    previewSize: IntSize,
    contentScale: ContentScale,
  ): PreviewGeometry? {
    val current = _transformConfig.get()
    return calculatePreviewGeometry(
      bufferSize = current.bufferSize,
      rotationDegrees = current.rotationDegrees,
      previewSize = previewSize,
      contentScale = contentScale,
    )
  }

  @MainThread
  internal fun reset() {
    _transformConfig.set(PreviewTransformConfig())
    _previewResolution.value = IntSize.Zero
    _retryGeneration.intValue = 0
  }
}

private data class PreviewTransformConfig(
  val sessionIdentity: CameraFrameTransformIdentity? = null,
  val transformIdentity: CameraFrameTransformIdentity? = null,
  val bufferSize: IntSize = IntSize.Zero,
  val rotationDegrees: Int = 0,
  val previewSize: IntSize = IntSize.Zero,
  val contentScale: ContentScale = ContentScale.Crop,
  val geometry: PreviewGeometry? = null,
  val isMirrored: Boolean = false,
)

internal data class PreviewGeometry(
  val previewSize: IntSize,
  val contentSize: IntSize,
  val offsetX: Float,
  val offsetY: Float,
  val scaleX: Float,
  val scaleY: Float,
)

internal fun createTextureViewTransform(geometry: PreviewGeometry): Matrix {
  val previewWidth = geometry.previewSize.width.toFloat()
  val previewHeight = geometry.previewSize.height.toFloat()
  return Matrix().apply {
    setValues(
      floatArrayOf(
        geometry.contentSize.width / previewWidth, 0f, geometry.offsetX,
        0f, geometry.contentSize.height / previewHeight, geometry.offsetY,
        0f, 0f, 1f,
      ),
    )
  }
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
  val contentWidth = (orientedWidth * scale.scaleX).roundToInt().coerceAtLeast(1)
  val contentHeight = (orientedHeight * scale.scaleY).roundToInt().coerceAtLeast(1)
  return PreviewGeometry(
    previewSize = previewSize,
    contentSize = IntSize(contentWidth, contentHeight),
    offsetX = ((previewSize.width - contentWidth) / 2).toFloat(),
    offsetY = ((previewSize.height - contentHeight) / 2).toFloat(),
    scaleX = contentWidth.toFloat() / orientedWidth,
    scaleY = contentHeight.toFloat() / orientedHeight,
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
