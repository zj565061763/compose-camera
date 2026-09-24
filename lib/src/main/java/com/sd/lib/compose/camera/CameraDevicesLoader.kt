package com.sd.lib.compose.camera

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutionException

/** 异步读取设备列表，结果在主线程回调 */
internal fun interface CameraDevicesSource {
  fun load(onResult: (Result<List<CameraDeviceInfo>>) -> Unit)
}

/** 在首次加载或手动刷新时读取设备列表，只发布最近一次请求的结果 */
internal class CameraDevicesLoader(
  private val state: CameraDevicesState,
  private val source: CameraDevicesSource,
) : AutoCloseable {
  private var _generation = 0
  private var _closed = false

  fun refresh() {
    if (_closed) return
    val generation = ++_generation
    state.beginRefresh()
    source.load { result ->
      if (_closed || generation != _generation) return@load
      result.fold(onSuccess = state::publishDevices, onFailure = state::publishError)
    }
  }

  override fun close() {
    _closed = true
  }
}

/** 设备列表与 CameraX 可打开的摄像头保持一致 */
internal class CameraXDevicesSource(private val context: Context) : CameraDevicesSource {
  override fun load(onResult: (Result<List<CameraDeviceInfo>>) -> Unit) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener(
      {
        val result = try {
          Result.success(future.get().platformOrderedCameraInfos(context).map(::toCameraDeviceInfo))
        } catch (error: ExecutionException) {
          Result.failure(error.cause ?: error)
        } catch (error: Exception) {
          Result.failure(error)
        }
        onResult(result)
      },
      ContextCompat.getMainExecutor(context),
    )
  }
}

/** 按系统相机列表的顺序返回 CameraX 可用的摄像头，与平台设备序号一致 */
internal fun ProcessCameraProvider.platformOrderedCameraInfos(context: Context): List<CameraInfo> {
  val cameraInfos = availableCameraInfos
  val platformIds = try {
    (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.toList()
  } catch (_: Exception) {
    return cameraInfos
  }
  return cameraInfos.sortedBy { info ->
    platformIds.indexOf(info.cameraIdString).takeIf { it >= 0 } ?: Int.MAX_VALUE
  }
}

private fun toCameraDeviceInfo(info: CameraInfo): CameraDeviceInfo {
  val lens = when (info.lensFacing) {
    CameraSelector.LENS_FACING_FRONT -> CameraLens.FRONT
    CameraSelector.LENS_FACING_BACK -> CameraLens.BACK
    else -> null
  }
  return CameraDeviceInfo(cameraId = info.cameraIdString, lens = lens)
}
