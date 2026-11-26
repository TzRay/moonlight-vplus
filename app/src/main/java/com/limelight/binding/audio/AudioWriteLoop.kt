package com.limelight.binding.audio

import android.media.AudioTrack
import com.limelight.LimeLog

/** One failed HAL operation must not skip later flush/release actions. */
internal inline fun audioCleanup(operation: String, work: () -> Unit): Boolean {
    return try {
        work()
        true
    } catch (e: Exception) {
        LimeLog.warning("AudioTrack $operation failed: ${e.message}")
        false
    }
}

internal fun releaseAudioOutput(flush: () -> Unit, release: () -> Unit, stop: (() -> Unit)? = null) {
    audioCleanup("flush", flush)
    if (stop != null) audioCleanup("stop", stop)
    audioCleanup("release", release)
}

internal fun interruptAudioWrite(track: AudioTrack?) {
    if (track != null && !audioCleanup("pause") { track.pause() }) {
        audioCleanup("stop after failed pause") { track.stop() }
    }
}

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

/** Serialize complete callback frames with flush/play. Pause interrupts a
 * blocking write before waiting for its owner to leave the current frame. */
internal class AudioWriteGate {
    private val writer = Any()
    @Volatile var paused = false
        private set
    inline fun frame(work: () -> Unit) = synchronized(writer) { work() }
    fun pause(interrupt: () -> Unit, afterWriter: () -> Unit) {
        paused = true
        try {
            interrupt()
        } finally {
            synchronized(writer) { afterWriter() }
        }
    }
    fun resume(beforeWriter: () -> Unit) = synchronized(writer) {
        beforeWriter()
        paused = false
    }
}
