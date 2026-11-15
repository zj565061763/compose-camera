package com.sd.lib.compose.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.camera.core.AspectRatio
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

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

/** 预览内容在 Compose 区域中的居中几何位置 */
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
  if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) {
    return null
  }
  return PreviewGeometry(
    previewSize = previewSize,
    contentSize = Size(width, height),
    offsetX = (previewSize.width - width) / 2f,
    offsetY = (previewSize.height - height) / 2f,
    scaleX = scale.scaleX,
    scaleY = scale.scaleY,
  )
}

/** 原始帧坐标依次经过裁剪、旋转、缩放和居中，映射到未镜像的 Compose 预览区域 */
internal fun createRawFrameToPreviewMatrix(
  frame: CameraFrame.Preview,
  rawFrame: RawFrameLayout,
  geometry: PreviewGeometry,
): Matrix? {
  if (frame.width != rawFrame.bufferSize.width || frame.height != rawFrame.bufferSize.height) return null
  if (normalizeRotation(frame.rotationDegrees) != rawFrame.rotationDegrees) return null
  val crop = rawFrame.cropRect
  if (crop.width <= 0 || crop.height <= 0) return null
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

/** 围绕预览区域水平中心翻转矩阵 */
internal fun applyHorizontalMirror(matrix: Matrix, previewWidth: Int) {
  matrix.postScale(-1f, 1f, previewWidth / 2f, 0f)
}

/** 把预览控件截图绘制到 Compose 预览区域并按需水平翻转，[source] 总会被回收或直接转交 */
internal fun renderPreviewBitmap(source: Bitmap, geometry: PreviewGeometry, shouldMirror: Boolean): Bitmap {
  val size = geometry.previewSize
  val isDirect = !shouldMirror &&
    source.width == size.width &&
    source.height == size.height &&
    geometry.offsetX == 0f &&
    geometry.offsetY == 0f
  if (isDirect) return source
  return try {
    val output = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    try {
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
      output
    } catch (error: Throwable) {
      output.recycle()
      throw error
    }
  } finally {
    source.recycle()
  }
}

/** 选择最接近预览区域比例的相机输出比例，比例相同时保留 4:3 的完整视野 */
internal fun chooseAspectRatio(previewViewSize: IntSize, sensorRotationDegrees: Int): Int {
  if (previewViewSize.width <= 0 || previewViewSize.height <= 0) return AspectRatio.RATIO_4_3
  val viewRatio = previewViewSize.width.toFloat() / previewViewSize.height
  val isLandscapeSensor = sensorRotationDegrees % 180 == 0
  val oriented16By9 = if (isLandscapeSensor) 16f / 9f else 9f / 16f
  val oriented4By3 = if (isLandscapeSensor) 4f / 3f else 3f / 4f
  return if (abs(oriented16By9 - viewRatio) < abs(oriented4By3 - viewRatio)) {
    AspectRatio.RATIO_16_9
  } else {
    AspectRatio.RATIO_4_3
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
