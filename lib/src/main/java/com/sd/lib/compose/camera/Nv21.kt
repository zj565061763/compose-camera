package com.sd.lib.compose.camera

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/** 把 YUV_420_888 帧转换为 NV21，尺寸匹配时复用 [reuse] */
internal fun ImageProxy.toNv21(reuse: ByteArray?): ByteArray? {
  if (format != ImageFormat.YUV_420_888 || planes.size < 3) return null
  val size = nv21BufferSize(width, height) ?: return null
  val output = reuse?.takeIf { it.size == size } ?: ByteArray(size)
  val (yPlane, uPlane, vPlane) = planes
  copyYuv420ToNv21(
    width = width,
    height = height,
    y = yPlane.buffer,
    yRowStride = yPlane.rowStride,
    yPixelStride = yPlane.pixelStride,
    u = uPlane.buffer,
    v = vPlane.buffer,
    uvRowStride = uPlane.rowStride,
    uvPixelStride = uPlane.pixelStride,
    output = output,
  )
  return output
}

/** 按行列步长读取三个平面，输出 Y 平面后接 VU 交错平面；U、V 平面的步长由格式保证相同 */
internal fun copyYuv420ToNv21(
  width: Int,
  height: Int,
  y: ByteBuffer,
  yRowStride: Int,
  yPixelStride: Int,
  u: ByteBuffer,
  v: ByteBuffer,
  uvRowStride: Int,
  uvPixelStride: Int,
  output: ByteArray,
) {
  var index = 0
  val yRows = y.duplicate()
  for (row in 0 until height) {
    val rowStart = row * yRowStride
    if (yPixelStride == 1) {
      yRows.position(rowStart)
      yRows.get(output, index, width)
      index += width
    } else {
      for (column in 0 until width) output[index++] = y.get(rowStart + column * yPixelStride)
    }
  }
  for (row in 0 until height / 2) {
    val rowStart = row * uvRowStride
    for (column in 0 until width / 2) {
      val offset = rowStart + column * uvPixelStride
      output[index++] = v.get(offset)
      output[index++] = u.get(offset)
    }
  }
}

/** NV21 需要正偶数宽高，所需字节数超出 `Int` 范围时返回 `null` */
internal fun nv21BufferSize(width: Int, height: Int): Int? {
  if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) return null
  val pixelCount = width.toLong() * height
  if (pixelCount > Int.MAX_VALUE * 2L / 3L) return null
  return (pixelCount * 3 / 2).toInt()
}
