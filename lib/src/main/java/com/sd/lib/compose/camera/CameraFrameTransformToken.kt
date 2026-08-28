package com.sd.lib.compose.camera

/**
 * 标识 [CameraFrame] 所属的预览变换。
 *
 * 此对象不持有帧图像，可以在帧回调返回后安全保留。
 * 使用 [isSameTransform] 判断两个令牌是否属于同一个相机会话和 Compose 显示坐标配置。
 */
class CameraFrameTransformToken internal constructor(
  transformIdentity: CameraFrameTransformIdentity?,
) {
  private val _transformIdentity = transformIdentity

  /** 判断两个令牌是否来自同一个会话和显示坐标 generation */
  fun isSameTransform(other: CameraFrameTransformToken): Boolean {
    if (this === other) return true
    val transformIdentity = _transformIdentity ?: return false
    return transformIdentity === other._transformIdentity
  }

  internal fun matches(transformIdentity: CameraFrameTransformIdentity?): Boolean {
    return transformIdentity != null && _transformIdentity === transformIdentity
  }
}

/** 稳定的预览变换 generation */
internal class CameraFrameTransformIdentity
