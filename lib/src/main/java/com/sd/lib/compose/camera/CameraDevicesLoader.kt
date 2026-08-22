package com.sd.lib.compose.camera

import android.content.Context
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalLensFacing
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat

/** 初始化 CameraX，并在首次加载或手动刷新时读取设备列表。 */
internal class CameraDevicesLoader(
  context: Context,
  private val state: CameraDevicesState,
) : AutoCloseable {
  private val _context = context.applicationContext
  private val _mainExecutor = ContextCompat.getMainExecutor(_context)

  private var _closed = false
  private var _providerInitializing = false
  private var _provider: ProcessCameraProvider? = null

  fun refresh() {
    if (_closed) return
    state.beginRefresh()
    val provider = _provider
    if (provider == null) {
      initializeProvider()
    } else {
      publishDevices(provider)
    }
  }

  private fun initializeProvider() {
    if (_closed || _providerInitializing) return
    _providerInitializing = true
    val providerFuture = try {
      ProcessCameraProvider.getInstance(_context)
    } catch (error: Exception) {
      _providerInitializing = false
      state.publishError(error)
      return
    }

    try {
      providerFuture.addListener(
        {
          _providerInitializing = false
          if (!_closed) {
            val provider = try {
              providerFuture.get()
            } catch (error: Exception) {
              state.publishError(error)
              return@addListener
            }
            _provider = provider
            publishDevices(provider)
          }
        },
        _mainExecutor,
      )
    } catch (error: Exception) {
      _providerInitializing = false
      state.publishError(error)
    }
  }

  private fun publishDevices(provider: ProcessCameraProvider) {
    val devices = try {
      provider.availableCameraInfos
        .map(CameraInfo::toCameraDeviceInfo)
        .distinctBy(CameraDeviceInfo::cameraId)
    } catch (error: Exception) {
      state.publishError(error)
      return
    }
    state.publishDevices(devices)
  }

  override fun close() {
    _closed = true
    _provider = null
  }
}

@androidx.annotation.OptIn(
  markerClass = [ExperimentalCamera2Interop::class, ExperimentalLensFacing::class],
)
private fun CameraInfo.toCameraDeviceInfo(): CameraDeviceInfo {
  return CameraDeviceInfo(
    cameraId = Camera2CameraInfo.from(this).cameraId,
    lens = cameraLensOrNull { lensFacing },
  )
}

/** CameraX 允许异常 HAL 在读取 lensFacing 时抛出 IllegalArgumentException */
@androidx.annotation.OptIn(markerClass = [ExperimentalLensFacing::class])
internal fun cameraLensOrNull(readLensFacing: () -> Int): CameraLens? {
  val lensFacing = try {
    readLensFacing()
  } catch (_: IllegalArgumentException) {
    return null
  }
  return when (lensFacing) {
    CameraSelector.LENS_FACING_FRONT -> CameraLens.FRONT
    CameraSelector.LENS_FACING_BACK -> CameraLens.BACK
    CameraSelector.LENS_FACING_EXTERNAL -> CameraLens.EXTERNAL
    else -> null
  }
}
