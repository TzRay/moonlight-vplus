package com.limelight.binding.video

import org.junit.Assert.*
import org.junit.Test

class StreamTimingDiagnosticsTest {
    @Test
    fun percentilesRetainTailSamplesAndUseDocumentedUpperBounds() {
        val histogram = StreamTimingHistogram()
        repeat(94) { histogram.record(100) }
        repeat(5) { histogram.record(40_000) }
        histogram.record(2_000_000)
        assertEquals(100L, histogram.count)
        assertEquals(50_000L, histogram.percentileUpperBound(95))
        assertEquals(50_000L, histogram.percentileUpperBound(99))
        assertEquals(2_000_000L, histogram.percentileUpperBound(100))
        assertEquals(2_000_000L, histogram.maxUs)
        histogram.reset()
        assertEquals(0L, histogram.count)
        assertEquals(0L, histogram.percentileUpperBound(99))
    }

    @Test
    fun longRenderStallsAreNotFilteredOut() {
        val logs = mutableListOf<String>()
        val diagnostics = StreamTimingDiagnostics(logs::add)
        diagnostics.recordRendered(1_000_000_000L)
        diagnostics.recordRendered(1_016_667_000L)
        diagnostics.recordRendered(1_516_667_000L)
        diagnostics.close()
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("metric=codec_render_interval n=2 "))
        assertTrue(logs.single().contains("max_us=500000"))
    }

    @Test
    fun duplicateAndOutOfOrderCallbacksDoNotMoveThePreviousTimestamp() {
        val logs = mutableListOf<String>()
        val diagnostics = StreamTimingDiagnostics(logs::add)
        diagnostics.recordRendered(1_000_000_000L)
        diagnostics.recordRendered(1_020_000_000L)
        diagnostics.recordRendered(1_020_000_000L)
        diagnostics.recordRendered(1_010_000_000L)
        diagnostics.recordRendered(1_040_000_000L)
        diagnostics.close()
        assertTrue(logs.single().contains(" n=2 "))
        assertTrue(logs.single().contains("max_us=20000"))
    }

    @Test
    fun reportingIsBoundedAndCloseFlushesOnlyOnce() {
        val logs = mutableListOf<String>()
        val diagnostics = StreamTimingDiagnostics(logs::add)
        diagnostics.recordInputAcquire(1_000_000L, 1_100_000L)
        assertTrue(logs.isEmpty())
        diagnostics.recordInputAcquire(10_001_000_000L, 10_001_100_000L)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains(" n=2 "))
        diagnostics.recordInputAcquire(11_001_000_000L, 11_001_100_000L)
        diagnostics.close()
        diagnostics.close()
        diagnostics.recordInputAcquire(12_001_000_000L, 12_001_100_000L)
        diagnostics.recordRendered(12_001_100_000L)
        assertEquals(2, logs.size)
        assertTrue(logs.last().contains(" n=1 "))
    }

    @Test
    fun separateProducersAndASecondSessionDoNotLeakSamples() {
        val logs = mutableListOf<String>()
        val diagnostics = StreamTimingDiagnostics(logs::add)
        val input = Thread { repeat(1_000) { diagnostics.recordInputAcquire(it * 1_000L, it * 1_000L + 500L) } }
        val render = Thread { repeat(1_001) { diagnostics.recordRendered((it + 1) * 1_000L) } }
        input.start(); render.start()
        input.join(); render.join()
        diagnostics.close()
        assertEquals(2, logs.size)
        assertTrue(logs.all { it.contains(" n=1000 ") })
        StreamTimingDiagnostics(logs::add).close()
        assertEquals(2, logs.size)
    }

    @Test
    fun intentionalPauseDoesNotDisableDiagnosticsOrBecomeARenderStall() {
        val logs = mutableListOf<String>()
        val diagnostics = StreamTimingDiagnostics(logs::add)
        diagnostics.recordRendered(1_000_000L)
        diagnostics.recordRendered(21_000_000L)
        diagnostics.close()
        diagnostics.resumeAfterPause()
        diagnostics.recordRendered(60_000_000_000L)
        diagnostics.recordRendered(60_020_000_000L)
        diagnostics.close()
        assertEquals(2, logs.size)
        assertTrue(logs.all { it.contains(" n=1 ") && it.contains("max_us=20000") })
    }
}
