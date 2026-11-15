package com.sd.lib.compose.camera

/** 控制 [CameraPreview] 是否产生分析帧以及帧的来源 */
sealed interface FrameProcessor {
  /** 不产生分析帧 */
  data object None : FrameProcessor

  /** 直接接收原始 NV21 预览帧 */
  class Preview(
    val onFrame: (CameraFrame.Preview) -> Unit,
  ) : FrameProcessor

  /** 按指定间隔从预览区域采样，间隔必须大于零 */
  class PreviewSampled(
    val intervalMillis: Long,
    val onFrame: (CameraFrame.PreviewSampled) -> Unit,
  ) : FrameProcessor {
    init {
      require(intervalMillis > 0) { "intervalMillis must be greater than zero." }
    }
  }
}

internal enum class FrameProcessorMode {
  None,
  Preview,
  PreviewSampled,
}

internal fun FrameProcessor.mode(): FrameProcessorMode {
  return when (this) {
    FrameProcessor.None -> FrameProcessorMode.None
    is FrameProcessor.Preview -> FrameProcessorMode.Preview
    is FrameProcessor.PreviewSampled -> FrameProcessorMode.PreviewSampled
  }
}
