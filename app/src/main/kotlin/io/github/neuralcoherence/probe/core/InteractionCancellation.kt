package io.github.neuralcoherence.probe.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Sleeps until the next request, waking early only when the user stops the task. */
internal class InteractionCancellation {
    @Volatile private var signal = CountDownLatch(1)

    // Reset only after the task coordinator has granted ownership of a new task.
    fun reset() { signal = CountDownLatch(1) }

    fun requestStop() { signal.countDown() }

    fun isStopRequested(): Boolean = signal.count == 0L

    fun awaitStop(timeoutMillis: Long): Boolean =
        signal.await(timeoutMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
}
