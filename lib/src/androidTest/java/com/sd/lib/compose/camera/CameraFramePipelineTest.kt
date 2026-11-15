package com.sd.lib.compose.camera

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.os.Handler
import android.os.Looper
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

  @Test
  fun rawAnalyzer_dropsFrameWhenClosedDuringConversion() {
    val state = CameraPreviewState()
    val oldSession = PreviewSession(
      contentSize = IntSize(100, 100),
      isPreviewMirrored = false,
      resolution = IntSize(2, 2),
      rawFrame = null,
    )
    val newSession = PreviewSession(
      contentSize = IntSize(100, 100),
      isPreviewMirrored = false,
      resolution = IntSize(2, 2),
      rawFrame = null,
    )
    runOnMainSync {
      state.updatePreviewLayout(IntSize(100, 100), ContentScale.Crop, isMirrored = false)
      state.startSession(oldSession)
    }
    val oldToken = CameraFrameTransformToken(state.currentTransformIdentity())
    val conversionEntered = CountDownLatch(1)
    val resumeConversion = CountDownLatch(1)
    val deliveredFrames = CopyOnWriteArrayList<CameraFrame.Preview>()
    val errors = CopyOnWriteArrayList<Throwable>()
    val analyzer = RawFrameAnalyzer(
      frameProcessor = {
        FrameProcessor.Preview { frame ->
          deliveredFrames.add(frame)
        }
      },
      currentFrameTransform = state::currentFrameTransform,
      onError = errors::add,
    )
    val image = FakeYuvImageProxy(
      onReadY = {
        conversionEntered.countDown()
        resumeConversion.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      },
    )
    val executor = Executors.newSingleThreadExecutor()

    try {
      val future = executor.submit {
        analyzer.analyze(image)
      }
      assertThat(conversionEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()

      runOnMainSync {
        analyzer.close()
        state.clearSession(oldSession)
        state.startSession(newSession)
      }
      assertThat(state.isFrameTransformCurrent(oldToken)).isFalse()

      resumeConversion.countDown()
      future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } finally {
      resumeConversion.countDown()
      analyzer.close()
      executor.shutdown()
      assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    }

    assertThat(image.isClosed).isTrue()
    assertThat(errors).isEmpty()
    assertThat(deliveredFrames).isEmpty()
  }

  @Test
  fun rawAnalyzer_dropsFrameWhenSessionChangesDuringConversionAndIsolatesForeignSession() {
    val state = CameraPreviewState()
    val session1 = PreviewSession(
      contentSize = IntSize(100, 100),
      isPreviewMirrored = false,
      resolution = IntSize(2, 2),
      rawFrame = null,
    )
    val session2 = PreviewSession(
      contentSize = IntSize(100, 100),
      isPreviewMirrored = false,
      resolution = IntSize(2, 2),
      rawFrame = null,
    )
    runOnMainSync {
      state.updatePreviewLayout(IntSize(100, 100), ContentScale.Crop, isMirrored = false)
      state.startSession(session1)
    }
    val conversionEntered = CountDownLatch(1)
    val resumeConversion = CountDownLatch(1)
    val deliveredTokens = CopyOnWriteArrayList<CameraFrameTransformToken>()
    val errors = CopyOnWriteArrayList<Throwable>()
    val activeSessionRef = AtomicReference<PreviewSession?>(session1)
    val analyzer = RawFrameAnalyzer(
      frameProcessor = {
        FrameProcessor.Preview { frame ->
          deliveredTokens.add(frame.transformToken)
        }
      },
      currentFrameTransform = {
        state.currentFrameTransform(activeSessionRef.get())
      },
      onError = errors::add,
    )
    val imageDuringSwitch = FakeYuvImageProxy(
      onReadY = {
        conversionEntered.countDown()
        resumeConversion.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      },
    )
    val executor = Executors.newSingleThreadExecutor()

    try {
      val firstFuture = executor.submit {
        analyzer.analyze(imageDuringSwitch)
      }
      assertThat(conversionEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()

      runOnMainSync {
        activeSessionRef.set(null)
        state.clearSession(session1)
        state.startSession(session2)
        activeSessionRef.set(session2)
      }
      resumeConversion.countDown()
      firstFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

      assertThat(imageDuringSwitch.isClosed).isTrue()
      assertThat(deliveredTokens).isEmpty()

      // 未归属当前活动会话的分析器即使在 state 已启动 session2 时也只能拿到无效 token
      activeSessionRef.set(null)
      executor.submit { analyzer.analyze(FakeYuvImageProxy()) }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      activeSessionRef.set(session1)
      executor.submit { analyzer.analyze(FakeYuvImageProxy()) }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      activeSessionRef.set(session2)
      executor.submit { analyzer.analyze(FakeYuvImageProxy()) }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } finally {
      resumeConversion.countDown()
      analyzer.close()
      executor.shutdown()
      assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    }

    assertThat(errors).isEmpty()
    assertThat(deliveredTokens).hasSize(3)
    assertThat(state.isFrameTransformCurrent(deliveredTokens[0])).isFalse()
    assertThat(state.isFrameTransformCurrent(deliveredTokens[1])).isFalse()
    assertThat(state.isFrameTransformCurrent(deliveredTokens[2])).isTrue()
  }

  @Test
  fun rawAnalyzer_dropsQueuedFrameWhenSessionStopsAndRestartsBeforeAnalyze() {
    val state = CameraPreviewState()
    val session1 = PreviewSession(
      contentSize = IntSize(100, 100),
      isPreviewMirrored = false,
      resolution = IntSize(2, 2),
      rawFrame = null,
    )
    val session2 = PreviewSession(
      contentSize = IntSize(100, 100),
      isPreviewMirrored = false,
      resolution = IntSize(2, 2),
      rawFrame = null,
    )
    runOnMainSync {
      state.updatePreviewLayout(IntSize(100, 100), ContentScale.Crop, isMirrored = false)
      state.startSession(session1)
    }
    val activeSessionRef = AtomicReference<PreviewSession?>(session1)
    val blockerEntered = CountDownLatch(1)
    val releaseBlocker = CountDownLatch(1)
    val deliveredFrames = CopyOnWriteArrayList<Pair<Byte, CameraFrameTransformToken>>()
    val errors = CopyOnWriteArrayList<Throwable>()
    val analyzer = RawFrameAnalyzer(
      frameProcessor = {
        FrameProcessor.Preview { frame ->
          deliveredFrames.add(frame.data[0] to frame.transformToken)
        }
      },
      currentFrameTransform = {
        state.currentFrameTransform(activeSessionRef.get())
      },
      onError = errors::add,
    )
    val executor = CameraAnalysisExecutor()
    val analyzerExecutor = analyzer.executor(executor)
    val queuedFromSession1 = FakeYuvImageProxy(yBytes = byteArrayOf(1, 1, 1, 1))
    val queuedWhileStopped = FakeYuvImageProxy(yBytes = byteArrayOf(2, 2, 2, 2))
    val freshFromSession2 = FakeYuvImageProxy(yBytes = byteArrayOf(9, 9, 9, 9))

    try {
      assertThat(
        executor.submit {
          blockerEntered.countDown()
          releaseBlocker.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        },
      ).isTrue()
      assertThat(blockerEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()

      analyzerExecutor.execute { analyzer.analyze(queuedFromSession1) }
      runOnMainSync {
        activeSessionRef.set(null)
        state.clearSession(session1)
      }
      analyzerExecutor.execute { analyzer.analyze(queuedWhileStopped) }
      runOnMainSync {
        state.startSession(session2)
        activeSessionRef.set(session2)
      }
      releaseBlocker.countDown()
      analyzerExecutor.execute { analyzer.analyze(freshFromSession2) }

      val drained = CountDownLatch(1)
      assertThat(executor.submit(drained::countDown)).isTrue()
      assertThat(drained.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    } finally {
      releaseBlocker.countDown()
      analyzer.close()
      executor.close()
    }

    assertThat(errors).isEmpty()
    assertThat(queuedFromSession1.isClosed).isTrue()
    assertThat(queuedWhileStopped.isClosed).isTrue()
    assertThat(freshFromSession2.isClosed).isTrue()
    assertThat(deliveredFrames).hasSize(1)
    assertThat(deliveredFrames[0].first).isEqualTo(9.toByte())
    assertThat(state.isFrameTransformCurrent(deliveredFrames[0].second)).isTrue()
  }

  @Test
  fun sampler_stopAndRestartSkipsPreviouslyQueuedFrame() {
    val executor = CameraAnalysisExecutor()
    val blockerEntered = CountDownLatch(1)
    val releaseBlocker = CountDownLatch(1)
    val firstCaptured = CountDownLatch(1)
    val restartedDelivered = CountDownLatch(1)
    val capturedBitmaps = CopyOnWriteArrayList<Bitmap>()
    val deliveredBitmaps = CopyOnWriteArrayList<Bitmap>()
    val errors = CopyOnWriteArrayList<Throwable>()
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
            FrameProcessor.PreviewSampled(intervalMillis = 10L) { frame ->
              deliveredBitmaps.add(frame.data)
              restartedDelivered.countDown()
            }
          },
          captureFrame = {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            capturedBitmaps.add(bitmap)
            firstCaptured.countDown()
            CameraFrame.PreviewSampled(bitmap, 0, CameraFrameTransformIdentity())
          },
          analysisExecutor = executor,
          onError = errors::add,
        ).also { it.start() }
      }

      assertThat(firstCaptured.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
      runOnMainSync {
        checkNotNull(sampler).also {
          it.stop()
          it.start()
        }
      }
      releaseBlocker.countDown()

      assertThat(restartedDelivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
      runOnMainSync { sampler?.close() }
      val drained = CountDownLatch(1)
      assertThat(executor.submit(drained::countDown)).isTrue()
      assertThat(drained.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    } finally {
      releaseBlocker.countDown()
      runOnMainSync { sampler?.close() }
      executor.close()
    }

    assertThat(errors).isEmpty()
    assertThat(capturedBitmaps.size).isAtLeast(2)
    assertThat(deliveredBitmaps).isNotEmpty()
    assertThat(deliveredBitmaps).doesNotContain(capturedBitmaps[0])
    assertThat(capturedBitmaps.all { it.isRecycled }).isTrue()
  }

  private companion object {
    const val TIMEOUT_SECONDS = 5L
  }
}

private fun runOnMainSync(action: () -> Unit) {
  InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
}

private class FakeYuvImageProxy(
  private val onReadY: () -> Unit = {},
  yBytes: ByteArray = byteArrayOf(1, 2, 3, 4),
) : ImageProxy {
  private val _closed = AtomicBoolean(false)
  private var _cropRect = Rect(0, 0, 2, 2)
  private val _planes = arrayOf<ImageProxy.PlaneProxy>(
    FakePlaneProxy(rowStride = 2, bytes = yBytes, beforeRead = onReadY),
    FakePlaneProxy(rowStride = 2, bytes = byteArrayOf(10)),
    FakePlaneProxy(rowStride = 2, bytes = byteArrayOf(20)),
  )
  private val _imageInfo = FakeImageInfo()

  val isClosed: Boolean
    get() {
      return _closed.get()
    }

  override fun close() {
    _closed.set(true)
  }

  override fun getCropRect(): Rect {
    return Rect(_cropRect)
  }

  override fun setCropRect(rect: Rect?) {
    _cropRect = rect?.let(::Rect) ?: Rect(0, 0, 2, 2)
  }

  override fun getFormat(): Int {
    return ImageFormat.YUV_420_888
  }

  override fun getHeight(): Int {
    return 2
  }

  override fun getWidth(): Int {
    return 2
  }

  override fun getPlanes(): Array<ImageProxy.PlaneProxy> {
    return _planes
  }

  @ExperimentalGetImage
  override fun getImage(): Image? {
    return null
  }

  override fun getImageInfo(): ImageInfo {
    return _imageInfo
  }
}

private class FakePlaneProxy(
  private val rowStride: Int,
  private val bytes: ByteArray,
  private val beforeRead: () -> Unit = {},
) : ImageProxy.PlaneProxy {
  override fun getRowStride(): Int {
    return rowStride
  }

  override fun getPixelStride(): Int {
    return 1
  }

  override fun getBuffer(): ByteBuffer {
    beforeRead()
    return ByteBuffer.wrap(bytes)
  }
}

@SuppressLint("RestrictedApi")
private class FakeImageInfo : ImageInfo {
  override fun getTagBundle(): TagBundle {
    return TagBundle.emptyBundle()
  }

  override fun getTimestamp(): Long {
    return 0L
  }

  override fun getRotationDegrees(): Int {
    return 0
  }

  override fun populateExifData(exifBuilder: ExifData.Builder) {
  }
}
