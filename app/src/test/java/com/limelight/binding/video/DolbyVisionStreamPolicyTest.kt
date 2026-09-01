package com.limelight.binding.video

import android.media.MediaCodecInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionStreamPolicyTest {
    @Test
    fun selectsUhd60For4k60() {
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd60,
            DolbyVisionStreamPolicy.levelFor(3840, 2160, 60),
        )
    }

    @Test
    fun rejectsStreamsAbovePublishedUhd60Limit() {
        assertNull(DolbyVisionStreamPolicy.levelFor(3840, 2160, 61))
        assertNull(DolbyVisionStreamPolicy.levelFor(4096, 2160, 60))
    }

    @Test
    fun detectsRpuWithThreeOrFourByteStartCode() {
        val threeByteStartCode = byteArrayOf(0, 0, 1, (62 shl 1).toByte(), 1, 2)
        val fourByteStartCode = byteArrayOf(0, 0, 0, 1, (62 shl 1).toByte(), 1, 2)

        assertTrue(DolbyVisionStreamPolicy.containsRpuNalUnit(threeByteStartCode, threeByteStartCode.size))
        assertTrue(DolbyVisionStreamPolicy.containsRpuNalUnit(fourByteStartCode, fourByteStartCode.size))
    }

    @Test
    fun doesNotMistakeRegularHevcNalForRpu() {
        val idr = byteArrayOf(0, 0, 0, 1, (19 shl 1).toByte(), 1, 2)

        assertFalse(DolbyVisionStreamPolicy.containsRpuNalUnit(idr, idr.size))
    }
}
