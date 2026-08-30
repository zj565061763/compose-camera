@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.hardware.Camera
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraDevicesStateTest {
  @Test
  fun refresh_enumerationFailurePublishesError() {
    val failure = IllegalStateException("enumeration")
    val state = CameraDevicesState()
    val loader = CameraDevicesLoader(
      state = state,
      cameraApi = FakeCameraDevicesApi(getNumberOfCameras = { throw failure }),
    )

    runOnMainSync(loader::refresh)

    assertThat(state.devices.value).isEmpty()
    assertThat(state.isLoading.value).isFalse()
    assertThat(state.hasLoadedDevices.value).isFalse()
    assertThat(state.error.value).isSameInstanceAs(failure)
  }

  @Test
  fun refresh_sameEnumerationFailureNotifiesListenerForEveryAttempt() {
    val failure = IllegalStateException("enumeration")
    val state = CameraDevicesState()
    val receivedErrors = mutableListOf<Throwable>()
    val loader = CameraDevicesLoader(
      state = state,
      cameraApi = FakeCameraDevicesApi(getNumberOfCameras = { throw failure }),
    )
    runOnMainSync {
      state.addRefreshListener { event ->
        if (event is CameraDevicesRefreshEvent.Failure) receivedErrors += event.error
      }
      loader.refresh()
      loader.refresh()
    }

    assertThat(receivedErrors).containsExactly(failure, failure).inOrder()
    assertThat(state.error.value).isSameInstanceAs(failure)
  }

  @Test
  fun refresh_closeDuringEnumerationFailureDoesNotPublishError() {
    val failure = IllegalStateException("enumeration")
    val state = CameraDevicesState()
    lateinit var loader: CameraDevicesLoader
    loader = CameraDevicesLoader(
      state = state,
      cameraApi = FakeCameraDevicesApi(
        getNumberOfCameras = {
          loader.close()
          throw failure
        },
      ),
    )

    runOnMainSync(loader::refresh)

    assertThat(state.devices.value).isEmpty()
    assertThat(state.hasLoadedDevices.value).isFalse()
    assertThat(state.error.value).isNull()
  }

  @Test
  fun refresh_lensFailurePreservesCameraIdAndContinuesEnumeration() {
    val state = CameraDevicesState()
    val loader = CameraDevicesLoader(
      state = state,
      cameraApi = FakeCameraDevicesApi(
        getNumberOfCameras = { 2 },
        getCameraInfo = { cameraId ->
          if (cameraId == 0) throw IllegalStateException("lens")
          cameraInfo(Camera.CameraInfo.CAMERA_FACING_FRONT)
        },
      ),
    )

    runOnMainSync(loader::refresh)

    assertThat(state.devices.value).containsExactly(
      CameraDeviceInfo(cameraId = "0", lens = null),
      CameraDeviceInfo(cameraId = "1", lens = CameraLens.FRONT),
    ).inOrder()
    assertThat(state.isLoading.value).isFalse()
    assertThat(state.hasLoadedDevices.value).isTrue()
    assertThat(state.error.value).isNull()
  }
}

private fun runOnMainSync(action: () -> Unit) {
  InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
}

private class FakeCameraDevicesApi(
  private val getNumberOfCameras: () -> Int,
  private val getCameraInfo: (Int) -> Camera.CameraInfo = { error("Unexpected camera info request.") },
) : CameraDevicesApi {
  override fun getNumberOfCameras(): Int = getNumberOfCameras.invoke()

  override fun getCameraInfo(cameraId: Int): Camera.CameraInfo = getCameraInfo.invoke(cameraId)
}

private fun cameraInfo(facing: Int): Camera.CameraInfo {
  return Camera.CameraInfo().also { it.facing = facing }
}
