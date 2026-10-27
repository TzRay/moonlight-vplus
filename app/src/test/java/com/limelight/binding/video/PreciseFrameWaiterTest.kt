package com.limelight.binding.video

import org.junit.Assert.*
import org.junit.Test

class PreciseFrameWaiterTest {
    @Test
    fun spuriousWakeupParksAgainInsteadOfSpinningForTheWholeFrame() {
        var now = 0L
        var clockReads = 0
        val parks = mutableListOf<Long>()
        val waiter = PreciseFrameWaiter(
            isActive = { true },
            nanoTime = { clockReads++; now.also { now += 100_000L } },
            parkNanos = { duration ->
                parks.add(duration)
                if (parks.size != 1) now += duration // first wakeup is spurious
            },
        )
        waiter.await(10_000_000L)
        assertEquals(2, parks.size)
        assertTrue("The full frame must not become a spin wait", clockReads < 12)
        assertTrue(now >= 10_000_000L)
    }

    @Test
    fun stoppingWhileParkedDoesNotWaitForTheDeadline() {
        var active = true
        var clockReads = 0
        val waiter = PreciseFrameWaiter(
            isActive = { active },
            nanoTime = { clockReads++; 0L },
            parkNanos = { active = false },
        )
        waiter.await(10_000_000L)
        assertEquals(1, clockReads)
    }

    @Test
    fun anExpiredDeadlineDoesNotPark() {
        PreciseFrameWaiter({ true }, { 20L }, { fail("Deadline already passed") }).await(10L)
    }

    @Test
    fun interruptionStopsWaitingAndPreservesTheFlag() {
        try {
            Thread.currentThread().interrupt()
            PreciseFrameWaiter({ true }, { fail("Interrupted thread must not spin"); 0L }, {}).await(10L)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun cancellationAlsoWorksInsideTheSpinWindow() {
        var checks = 0
        var reads = 0
        PreciseFrameWaiter({ ++checks < 4 }, { reads++; 0L }, { fail("Should be spinning") })
            .await(PreciseFrameWaiter.SPIN_WINDOW_NS)
        assertEquals(3, reads)
    }
}
