package com.limelight.binding.audio

import org.junit.Assert.*
import org.junit.Test

class AudioWriteLoopTest {
    @Test fun positiveShortWritesPreserveTheEntirePcmPayload() {
        val source = ByteArray(24) { it.toByte() }
        val sink = ArrayList<Byte>()
        val accepted = writeAudioFully(source.size, { true }) { offset, remaining ->
            val count = minOf(4, remaining)
            for (index in offset until offset + count) sink.add(source[index])
            count
        }
        assertEquals(source.size, accepted)
        assertArrayEquals(source, sink.toByteArray())
    }

    @Test fun zeroAndNegativeResultsDoNotSpinOrReplayAcceptedSamples() {
        for (failure in listOf(0, -6)) {
            val offsets = ArrayList<Int>()
            val accepted = writeAudioFully(24, { true }) { offset, _ ->
                offsets.add(offset)
                if (offset == 0) 8 else failure
            }
            assertEquals(8, accepted)
            assertEquals(listOf(0, 8), offsets)
        }
    }

    @Test fun trackReplacementOrPauseStopsBetweenWrites() {
        var active = true
        val accepted = writeAudioFully(24, { active }) { _, _ ->
            active = false
            8
        }
        assertEquals(8, accepted)
        assertEquals(0, writeAudioFully(24, { false }) { _, _ -> error("must not write") })
    }

    @Test fun invalidOverReportedWriteCannotAdvancePastThePayload() {
        assertEquals(0, writeAudioFully(24, { true }) { _, _ -> 25 })
        assertEquals(0, writeAudioFully(0, { true }) { _, _ -> error("empty payload") })
    }

    @Test fun multichannelBuffersContainWholeSampleFrames() {
        assertEquals(8192, alignedPcmBufferSize(4096, 8192, 2))
        assertEquals(8196, alignedPcmBufferSize(4096, 8192, 6))
        assertEquals(8192, alignedPcmBufferSize(4096, 8192, 8))
        assertEquals(8208, alignedPcmBufferSize(4096, 8192, 12))
        assertEquals(12000, alignedPcmBufferSize(12000, 8192, 12))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidRouteBufferSize() {
        alignedPcmBufferSize(-2, 8192, 8)
    }
}
