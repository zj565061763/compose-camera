@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CameraPreviewRuntimeTest {
  @Test
  fun cameraPreviewRuntimeStore_reusesRuntimeUntilCurrentRuntimeCloses() {
    val store = CameraPreviewRuntimeStore()
    val first = store.acquire()
    val second = store.acquire()

    assertThat(second.cameraHandler.looper).isSameInstanceAs(first.cameraHandler.looper)

    first.close()
    second.close()
    store.closeCurrentRuntime()
    val replacement = store.acquire()

    assertThat(replacement.cameraHandler.looper).isNotSameInstanceAs(first.cameraHandler.looper)
    replacement.close()
    store.close()
    assertThrows(IllegalStateException::class.java) { store.acquire() }
  }

  @Test
  fun cameraPreviewRuntimeStore_reopensRuntimeWhilePreviousControllerIsClosing() {
    val store = CameraPreviewRuntimeStore()
    val oldLease = store.acquire()

    store.closeCurrentRuntime()
    val newLease = store.acquire()

    assertThat(newLease.cameraHandler.looper).isSameInstanceAs(oldLease.cameraHandler.looper)
    oldLease.close()
    assertThat(newLease.cameraHandler.post {}).isTrue()
    newLease.close()
    store.close()
  }

  @Test
  fun cameraPreviewRuntime_reusesCameraThreadUntilLastLeaseCloses() {
    val runtime = CameraPreviewRuntime()
    val firstLease = runtime.acquire()
    val secondLease = runtime.acquire()
    val callbacks = CountDownLatch(2)
    val cameraThread = AtomicReference<Thread?>()

    try {
      assertThat(firstLease.cameraHandler.looper).isSameInstanceAs(secondLease.cameraHandler.looper)
      check(firstLease.cameraHandler.post {
        cameraThread.compareAndSet(null, Thread.currentThread())
        callbacks.countDown()
      })

      firstLease.close()
      runtime.close()

      check(secondLease.cameraHandler.post {
        cameraThread.compareAndSet(null, Thread.currentThread())
        callbacks.countDown()
      })
      assertThat(callbacks.await(5, TimeUnit.SECONDS)).isTrue()

      secondLease.close()
      checkNotNull(cameraThread.get()).join(5_000)

      assertThat(checkNotNull(cameraThread.get()).isAlive).isFalse()
      assertThrows(IllegalStateException::class.java) { runtime.acquire() }
    } finally {
      firstLease.close()
      secondLease.close()
      runtime.close()
    }
  }
}
