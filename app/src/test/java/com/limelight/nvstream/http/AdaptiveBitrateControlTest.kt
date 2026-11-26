package com.limelight.nvstream.http

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AdaptiveBitrateControlTest {
    private class FakeHost(version: Int) : AbrEndpoint {
        val caps = AbrCapabilities(version > 0, version, if (version >= 2) listOf("packet_sample_v2") else emptyList())
        val reports = mutableListOf<NetworkFeedback>()
        val writes = mutableListOf<Int>()
        var respond: () -> AbrAction? = { null }
        override fun getAbrCapabilities() = caps
        override fun setAbrMode(config: AbrConfig) = true
        override fun reportNetworkFeedback(feedback: NetworkFeedback): AbrAction? { reports += feedback; return respond() }
        override fun setBitrate(bitrateKbps: Int): Boolean { writes += bitrateKbps; return true }
    }

    private class Replay(version: Int) : AutoCloseable {
        val host = FakeHost(version)
        val worker = object : ScheduledThreadPoolExecutor(1) {
            override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long,
                                                unit: TimeUnit): ScheduledFuture<*> =
                super.scheduleWithFixedDelay(command, 1, 1, TimeUnit.DAYS)
        }
        var ms = 10000L
        var samples = 0
        var resets = 0
        private var observation: AdaptiveBitrateService.AbrStats? = null
        val service = AdaptiveBitrateService({host}, {samples++; observation}, {_, _ ->}, {resets++}, {ms}, worker)
        init { service.start(20000, "balanced"); drain() }
        fun sample(expected: Int, missing: Int, gaps: Int = 0, dropped: Int = 0) {
            observation = AdaptiveBitrateService.AbrStats(if (expected > 0) 100f * missing / expected else 0f,
                9, 60f, dropped, AbrPacketSample(expected.toLong(), missing.toLong(), gaps.toLong(),
                    1, expected >= 100 && gaps == 0, false))
        }
        fun tick(expected: Int, missing: Int, gaps: Int = 0, dropped: Int = 0) {
            sample(expected, missing, gaps, dropped)
            worker.submit { service.tick() }.get(5, TimeUnit.SECONDS)
            ms += 1000
        }
        fun drain() { worker.submit {}.get(5, TimeUnit.SECONDS) }
        override fun close() { service.stop(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun localControllerKeepsSparseRecoveredLossAndRespondsToDamage() {
        Replay(0).use { r ->
            repeat(24) { r.tick(if (it % 4 == 0) 20 else 0, if (it % 4 == 0) 1 else 0) }
            r.tick(10, 1)
            assertTrue(r.host.writes.isEmpty())
            r.tick(100, 10)
            assertEquals(listOf(14000), r.host.writes)
        }
        Replay(0).use { r ->
            r.tick(4, 2, dropped = 1)
            r.tick(4, 2, dropped = 1)
            r.tick(4, 2, dropped = 1)
            assertEquals(listOf(18000), r.host.writes)
        }
        Replay(0).use { r ->
            r.tick(4, 0, gaps = 1)
            r.tick(4, 0, dropped = 1)
            assertEquals(listOf(18000), r.host.writes)
        }
        Replay(0).use { r ->
            r.tick(0, 0, dropped = 1)
            r.tick(0, 0, dropped = 3)
            assertEquals(listOf(18000), r.host.writes)
        }
    }

    @Test fun extendedHostGetsIdleTelemetryAndLegacyHostOnlyGetsMeasuredLoss() {
        for (version in 1..2) Replay(version).use { r ->
            r.tick(0, 0)
            r.tick(20, 1)
            r.tick(100, 10)
            assertEquals(if (version == 2) 3 else 1, r.host.reports.size)
            if (version == 2) assertEquals(listOf(false, false, true), r.host.reports.map { it.packets!!.trusted })
            else assertNull(r.host.reports.single().packets)
        }
    }

    @Test fun legacyHostGetsNoUnknownLossReportButVisibleDamageCanStillLowerTheRate() {
        Replay(1).use { r ->
            r.tick(4, 1)
            r.tick(4, 2, dropped = 1)
            r.tick(4, 2, dropped = 1)
            assertTrue(r.host.reports.isEmpty())
            assertEquals(listOf(18000), r.host.writes)
            r.tick(100, 0)
            assertEquals(18000, r.host.reports.single().currentBitrate)
        }
    }

    @Test fun legacyHostAdjustmentAlreadyAppliedAtReplyCannotWinOverQueuedManualSelection() {
        Replay(1).use { r ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            r.host.respond = {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                r.host.setBitrate(14000) // Legacy host changes the encoder before replying.
                AbrAction(14000, "legacy loss")
            }
            r.sample(100, 10)
            r.worker.execute { r.service.tick() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            r.service.setSuspended(true)
            assertTrue(r.service.setManualBitrate(35000) { assertTrue(it) })
            release.countDown()
            r.drain()
            assertEquals(listOf(14000, 20000, 35000), r.host.writes)
            assertEquals(35000, r.service.currentBitrate)
        }
    }

    @Test fun lostLegacyReplyDuringSuspensionRestoresPreviousForegroundRate() {
        Replay(1).use { r ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            r.host.respond = {
                r.host.setBitrate(14000)
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                null
            }
            r.sample(100, 10)
            r.worker.execute { r.service.tick() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            r.service.setSuspended(true)
            release.countDown()
            r.drain()
            assertEquals(listOf(14000, 20000), r.host.writes)
        }
    }

    @Test fun changingModeKeepsTheManuallySelectedRate() {
        Replay(2).use { r ->
            assertTrue(r.service.setManualBitrate(35000) { assertTrue(it) })
            r.service.setMode("quality")
            r.drain()
            r.tick(0, 0)
            assertEquals(listOf(35000), r.host.writes)
            assertEquals(35000, r.host.reports.single().currentBitrate)
        }
    }

    @Test fun turningAbrOffAndOnOrdersFinalRestoreBeforeNewManualWrite() {
        val worker = ScheduledThreadPoolExecutor(1)
        val host = FakeHost(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun service() = AdaptiveBitrateService({ host }, { null }, { _, _ -> },
            executor = worker, shutdownExecutorOnStop = false)
        try {
            val oldService = service()
            oldService.start(20000, "balanced")
            assertTrue(oldService.setManualBitrate(22000) { assertTrue(it) })
            worker.submit { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            oldService.stop()
            // The ordinary, ABR-disabled manual entry uses the same connection queue.
            worker.execute { host.setBitrate(30000) }
            val newService = service()
            newService.start(30000, "quality")
            assertTrue(newService.setManualBitrate(35000) { assertTrue(it) })
            release.countDown()
            worker.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(22000, 20000, 30000, 35000), host.writes)
            newService.stop()
        } finally {
            release.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun lateFeedbackCannotOverrideManualBitrateOrDecideWhileSuspended() {
        Replay(2).use { r ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            r.host.respond = { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); AbrAction(14000, "loss") }
            r.sample(100, 10)
            r.worker.execute { r.service.tick() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            r.service.setSuspended(true)
            assertTrue(r.service.setManualBitrate(35000) { assertTrue(it) })
            release.countDown()
            r.drain()
            val samples = r.samples
            r.tick(100, 10)
            assertEquals(samples, r.samples)
            assertEquals(listOf(35000), r.host.writes)
            assertEquals(35000, r.service.currentBitrate)
            r.host.respond = { null }
            r.service.setSuspended(false)
            r.tick(0, 0)
            assertEquals(2, r.resets)
            assertEquals(35000, r.host.reports.last().currentBitrate)
        }
    }
}
