package com.limelight.binding.audio

/** Keeps clock validity and stream offsets with the AudioTrack that owns them. */
internal class AudioPresentationClock(
    private val onClock: (framePosition: Long, systemNanoTime: Long, sampleRate: Int) -> Unit
) {
    private var track: Any? = null
    private var streamFrameOffset = 0L
    private var valid = false

    @Synchronized
    fun reset(track: Any, streamFrameOffset: Long) {
        this.track = track
        this.streamFrameOffset = streamFrameOffset
        valid = true
    }

    @Synchronized
    fun invalidate(track: Any) {
        if (this.track !== track) return
        valid = false
        onClock(-1L, -1L, 0)
    }

    @Synchronized
    fun publish(track: Any, framePosition: Long, systemNanoTime: Long, sampleRate: Int) {
        if (this.track !== track || !valid) return
        onClock(streamFrameOffset + framePosition, systemNanoTime, sampleRate)
    }

    @Synchronized
    fun clear() {
        track = null
        valid = false
        onClock(-1L, -1L, 0)
    }
}
