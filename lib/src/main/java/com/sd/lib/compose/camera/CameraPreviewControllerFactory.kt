package com.sd.lib.compose.camera

import android.view.TextureView
import androidx.annotation.MainThread
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.LifecycleOwner

internal interface CameraPreviewControllerHandle : AutoCloseable {
  @MainThread
  fun start()

  @MainThread
  fun requestFocus()

  @MainThread
  override fun close()
}

internal fun interface CameraPreviewControllerFactory {
  fun create(config: CameraPreviewControllerConfig): CameraPreviewControllerHandle
}

internal class CameraPreviewControllerConfig(
  val lifecycleOwner: LifecycleOwner,
  val textureView: TextureView,
  val cameraId: String?,
  val displayRotation: Int,
  val previewViewSizeProvider: () -> IntSize,
  val transformIdentityProvider: () -> CameraFrameTransformIdentity?,
  val onSessionStarted: (
    CameraFrameTransformIdentity,
    IntSize,
    Int,
    Boolean,
  ) -> Boolean,
  val onPreviewFrameAvailable: (CameraFrameTransformIdentity) -> Unit,
  val frameProcessor: ActiveFrameProcessor,
  val captureSampledFrame: (
    CameraFrameTransformIdentity,
    Boolean,
  ) -> CameraFrame.PreviewSampled?,
  val onSessionFailure: (Throwable) -> Unit,
  val onError: (Throwable) -> Unit,
  val onSessionClosed: (CameraFrameTransformIdentity?) -> Unit,
)

private val DefaultCameraPreviewControllerFactory = CameraPreviewControllerFactory { config ->
  CameraPreviewController(
    lifecycleOwner = config.lifecycleOwner,
    textureView = config.textureView,
    cameraId = config.cameraId,
    displayRotation = config.displayRotation,
    previewViewSizeProvider = config.previewViewSizeProvider,
    transformIdentityProvider = config.transformIdentityProvider,
    onSessionStarted = config.onSessionStarted,
    onPreviewFrameAvailable = config.onPreviewFrameAvailable,
    frameProcessor = config.frameProcessor,
    captureSampledFrame = config.captureSampledFrame,
    onSessionFailure = config.onSessionFailure,
    onError = config.onError,
    onSessionClosed = config.onSessionClosed,
  )
}

internal val LocalCameraPreviewControllerFactory = staticCompositionLocalOf {
  DefaultCameraPreviewControllerFactory
}
