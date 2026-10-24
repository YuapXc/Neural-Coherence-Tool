package io.github.neuralcoherence.probe.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionCancellationTest {
    @Test
    fun stopWakesAnAlreadyWaitingTask() {
        val cancellation = InteractionCancellation()
        val started = CountDownLatch(1)
        val waiter = FutureTask {
            started.countDown()
            cancellation.awaitStop(30_000L)
        }
        val thread = Thread(waiter)
        thread.start()
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            cancellation.requestStop()
            assertTrue(waiter.get(2, TimeUnit.SECONDS))
        } finally {
            cancellation.requestStop()
            thread.join(2000)
        }
    }

    @Test
    fun stopBeforeWaitingIsNotLostAndNextTaskCanReset() {
        val cancellation = InteractionCancellation()
        cancellation.requestStop()
        assertTrue(cancellation.isStopRequested())
        assertTrue(cancellation.awaitStop(30_000L))
        cancellation.reset()
        assertFalse(cancellation.isStopRequested())
        assertFalse(cancellation.awaitStop(0L))
    }

    @Test
    fun elapsedDelayDoesNotCancelTheTask() {
        val cancellation = InteractionCancellation()
        assertFalse(cancellation.awaitStop(10L))
        assertFalse(cancellation.isStopRequested())
    }
}
