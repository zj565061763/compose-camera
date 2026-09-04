package com.sd.lib.compose.camera

import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/** 一次枚举得到的摄像头，调用方应把 [cameraId] 视为不透明标识。 */
@Immutable
data class CameraDeviceInfo(
  val cameraId: String,
  val lens: CameraLens?,
)

internal sealed interface CameraDevicesRefreshEvent {
  data object Success : CameraDevicesRefreshEvent

  data class Failure(val error: Throwable) : CameraDevicesRefreshEvent
}

/** 可用摄像头列表及其加载状态 */
@Stable
class CameraDevicesState internal constructor() {
  private val _devices = mutableStateOf(emptyList<CameraDeviceInfo>())
  private val _isLoading = mutableStateOf(true)
  private val _hasLoadedDevices = mutableStateOf(false)
  private val _error = mutableStateOf<Throwable?>(null)
  private var _refreshAction: (() -> Unit)? = null
  private var _latestRefreshEvent: CameraDevicesRefreshEvent? = null
  private val _refreshListeners = linkedSetOf<(CameraDevicesRefreshEvent) -> Unit>()

  /** 最近一次枚举到的摄像头 */
  val devices: State<List<CameraDeviceInfo>> = _devices

  /** 是否正在重新读取摄像头列表 */
  val isLoading: State<Boolean> = _isLoading

  /** 当前设备枚举错误 */
  val error: State<Throwable?> = _error

  internal val hasLoadedDevices: State<Boolean> = _hasLoadedDevices

  /** 主动重新读取摄像头列表 */
  @MainThread
  fun refresh() {
    _refreshAction?.invoke()
  }

  @MainThread
  internal fun attachRefreshAction(action: (() -> Unit)?) {
    _refreshAction = action
  }

  /** 返回订阅前最近完成的刷新事件，由订阅者完成一次补发。 */
  @MainThread
  internal fun addRefreshListener(
    listener: (CameraDevicesRefreshEvent) -> Unit,
  ): CameraDevicesRefreshEvent? {
    _refreshListeners += listener
    return _latestRefreshEvent
  }

  @MainThread
  internal fun removeRefreshListener(listener: (CameraDevicesRefreshEvent) -> Unit) {
    _refreshListeners -= listener
  }

  @MainThread
  internal fun beginRefresh() {
    _isLoading.value = true
  }

  @MainThread
  internal fun publishDevices(devices: List<CameraDeviceInfo>) {
    _devices.value = devices
    _isLoading.value = false
    _hasLoadedDevices.value = true
    _error.value = null
    val event = CameraDevicesRefreshEvent.Success
    _latestRefreshEvent = event
    notifyListeners(_refreshListeners.toList(), event)
  }

  @MainThread
  internal fun publishError(error: Throwable) {
    _isLoading.value = false
    _error.value = error
    val event = CameraDevicesRefreshEvent.Failure(error)
    _latestRefreshEvent = event
    notifyListeners(_refreshListeners.toList(), event)
  }
}

/**
 * 发布当前能够识别的摄像头。
 *
 * 调用方应在取得 `android.permission.CAMERA` 后组合。
 * 状态不会监听运行时设备变化；需要更新列表时调用 [CameraDevicesState.refresh] 主动重新枚举。
 */
@Composable
fun rememberCameraDevicesState(): CameraDevicesState {
  val state = remember { CameraDevicesState() }

  DisposableEffect(state) {
    val loader = CameraDevicesLoader(state)
    state.attachRefreshAction(loader::refresh)
    loader.refresh()

    onDispose {
      state.attachRefreshAction(null)
      loader.close()
    }
  }
  return state
}

/** 一个订阅者的普通异常不能阻止同一事件送达其他订阅者 */
private fun <T> notifyListeners(listeners: List<(T) -> Unit>, value: T) {
  var firstFailure: Exception? = null
  listeners.forEach { listener ->
    try {
      listener(value)
    } catch (error: Exception) {
      val previousFailure = firstFailure
      if (previousFailure == null) {
        firstFailure = error
      } else if (previousFailure !== error) {
        previousFailure.addSuppressed(error)
      }
    }
  }
  firstFailure?.also { throw it }
}
