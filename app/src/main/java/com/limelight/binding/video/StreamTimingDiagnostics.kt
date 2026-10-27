package com.limelight.binding.video

/** Fixed storage, no per-sample allocations. Percentiles are bucket upper bounds. */
internal class StreamTimingHistogram {
    private val buckets = LongArray(UPPER_BOUNDS_US.size)
    var count = 0L
        private set
    var maxUs = 0L
        private set

    fun record(durationUs: Long) {
        if (durationUs < 0) return
        var low = 0
        var high = UPPER_BOUNDS_US.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (durationUs <= UPPER_BOUNDS_US[mid]) high = mid else low = mid + 1
        }
        buckets[low]++
        count++
        maxUs = maxOf(maxUs, durationUs)
    }

    fun percentileUpperBound(percent: Int): Long {
        require(percent in 1..100)
        if (count == 0L) return 0L
        val rank = (count * percent + 99) / 100
        var cumulative = 0L
        for (index in buckets.indices) {
            cumulative += buckets[index]
            if (cumulative >= rank) return minOf(UPPER_BOUNDS_US[index], maxUs)
        }
        return maxUs
    }

    fun reset() {
        buckets.fill(0)
        count = 0
        maxUs = 0
    }

    companion object {
        // Keep the native VideoTiming histogram boundaries in sync.
        private val UPPER_BOUNDS_US = longArrayOf(
            0, 50, 100, 250, 500, 1_000, 2_000, 4_000, 8_000, 12_000,
            16_000, 20_000, 25_000, 33_000, 50_000, 75_000, 100_000,
            200_000, 500_000, 1_000_000, Long.MAX_VALUE,
        )
    }
}

/**
 * Opt-in session diagnostics, independent of the overlay and debug/release builds.
 * Input acquisition and codec callbacks can run on different threads. Synchronization
 * also makes close() safe against late callbacks. No packet data is logged.
 */
internal class StreamTimingDiagnostics(private val log: (String) -> Unit) {
    private class Window(val name: String) {
        val histogram = StreamTimingHistogram()
        var startNs = 0L
        var endNs = 0L
    }

    private val input = Window("decoder_input_acquire")
    private val render = Window("codec_render_interval")
    private var lastRenderedNs = 0L
    private var closed = false

    @Synchronized
    fun recordInputAcquire(startNs: Long, endNs: Long) {
        if (closed || endNs < startNs) return
        record(input, (endNs - startNs) / 1_000, endNs)
    }

    @Synchronized
    fun recordRendered(renderTimeNs: Long) {
        if (closed || renderTimeNs <= 0 || renderTimeNs <= lastRenderedNs) return
        val previousNs = lastRenderedNs
        lastRenderedNs = renderTimeNs
        if (previousNs != 0L) {
            // Use the timestamp reported by MediaCodec, not the callback delivery
            // time or the requested releaseOutputBuffer deadline. Keep long stalls.
            // Older Android versions can omit callbacks: these are observed codec
            // timestamp gaps, not a complete display/drop-rate measurement.
            record(render, (renderTimeNs - previousNs) / 1_000, renderTimeNs)
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        report(input)
        report(render)
    }

    @Synchronized
    fun resumeAfterPause() {
        // cleanup() is also used for an explicit background pause. Do not turn
        // that intentional pause into a render stall or disable later diagnostics.
        lastRenderedNs = 0
        closed = false
    }

    private fun record(window: Window, durationUs: Long, endNs: Long) {
        if (window.histogram.count == 0L) window.startNs = endNs
        window.endNs = endNs
        window.histogram.record(durationUs)
        if (endNs - window.startNs >= REPORT_INTERVAL_NS) report(window)
    }

    private fun report(window: Window) {
        val histogram = window.histogram
        if (histogram.count == 0L) return
        log("SD[java] metric=${window.name} n=${histogram.count} " +
            "span_ms=${(window.endNs - window.startNs) / 1_000_000} " +
            "p95_us_le=${histogram.percentileUpperBound(95)} " +
            "p99_us_le=${histogram.percentileUpperBound(99)} max_us=${histogram.maxUs}")
        histogram.reset()
    }

    companion object {
        private const val REPORT_INTERVAL_NS = 10_000_000_000L
    }
}
