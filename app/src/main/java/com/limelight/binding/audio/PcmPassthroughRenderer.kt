package com.limelight.binding.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

import com.limelight.LimeLog
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge

/**
 * Raw PCM_S16 (LPCM) passthrough renderer.
 *
 * Hands little-endian interleaved 16-bit PCM directly to the platform mixer
 * via [AudioFormat.ENCODING_PCM_16BIT]. No codec block delay, no AVR decoder,
 * lowest possible audio latency at the cost of bandwidth (~1.5 Mbps stereo,
 * ~4.6 Mbps 5.1, ~9.2 Mbps 7.1.4).
 *
 * Server side guarantees 5 ms framing (240 samples / channel @ 48 kHz).
 */
class PcmPassthroughRenderer(
    private val context: Context,
    private val bufferBytes: Int = 8 * 1024,
    enableSystemAudioHaptics: Boolean = false,
    onSystemAudioHapticsActiveChanged: (Boolean) -> Unit = {}
) : AudioRenderer {

    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopped = true
    private var sampleFrameBytes = 0
    private val systemAudioHaptics = SystemAudioHapticsSession(
        enableSystemAudioHaptics,
        onSystemAudioHapticsActiveChanged
    )

    override fun setup(
        audioConfiguration: MoonBridge.AudioConfiguration,
        sampleRate: Int,
        samplesPerFrame: Int,
        codec: Int,
        bitrate: Int
    ): Int {
        if (codec != MoonBridge.AUDIO_CODEC_PCM_S16) {
            return -1
        }
        if (sampleRate <= 0 || samplesPerFrame <= 0) return -1
        sampleFrameBytes = audioConfiguration.channelCount * 2

        val channelMask = when (audioConfiguration.channelCount) {
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> CHANNEL_OUT_7POINT1_SURROUND
            12 -> CHANNEL_OUT_7POINT1POINT4
            else -> {
                LimeLog.severe("PcmPassthroughRenderer: unsupported channels=${audioConfiguration.channelCount}")
                return -1
            }
        }
        val channelMaskName = channelMaskToString(channelMask)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .also { systemAudioHaptics.configureAudioAttributes(it) }
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        try {
            // Use minBufferSize as floor; user-configurable cap on top to
            // bound jitter buffer / latency.
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) {
                LimeLog.warning("PcmPassthroughRenderer: PCM_S16 ${audioConfiguration.channelCount}ch ($channelMaskName) not supported on this device/route (minBuffer=$minBuf)")
                return -1
            }
            val bufferSize = alignedPcmBufferSize(minBuf, bufferBytes, audioConfiguration.channelCount)

            track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val builder = AudioTrack.Builder()
                    .setAudioFormat(format)
                    .setAudioAttributes(attributes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    } catch (_: Exception) {
                        // The hint is optional on vendor audio routes.
                    }
                }
                builder.build()
            } else {
                AudioTrack(attributes, format, bufferSize,
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
            }
            systemAudioHaptics.attach(track!!)
            track!!.play()
            stopped = false
            val frameBytes = audioConfiguration.channelCount * samplesPerFrame * 2
            val capacityMs = bufferSize * 1000.0 / (sampleRate * sampleFrameBytes)
            LimeLog.info("PcmPassthroughRenderer: PCM_S16 @${sampleRate} Hz, ${audioConfiguration.channelCount}ch (mask=$channelMaskName), bitrate=$bitrate bps, requestedBuffer=$bufferSize B ($capacityMs ms capacity, not measured latency), frame=$frameBytes B")
            return 0
        } catch (e: Exception) {
            LimeLog.severe("PcmPassthroughRenderer: AudioTrack create failed: ${e.message}")
            systemAudioHaptics.detach()
            try {
                track?.release()
            } catch (_: Exception) {}
            track = null
            return -2
        }
    }

    override fun start() {
        // play() already in setup().
    }

    override fun stop() {
        stopped = true
        try {
            systemAudioHaptics.detach()
            track?.pause()
            track?.flush()
        } catch (_: Exception) {}
    }

    fun detachSystemAudioHaptics(): Boolean {
        systemAudioHaptics.detach()
        return true
    }

    fun attachSystemAudioHaptics(): Boolean {
        val currentTrack = track ?: return false
        return systemAudioHaptics.attach(currentTrack)
    }

    override fun playDecodedAudio(audioData: ShortArray) {
        // Unused.
    }

    override fun playEncodedAudio(audioData: ByteArray, length: Int) {
        val t = track ?: return
        if (stopped || length <= 0 || length > audioData.size || sampleFrameBytes <= 0 || length % sampleFrameBytes != 0) return
        try {
            val written = writeAudioFully(length, { !stopped && track === t }) { offset, remaining ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    t.write(audioData, offset, remaining, AudioTrack.WRITE_BLOCKING)
                } else {
                    @Suppress("DEPRECATION")
                    t.write(audioData, offset, remaining)
                }
            }
            if (written != length && !stopped) LimeLog.warning("PcmPassthroughRenderer: accepted $written/$length bytes")
        } catch (e: Exception) {
            LimeLog.warning("PcmPassthroughRenderer.write failed: ${e.message}")
        }
    }

    override fun cleanup() {
        stopped = true
        try {
            systemAudioHaptics.close()
            track?.stop()
            track?.release()
        } catch (_: Exception) {}
        track = null
    }

    private fun channelMaskToString(channelMask: Int): String {
        return when (channelMask) {
            AudioFormat.CHANNEL_OUT_STEREO -> "STEREO"
            AudioFormat.CHANNEL_OUT_5POINT1 -> "5.1"
            CHANNEL_OUT_7POINT1_SURROUND -> "7.1"
            CHANNEL_OUT_7POINT1POINT4 -> "7.1.4"
            else -> "0x${channelMask.toString(16)}"
        }
    }

    private companion object {
        // Literal values avoid runtime field access on older Android releases.
        private const val CHANNEL_OUT_7POINT1_SURROUND = 0x000018fc
        private const val CHANNEL_OUT_7POINT1POINT4 = 0x000b58fc
    }
}
