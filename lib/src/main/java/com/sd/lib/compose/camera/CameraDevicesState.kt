package com.sd.lib.compose.camera

import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** CameraX 一次枚举得到的摄像头。cameraId 只保证在当前设备和 Camera HAL 中有意义。 */
@Immutable
data class CameraDeviceInfo(
  val cameraId: String,
  val lens: CameraLens?,
)

/** CameraX 可用摄像头列表及其加载状态 */
@Stable
class CameraDevicesState internal constructor() {
  private val _devices = mutableStateOf(emptyList<CameraDeviceInfo>())
  private val _isLoading = mutableStateOf(true)
  private val _hasLoadedDevices = mutableStateOf(false)
  private val _error = mutableStateOf<Throwable?>(null)
  private var _refreshAction: (() -> Unit)? = null
  private val _errorListeners = linkedSetOf<(Throwable) -> Unit>()

  /** CameraX 最近一次枚举到的摄像头，保持 CameraX 提供的顺序。 */
  val devices: State<List<CameraDeviceInfo>> = _devices

  /** 是否正在重新读取 CameraX 摄像头列表 */
  val isLoading: State<Boolean> = _isLoading

  /** 当前 CameraX 初始化或设备枚举错误 */
  val error: State<Throwable?> = _error

  internal val hasLoadedDevices: State<Boolean> = _hasLoadedDevices

  /** 主动重新读取摄像头列表；初始化失败时也会重新尝试取得 CameraX Provider。 */
  @MainThread
  fun refresh() {
    _refreshAction?.invoke()
  }

  @MainThread
  internal fun attachRefreshAction(action: (() -> Unit)?) {
    _refreshAction = action
  }

  /** 返回订阅前已经存在的错误，由订阅者完成一次补发。 */
  @MainThread
  internal fun addErrorListener(
    listener: (Throwable) -> Unit,
  ): Throwable? {
    _errorListeners += listener
    return _error.value
  }

  @MainThread
  internal fun removeErrorListener(listener: (Throwable) -> Unit) {
    _errorListeners -= listener
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
    publishErrorIfChanged(null)
  }

  @MainThread
  internal fun publishError(error: Throwable) {
    _isLoading.value = false
    publishErrorIfChanged(error)
  }

  @MainThread
  private fun publishErrorIfChanged(error: Throwable?) {
    if (error == null) {
      _error.value = null
      return
    }
    if (_error.value === error) return
    _error.value = error
    notifyListeners(_errorListeners.toList(), error)
  }
}

/**
 * 发布 CameraX 当前能够识别的摄像头。
 *
 * 调用方应在取得 `android.permission.CAMERA` 后组合。
 * 状态不会监听运行时设备变化；需要更新列表时调用 [CameraDevicesState.refresh] 主动重新枚举。
 */
@Composable
fun rememberCameraDevicesState(): CameraDevicesState {
  val context = LocalContext.current.applicationContext
  val state = remember(context) { CameraDevicesState() }

  DisposableEffect(context, state) {
    val loader = CameraDevicesLoader(context, state)
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
