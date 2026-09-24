package com.sd.lib.compose.camera

import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** 一次枚举得到的摄像头，调用方应把 [cameraId] 视为不透明标识 */
@Immutable
data class CameraDeviceInfo(
  val cameraId: String,
  val lens: CameraLens?,
)

/** 可用摄像头列表及其加载状态 */
@Stable
class CameraDevicesState internal constructor() {
  private val _devices = mutableStateOf(emptyList<CameraDeviceInfo>())
  private val _isLoading = mutableStateOf(true)
  private val _hasLoadedDevices = mutableStateOf(false)
  private val _error = mutableStateOf<Throwable?>(null)
  private val _refreshVersion = mutableIntStateOf(0)
  private var _refreshAction: (() -> Unit)? = null

  /** 最近一次枚举到的摄像头 */
  val devices: State<List<CameraDeviceInfo>> = _devices

  /** 是否正在重新读取摄像头列表 */
  val isLoading: State<Boolean> = _isLoading

  /** 当前设备枚举错误 */
  val error: State<Throwable?> = _error

  internal val hasLoadedDevices: State<Boolean> = _hasLoadedDevices

  /** 每次枚举完成都会递增，连续失败使用同一个异常实例时也能被观察到 */
  internal val refreshVersion: State<Int> = _refreshVersion

  /** 主动重新读取摄像头列表 */
  @MainThread
  fun refresh() {
    _refreshAction?.invoke()
  }

  @MainThread
  internal fun attachRefreshAction(action: (() -> Unit)?) {
    _refreshAction = action
  }

  @MainThread
  internal fun beginRefresh() {
    _isLoading.value = true
  }

  @MainThread
  internal fun publishDevices(devices: List<CameraDeviceInfo>) {
    _devices.value = devices
    _hasLoadedDevices.value = true
    _error.value = null
    finishRefresh()
  }

  @MainThread
  internal fun publishError(error: Throwable) {
    _error.value = error
    finishRefresh()
  }

  private fun finishRefresh() {
    _isLoading.value = false
    _refreshVersion.intValue++
  }
}

/**
 * 发布当前能够识别的摄像头
 *
 * 调用方应在取得 `android.permission.CAMERA` 后组合。
 * 状态不会监听运行时设备变化；需要更新列表时调用 [CameraDevicesState.refresh] 主动重新枚举。
 */
@Composable
fun rememberCameraDevicesState(): CameraDevicesState {
  val context = LocalContext.current.applicationContext
  val state = remember { CameraDevicesState() }

  DisposableEffect(state, context) {
    val loader = CameraDevicesLoader(state, CameraXDevicesSource(context))
    state.attachRefreshAction(loader::refresh)
    loader.refresh()

    onDispose {
      state.attachRefreshAction(null)
      loader.close()
    }
  }
  return state
}
