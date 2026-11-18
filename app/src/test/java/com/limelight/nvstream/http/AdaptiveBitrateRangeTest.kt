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
        assertEquals(null, sampler.sample(packets(0xffffffffL, 0xfffffffeL), 10, 4, 1000, rtt))
        val first = sampler.sample(packets(1, 98), 65, 5, 2000, rtt)!!
        assertEquals(AdaptiveBitrateService.AbrStats(2f, 9, 55f, 1), first)
        assertEquals(null, sampler.sample(packets(1, 98), 70, 5, 2500, rtt))
        assertEquals(AdaptiveBitrateService.AbrStats(0f, 9, 30f, 2),
            sampler.sample(packets(1, 148), 95, 7, 3000, rtt))
    }

    @Test
    fun ordinaryPresetsKeepTheirExistingRanges() {
        assertEquals(5000..15_000, AdaptiveBitrateService.bitrateRange(10_000, "quality"))
        assertEquals(3000..20_000, AdaptiveBitrateService.bitrateRange(10_000, "balanced"))
        assertEquals(2000..12_000, AdaptiveBitrateService.bitrateRange(10_000, "lowLatency"))
    }
}
