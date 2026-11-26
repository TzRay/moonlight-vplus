package com.limelight.binding.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.limelight.LimeLog
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge

/** Opt-in AC3 carrier for devices whose raw ENCODING_AC3 route decodes to PCM. */
class Ac3Iec61937Renderer(private val encodedBufferBytes: Int) : AudioRenderer {
    private val writes = AudioWriteGate()
    @Volatile private var track: AudioTrack? = null
    private val packetizer = Ac3Iec61937Packetizer()
    @Volatile private var failed = false
    private val sink = Ac3Iec61937Packetizer.Sink { words ->
        if (writes.paused) return@Sink
        val output = track ?: return@Sink
        val written = writeAudioFully(words.size, { !writes.paused }) { offset, remaining ->
            output.write(words, offset, remaining)
        }
        check(writes.paused || written == words.size) { "IEC61937 incomplete burst ($written/${words.size})" }

    }

    override fun setup(audioConfiguration: MoonBridge.AudioConfiguration, sampleRate: Int,
                       samplesPerFrame: Int, codec: Int, bitrate: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            codec != MoonBridge.AUDIO_CODEC_AC3 || sampleRate != 48000 ||
            audioConfiguration.channelCount !in 1..6) {
            LimeLog.severe("IEC61937 compatibility mode requires Android 7+, 48 kHz AC3, 1-6 channels")
            return -1
        }
        cleanup()
        failed = false
        return try {
            val mask = AudioFormat.CHANNEL_OUT_STEREO // carrier, NOT content layout
            val min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_IEC61937)
            check(min > 0) { "IEC61937 unsupported on this route (min=$min)" }
            // Preferences are encoded bytes. Convert to whole 32 ms carrier bursts.
            val frameBytes = if (bitrate > 0) (bitrate.toLong() * Ac3Iec61937Packetizer.SAMPLES_PER_FRAME / sampleRate / 8).coerceIn(128, 2560).toInt() else 2560
            val preferredBursts = ((encodedBufferBytes.coerceAtLeast(0).toLong() + frameBytes - 1) / frameBytes).coerceIn(4, 16).toInt()
            val minBursts = (min.toLong() + Ac3Iec61937Packetizer.BURST_BYTES - 1) / Ac3Iec61937Packetizer.BURST_BYTES
            val buffer = maxOf(preferredBursts.toLong(), minBursts) * Ac3Iec61937Packetizer.BURST_BYTES
            check(buffer <= Int.MAX_VALUE)
            val builder = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_IEC61937)
                    .setSampleRate(sampleRate).setChannelMask(mask).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(buffer.toInt())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            track = builder.build()
            check(track!!.state == AudioTrack.STATE_INITIALIZED)
            track!!.pause()
            track!!.flush()
            writes.resume { track!!.play() }
            LimeLog.info("Ac3Iec61937Renderer: 48 kHz AC3 ${audioConfiguration.channelCount}ch; IEC61937 stereo carrier, buffer=$buffer bytes (${buffer / 192} ms)")
            0
        } catch (e: Exception) {
            LimeLog.severe("IEC61937 setup failed: ${e.message}; select raw AC3 to revert")
            cleanup()
            -2
        }
    }

    override fun start() {}
    override fun playDecodedAudio(audioData: ShortArray) {}
    override fun playEncodedAudio(audioData: ByteArray, length: Int) = writes.frame {
        if (failed || track == null) return@frame
        try {
            packetizer.append(audioData, length, sink)
        } catch (e: Exception) {
            // Do not resume halfway through a burst or silently switch to lossy PCM.
            failed = true
            LimeLog.severe("IEC61937 stopped: ${e.message}; reconnect or select raw AC3")
            stop()
        }
    }

    fun pauseProcessing() {
        writes.pause({ track?.pause() }, { track?.flush() })
    }

    fun resumeProcessing() {
        if (!failed) writes.resume {
            // A packetized frame may straddle two callbacks or the foreground edge.
            packetizer.discardPartialFrame()
            track?.play()
        }
    }

    override fun stop() {
        try { writes.pause({ track?.pause() }, { track?.flush(); packetizer.reset() }) } catch (_: Exception) {}
    }
    override fun cleanup() {
        stop()
        try { track?.release() } catch (_: Exception) {}
        track = null
    }
}
