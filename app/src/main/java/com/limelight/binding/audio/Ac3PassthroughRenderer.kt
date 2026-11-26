package com.limelight.binding.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build

import com.limelight.LimeLog
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge

/**
 * Bit-perfect AC3 / E-AC3 passthrough renderer.
 *
 * Hands the encoded bitstream straight to the platform [AudioTrack] in
 * [AudioFormat.ENCODING_AC3] / [AudioFormat.ENCODING_E_AC3] mode. The OS /
 * receiver then decodes (or further forwards via SPDIF / HDMI to an AVR).
 *
 * Implementation cribbed from Kodi's `AESinkAUDIOTRACK.cpp` which is the
 * battle-tested reference for raw AC3/EAC3 passthrough on Android:
 *   - capability probed via [AudioTrack.getMinBufferSize] (works since API 1
 *     unlike `isDirectPlaybackSupported` which is API 29+ and known to lie
 *     on many Android TV firmwares),
 *   - `AudioAttributes` set to USAGE_MEDIA + CONTENT_TYPE_MUSIC so the
 *     framework routes to the HDMI/SPDIF passthrough sink instead of the
 *     internal speaker mixer (USAGE_GAME breaks routing on many TVs),
 *   - channel mask uses CHANNEL_OUT_5POINT1 for 6-channel passthrough when
 *     the platform accepts it. Some Android TV firmwares require this for
 *     E-AC3/DDP HDMI routing, while others only accept STEREO, so setup
 *     falls back to STEREO if the 5.1 probe is rejected.
 *   - no offloaded playback (Kodi avoids it for raw PT; offload semantics
 *     for compressed bitstreams are inconsistent across vendors and can
 *     drop frames silently).
 *
 * Only [setup] for non-Opus codecs succeeds; Opus must use [AndroidAudioRenderer].
 */
class Ac3PassthroughRenderer(
    private val context: Context,
    private val bufferBytes: Int = 16 * 1024
) : AudioRenderer {

    private val writes = AudioWriteGate()
    @Volatile private var track: AudioTrack? = null
    private var encoding: Int = 0
    private var codecName: String = ""

    override fun setup(
        audioConfiguration: MoonBridge.AudioConfiguration,
        sampleRate: Int,
        samplesPerFrame: Int,
        codec: Int,
        bitrate: Int
    ): Int {
        if (codec == MoonBridge.AUDIO_CODEC_OPUS) {
            return -1
        }

        encoding = when (codec) {
            MoonBridge.AUDIO_CODEC_AC3 -> {
                codecName = "AC3"
                AudioFormat.ENCODING_AC3
            }
            MoonBridge.AUDIO_CODEC_EAC3 -> {
                codecName = "E-AC3"
                AudioFormat.ENCODING_E_AC3
            }
            else -> {
                LimeLog.severe("Ac3PassthroughRenderer: unknown codec=$codec")
                return -1
            }
        }

        // Reject configs the encoder couldn't handle.
        if (audioConfiguration.channelCount !in 1..6) {
            LimeLog.severe("Ac3PassthroughRenderer: unsupported channels=${audioConfiguration.channelCount}")
            return -1
        }
        val channelMasks = if (audioConfiguration.channelCount == 6) {
            intArrayOf(AudioFormat.CHANNEL_OUT_5POINT1, AudioFormat.CHANNEL_OUT_STEREO)
        } else {
            intArrayOf(AudioFormat.CHANNEL_OUT_STEREO)
        }

        // USAGE_MEDIA + CONTENT_TYPE_MUSIC matches what Kodi sends; this is
        // what causes the HDMI/SPDIF route on every Android TV firmware
        // we've seen. USAGE_GAME would route to the internal mixer.
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        // Real capability probe: getMinBufferSize returns < 0 / ERROR_BAD_VALUE
        // when the framework has no codec/route able to consume this encoding.
        // This is the exact check Kodi uses (VerifySinkConfiguration).
        var channelMask = 0
        var channelMaskName = ""
        var minBuffer = -1
        for (candidateMask in channelMasks) {
            val candidateMinBuffer = try {
                AudioTrack.getMinBufferSize(sampleRate, candidateMask, encoding)
            } catch (e: Throwable) {
                LimeLog.warning("Ac3PassthroughRenderer: getMinBufferSize threw for ${channelMaskToString(candidateMask)}: ${e.message}")
                -1
            }

            if (candidateMinBuffer > 0) {
                channelMask = candidateMask
                channelMaskName = channelMaskToString(candidateMask)
                minBuffer = candidateMinBuffer
                break
            }

            LimeLog.warning("Ac3PassthroughRenderer: $codecName ${channelMaskToString(candidateMask)} mask not supported on this device/route (minBuffer=$candidateMinBuffer)")
        }
        if (minBuffer <= 0) {
            LimeLog.warning("Ac3PassthroughRenderer: $codecName not supported on this device/route (minBuffer=$minBuffer)")
            return -1
        }

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        // Buffer sizing — game streaming is latency-critical, so we deviate
        // from Kodi's HTPC-oriented values:
        //   Kodi AC3:  max(minBuffer * 3, frame * 8)   ≈ 256 ms
        //   Kodi EAC3: 2 * 10752                       ≈ 443 ms
        // We target ~4 frames (~128 ms) which is enough headroom for one
        // typical AVR HDMI decode round-trip + jitter while keeping audio
        // delay below human-perceptible-during-gaming threshold.
        //
        // The user-tunable bufferBytes pref now acts as a CEILING (let the
        // user trade latency for stability) rather than a hard FLOOR (which
        // forced everyone to ≥200 ms regardless of preference).
        val frameBytes = if (bitrate > 0) bitrate * 1536 / sampleRate / 8 else 1536
        val targetFrames = 4
        val computedBuffer = maxOf(minBuffer, frameBytes * targetFrames)
        // Apply the user pref as a ceiling, but never less than computedBuffer
        // (so the framework's own minimum is always respected) and never
        // above 16 frames (~512 ms — diminishing returns past that).
        val bufferSize = computedBuffer
            .coerceAtLeast(bufferBytes.coerceAtMost(frameBytes * 16))

        try {
            val builder = AudioTrack.Builder()
                .setAudioFormat(format)
                .setAudioAttributes(attributes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
            // Hint the framework to pick a low-latency audio path. May be
            // ignored for compressed encodings on some vendor stacks, but
            // it's harmless and helps where it does work.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            track = builder.build()

            if (track!!.state != AudioTrack.STATE_INITIALIZED) {
                LimeLog.severe("Ac3PassthroughRenderer: AudioTrack state=${track!!.state}, not INITIALIZED")
                track?.release()
                track = null
                return -2
            }

            // Match Kodi's idle-then-play sequencing: pause+flush before
            // play() makes some TV firmwares more willing to accept the
            // first frame without a startup glitch.
            track!!.pause()
            track!!.flush()
            writes.resume { track!!.play() }

            val bufferMs = if (frameBytes > 0) bufferSize.toLong() * 32 / frameBytes else 0
            LimeLog.info("Ac3PassthroughRenderer: $codecName initialized @${sampleRate} Hz, ${audioConfiguration.channelCount}ch (mask=$channelMaskName), bitrate=$bitrate bps, buffer=${bufferSize}B (~${bufferMs} ms, min=$minBuffer, frame=$frameBytes)")
            return 0
        } catch (e: Exception) {
            LimeLog.severe("Ac3PassthroughRenderer: AudioTrack create failed: ${e.message}")
            try {
                track?.release()
            } catch (_: Exception) {}
            track = null
            return -2
        }
    }

    override fun start() {
        // play() already called in setup(); nothing additional.
    }

    fun pauseProcessing() {
        writes.pause({ interruptAudioWrite(track) }, { audioCleanup("flush") { track?.flush() } })
    }

    fun resumeProcessing() {
        writes.resume { track?.play() }
    }

    override fun stop() {
        try { pauseProcessing() } catch (_: Exception) {}
    }

    override fun playDecodedAudio(audioData: ShortArray) {}

    override fun playEncodedAudio(audioData: ByteArray, length: Int) = writes.frame {
        val output = track ?: return@frame
        if (writes.paused || length !in 1..audioData.size) return@frame
        try {
            // Sunshine sends one complete AC3/EAC3 access unit per RTP payload.
            val written = writeAudioFully(length, { !writes.paused }) { offset, remaining ->
                output.write(audioData, offset, remaining, AudioTrack.WRITE_BLOCKING)
            }
            if (written != length && !writes.paused) LimeLog.warning("AC3 write accepted $written/$length bytes")
        } catch (e: Exception) {
            LimeLog.warning("Ac3PassthroughRenderer.write failed: ${e.message}")
        }
    }

    override fun cleanup() {
        val output = track
        writes.pause({ interruptAudioWrite(output) }, {
            track = null
            releaseAudioOutput(flush = { output?.flush() }, release = { output?.release() }, stop = { output?.stop() })
        })
    }

    private fun channelMaskToString(channelMask: Int): String {
        return when (channelMask) {
            AudioFormat.CHANNEL_OUT_5POINT1 -> "5.1"
            AudioFormat.CHANNEL_OUT_STEREO -> "STEREO"
            else -> "0x${channelMask.toString(16)}"
        }
    }
}
