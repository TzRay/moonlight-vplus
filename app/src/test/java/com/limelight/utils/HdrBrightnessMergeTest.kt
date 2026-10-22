package com.limelight.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证上游亮度覆盖逻辑不会破坏本地低亮度精度。 */
class HdrBrightnessMergeTest {
    @Test
    fun peakOverridePreservesFractionalMinimum() {
        val report = HdrBrightness.fromManual(500, 0.001f, 200)
        val result = HdrBrightness.forSession(report, 1500)!!

        assertEquals(0.001f, result.minNits, 0.000001f)
        assertEquals(1500f, result.maxNits, 0f)
        assertEquals(300f, result.maxFullFrameNits, 0f)
    }

    @Test
    fun peakOverrideRetainsUpstreamBounds() {
        val report = HdrBrightness.fromManual(500, 0.005f, 200)
        assertEquals(300f, HdrBrightness.forSession(report, 0)!!.maxNits, 0f)
        assertEquals(4000f, HdrBrightness.forSession(report, 10000)!!.maxNits, 0f)
    }

    @Test
    fun manualCalibrationRetainsConfiguredValuesInLaunchQuery() {
        val result = HdrBrightness.fromManual(1500, 0.001f, 600)
        assertEquals(
            "&minBrightness=0.001&maxBrightness=1500.0&maxAverageBrightness=600.0",
            result.toQueryParameters(),
        )
    }
}
