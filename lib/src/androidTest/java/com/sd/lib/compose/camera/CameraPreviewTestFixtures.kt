package com.sd.lib.compose.camera

internal fun frame(
  identity: CameraFrameTransformIdentity?,
  width: Int,
  height: Int,
  rotationDegrees: Int,
  data: ByteArray = ByteArray(width * height * 3 / 2),
): CameraFrame.Preview {
  return CameraFrame.Preview(
    data = data,
    width = width,
    height = height,
    rotationDegrees = rotationDegrees,
    transformIdentity = identity,
  )
}
