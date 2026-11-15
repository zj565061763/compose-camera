package com.sd.lib.compose.camera

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CameraFramePipelineTest {
  @Test
  fun sampler_skipsCaptureWhileBusyAndRecyclesBitmaps() {
    val executor = CameraAnalysisExecutor()
    val allowFinishFirst = CountDownLatch(1)
    val firstEntered = CountDownLatch(1)
    val secondDelivered = CountDownLatch(1)
    val deliveredCount = AtomicInteger()
    val capturedBitmaps = mutableListOf<Bitmap>()
    val errors = CopyOnWriteArrayList<Throwable>()
    var sampler: PreviewFrameSampler? = null

    try {
      runOnMainSync {
        sampler = PreviewFrameSampler(
          frameProcessor = {
            FrameProcessor.PreviewSampled(intervalMillis = 20) {
              val count = deliveredCount.incrementAndGet()
              if (count == 1) {
                firstEntered.countDown()
                allowFinishFirst.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // 模拟用户回调留下中断状态，不应影响后续帧
                Thread.currentThread().interrupt()
              } else if (count == 2) {
                secondDelivered.countDown()
              }
            }
          },
          captureFrame = {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            capturedBitmaps += bitmap
            CameraFrame.PreviewSampled(bitmap, 0, CameraFrameTransformIdentity())
          },
          analysisExecutor = executor,
          onError = errors::add,
        ).also { it.start() }
      }

      assertThat(firstEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
      // 第一帧阻塞期间主线程不应无限堆积新截图
      runOnMainSync { assertThat(capturedBitmaps.size).isEqualTo(1) }

      allowFinishFirst.countDown()
      assertThat(secondDelivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
      runOnMainSync { sampler?.close() }
      // 等待分析单线程执行完第二帧回调后的 finally 回收逻辑
      val drained = CountDownLatch(1)
      assertThat(executor.submit(drained::countDown)).isTrue()
      assertThat(drained.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    } finally {
      runOnMainSync { sampler?.close() }
      executor.close()
    }

    assertThat(errors).isEmpty()
    assertThat(capturedBitmaps.all { it.isRecycled }).isTrue()
  }

  @Test
  fun sampler_stopSkipsQueuedFrameAndRecyclesBitmap() {
    val executor = CameraAnalysisExecutor()
    val blockerEntered = CountDownLatch(1)
    val releaseBlocker = CountDownLatch(1)
    val captured = CountDownLatch(1)
    val capturedBitmap = AtomicReference<Bitmap?>()
    val deliveredCount = AtomicInteger()
    var sampler: PreviewFrameSampler? = null

    try {
      assertThat(
        executor.submit {
          blockerEntered.countDown()
          releaseBlocker.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        },
      ).isTrue()
      assertThat(blockerEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()

      runOnMainSync {
        sampler = PreviewFrameSampler(
          frameProcessor = {
            FrameProcessor.PreviewSampled(intervalMillis = 10L) {
              deliveredCount.incrementAndGet()
            }
          },
          captureFrame = {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            capturedBitmap.set(bitmap)
            captured.countDown()
            CameraFrame.PreviewSampled(bitmap, 0, CameraFrameTransformIdentity())
          },
          analysisExecutor = executor,
          onError = {},
        ).also { it.start() }
      }

      assertThat(captured.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
      // 采样帧已在分析队列中排队，stop() 后不应再触发新的帧回调，但仍需回收 Bitmap
      runOnMainSync { sampler?.stop() }
      releaseBlocker.countDown()

      val drained = CountDownLatch(1)
      assertThat(executor.submit(drained::countDown)).isTrue()
      assertThat(drained.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    } finally {
      releaseBlocker.countDown()
      runOnMainSync { sampler?.close() }
      executor.close()
    }

    assertThat(deliveredCount.get()).isEqualTo(0)
    assertThat(checkNotNull(capturedBitmap.get()).isRecycled).isTrue()
  }

  @Test
  fun sampler_shorteningIntervalReschedulesImmediately() {
    val executor = CameraAnalysisExecutor()
    val delivered = CountDownLatch(1)
    val clock = AtomicLong(1_000L)
    val processorRef = AtomicReference<FrameProcessor>(
      FrameProcessor.PreviewSampled(intervalMillis = 60_000L) {},
    )
    var sampler: PreviewFrameSampler? = null

    try {
      runOnMainSync {
        val activeSampler = PreviewFrameSampler(
          frameProcessor = processorRef::get,
          captureFrame = {
            CameraFrame.PreviewSampled(
              Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
              0,
              CameraFrameTransformIdentity(),
            )
          },
          analysisExecutor = executor,
          onError = {},
          mainHandler = Handler(Looper.getMainLooper()),
          uptimeMillis = clock::get,
        ).also {
          it.start()
          sampler = it
        }

        clock.addAndGet(30L)
        processorRef.set(
          FrameProcessor.PreviewSampled(intervalMillis = 20L) {
            delivered.countDown()
          },
        )
        activeSampler.onProcessorUpdated()
      }

      assertThat(delivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
      runOnMainSync { sampler?.close() }
    } finally {
      executor.close()
    }
  }

  @Test
  fun sampler_recyclesBitmapWhenExecutorAlreadyClosed() {
    val executor = CameraAnalysisExecutor().also { it.close() }
    assertThat(executor.submit {}).isFalse()
    var rejected = false
    try {
      executor.execute {}
    } catch (_: RejectedExecutionException) {
      rejected = true
    }
    assertThat(rejected).isTrue()

    val recycledCheck = AtomicReference<Bitmap?>()
    val checked = CountDownLatch(1)
    var sampler: PreviewFrameSampler? = null

    try {
      runOnMainSync {
        sampler = PreviewFrameSampler(
          frameProcessor = { FrameProcessor.PreviewSampled(intervalMillis = 10L) {} },
          captureFrame = {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            recycledCheck.set(bitmap)
            checked.countDown()
            CameraFrame.PreviewSampled(bitmap, 0, CameraFrameTransformIdentity())
          },
          analysisExecutor = executor,
          onError = {},
        ).also { it.start() }
      }

      assertThat(checked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    } finally {
      runOnMainSync { sampler?.close() }
    }

    runOnMainSync {
      assertThat(checkNotNull(recycledCheck.get()).isRecycled).isTrue()
    }
  }

  private companion object {
    const val TIMEOUT_SECONDS = 5L
  }
}

private fun runOnMainSync(action: () -> Unit) {
  InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
}
