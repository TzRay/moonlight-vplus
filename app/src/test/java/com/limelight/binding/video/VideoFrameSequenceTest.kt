package com.limelight.binding.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFrameSequenceTest {
    @Test fun firstAcceptedFrameAfterRebuildRebasesOnceAndRepeatedIdrKeepsItsIdentity() {
        val sequence = VideoFrameSequence()
        assertEquals(0, sequence.accept(10))
        assertEquals(10, sequence.accept(13))
        sequence.reset()
        sequence.reset()
        // Rejected pre-IDR frames and failed PyroWave submissions never call accept.
        assertEquals(0, sequence.accept(300))
        assertEquals(300, sequence.accept(300))
        assertEquals(300, sequence.accept(304))
        assertEquals(2, sequence.generation)
    }
}
