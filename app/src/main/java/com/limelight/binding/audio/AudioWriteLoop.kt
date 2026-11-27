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
    private sealed interface State {
        object Playing : State
        object Paused : State
        object Retired : State
        class Starting : State
        class Pausing : State
    }

    private val writer = Any()
    private val intent = Any()
    @Volatile private var state: State = State.Playing
    private class Output(val open: () -> Unit, val release: () -> Unit)
    private var output: Output? = null

    val paused: Boolean get() = state !== State.Playing
    inline fun frame(work: () -> Unit) = synchronized(writer) { work() }

    private fun change(expected: State, next: State): Boolean = synchronized(intent) {
        if (state !== expected) return@synchronized false
        state = next
        true
    }

    fun pause(interrupt: () -> Unit, afterWriter: () -> Unit, retire: Boolean = false) {
        val pausing = State.Pausing()
        val interruptedStart = synchronized(intent) {
            val starting = state is State.Starting
            if (state !== State.Retired) state = if (retire) State.Retired else pausing
            starting
        }
        try {
            interrupt()
        } finally {
            synchronized(writer) {
                if (retire) output = null
                try {
                    // A HAL open/play already in flight may have published a different
                    // playing track since the lock-free interrupt. Interrupt that one too.
                    if (interruptedStart) audioCleanup("interrupt resumed track", interrupt)
                    afterWriter()
                } finally {
                    change(pausing, State.Paused)
                }
            }
        }
    }

    fun startPlayback(release: () -> Unit, open: () -> Unit) = synchronized(writer) {
        val starting = State.Starting()
        synchronized(intent) { state = starting }
        output = null
        try {
            open()
            output = Output(open, release)
            change(starting, State.Playing)
        } finally {
            change(starting, State.Paused)
        }
    }

    fun resumePlayback(play: () -> Unit, onReady: () -> Unit = {}) {
        val starting = State.Starting()
        if (!change(State.Paused, starting)) return
        synchronized(writer) {
            try {
                val previous = output ?: return
                if (state !== starting) return
                if (!audioCleanup("resume", play)) {
                    if (state !== starting) return
                    previous.release()
                    if (state !== starting) return
                    if (!audioCleanup("reopen on the current route", previous.open)) {
                        previous.release()
                        return
                    }
                }
                if (state === starting) {
                    onReady()
                    change(starting, State.Playing)
                }
            } finally {
                change(starting, State.Paused)
            }
        }
    }
}
