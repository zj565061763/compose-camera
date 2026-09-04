package com.sd.lib.compose.camera

internal fun <T : Throwable> mergeFailures(failure: T?, nextFailure: T): T {
  return when {
    failure == null -> nextFailure
    failure is Error || nextFailure !is Error -> failure.also {
      if (failure !== nextFailure) failure.addSuppressed(nextFailure)
    }
    else -> nextFailure.also { nextFailure.addSuppressed(failure) }
  }
}

internal fun throwAfterCleanup(
  failure: Throwable,
  cleanupActions: List<() -> Unit>,
): Nothing {
  var mergedFailure = failure
  cleanupActions.forEach { action ->
    try {
      action()
    } catch (cleanupFailure: Throwable) {
      mergedFailure = mergeFailures(mergedFailure, cleanupFailure)
    }
  }
  throw mergedFailure
}
