package com.sd.lib.compose.camera

import android.graphics.Matrix
import android.graphics.Rect
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import java.util.concurrent.atomic.AtomicReference

@Composable
fun rememberCameraPreviewState(): CameraPreviewState {
  return remember { CameraPreviewState() }
}

/**
 * 每个正在组合的 [CameraPreview] 必须使用独立实例，不能在多个预览间共享。
 */
@Stable
class CameraPreviewState internal constructor() {
  // onFrame 在后台线程读取，主线程以完整快照发布 CameraX 和布局变换。
  private val _transformConfig = AtomicReference(PreviewTransformConfig())
  private val _previewResolution = mutableStateOf(IntSize.Zero)
  private val _retryGeneration = mutableIntStateOf(0)

  /**
   * CameraX 为预览 Surface 协商出的实际缓冲区分辨率。
   * 宽高使用相机缓冲区坐标，不代表旋转后的界面方向，也可能与 Compose 预览区域尺寸不同。
   * 预览尚未绑定或已经释放时为 [IntSize.Zero]。
   */
  val previewResolution: State<IntSize> = _previewResolution

  /**
   * 在导致预览失败的外部条件已经解决后重新创建相机会话。
   *
   * 例如关闭勿扰模式、恢复设备策略或处理完 CameraX 报告的其他不可恢复错误后调用。
   * 正常运行时调用也会安全地解绑并重新绑定当前 [CameraPreview] 会话。
   * 与当前预览关联的 [CameraDevicesState] 也会主动刷新一次。
   */
  @MainThread
  fun retry() {
    _retryGeneration.intValue++
  }

  internal val retryGeneration: Int get() = _retryGeneration.intValue

  /**
   * 判断异步帧结果是否仍属于当前预览变换。
   *
   * [token] 可以在 `CameraPreview.onFrame` 回调期间取得，并在回调结束后的任意线程校验。
   * 预览尚未绑定、已经释放、CameraX 已发布新 `SurfaceRequest`，或布局尺寸、
   * `contentScale`、额外镜像已经改变时返回 `false`。
   */
  @AnyThread
  fun isFrameTransformCurrent(token: CameraFrameTransformToken): Boolean {
    val transformIdentity = _transformConfig.get().transformIdentity
    return token.matches(transformIdentity)
  }

  @AnyThread
  internal fun currentTransformIdentity(): CameraFrameTransformIdentity? {
    return _transformConfig.get().transformIdentity
  }

  /**
   * 把 [CameraFrame.image] 原始缓冲区坐标映射到 Compose 预览区域的矩阵。
   * 调用方不需要预先处理 `cropRect`、`rotationDegrees` 或镜像；
   * 矩阵会组合 CameraX 的 sensor-to-buffer 变换、Viewfinder 变换以及 CameraPreview 的额外镜像。
   * 如果第三方检测器返回的是已经按 `rotationDegrees` 旋转后的坐标，
   * 需要先转换回 [CameraFrame.image] 的原始缓冲区坐标。
   *
   * 可直接在 CameraPreview 的 `onFrame` 分析线程调用；每次调用使用同一份不可变配置快照。
   * 预览尚未完成布局，或者 CameraX 尚未提供完整变换信息时返回 `null`。
   */
  @AnyThread
  fun createTransformToPreview(frame: CameraFrame): Matrix? {
    val config = _transformConfig.get()
    if (!frame.transformToken.matches(config.transformIdentity)) return null
    val previewTransform = config.previewTransform ?: return null
    val previewSensorToBuffer = previewTransform.sensorToBuffer.toMatrix()
    val previewBufferToViewfinder = previewTransform.correctMirrorAxis(
      config.previewBufferToViewfinder?.toMatrix() ?: return null,
    )
    val previewSize = config.previewLayout.previewSize
    if (previewSize.width <= 0 || previewSize.height <= 0) return null

    val analysisToSensor = Matrix()
    val sensorToAnalysis = Matrix(frame.image.imageInfo.sensorToBufferTransformMatrix)
    if (!sensorToAnalysis.invert(analysisToSensor)) return null

    val analysisToPreviewBuffer = Matrix().apply {
      setConcat(previewSensorToBuffer, analysisToSensor)
    }
    val analysisToViewfinder = Matrix().apply {
      setConcat(previewBufferToViewfinder, analysisToPreviewBuffer)
    }

    if (!config.needsAdditionalMirror) return analysisToViewfinder

    val additionalMirror = horizontalMirrorMatrix(previewSize.width.toFloat())
    return Matrix().apply { setConcat(additionalMirror, analysisToViewfinder) }
  }

  @MainThread
  internal fun updatePreviewLayout(
    previewSize: IntSize,
    contentScale: ContentScale,
    needsAdditionalMirror: Boolean,
  ) {
    val current = _transformConfig.get()
    val previewLayout = PreviewLayout(previewSize, contentScale)
    val isLayoutChanged = current.previewLayout != previewLayout
    val isDisplayTransformChanged = isLayoutChanged ||
      current.needsAdditionalMirror != needsAdditionalMirror
    _transformConfig.set(
      current.copy(
        previewLayout = previewLayout,
        needsAdditionalMirror = needsAdditionalMirror,
        // 同一请求内的显示坐标变化也必须让旧帧 token 失效
        transformIdentity = if (isDisplayTransformChanged && current.requestIdentity != null) {
          CameraFrameTransformIdentity()
        } else {
          current.transformIdentity
        },
        // 新布局必须等待 Viewfinder 发布与它匹配的矩阵
        previewBufferToViewfinder = if (isLayoutChanged) {
          null
        } else {
          current.previewBufferToViewfinder
        },
      ),
    )
  }

  @MainThread
  internal fun updateSurfaceRequest(requestIdentity: CameraFrameTransformIdentity) {
    _transformConfig.set(
      _transformConfig.get().copy(
        requestIdentity = requestIdentity,
        transformIdentity = requestIdentity,
        previewTransform = null,
        previewBufferToViewfinder = null,
      ),
    )
    _previewResolution.value = IntSize.Zero
  }

  @MainThread
  internal fun updatePreviewTransformation(
    requestIdentity: CameraFrameTransformIdentity,
    sensorToBuffer: Matrix,
    cropRect: Rect,
    rotationDegrees: Int,
    isMirrored: Boolean,
  ): Boolean {
    val current = _transformConfig.get()
    if (current.requestIdentity !== requestIdentity) return false
    _transformConfig.set(
      current.copy(
        previewTransform = PreviewTransformSnapshot(
          sensorToBuffer = MatrixSnapshot(sensorToBuffer),
          cropRect = RectSnapshot(cropRect),
          rotationDegrees = rotationDegrees,
          isMirrored = isMirrored,
        ),
      ),
    )
    return true
  }

  @MainThread
  internal fun updateViewfinderToBuffer(
    requestIdentity: CameraFrameTransformIdentity,
    previewSize: IntSize,
    contentScale: ContentScale,
    viewfinderToBuffer: Matrix,
  ) {
    val current = _transformConfig.get()
    if (
      current.requestIdentity !== requestIdentity ||
      current.previewLayout != PreviewLayout(previewSize, contentScale)
    ) {
      return
    }

    val bufferToViewfinder = Matrix()
    val snapshot = if (viewfinderToBuffer.invert(bufferToViewfinder)) {
      MatrixSnapshot(bufferToViewfinder)
    } else {
      null
    }
    _transformConfig.set(current.copy(previewBufferToViewfinder = snapshot))
  }

  @MainThread
  internal fun clearSurfaceRequest(requestIdentity: CameraFrameTransformIdentity? = null) {
    val current = _transformConfig.get()
    if (requestIdentity != null && current.requestIdentity !== requestIdentity) return
    _transformConfig.set(
      current.copy(
        requestIdentity = null,
        transformIdentity = null,
        previewTransform = null,
        previewBufferToViewfinder = null,
      ),
    )
    _previewResolution.value = IntSize.Zero
  }

  @MainThread
  internal fun updatePreviewResolution(
    requestIdentity: CameraFrameTransformIdentity,
    size: IntSize,
  ) {
    if (_transformConfig.get().requestIdentity !== requestIdentity) return
    _previewResolution.value = size
  }

  @MainThread
  internal fun reset() {
    _transformConfig.set(PreviewTransformConfig())
    _previewResolution.value = IntSize.Zero
    _retryGeneration.intValue = 0
  }
}

private data class PreviewTransformConfig(
  val requestIdentity: CameraFrameTransformIdentity? = null,
  val transformIdentity: CameraFrameTransformIdentity? = null,
  val previewLayout: PreviewLayout = PreviewLayout(),
  val needsAdditionalMirror: Boolean = false,
  val previewTransform: PreviewTransformSnapshot? = null,
  val previewBufferToViewfinder: MatrixSnapshot? = null,
)

private data class PreviewLayout(
  val previewSize: IntSize = IntSize.Zero,
  val contentScale: ContentScale? = null,
)

/** 防止可变 Matrix 跨线程共享 */
private class MatrixSnapshot(matrix: Matrix) {
  private val _values = FloatArray(9).also(matrix::getValues)

  fun toMatrix(): Matrix = Matrix().apply { setValues(_values) }
}

private data class PreviewTransformSnapshot(
  val sensorToBuffer: MatrixSnapshot,
  val cropRect: RectSnapshot,
  val rotationDegrees: Int,
  val isMirrored: Boolean,
) {
  /** CameraXViewfinder 1.5.x 在四分之一转时把输出水平镜像应用到了错误的源坐标轴 */
  fun correctMirrorAxis(bufferToViewfinder: Matrix): Matrix {
    if (!isMirrored || (rotationDegrees != 90 && rotationDegrees != 270)) {
      return bufferToViewfinder
    }

    val sourceXAxis = floatArrayOf(1f, 0f).also(bufferToViewfinder::mapVectors)
    val expectedYDirection = if (rotationDegrees == 90) 1f else -1f
    // 新版 CameraX 已经修正时保持原矩阵，避免依赖升级后重复翻转。
    if (sourceXAxis[1] * expectedYDirection >= 0f) return bufferToViewfinder

    val sourceCorrection = Matrix().apply {
      setRotate(180f, cropRect.centerX, cropRect.centerY)
    }
    return Matrix().apply { setConcat(bufferToViewfinder, sourceCorrection) }
  }
}

private data class RectSnapshot(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
) {
  constructor(rect: Rect) : this(rect.left, rect.top, rect.right, rect.bottom)

  val centerX: Float get() = (left + right) / 2f
  val centerY: Float get() = (top + bottom) / 2f
}

private fun horizontalMirrorMatrix(width: Float): Matrix = Matrix().apply {
  setValues(
    floatArrayOf(
      -1f, 0f, width,
      0f, 1f, 0f,
      0f, 0f, 1f,
    ),
  )
}
