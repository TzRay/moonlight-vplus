package com.limelight.nvstream.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBitrateRangeTest {
    @Test
    fun everySelectableBaselineHasValidBoundsContainingItsStartingValue() {
        for (mode in listOf("quality", "balanced", "lowLatency")) {
            for (baseline in 500..800_000 step 500) {
                val range = AdaptiveBitrateService.bitrateRange(baseline, mode)
                assertTrue("$mode/$baseline must remain adjustable", range.first <= range.last)
                assertTrue("$mode/$baseline must contain the user's starting value", baseline in range)
                assertTrue(range.first >= 500 && range.last <= 800_000)
            }
        }
    }

    @Test
    fun telemetryUsesPacketDeltasAndActualDecoderOutputAcrossCounterWrapAndIdle() {
        fun packets(missing: Long, expected: Long) = (missing shl 32) or expected
        val sampler = AdaptiveBitrateService.StatsSampler()
        val rtt = 9L shl 32
        fun sample(p: Long, n: Int, lost: Long, ms: Long, gaps: Long = 0, generation: Int = 1) =
            sampler.sample(p, n, lost, ms, rtt, gaps, n, generation)
        assertEquals(null, sample(packets(0xffffffffL, 0xfffffffeL), 10, 4, 1000))
        val first = sample(packets(1, 98), 65, 5, 2000)!!
        assertEquals(AbrPacketSample(100, 2, 0, 55, true, true), first.packets)
        assertEquals(2f, first.packetLoss, 0f)
        val idle = sample(packets(1, 98), 70, 5, 2500)!!
        assertEquals(0L, idle.packets.expected)
        assertTrue(!idle.packets.trusted)
        val resumed = sample(packets(1, 148), 95, 7, 3000, 1)!!
        assertEquals(50f, resumed.decodeFps, 0f)
        assertEquals(2, resumed.droppedFrames)
        assertEquals(AbrPacketSample(50, 0, 1, 25, false, false), resumed.packets)
        assertEquals(null, sample(packets(1, 148), 96, 7, 3500, 1, 2))
        sampler.reset()
        assertEquals(null, sample(packets(1, 148), 99, 7, 4000))
    }

    @Test
    fun ordinaryPresetsKeepTheirExistingRanges() {
        assertEquals(5000..15_000, AdaptiveBitrateService.bitrateRange(10_000, "quality"))
        assertEquals(3000..20_000, AdaptiveBitrateService.bitrateRange(10_000, "balanced"))
        assertEquals(2000..12_000, AdaptiveBitrateService.bitrateRange(10_000, "lowLatency"))
    }
}
