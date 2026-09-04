@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CameraFrameDispatcherTest {
  @Test
  fun frameDispatcher_keepsOnlyLatestPendingFrame() {
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val callbacks = CountDownLatch(2)
    val buffersReturned = CountDownLatch(3)
    val seen = mutableListOf<Int>()
    val returned = mutableListOf<Int>()
    val error = AtomicReference<Throwable?>()
    val dispatcher = CameraFrameDispatcher(
      onFrame = { frame ->
        synchronized(seen) { seen += frame.data[0].toInt() }
        if (frame.data[0].toInt() == 1) {
          firstStarted.countDown()
          check(releaseFirst.await(5, TimeUnit.SECONDS))
        }
        callbacks.countDown()
      },
      onError = error::set,
    )

    dispatcher.offerFrame(1, returned, buffersReturned)
    assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue()
    dispatcher.offerFrame(2, returned, buffersReturned)
    dispatcher.offerFrame(3, returned, buffersReturned)
    releaseFirst.countDown()

    assertThat(callbacks.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(buffersReturned.await(5, TimeUnit.SECONDS)).isTrue()
    dispatcher.close()
    assertThat(error.get()).isNull()
    assertThat(seen).containsExactly(1, 3).inOrder()
    assertThat(returned).containsExactly(1, 2, 3)
  }

  @Test
  fun frameDispatcher_closeDiscardsPendingFrameAndFinishesStartedCallback() {
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val firstFinished = CountDownLatch(1)
    val buffersReturned = CountDownLatch(2)
    val callbackCount = AtomicInteger()
    val returned = mutableListOf<Int>()
    val error = AtomicReference<Throwable?>()
    val dispatcher = CameraFrameDispatcher(
      onFrame = {
        callbackCount.incrementAndGet()
        firstStarted.countDown()
        check(releaseFirst.await(5, TimeUnit.SECONDS))
        firstFinished.countDown()
      },
      onError = error::set,
    )

    dispatcher.offerFrame(1, returned, buffersReturned)
    assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue()
    dispatcher.offerFrame(2, returned, buffersReturned)

    dispatcher.close()
    releaseFirst.countDown()

    assertThat(firstFinished.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(buffersReturned.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(callbackCount.get()).isEqualTo(1)
    assertThat(returned).containsExactly(1, 2)
    assertThat(error.get()).isNull()
  }

  @Test
  fun frameDispatcher_closeAfterDequeueDiscardsFrameBeforeCallback() {
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val secondDequeued = CountDownLatch(1)
    val releaseSecondStart = CountDownLatch(1)
    val buffersReturned = CountDownLatch(2)
    val frameStartCount = AtomicInteger()
    val callbackCount = AtomicInteger()
    val returned = mutableListOf<Int>()
    val error = AtomicReference<Throwable?>()
    val dispatcher = CameraFrameDispatcher(
      onFrame = {
        callbackCount.incrementAndGet()
        firstStarted.countDown()
        check(releaseFirst.await(5, TimeUnit.SECONDS))
      },
      onError = error::set,
      beforeFrameStart = {
        if (frameStartCount.incrementAndGet() == 2) {
          secondDequeued.countDown()
          check(releaseSecondStart.await(5, TimeUnit.SECONDS))
        }
      },
    )

    try {
      dispatcher.offerFrame(1, returned, buffersReturned)
      assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue()
      dispatcher.offerFrame(2, returned, buffersReturned)
      releaseFirst.countDown()
      assertThat(secondDequeued.await(5, TimeUnit.SECONDS)).isTrue()

      dispatcher.close()
      releaseSecondStart.countDown()

      assertThat(buffersReturned.await(5, TimeUnit.SECONDS)).isTrue()
      assertThat(callbackCount.get()).isEqualTo(1)
      assertThat(returned).containsExactly(1, 2)
      assertThat(error.get()).isNull()
    } finally {
      releaseFirst.countDown()
      releaseSecondStart.countDown()
      dispatcher.close()
    }
  }

  @Test
  fun frameDispatcher_discardPendingAfterDequeueDiscardsOldFrameAndContinues() {
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val secondDequeued = CountDownLatch(1)
    val releaseSecondStart = CountDownLatch(1)
    val callbacks = CountDownLatch(2)
    val buffersReturned = CountDownLatch(3)
    val frameStartCount = AtomicInteger()
    val seen = mutableListOf<Int>()
    val returned = mutableListOf<Int>()
    val error = AtomicReference<Throwable?>()
    val dispatcher = CameraFrameDispatcher(
      onFrame = { frame ->
        val value = frame.data[0].toInt()
        synchronized(seen) { seen += value }
        if (value == 1) {
          firstStarted.countDown()
          check(releaseFirst.await(5, TimeUnit.SECONDS))
        }
        callbacks.countDown()
      },
      onError = error::set,
      beforeFrameStart = {
        if (frameStartCount.incrementAndGet() == 2) {
          secondDequeued.countDown()
          check(releaseSecondStart.await(5, TimeUnit.SECONDS))
        }
      },
    )

    try {
      dispatcher.offerFrame(1, returned, buffersReturned)
      assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue()
      dispatcher.offerFrame(2, returned, buffersReturned)
      releaseFirst.countDown()
      assertThat(secondDequeued.await(5, TimeUnit.SECONDS)).isTrue()

      dispatcher.discardPending()
      dispatcher.offerFrame(3, returned, buffersReturned)
      releaseSecondStart.countDown()

      assertThat(callbacks.await(5, TimeUnit.SECONDS)).isTrue()
      assertThat(buffersReturned.await(5, TimeUnit.SECONDS)).isTrue()
      assertThat(seen).containsExactly(1, 3).inOrder()
      assertThat(returned).containsExactly(1, 2, 3)
      assertThat(error.get()).isNull()
    } finally {
      releaseFirst.countDown()
      releaseSecondStart.countDown()
      dispatcher.close()
    }
  }

  @Test
  fun analysisCoordinator_reusesSingleExecutorAcrossRawDispatchers() {
    val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "SharedAnalysis") }
    val coordinator = CameraAnalysisCoordinator { executor }
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val secondStarted = CountDownLatch(1)
    val callbacks = CountDownLatch(2)
    val buffersReturned = CountDownLatch(3)
    val seen = mutableListOf<Int>()
    val callbackThreads = mutableListOf<String>()
    val returned = mutableListOf<Int>()
    val error = AtomicReference<Throwable?>()
    val callback: (CameraFrame.Preview) -> Unit = { frame ->
      val value = frame.data[0].toInt()
      synchronized(seen) {
        seen += value
        callbackThreads += Thread.currentThread().name
      }
      if (value == 1) {
        firstStarted.countDown()
        check(releaseFirst.await(5, TimeUnit.SECONDS))
      } else {
        secondStarted.countDown()
      }
      callbacks.countDown()
    }
    val firstDispatcher = CameraFrameDispatcher(
      callback,
      error::set,
      analysisCoordinator = coordinator,
    )
    val secondDispatcher = CameraFrameDispatcher(
      callback,
      error::set,
      analysisCoordinator = coordinator,
    )

    try {
      firstDispatcher.offerFrame(1, returned, buffersReturned)
      assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue()
      firstDispatcher.close()
      secondDispatcher.offerFrame(2, returned, buffersReturned)
      secondDispatcher.offerFrame(3, returned, buffersReturned)
      firstDispatcher.discardPending()

      assertThat(secondStarted.await(200, TimeUnit.MILLISECONDS)).isFalse()
      releaseFirst.countDown()

      assertThat(callbacks.await(5, TimeUnit.SECONDS)).isTrue()
      assertThat(buffersReturned.await(5, TimeUnit.SECONDS)).isTrue()
      assertThat(seen).containsExactly(1, 3).inOrder()
      assertThat(callbackThreads).containsExactly("SharedAnalysis", "SharedAnalysis").inOrder()
      assertThat(returned).containsExactly(1, 2, 3)
      assertThat(executor.isShutdown).isFalse()
      assertThat(error.get()).isNull()
      coordinator.close()
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
    } finally {
      releaseFirst.countDown()
      firstDispatcher.close()
      secondDispatcher.close()
      coordinator.close()
    }
  }

  @Test
  fun analysisCoordinator_serializesSampledCallbackAfterRawDispatcher() {
    val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "SharedAnalysis") }
    val coordinator = CameraAnalysisCoordinator { executor }
    val now = AtomicLong(1_000)
    val rawStarted = CountDownLatch(1)
    val releaseRaw = CountDownLatch(1)
    val rawBufferReturned = CountDownLatch(1)
    val sampledStarted = CountDownLatch(1)
    val sampledThread = AtomicReference<String?>()
    val capturedIdentities = mutableListOf<CameraFrameTransformIdentity>()
    val returned = mutableListOf<Int>()
    val error = AtomicReference<Throwable?>()
    val rawDispatcher = CameraFrameDispatcher(
      onFrame = {
        rawStarted.countDown()
        check(releaseRaw.await(5, TimeUnit.SECONDS))
        Thread.currentThread().interrupt()
      },
      onError = error::set,
      analysisCoordinator = coordinator,
    )
    val sampledDispatcher = PreviewSampledFrameDispatcher(
      mainHandler = Handler(Looper.getMainLooper()),
      intervalMillis = { 100 },
      captureFrame = { identity, _ ->
        synchronized(capturedIdentities) { capturedIdentities += identity }
        CameraFrame.PreviewSampled(
          data = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
          rotationDegrees = 0,
          transformIdentity = identity,
        )
      },
      onFrame = {
        sampledThread.set(Thread.currentThread().name)
        sampledStarted.countDown()
      },
      onError = error::set,
      elapsedRealtimeMillis = now::get,
      analysisCoordinator = coordinator,
    )

    try {
      rawDispatcher.offerFrame(1, returned, rawBufferReturned)
      assertThat(rawStarted.await(5, TimeUnit.SECONDS)).isTrue()
      rawDispatcher.close()
      sampledDispatcher.start()
      val replacedIdentity = CameraFrameTransformIdentity()
      val latestIdentity = CameraFrameTransformIdentity()
      now.set(1_100)
      sampledDispatcher.offer(replacedIdentity, isPreviewMirrored = false)
      now.set(1_200)
      sampledDispatcher.offer(latestIdentity, isPreviewMirrored = false)

      assertThat(sampledStarted.await(200, TimeUnit.MILLISECONDS)).isFalse()
      assertThat(synchronized(capturedIdentities) { capturedIdentities.toList() }).isEmpty()
      releaseRaw.countDown()

      assertThat(sampledStarted.await(5, TimeUnit.SECONDS)).isTrue()
      assertThat(rawBufferReturned.await(5, TimeUnit.SECONDS)).isTrue()
      assertThat(capturedIdentities).containsExactly(latestIdentity)
      assertThat(sampledThread.get()).isEqualTo("SharedAnalysis")
      assertThat(executor.isShutdown).isFalse()
      assertThat(error.get()).isNull()
      coordinator.close()
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
    } finally {
      releaseRaw.countDown()
      rawDispatcher.close()
      sampledDispatcher.close()
      coordinator.close()
    }
  }

  @Test
  fun frameDispatcher_callbackInterruptDoesNotAffectBufferReturn() {
    val cameraThread = HandlerThread("CameraPreview-BufferReturnTest").also { it.start() }
    val cameraHandler = Handler(cameraThread.looper)
    val cameraBlocked = CountDownLatch(1)
    val releaseCamera = CountDownLatch(1)
    val returnStarted = CountDownLatch(1)
    val bufferReturned = CountDownLatch(1)
    val analysisDrained = CountDownLatch(1)
    val error = AtomicReference<Throwable?>()
    val executor = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, CAMERA_ANALYSIS_THREAD_NAME)
    }
    val coordinator = CameraAnalysisCoordinator { executor }
    var dispatcher: CameraFrameDispatcher? = null

    try {
      check(
        cameraHandler.post {
          cameraBlocked.countDown()
          check(releaseCamera.await(15, TimeUnit.SECONDS))
        },
      )
      assertThat(cameraBlocked.await(5, TimeUnit.SECONDS)).isTrue()
      val currentDispatcher = CameraFrameDispatcher(
        onFrame = { Thread.currentThread().interrupt() },
        onError = error::set,
        analysisCoordinator = coordinator,
      ).also { dispatcher = it }

      currentDispatcher.offer(
        data = ByteArray(6),
        width = 2,
        height = 2,
        rotationDegrees = 0,
        transformIdentity = CameraFrameTransformIdentity(),
        returnBuffer = {
          returnStarted.countDown()
          check(cameraHandler.post { bufferReturned.countDown() })
          check(bufferReturned.await(5, TimeUnit.SECONDS))
        },
      )

      assertThat(returnStarted.await(5, TimeUnit.SECONDS)).isTrue()
      assertThat(bufferReturned.await(200, TimeUnit.MILLISECONDS)).isFalse()
      releaseCamera.countDown()
      assertThat(bufferReturned.await(5, TimeUnit.SECONDS)).isTrue()
      executor.execute { analysisDrained.countDown() }
      assertThat(analysisDrained.await(5, TimeUnit.SECONDS)).isTrue()
      assertThat(error.get()).isNull()
    } finally {
      releaseCamera.countDown()
      dispatcher?.close()
      coordinator.close()
      cameraThread.quitSafely()
      cameraThread.join(5_000)
    }
  }

  @Test
  fun frameDispatcher_executorRejectionReturnsFrameAndReportsError() {
    val executor = Executors.newSingleThreadExecutor().also { it.shutdown() }
    val coordinator = CameraAnalysisCoordinator { executor }
    val callback = CountDownLatch(1)
    val returned = CountDownLatch(1)
    val receivedError = AtomicReference<Throwable?>()
    val dispatcher = CameraFrameDispatcher(
      onFrame = { callback.countDown() },
      onError = receivedError::set,
      analysisCoordinator = coordinator,
    )

    dispatcher.offer(
      data = ByteArray(6),
      width = 2,
      height = 2,
      rotationDegrees = 0,
      transformIdentity = CameraFrameTransformIdentity(),
      returnBuffer = { returned.countDown() },
    )

    assertThat(returned.await(1, TimeUnit.SECONDS)).isTrue()
    assertThat(callback.await(100, TimeUnit.MILLISECONDS)).isFalse()
    assertThat(receivedError.get()).isInstanceOf(RejectedExecutionException::class.java)
    dispatcher.close()
    coordinator.close()
  }

  @Test
  fun frameDispatcher_invalidFrameIsReturnedWithoutCallback() {
    val callback = CountDownLatch(1)
    val returned = CountDownLatch(1)
    val dispatcher = CameraFrameDispatcher(
      onFrame = { callback.countDown() },
      onError = {},
    )

    dispatcher.offer(
      data = ByteArray(1),
      width = 640,
      height = 480,
      rotationDegrees = 0,
      transformIdentity = CameraFrameTransformIdentity(),
      returnBuffer = { returned.countDown() },
    )

    assertThat(returned.await(1, TimeUnit.SECONDS)).isTrue()
    assertThat(callback.await(100, TimeUnit.MILLISECONDS)).isFalse()
    dispatcher.close()
  }

  @Test
  fun nullPreviewCallback_reportsRuntimeErrorAndStopsSession() {
    val receivedError = AtomicReference<Throwable?>()
    val stopCount = AtomicInteger()

    reportNullPreviewCallbackAndStop(
      onError = receivedError::set,
      stopSession = { stopCount.incrementAndGet() },
    )

    val error = receivedError.get()
    assertThat(error).isInstanceOf(CameraPreviewException::class.java)
    val exception = error as CameraPreviewException
    assertThat(exception.reason).isEqualTo(CameraPreviewException.Reason.CAMERA_RUNTIME_ERROR)
    assertThat(exception.cameraErrorCode).isNull()
    assertThat(exception).hasMessageThat().contains("null preview callback buffer")
    assertThat(stopCount.get()).isEqualTo(1)
  }

  @Test
  fun returnStalePreviewCallbackBuffer_reportsReturnFailure() {
    val buffer = ByteArray(6)
    val returnFailure = IllegalStateException("return failed")
    val receivedError = AtomicReference<Throwable?>()

    returnStalePreviewCallbackBuffer(
      data = buffer,
      returnBuffer = { throw returnFailure },
      onError = receivedError::set,
    )

    assertThat(receivedError.get()).isSameInstanceAs(returnFailure)
  }

  @Test
  fun checkNv21PreviewFormat_rejectsDifferentConfiguredFormat() {
    checkNv21PreviewFormat(ImageFormat.NV21)

    val error = assertThrows(IllegalStateException::class.java) {
      checkNv21PreviewFormat(ImageFormat.YV12)
    }

    assertThat(error).hasMessageThat().contains(ImageFormat.YV12.toString())
    assertThat(error).hasMessageThat().contains("NV21")
  }

  @Test
  fun nv21BufferSize_rejectsOddOrOverflowingDimensions() {
    assertThat(nv21BufferSize(640, 480)).isEqualTo(460_800)
    assertThat(nv21BufferSize(1, 2)).isNull()
    assertThat(nv21BufferSize(2, 1)).isNull()
    assertThat(nv21BufferSize(Int.MAX_VALUE - 1, Int.MAX_VALUE - 1)).isNull()
  }

  @Test
  fun frameDispatcher_callbackAndBufferFailuresAreAggregated() {
    val callbackFailure = IllegalStateException("callback")
    val bufferFailure = IllegalArgumentException("buffer")
    val receivedFailure = AtomicReference<Throwable?>()
    val failureReceived = CountDownLatch(1)
    val dispatcher = CameraFrameDispatcher(
      onFrame = { throw callbackFailure },
      onError = { error ->
        receivedFailure.set(error)
        failureReceived.countDown()
      },
    )

    dispatcher.offer(
      data = ByteArray(6),
      width = 2,
      height = 2,
      rotationDegrees = 0,
      transformIdentity = CameraFrameTransformIdentity(),
      returnBuffer = { throw bufferFailure },
    )

    assertThat(failureReceived.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(receivedFailure.get()).isSameInstanceAs(callbackFailure)
    assertThat(callbackFailure.suppressed.asList()).containsExactly(bufferFailure)
    dispatcher.close()
  }
}

private fun CameraFrameDispatcher.offerFrame(
  value: Int,
  returned: MutableList<Int>,
  buffersReturned: CountDownLatch,
) {
  offer(
    data = ByteArray(6).also { it[0] = value.toByte() },
    width = 2,
    height = 2,
    rotationDegrees = 0,
    transformIdentity = CameraFrameTransformIdentity(),
    returnBuffer = { buffer ->
      synchronized(returned) { returned += buffer[0].toInt() }
      buffersReturned.countDown()
    },
  )
}
