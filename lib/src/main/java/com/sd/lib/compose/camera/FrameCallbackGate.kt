package com.sd.lib.compose.camera

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 原子控制帧回调准入，并等待已经准入的同步回调完成。 */
internal class FrameCallbackGate {
  private val _lock = ReentrantLock()
  private val _idle = _lock.newCondition()
  private var _generation = 0L
  private var _closed = false
  private var _activeCallbackCount = 0

  fun currentGeneration(): Long = _lock.withLock { _generation }

  fun runIfCurrent(generation: Long, action: () -> Unit) {
    val acquired = _lock.withLock {
      if (_closed || generation != _generation) {
        false
      } else {
        _activeCallbackCount++
        true
      }
    }
    if (!acquired) return

    try {
      action()
    } finally {
      _lock.withLock {
        _activeCallbackCount--
        if (_activeCallbackCount == 0) _idle.signalAll()
      }
    }
  }

  fun advanceGeneration(): Long {
    return _lock.withLock {
      _generation++
      _generation
    }
  }

  fun closeAdmission(): Long {
    return _lock.withLock {
      if (!_closed) {
        _closed = true
        _generation++
      }
      _generation
    }
  }

  fun awaitIdle() {
    _lock.withLock {
      while (_activeCallbackCount != 0) _idle.awaitUninterruptibly()
    }
  }
}
