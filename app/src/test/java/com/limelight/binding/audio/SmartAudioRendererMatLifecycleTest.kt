package com.limelight.binding.audio

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.LongSupplier

class SmartAudioRendererMatLifecycleTest {
    @Test(timeout = 5000) fun setupChecksTheLocalDeadlineAgainWhenTheReadyWaitReturns() {
        val budget = TimeUnit.MILLISECONDS.toNanos(MatPlaybackSession.HDMI_READY_TIMEOUT_MS.toLong())
        for (readyAt in listOf(budget - 1, budget, budget + 1)) {
            val waitStarted = CountDownLatch(1)
            val closes = AtomicInteger()
            val sink = object : MatCarrierPlayer.Sink {
                override fun prepare() {
                    check(waitStarted.await(1, TimeUnit.SECONDS))
                }
                override fun play() = error("no carrier was queued")
                override fun playedFrames() = 0L
                override fun write(words: ShortArray, offset: Int, count: Int) = count
                override fun close() { closes.incrementAndGet() }
            }
            var samples = 0
            val clock = LongSupplier {
                when (++samples) {
                    1 -> 0L // Start of this preparation.
                    2 -> {
                        waitStarted.countDown()
                        // Wait for the actual writer's READY latch, after prepare and threshold
                        // lookup. The third sample must come from the post-READY check.
                        val owner = MatPlaybackSession::class.java.getDeclaredField("owner").apply { isAccessible = true }
                        assertTrue((owner.get(null) as MatCarrierPlayer).awaitReady(1000))
                        0L // Value used to enter awaitReady.
                    }
                    else -> readyAt
                }
            }
            if (readyAt < budget) {
                MatPlaybackSession(sink, false, { false }, clock).close()
            } else {
                val error = assertThrows(IllegalStateException::class.java) {
                    MatPlaybackSession(sink, false, { false }, clock)
                }
                assertTrue(error.message.orEmpty().contains("8000 ms"))
            }
            assertEquals("start, enter ready wait, commit ready", 3, samples)
            assertEquals(1, closes.get())
        }
    }

    @Test fun handoffRejectsSupersededPreparationAndKeepsConnectionStopLiveAfterSuccess() {
        var stop = false
        var superseded = false
        val cancelled = MatPreparationCancellation({ stop }, { superseded })
        assertFalse(cancelled.asBoolean)
        superseded = true
        assertTrue(cancelled.asBoolean)
        assertFalse(cancelled.finish())

        superseded = false
        val running = MatPreparationCancellation({ stop }, { superseded })
        assertTrue(running.finish())
        superseded = true
        assertFalse(running.asBoolean)
        stop = true
        assertTrue(running.asBoolean)
    }

    @Test(timeout = 6500) fun stopSignalsTheWriterAndCleanupWaitsOnceAndForgetsTheDelegateEvenOnTimeout() {
        val closing = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val sink = object : MatCarrierPlayer.Sink {
            override fun prepare() {}
            override fun play() {}
            override fun playedFrames() = 0L
            override fun write(words: ShortArray, offset: Int, count: Int) = count
            override fun close() {
                closing.countDown()
                try {
                    // A platform close may ignore interruption until its driver returns.
                    while (finish.count != 0L) {
                        try { finish.await() } catch (_: InterruptedException) {}
                    }
                } finally { closed.countDown() }
            }
        }
        val session = MatPlaybackSession(sink)
        val context = ContextWrapper(null)
        val mat = MatPassthroughRenderer(context)
        val dispatcher = SmartAudioRenderer(context, false, false)
        // Install an already prepared session without loading Android's native bridge or probing HDMI.
        val sessionField = MatPassthroughRenderer::class.java.getDeclaredField("session").apply { isAccessible = true }
        sessionField.set(mat, session)
        val delegateField = SmartAudioRenderer::class.java.getDeclaredField("delegate").apply { isAccessible = true }
        delegateField.set(dispatcher, mat)
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.submit { dispatcher.stop() }.get(1, TimeUnit.SECONDS)
            assertTrue(closing.await(1, TimeUnit.SECONDS))
            assertSame(mat, delegateField.get(dispatcher))
            assertSame(session, sessionField.get(mat))
            assertThrows(MatCarrierPlayer.CleanupPending::class.java) { dispatcher.cleanup() }
            assertNull(delegateField.get(dispatcher))
            assertNull(sessionField.get(mat))
            dispatcher.cleanup()
            assertThrows(MatCarrierPlayer.CleanupPending::class.java) { MatPlaybackSession(sink) }
        } finally {
            finish.countDown()
            pool.shutdownNow()
            assertTrue(closed.await(1, TimeUnit.SECONDS))
            session.close()
        }
    }
}
