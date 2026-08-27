@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.hardware.Camera

/** 在首次加载或手动刷新时读取设备列表 */
internal class CameraDevicesLoader(
  private val state: CameraDevicesState,
) : AutoCloseable {
  private var _closed = false

  fun refresh() {
    if (_closed) return
    state.beginRefresh()
    val devices = try {
      List(Camera.getNumberOfCameras()) { cameraId -> cameraDeviceInfo(cameraId) }
    } catch (error: Exception) {
      state.publishError(error)
      return
    }
    if (!_closed) state.publishDevices(devices)
  }

  override fun close() {
    _closed = true
  }
}

private fun cameraDeviceInfo(cameraId: Int): CameraDeviceInfo {
  return CameraDeviceInfo(
    cameraId = cameraId.toString(),
    lens = readCameraLens(cameraId),
  )
}

private fun readCameraLens(cameraId: Int): CameraLens? {
  return try {
    val info = Camera.CameraInfo().also { Camera.getCameraInfo(cameraId, it) }
    when (info.facing) {
      Camera.CameraInfo.CAMERA_FACING_FRONT -> CameraLens.FRONT
      Camera.CameraInfo.CAMERA_FACING_BACK -> CameraLens.BACK
      else -> null
    }
  } catch (_: Exception) {
    null
  }
}
