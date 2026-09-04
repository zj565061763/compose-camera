@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.os.HandlerThread
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
  fun cameraPreviewRuntimeStore_acquireFailureClosesPartialRuntimeAndCanRetry() {
    val acquireFailure = IllegalStateException("handler creation")
    val failedThread = AtomicReference<HandlerThread?>()
    var runtimeCount = 0
    val store = CameraPreviewRuntimeStore {
      runtimeCount++
      if (runtimeCount == 1) {
        CameraPreviewRuntime { thread ->
          failedThread.set(thread)
          throw acquireFailure
        }
      } else {
        CameraPreviewRuntime()
      }
    }
    var lease: CameraPreviewRuntimeLease? = null

    try {
      val thrown = assertThrows(IllegalStateException::class.java) { store.acquire() }

      assertThat(thrown).isSameInstanceAs(acquireFailure)
      checkNotNull(failedThread.get()).join(5_000)
      assertThat(checkNotNull(failedThread.get()).isAlive).isFalse()

      lease = store.acquire()
      assertThat(checkNotNull(lease).cameraHandler.looper.thread).isNotSameInstanceAs(failedThread.get())
    } finally {
      val successfulThread = lease?.cameraHandler?.looper?.thread
      lease?.close()
      store.close()
      failedThread.get()?.quitSafely()
      failedThread.get()?.join(5_000)
      successfulThread?.join(5_000)
    }

    assertThat(checkNotNull(lease).cameraHandler.looper.thread.isAlive).isFalse()
  }

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
  fun cameraPreviewRuntimeStore_replacesInvalidatedRuntime() {
    val store = CameraPreviewRuntimeStore()
    val invalidatedLease = store.acquire()
    var replacementLease: CameraPreviewRuntimeLease? = null

    try {
      invalidatedLease.invalidateRuntime()
      replacementLease = store.acquire()

      assertThat(checkNotNull(replacementLease).cameraHandler.looper)
        .isNotSameInstanceAs(invalidatedLease.cameraHandler.looper)
    } finally {
      invalidatedLease.close()
      replacementLease?.close()
      store.close()
    }
    invalidatedLease.cameraHandler.looper.thread.join(5_000)
    checkNotNull(replacementLease).cameraHandler.looper.thread.join(5_000)
    assertThat(invalidatedLease.cameraHandler.looper.thread.isAlive).isFalse()
    assertThat(checkNotNull(replacementLease).cameraHandler.looper.thread.isAlive).isFalse()
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
