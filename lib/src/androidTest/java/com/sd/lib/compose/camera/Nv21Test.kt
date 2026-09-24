package com.sd.lib.compose.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class Nv21Test {
  @Test
  fun copyYuv420ToNv21_skipsRowPaddingAndInterleavesVu() {
    // 4×2 帧，Y 行尾有 2 字节填充，UV 为半平面交错布局
    val y = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 0, 0, 5, 6, 7, 8))
    val uv = byteArrayOf(10, 20, 11, 21, 0)
    val u = ByteBuffer.wrap(uv)
    val v = ByteBuffer.wrap(uv, 1, uv.size - 1).slice()
    val output = ByteArray(12)

    copyYuv420ToNv21(4, 2, y, 6, 1, u, v, 4, 2, output)

    assertThat(output).isEqualTo(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 20, 10, 21, 11))
  }

  @Test
  fun copyYuv420ToNv21_readsPlanarChromaAndPixelStride() {
    val y = ByteBuffer.wrap(byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0))
    val u = ByteBuffer.wrap(byteArrayOf(10))
    val v = ByteBuffer.wrap(byteArrayOf(20))
    val output = ByteArray(6)

    copyYuv420ToNv21(2, 2, y, 4, 2, u, v, 1, 1, output)

    assertThat(output).isEqualTo(byteArrayOf(1, 2, 3, 4, 20, 10))
  }

  @Test
  fun nv21BufferSize_rejectsOddOrOverflowingDimensions() {
    assertThat(nv21BufferSize(4, 2)).isEqualTo(12)
    assertThat(nv21BufferSize(3, 2)).isNull()
    assertThat(nv21BufferSize(0, 2)).isNull()
    assertThat(nv21BufferSize(65_536, 65_536)).isNull()
  }

  @Test
  fun cameraFrame_toBitmapConvertsValidFrameAndReturnsNullForInvalidFrame() {
    val bitmap = checkNotNull(frame(CameraFrameTransformIdentity(), 4, 4, 0).toBitmap())

    assertThat(bitmap.width).isEqualTo(4)
    assertThat(bitmap.height).isEqualTo(4)
    assertThat(frame(null, 0, 0, 0, ByteArray(0)).toBitmap()).isNull()
    bitmap.recycle()
  }
}
