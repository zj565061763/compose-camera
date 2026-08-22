package com.sd.lib.compose.camera

enum class CameraMirrorMode {
  /** 保持 CameraX 的默认镜像行为，通常前置摄像头镜像、后置摄像头不镜像。 */
  AUTO,

  /** 始终镜像 */
  ON,

  /** 始终不做镜像 */
  OFF,
}
