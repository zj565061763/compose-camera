@file:Suppress("DEPRECATION")

package com.sd.lib.compose.camera

import android.graphics.Bitmap
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PreviewSampledFrameDispatcherTest {
  @Test
  fun sampledFrameDispatcher_waitsForIntervalAndRunsOnAnalysisThread() {
    val now = AtomicLong(1_000)
    val callback = CountDownLatch(1)
    val result = AtomicReference<SampledFrameResult?>()
    val error = AtomicReference<Throwable?>()
    val identity = CameraFrameTransformIdentity()
    val dispatcher = PreviewSampledFrameDispatcher(
      mainHandler = Handler(Looper.getMainLooper()),
      intervalMillis = { 100 },
      captureFrame = { currentIdentity, _ ->
        CameraFrame.PreviewSampled(
          data = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
          rotationDegrees = 0,
          transformIdentity = currentIdentity,
        )
      },
      onFrame = { frame ->
        result.set(
          SampledFrameResult(
            token = frame.transformToken,
            threadName = Thread.currentThread().name,
            recycledDuringCallback = frame.data.isRecycled,
          ),
        )
        callback.countDown()
      },
      onError = error::set,
      elapsedRealtimeMillis = now::get,
    )
    dispatcher.start()

    now.set(1_099)
    dispatcher.offer(identity, isPreviewMirrored = false)
    assertThat(callback.await(100, TimeUnit.MILLISECONDS)).isFalse()
    now.set(1_100)
    dispatcher.offer(identity, isPreviewMirrored = false)

    assertThat(callback.await(5, TimeUnit.SECONDS)).isTrue()
    dispatcher.close()
    assertThat(error.get()).isNull()
    assertThat(checkNotNull(result.get()).threadName).isEqualTo(CAMERA_ANALYSIS_THREAD_NAME)
    assertThat(checkNotNull(result.get()).recycledDuringCallback).isFalse()
    assertThat(checkNotNull(result.get()).token.matches(identity)).isTrue()
  }

  @Test
  fun sampledFrameDispatcher_interruptWhileCaptureQueuedCancelsCapture() {
    val captureThread = HandlerThread("CameraPreview-CaptureTest").also { it.start() }
    val captureHandler = Handler(captureThread.looper)
    val handlerBlocked = CountDownLatch(1)
    val releaseHandler = CountDownLatch(1)
    val handlerDrained = CountDownLatch(1)
    val analysisThread = AtomicReference<Thread?>()
    val executor = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, CAMERA_ANALYSIS_THREAD_NAME).also(analysisThread::set)
    }
    val coordinator = CameraAnalysisCoordinator { executor }
    val now = AtomicLong(1_000)
    val captureCount = AtomicInteger()
    val callbackCount = AtomicInteger()
    val receivedError = AtomicReference<Throwable?>()
    val errorReceived = CountDownLatch(1)
    val dispatcher = PreviewSampledFrameDispatcher(
      mainHandler = captureHandler,
      intervalMillis = { 100 },
      captureFrame = { identity, _ ->
        captureCount.incrementAndGet()
        CameraFrame.PreviewSampled(
          data = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
          rotationDegrees = 0,
          transformIdentity = identity,
        )
      },
      onFrame = { callbackCount.incrementAndGet() },
      onError = { error ->
        receivedError.set(error)
        errorReceived.countDown()
      },
      elapsedRealtimeMillis = now::get,
      analysisCoordinator = coordinator,
    )

    try {
      check(
        captureHandler.post {
          handlerBlocked.countDown()
          check(releaseHandler.await(15, TimeUnit.SECONDS))
        },
      )
      assertThat(handlerBlocked.await(5, TimeUnit.SECONDS)).isTrue()
      dispatcher.start()
      now.set(1_100)
      dispatcher.offer(CameraFrameTransformIdentity(), isPreviewMirrored = false)
      val worker = checkNotNull(analysisThread.get())
      assertThat(awaitThreadState(worker, Thread.State.WAITING, 5_000)).isTrue()

      worker.interrupt()

      assertThat(errorReceived.await(5, TimeUnit.SECONDS)).isTrue()
      releaseHandler.countDown()
      check(captureHandler.post { handlerDrained.countDown() })
      assertThat(handlerDrained.await(5, TimeUnit.SECONDS)).isTrue()
      assertThat(captureCount.get()).isEqualTo(0)
      assertThat(callbackCount.get()).isEqualTo(0)
      assertThat(receivedError.get()).isInstanceOf(InterruptedException::class.java)
    } finally {
      releaseHandler.countDown()
      dispatcher.close()
      coordinator.close()
      captureThread.quitSafely()
      captureThread.join(5_000)
    }
  }

  @Test
  fun sampledFrameDispatcher_interruptDuringCaptureRecyclesLateBitmapAndContinues() {
    val captureThread = HandlerThread("CameraPreview-CaptureTest").also { it.start() }
    val captureHandler = Handler(captureThread.looper)
    val analysisThread = AtomicReference<Thread?>()
    val executor = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, CAMERA_ANALYSIS_THREAD_NAME).also(analysisThread::set)
    }
    val coordinator = CameraAnalysisCoordinator { executor }
    val now = AtomicLong(1_000)
    val firstIdentity = CameraFrameTransformIdentity()
    val interruptedIdentity = CameraFrameTransformIdentity()
    val latestIdentity = CameraFrameTransformIdentity()
    val firstCallback = CountDownLatch(1)
    val interruptedCaptureStarted = CountDownLatch(1)
    val releaseInterruptedCapture = CountDownLatch(1)
    val latestCallback = CountDownLatch(1)
    val errorReceived = CountDownLatch(1)
    val callbackCount = AtomicInteger()
    val errorCount = AtomicInteger()
    val interruptedBitmap = AtomicReference<Bitmap?>()
    val receivedError = AtomicReference<Throwable?>()
    val dispatcher = PreviewSampledFrameDispatcher(
      mainHandler = captureHandler,
      intervalMillis = { 100 },
      captureFrame = { identity, _ ->
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        if (identity === interruptedIdentity) {
          interruptedBitmap.set(bitmap)
          interruptedCaptureStarted.countDown()
          check(releaseInterruptedCapture.await(15, TimeUnit.SECONDS))
        }
        CameraFrame.PreviewSampled(
          data = bitmap,
          rotationDegrees = 0,
          transformIdentity = identity,
        )
      },
      onFrame = { frame ->
        callbackCount.incrementAndGet()
        when {
          frame.transformToken.matches(firstIdentity) -> firstCallback.countDown()
          frame.transformToken.matches(latestIdentity) -> latestCallback.countDown()
        }
      },
      onError = { error ->
        receivedError.set(error)
        errorCount.incrementAndGet()
        errorReceived.countDown()
      },
      elapsedRealtimeMillis = now::get,
      analysisCoordinator = coordinator,
    )

    try {
      dispatcher.start()
      now.set(1_100)
      dispatcher.offer(firstIdentity, isPreviewMirrored = false)
      assertThat(firstCallback.await(5, TimeUnit.SECONDS)).isTrue()

      now.set(1_200)
      dispatcher.offer(interruptedIdentity, isPreviewMirrored = false)
      assertThat(interruptedCaptureStarted.await(5, TimeUnit.SECONDS)).isTrue()
      now.set(1_300)
      dispatcher.offer(latestIdentity, isPreviewMirrored = false)

      val worker = checkNotNull(analysisThread.get())
      assertThat(awaitThreadState(worker, Thread.State.WAITING, 5_000)).isTrue()

      worker.interrupt()
      assertThat(awaitThreadInterruptCleared(worker, 5_000)).isTrue()
      releaseInterruptedCapture.countDown()

      assertThat(errorReceived.await(5, TimeUnit.SECONDS)).isTrue()
      assertThat(latestCallback.await(5, TimeUnit.SECONDS)).isTrue()
      assertThat(checkNotNull(interruptedBitmap.get()).isRecycled).isTrue()
      assertThat(receivedError.get()).isInstanceOf(InterruptedException::class.java)
      assertThat(errorCount.get()).isEqualTo(1)
      assertThat(callbackCount.get()).isEqualTo(2)
    } finally {
      releaseInterruptedCapture.countDown()
      dispatcher.close()
      coordinator.close()
      captureThread.quitSafely()
      captureThread.join(5_000)
    }
  }

  @Test
  fun sampledFrameDispatcher_mainQueueDelayCapturesOnlyLatestRequest() {
    val captureThread = HandlerThread("CameraPreview-CaptureTest").also { it.start() }
    val captureHandler = Handler(captureThread.looper)
    val handlerBlocked = CountDownLatch(1)
    val releaseHandler = CountDownLatch(1)
    val analysisThread = AtomicReference<Thread?>()
    val executor = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, CAMERA_ANALYSIS_THREAD_NAME).also(analysisThread::set)
    }
    val coordinator = CameraAnalysisCoordinator { executor }
    val now = AtomicLong(1_000)
    val firstIdentity = CameraFrameTransformIdentity()
    val replacedIdentity = CameraFrameTransformIdentity()
    val latestIdentity = CameraFrameTransformIdentity()
    val capturedIdentities = mutableListOf<CameraFrameTransformIdentity>()
    val callbackCount = AtomicInteger()
    val latestCallback = AtomicBoolean()
    val callbackReceived = CountDownLatch(1)
    val analysisDrained = CountDownLatch(1)
    val error = AtomicReference<Throwable?>()
    val dispatcher = PreviewSampledFrameDispatcher(
      mainHandler = captureHandler,
      intervalMillis = { 100 },
      captureFrame = { identity, _ ->
        synchronized(capturedIdentities) { capturedIdentities += identity }
        CameraFrame.PreviewSampled(
          data = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
          rotationDegrees = 0,
          transformIdentity = identity,
        )
      },
      onFrame = { frame ->
        callbackCount.incrementAndGet()
        latestCallback.set(frame.transformToken.matches(latestIdentity))
        callbackReceived.countDown()
      },
      onError = error::set,
      elapsedRealtimeMillis = now::get,
      analysisCoordinator = coordinator,
    )

    try {
      check(
        captureHandler.post {
          handlerBlocked.countDown()
          check(releaseHandler.await(15, TimeUnit.SECONDS))
        },
      )
      assertThat(handlerBlocked.await(5, TimeUnit.SECONDS)).isTrue()
      dispatcher.start()
      now.set(1_100)
      dispatcher.offer(firstIdentity, isPreviewMirrored = false)
      val worker = checkNotNull(analysisThread.get())
      assertThat(awaitThreadState(worker, Thread.State.WAITING, 5_000)).isTrue()

      now.set(1_200)
      dispatcher.offer(replacedIdentity, isPreviewMirrored = false)
      now.set(1_300)
      dispatcher.offer(latestIdentity, isPreviewMirrored = false)
      releaseHandler.countDown()

      assertThat(callbackReceived.await(5, TimeUnit.SECONDS)).isTrue()
      executor.execute { analysisDrained.countDown() }
      assertThat(analysisDrained.await(5, TimeUnit.SECONDS)).isTrue()
      assertThat(capturedIdentities).containsExactly(latestIdentity)
      assertThat(callbackCount.get()).isEqualTo(1)
      assertThat(latestCallback.get()).isTrue()
      assertThat(error.get()).isNull()
    } finally {
      releaseHandler.countDown()
      dispatcher.close()
      coordinator.close()
      captureThread.quitSafely()
      captureThread.join(5_000)
    }
  }

  @Test
  fun sampledFrameDispatcher_keepsOnlyLatestPendingCapture() {
    val now = AtomicLong(1_000)
    val firstCallbackStarted = CountDownLatch(1)
    val releaseFirstCallback = CountDownLatch(1)
    val callbacks = CountDownLatch(2)
    val capturedIdentities = mutableListOf<CameraFrameTransformIdentity>()
    val error = AtomicReference<Throwable?>()
    val firstIdentity = CameraFrameTransformIdentity()
    val replacedIdentity = CameraFrameTransformIdentity()
    val latestIdentity = CameraFrameTransformIdentity()
    val dispatcher = PreviewSampledFrameDispatcher(
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
      onFrame = { frame ->
        if (frame.transformToken.matches(firstIdentity)) {
          firstCallbackStarted.countDown()
          check(releaseFirstCallback.await(5, TimeUnit.SECONDS))
        }
        callbacks.countDown()
      },
      onError = error::set,
      elapsedRealtimeMillis = now::get,
    )
    dispatcher.start()

    now.set(1_100)
    dispatcher.offer(firstIdentity, isPreviewMirrored = false)
    assertThat(firstCallbackStarted.await(5, TimeUnit.SECONDS)).isTrue()
    now.set(1_200)
    dispatcher.offer(replacedIdentity, isPreviewMirrored = false)
    now.set(1_300)
    dispatcher.offer(latestIdentity, isPreviewMirrored = false)
    releaseFirstCallback.countDown()

    assertThat(callbacks.await(5, TimeUnit.SECONDS)).isTrue()
    dispatcher.close()
    assertThat(error.get()).isNull()
    assertThat(capturedIdentities).containsExactly(firstIdentity, latestIdentity).inOrder()
  }

  @Test
  fun sampledFrameDispatcher_closeDiscardsPendingCaptureAndFinishesStartedCallback() {
    val now = AtomicLong(1_000)
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val firstFinished = CountDownLatch(1)
    val closeFinished = CountDownLatch(1)
    val callbackCount = AtomicInteger()
    val capturedIdentities = mutableListOf<CameraFrameTransformIdentity>()
    val error = AtomicReference<Throwable?>()
    val firstIdentity = CameraFrameTransformIdentity()
    val pendingIdentity = CameraFrameTransformIdentity()
    val dispatcher = PreviewSampledFrameDispatcher(
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
        callbackCount.incrementAndGet()
        firstStarted.countDown()
        check(releaseFirst.await(5, TimeUnit.SECONDS))
        firstFinished.countDown()
      },
      onError = error::set,
      elapsedRealtimeMillis = now::get,
    )
    dispatcher.start()
    now.set(1_100)
    dispatcher.offer(firstIdentity, isPreviewMirrored = false)
    assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue()
    now.set(1_200)
    dispatcher.offer(pendingIdentity, isPreviewMirrored = false)

    val closeThread = Thread {
      dispatcher.close()
      closeFinished.countDown()
    }.also(Thread::start)
    assertThat(closeFinished.await(100, TimeUnit.MILLISECONDS)).isFalse()
    releaseFirst.countDown()

    assertThat(firstFinished.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(closeFinished.await(5, TimeUnit.SECONDS)).isTrue()
    closeThread.join(5_000)
    assertThat(callbackCount.get()).isEqualTo(1)
    assertThat(capturedIdentities).containsExactly(firstIdentity)
    assertThat(error.get()).isNull()
  }

  @Test
  fun sampledFrameDispatcher_stopCannotPassAcquiredCallbackBeforeUserEntry() {
    val now = AtomicLong(1_000)
    val callbackAcquired = CountDownLatch(1)
    val releaseCallback = CountDownLatch(1)
    val callbackEntered = CountDownLatch(1)
    val stopFinished = CountDownLatch(1)
    val callbackOrder = AtomicInteger()
    val stopOrder = AtomicInteger()
    val order = AtomicInteger()
    val error = AtomicReference<Throwable?>()
    val dispatcher = PreviewSampledFrameDispatcher(
      mainHandler = Handler(Looper.getMainLooper()),
      intervalMillis = { 100 },
      captureFrame = { identity, _ ->
        CameraFrame.PreviewSampled(
          data = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
          rotationDegrees = 0,
          transformIdentity = identity,
        )
      },
      onFrame = {
        callbackOrder.set(order.incrementAndGet())
        callbackEntered.countDown()
      },
      onError = error::set,
      elapsedRealtimeMillis = now::get,
      beforeFrameCallback = {
        callbackAcquired.countDown()
        check(releaseCallback.await(5, TimeUnit.SECONDS))
      },
    )
    dispatcher.start()
    now.set(1_100)
    dispatcher.offer(CameraFrameTransformIdentity(), isPreviewMirrored = false)
    assertThat(callbackAcquired.await(5, TimeUnit.SECONDS)).isTrue()
    val stopThread = Thread {
      dispatcher.stop()
      stopOrder.set(order.incrementAndGet())
      stopFinished.countDown()
    }.also(Thread::start)

    assertThat(stopFinished.await(100, TimeUnit.MILLISECONDS)).isFalse()
    assertThat(callbackEntered.count).isEqualTo(1)
    releaseCallback.countDown()

    assertThat(callbackEntered.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(stopFinished.await(5, TimeUnit.SECONDS)).isTrue()
    stopThread.join(5_000)
    dispatcher.close()
    assertThat(callbackOrder.get()).isLessThan(stopOrder.get())
    assertThat(error.get()).isNull()
  }

  @Test
  fun sampledFrameDispatcher_restartDiscardsPreviousCaptureBeforeCallback() {
    val now = AtomicLong(1_000)
    val captureStarted = CountDownLatch(1)
    val releaseCapture = CountDownLatch(1)
    val callback = CountDownLatch(1)
    val bitmap = AtomicReference<Bitmap?>()
    val error = AtomicReference<Throwable?>()
    val dispatcher = PreviewSampledFrameDispatcher(
      mainHandler = Handler(Looper.getMainLooper()),
      intervalMillis = { 100 },
      captureFrame = { identity, _ ->
        val data = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(bitmap::set)
        captureStarted.countDown()
        check(releaseCapture.await(5, TimeUnit.SECONDS))
        CameraFrame.PreviewSampled(data, rotationDegrees = 0, transformIdentity = identity)
      },
      onFrame = { callback.countDown() },
      onError = error::set,
      elapsedRealtimeMillis = now::get,
    )
    dispatcher.start()
    now.set(1_100)
    dispatcher.offer(CameraFrameTransformIdentity(), isPreviewMirrored = false)
    assertThat(captureStarted.await(5, TimeUnit.SECONDS)).isTrue()

    dispatcher.stop()
    now.set(2_000)
    dispatcher.start()
    releaseCapture.countDown()

    assertThat(callback.await(500, TimeUnit.MILLISECONDS)).isFalse()
    dispatcher.close()
    assertThat(error.get()).isNull()
    assertThat(checkNotNull(bitmap.get()).isRecycled).isTrue()
  }

  @Test
  fun sampledFrameDispatcher_callbackFailureIsReportedAndBitmapIsRecycled() {
    val now = AtomicLong(1_000)
    val failureReceived = CountDownLatch(1)
    val callbackFailure = IllegalStateException("sample callback")
    val receivedFailure = AtomicReference<Throwable?>()
    val bitmap = AtomicReference<Bitmap?>()
    val dispatcher = PreviewSampledFrameDispatcher(
      mainHandler = Handler(Looper.getMainLooper()),
      intervalMillis = { 100 },
      captureFrame = { identity, _ ->
        val data = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(bitmap::set)
        CameraFrame.PreviewSampled(data, rotationDegrees = 0, transformIdentity = identity)
      },
      onFrame = { throw callbackFailure },
      onError = { error ->
        receivedFailure.set(error)
        failureReceived.countDown()
      },
      elapsedRealtimeMillis = now::get,
    )
    dispatcher.start()

    now.set(1_100)
    dispatcher.offer(CameraFrameTransformIdentity(), isPreviewMirrored = false)

    assertThat(failureReceived.await(5, TimeUnit.SECONDS)).isTrue()
    dispatcher.close()
    assertThat(receivedFailure.get()).isSameInstanceAs(callbackFailure)
    assertThat(checkNotNull(bitmap.get()).isRecycled).isTrue()
  }

  @Test
  fun previewSampledProcessor_rejectsNonPositiveInterval() {
    assertThrows(IllegalArgumentException::class.java) {
      FrameProcessor.PreviewSampled(intervalMillis = 0) {}
    }
  }
}

private data class SampledFrameResult(
  val token: CameraFrameTransformToken,
  val threadName: String,
  val recycledDuringCallback: Boolean,
)

private fun awaitThreadState(thread: Thread, state: Thread.State, timeoutMillis: Long): Boolean {
  val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
  while (System.nanoTime() < deadline) {
    if (thread.state == state) return true
    Thread.yield()
  }
  return thread.state == state
}

private fun awaitThreadInterruptCleared(thread: Thread, timeoutMillis: Long): Boolean {
  val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
  while (System.nanoTime() < deadline) {
    if (!thread.isInterrupted) return true
    Thread.yield()
  }
  return !thread.isInterrupted
}
