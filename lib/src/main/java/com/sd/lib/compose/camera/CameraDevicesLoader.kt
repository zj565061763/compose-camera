@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.hardware.Camera

/** 在首次加载或手动刷新时读取设备列表 */
internal class CameraDevicesLoader(
  private val state: CameraDevicesState,
  private val cameraApi: CameraDevicesApi = PlatformCameraDevicesApi,
) : AutoCloseable {
  private var _closed = false

  fun refresh() {
    if (_closed) return
    state.beginRefresh()
    val devices = try {
      List(cameraApi.getNumberOfCameras()) { cameraId -> cameraDeviceInfo(cameraApi, cameraId) }
    } catch (error: Exception) {
      if (!_closed) state.publishError(error)
      return
    }
    if (!_closed) state.publishDevices(devices)
  }

  override fun close() {
    _closed = true
  }
}

internal interface CameraDevicesApi {
  fun getNumberOfCameras(): Int
  fun getCameraInfo(cameraId: Int): Camera.CameraInfo
}

private object PlatformCameraDevicesApi : CameraDevicesApi {
  override fun getNumberOfCameras(): Int = Camera.getNumberOfCameras()

  override fun getCameraInfo(cameraId: Int): Camera.CameraInfo {
    return Camera.CameraInfo().also { Camera.getCameraInfo(cameraId, it) }
  }
}

private fun cameraDeviceInfo(cameraApi: CameraDevicesApi, cameraId: Int): CameraDeviceInfo {
  return CameraDeviceInfo(
    cameraId = cameraId.toString(),
    lens = readCameraLens(cameraApi, cameraId),
  )
}

private fun readCameraLens(cameraApi: CameraDevicesApi, cameraId: Int): CameraLens? {
  return try {
    val info = cameraApi.getCameraInfo(cameraId)
    when (info.facing) {
      Camera.CameraInfo.CAMERA_FACING_FRONT -> CameraLens.FRONT
      Camera.CameraInfo.CAMERA_FACING_BACK -> CameraLens.BACK
      else -> null
    }
  } catch (_: Exception) {
    null
  }
}
