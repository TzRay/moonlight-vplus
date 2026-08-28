package com.limelight.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证上游亮度覆盖逻辑不会破坏本地低亮度精度。 */
class HdrBrightnessMergeTest {
    @Test
    fun peakOverridePreservesFractionalMinimum() {
        val result = HdrCapabilityHelper.applyBrightnessOverride(arrayOf<Number>(0.001f, 500, 200), 1500)

        assertEquals(0.001f, result[0].toFloat(), 0.000001f)
        assertEquals(1500, result[1].toInt())
        assertEquals(300, result[2].toInt())
    }

    @Test
    fun peakOverrideRetainsUpstreamBounds() {
        val range = arrayOf<Number>(0.005f, 500, 200)
        assertEquals(300, HdrCapabilityHelper.applyBrightnessOverride(range, 0)[1].toInt())
        assertEquals(4000, HdrCapabilityHelper.applyBrightnessOverride(range, 10000)[1].toInt())
    }

    @Test
    fun missingMinimumUsesFractionalDefault() {
        val result = HdrCapabilityHelper.applyBrightnessOverride(emptyArray<Number>(), 1000)
        assertEquals(0.001f, result[0].toFloat(), 0.000001f)
    }
}
