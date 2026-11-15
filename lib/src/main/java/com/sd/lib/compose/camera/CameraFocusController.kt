package com.sd.lib.compose.camera

import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.MainThread
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor

/** 抽象底层对焦能力，便于测试与扩展更多对焦控制 */
internal interface CameraFocusOperations {
  val isAutoFocusOnly: Boolean

  /** 触发一次中心对焦；设备不支持对焦测光分区时安全忽略，异步结果在主线程通过 [onResult] 回调 */
  fun triggerCenterFocus(onResult: (Throwable?) -> Unit)
}

/**
 * 管理单个会话期间的单次自动对焦生命周期
 *
 * 只在摄像头仅支持单次自动对焦时于首帧上屏后自动触发一次对焦，并响应显式的 [requestFocus] 请求。
 */
internal class CameraFocusController(
  private val operations: CameraFocusOperations,
  private val onError: (Throwable) -> Unit,
) : AutoCloseable {
  private var _isSessionActive = false
  private var _closed = false

  @MainThread
  fun onSessionStarted() {
    if (_closed) return
    _isSessionActive = true
    if (operations.isAutoFocusOnly) requestFocus()
  }

  @MainThread
  fun onSessionStopped() {
    _isSessionActive = false
  }

  @MainThread
  fun requestFocus() {
    if (_closed || !_isSessionActive || !operations.isAutoFocusOnly) return
    operations.triggerCenterFocus { error ->
      if (!_closed && error != null) onError(error)
    }
  }

  @MainThread
  override fun close() {
    _closed = true
    _isSessionActive = false
  }
}

internal class CameraXFocusOperations(
  private val camera: Camera,
  private val mainExecutor: Executor,
) : CameraFocusOperations {
  override val isAutoFocusOnly: Boolean = camera.cameraInfo.isAutoFocusOnly()

  override fun triggerCenterFocus(onResult: (Throwable?) -> Unit) {
    val action = createCenterFocusAction()
    // 部分仅支持 AF_MODE_AUTO 的外接或模拟器摄像头没有 AF 测光分区，直接调用会抛 IllegalArgumentException
    val isSupported = try {
      camera.cameraInfo.isFocusMeteringSupported(action)
    } catch (_: Exception) {
      false
    }
    if (!isSupported) return

    val future = try {
      camera.cameraControl.startFocusAndMetering(action)
    } catch (error: Exception) {
      onResult(error.takeUnless { it is CameraControl.OperationCanceledException })
      return
    }
    future.addListener(
      {
        try {
          future.get()
          onResult(null)
        } catch (error: ExecutionException) {
          // 新的对焦请求或会话关闭会取消进行中的请求，不视为错误
          val cause = error.cause ?: error
          onResult(cause.takeUnless { it is CameraControl.OperationCanceledException })
        } catch (_: CancellationException) {
          onResult(null)
        } catch (error: InterruptedException) {
          Thread.currentThread().interrupt()
          onResult(error)
        } catch (error: Exception) {
          onResult(error)
        }
      },
      mainExecutor,
    )
  }
}

/** 只支持单次自动对焦且不支持连续对焦时需要由库主动触发对焦 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
internal fun CameraInfo.isAutoFocusOnly(): Boolean {
  val modes = try {
    Camera2CameraInfo.from(this).getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
  } catch (_: Exception) {
    null
  } ?: return false
  return CameraCharacteristics.CONTROL_AF_MODE_AUTO in modes &&
    CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE !in modes &&
    CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO !in modes
}

private fun createCenterFocusAction(): FocusMeteringAction {
  val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
  return FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
    .disableAutoCancel()
    .build()
}
