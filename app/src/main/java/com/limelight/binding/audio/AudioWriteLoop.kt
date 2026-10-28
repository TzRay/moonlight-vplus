package com.limelight.binding.audio

/** Drain a blocking AudioTrack write without dropping a positive short write.
 * Stop on cancellation, zero progress, or error; never spin or replay a prefix.
 * Units are shorts for decoded PCM and bytes for passthrough PCM.
 */
internal inline fun writeAudioFully(
    length: Int,
    keepWriting: () -> Boolean,
    write: (offset: Int, remaining: Int) -> Int
): Int {
    require(length >= 0)
    var offset = 0
    while (offset < length && keepWriting()) {
        val written = write(offset, length - offset)
        if (written <= 0 || written > length - offset) break
        offset += written
    }
    return offset
}

/** AudioTrack sizes must contain whole interleaved sample frames. */
internal fun alignedPcmBufferSize(minimum: Int, requested: Int, channels: Int): Int {
    require(minimum > 0 && requested > 0 && channels in 1..12)
    val frameBytes = channels * 2L
    val size = maxOf(minimum, requested).toLong()
    val aligned = ((size + frameBytes - 1) / frameBytes) * frameBytes
    require(aligned <= Int.MAX_VALUE)
    return aligned.toInt()
}
