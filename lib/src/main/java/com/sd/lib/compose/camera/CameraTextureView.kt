package com.sd.lib.compose.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.view.TextureView
import java.util.concurrent.atomic.AtomicLong

/** 保留 TextureView 的帧监听，并单独记录生产者提交的新帧 */
internal open class CameraTextureView(context: Context) : TextureView(context) {
  private var _surfaceTextureListener: SurfaceTextureListener? = null

  val frameNumber: Long
    get() = (surfaceTexture as? PreviewSurfaceTexture)?.frameNumber ?: 0L

  init {
    super.setSurfaceTextureListener(object : SurfaceTextureListener {
      override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        // 等平台创建渲染层后再替换尚未交付的 Surface，保留低版本的销毁回调。
        val previewSurface = PreviewSurfaceTexture()
        previewSurface.setDefaultBufferSize(width, height)
        setSurfaceTexture(previewSurface)
        _surfaceTextureListener?.onSurfaceTextureAvailable(previewSurface, width, height)
      }

      override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        _surfaceTextureListener?.onSurfaceTextureSizeChanged(surface, width, height)
      }

      override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        return _surfaceTextureListener?.onSurfaceTextureDestroyed(surface) ?: true
      }

      override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        _surfaceTextureListener?.onSurfaceTextureUpdated(surface)
      }
    })
  }

  override fun setSurfaceTextureListener(listener: SurfaceTextureListener?) {
    _surfaceTextureListener = listener
  }

  override fun getSurfaceTextureListener(): SurfaceTextureListener? = _surfaceTextureListener
}

private class PreviewSurfaceTexture : SurfaceTexture(0) {
  private val _frameNumber = AtomicLong()

  val frameNumber: Long
    get() = _frameNumber.get()

  init {
    detachFromGLContext()
    setOnFrameAvailableListener(null, null)
  }

  override fun setOnFrameAvailableListener(listener: OnFrameAvailableListener?, handler: Handler?) {
    // 首次绘制和可见性切换期间也记录帧；先计数，再让 TextureView 安排更新。
    super.setOnFrameAvailableListener({ surface ->
      _frameNumber.incrementAndGet()
      listener?.onFrameAvailable(surface)
    }, handler)
  }
}
