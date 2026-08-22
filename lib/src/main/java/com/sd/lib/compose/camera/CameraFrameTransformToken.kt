package com.sd.lib.compose.camera

/**
 * 标识 [CameraFrame] 所属的预览变换。
 *
 * 此对象不持有帧图像，可以在 `CameraPreview.onFrame` 返回后安全保留。
 * 使用 [isSameTransform] 判断两个令牌是否属于同一个 `SurfaceRequest` 和 Compose 显示坐标配置。
 */
class CameraFrameTransformToken internal constructor(
  transformIdentity: CameraFrameTransformIdentity?,
) {
  private val _transformIdentity = transformIdentity

  /** 判断两个令牌是否来自同一个请求和显示坐标 generation */
  fun isSameTransform(other: CameraFrameTransformToken): Boolean {
    if (this === other) return true
    val transformIdentity = _transformIdentity ?: return false
    return transformIdentity === other._transformIdentity
  }

  internal fun matches(transformIdentity: CameraFrameTransformIdentity?): Boolean {
    return transformIdentity != null && _transformIdentity === transformIdentity
  }
}

/** 不引用 SurfaceRequest 的稳定预览变换 generation */
internal class CameraFrameTransformIdentity
