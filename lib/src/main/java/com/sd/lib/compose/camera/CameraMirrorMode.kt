package com.sd.lib.compose.camera

/** 预览镜像模式 */
enum class CameraMirrorMode {
  /** 前置摄像头镜像，后置摄像头不镜像。 */
  AUTO,

  /** 始终镜像 */
  ON,

  /** 始终不做镜像 */
  OFF,
}

internal fun CameraMirrorMode.isMirrored(isFrontFacing: Boolean): Boolean {
  return when (this) {
    CameraMirrorMode.AUTO -> isFrontFacing
    CameraMirrorMode.ON -> true
    CameraMirrorMode.OFF -> false
  }
}
