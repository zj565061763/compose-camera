package com.sd.lib.compose.camera

import android.graphics.SurfaceTexture
import java.util.WeakHashMap

/** 延迟释放仍被相机会话使用的 SurfaceTexture */
internal class SurfaceTextureReleaseCoordinator(
  private val releaseSurfaceTexture: (SurfaceTexture) -> Unit = SurfaceTexture::release,
) {
  private val _lock = Any()
  private val _states = WeakHashMap<SurfaceTexture, SurfaceTextureReleaseState>()

  fun retain(surfaceTexture: SurfaceTexture) {
    synchronized(_lock) {
      val state = _states.getOrPut(surfaceTexture, ::SurfaceTextureReleaseState)
      check(!state.releaseRequested && !state.released) { "SurfaceTexture has already been destroyed." }
      state.useCount++
    }
  }

  fun requestRelease(surfaceTexture: SurfaceTexture) {
    val shouldRelease = synchronized(_lock) {
      val state = _states.getOrPut(surfaceTexture, ::SurfaceTextureReleaseState)
      if (state.releaseRequested || state.released) {
        false
      } else {
        state.releaseRequested = true
        if (state.useCount == 0) {
          state.released = true
          true
        } else {
          false
        }
      }
    }
    if (shouldRelease) releaseSurfaceTexture(surfaceTexture)
  }

  fun releaseAfterUse(surfaceTexture: SurfaceTexture) {
    val shouldRelease = synchronized(_lock) {
      val state = checkNotNull(_states[surfaceTexture]) { "SurfaceTexture is not retained." }
      check(state.useCount > 0) { "SurfaceTexture use count is already zero." }
      state.useCount--
      if (state.useCount == 0 && state.releaseRequested) {
        state.released = true
        true
      } else {
        if (state.useCount == 0) _states.remove(surfaceTexture)
        false
      }
    }
    if (shouldRelease) releaseSurfaceTexture(surfaceTexture)
  }
}

private class SurfaceTextureReleaseState {
  var useCount = 0
  var releaseRequested = false
  var released = false
}
