package com.sd.lib.compose.camera

import androidx.test.annotation.UiThreadTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraDevicesStateTest {
  private val _source = FakeCameraDevicesSource()

  @Test
  @UiThreadTest
  fun refresh_publishesDevicesAndClearsError() {
    val state = CameraDevicesState()
    val loader = CameraDevicesLoader(state, _source)
    val devices = listOf(CameraDeviceInfo("1", CameraLens.FRONT), CameraDeviceInfo("0", CameraLens.BACK))

    loader.refresh()
    assertThat(state.isLoading.value).isTrue()
    _source.complete(Result.failure(IllegalStateException()))
    loader.refresh()
    _source.complete(Result.success(devices))

    assertThat(state.devices.value).isEqualTo(devices)
    assertThat(state.error.value).isNull()
    assertThat(state.isLoading.value).isFalse()
    assertThat(state.hasLoadedDevices.value).isTrue()
  }

  @Test
  @UiThreadTest
  fun refresh_sameFailureIsObservableForEveryAttempt() {
    val state = CameraDevicesState()
    val loader = CameraDevicesLoader(state, _source)
    val error = IllegalStateException()

    repeat(2) { attempt ->
      loader.refresh()
      _source.complete(Result.failure(error))
      assertThat(state.error.value).isSameInstanceAs(error)
      assertThat(state.refreshVersion.value).isEqualTo(attempt + 1)
    }
    assertThat(state.hasLoadedDevices.value).isFalse()
  }

  @Test
  @UiThreadTest
  fun refresh_ignoresStaleAndClosedResults() {
    val state = CameraDevicesState()
    val loader = CameraDevicesLoader(state, _source)

    loader.refresh()
    val staleResult = _source.takeCallback()
    loader.refresh()
    staleResult(Result.success(listOf(CameraDeviceInfo("stale", null))))
    assertThat(state.devices.value).isEmpty()

    loader.close()
    _source.complete(Result.success(listOf(CameraDeviceInfo("closed", null))))
    assertThat(state.devices.value).isEmpty()
    assertThat(state.refreshVersion.value).isEqualTo(0)
  }
}

private class FakeCameraDevicesSource : CameraDevicesSource {
  private val _callbacks = ArrayDeque<(Result<List<CameraDeviceInfo>>) -> Unit>()

  override fun load(onResult: (Result<List<CameraDeviceInfo>>) -> Unit) {
    _callbacks.addLast(onResult)
  }

  fun takeCallback(): (Result<List<CameraDeviceInfo>>) -> Unit = _callbacks.removeFirst()

  fun complete(result: Result<List<CameraDeviceInfo>>) {
    takeCallback().invoke(result)
  }
}
