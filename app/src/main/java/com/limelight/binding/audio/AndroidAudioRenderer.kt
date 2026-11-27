package com.limelight.binding.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.audiofx.AudioEffect
import android.os.Build

import com.limelight.LimeLog
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge
import java.util.concurrent.TimeUnit

class AndroidAudioRenderer(
    private val context: Context,
    private val enableAudioFx: Boolean,
    private val enableSpatializer: Boolean,
    enableSystemAudioHaptics: Boolean = false,
    onSystemAudioHapticsActiveChanged: (Boolean) -> Unit = {},
    onAudioPresentationClock: (framePosition: Long, systemNanoTime: Long, sampleRate: Int) -> Unit = { _, _, _ -> }
) : AudioRenderer {

    private sealed interface Phase {
        object Paused : Phase
        object Playing : Phase
        object Stopped : Phase
        class Opening(val automatic: Boolean = false, val retryDelayMs: Long = 250) : Phase
        class Retry(val deadline: Long, val delayMs: Long) : Phase
        class Pausing : Phase
        class Failed(val retry: Boolean) : Phase
    }

    // Platform calls may block. Intent can be cancelled and decoded frames can
    // advance independently until a fully started track is published.
    private val intent = Any()
    private val lifecycle = Any()
    private val decodedWriter = Any()
    @Volatile private var phase: Phase = Phase.Paused
    private var allowRouteRetry = true
    internal var monotonicNanos: () -> Long = System::nanoTime
    @Volatile private var track: AudioTrack? = null
    private val audioTimestamp = AudioTimestamp()
    private val systemAudioHaptics = SystemAudioHapticsSession(
        enableSystemAudioHaptics,
        onSystemAudioHapticsActiveChanged
    )

    // 保存当前的静音状态
    private var isMuted = false
    // 保存目标音量增益。默认 1.0f (100%)
    private var mTargetVolume = 1.0f

    // 保存初始化的配置参数，用于重建
    private var savedAudioConfig: MoonBridge.AudioConfiguration? = null
    private var savedSampleRate = 0
    private var savedSamplesPerFrame = 0
    private var savedChannelCount = 0
    private var decodedStreamFramePosition = 0L
    private val presentationClock = AudioPresentationClock(onAudioPresentationClock)

    internal var openTrack: (Int, Int, Int, Int) -> AudioTrack? = ::openConfiguredTrack

    private fun openConfiguredTrack(channelConfig: Int, sampleRate: Int, bytesPerFrame: Int, attempt: Int): AudioTrack? {
        val lowLatency = attempt < 2
        val bufferSize = when (attempt) {
            0, 2 -> bytesPerFrame * 2
            else -> {
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val rawSize = maxOf(minBuf, bytesPerFrame * 2)
                // Round to next frame
                ((rawSize + (bytesPerFrame - 1)) / bytesPerFrame) * bytesPerFrame
            }
        }

        // Skip low latency options if hardware sample rate doesn't match the content
        if (lowLatency && AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate) {
            return null
        }

        // Skip low latency options when using audio effects or spatializer
        if ((enableAudioFx || enableSpatializer) && lowLatency) {
            return null
        }

        return createAudioTrack(channelConfig, sampleRate, bufferSize, lowLatency).also {
            LimeLog.info("Audio track configuration: ${bufferSize} lowLatency=${lowLatency} spatializer=${enableSpatializer}")
        }
    }

    private fun createAudioTrack(channelConfig: Int, sampleRate: Int, bufferSize: Int, lowLatency: Boolean): AudioTrack {
        val attributesBuilder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
        systemAudioHaptics.configureAudioAttributes(attributesBuilder)

        // Enable spatialization attribute if supported and requested
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && enableSpatializer) {
            attributesBuilder.setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO)
        }

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Use FLAG_LOW_LATENCY on L through N
            if (lowLatency) {
                @Suppress("DEPRECATION")
                attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY)
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val trackBuilder = AudioTrack.Builder()
                .setAudioFormat(format)
                .setAudioAttributes(attributesBuilder.build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)

            // Use PERFORMANCE_MODE_LOW_LATENCY on O and later
            if (lowLatency) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            trackBuilder.build()
        } else {
            AudioTrack(
                attributesBuilder.build(),
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        }
    }

    private fun openOutput(opening: Phase.Opening, resume: Boolean): Int = synchronized(lifecycle) {
        try {
            if (phase !== opening) return -2
            releaseCurrentOutput()
            val config = savedAudioConfig ?: return -1
            return prepareOutput(config, savedSampleRate, savedSamplesPerFrame, opening, resume)
        } finally {
            synchronized(intent) {
                if (phase === opening) phase = if (resume) waitForRoute(opening.retryDelayMs) else Phase.Paused
            }
        }
    }

    private fun waitForRoute(delayMs: Long) =
        Phase.Retry(monotonicNanos() + TimeUnit.MILLISECONDS.toNanos(delayMs), delayMs)

    private fun prepareOutput(
        audioConfiguration: MoonBridge.AudioConfiguration,
        sampleRate: Int,
        samplesPerFrame: Int,
        opening: Phase.Opening,
        resume: Boolean
    ): Int {
        val channelConfig: Int
        val bytesPerFrame: Int

        when (audioConfiguration.channelCount) {
            2 -> channelConfig = AudioFormat.CHANNEL_OUT_STEREO
            4 -> channelConfig = AudioFormat.CHANNEL_OUT_QUAD
            6 -> channelConfig = AudioFormat.CHANNEL_OUT_5POINT1
            8 -> channelConfig = 0x000018fc // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            12 -> channelConfig = 0x000b58fc // AudioFormat.CHANNEL_OUT_7POINT1POINT4
            else -> {
                LimeLog.severe("Decoder returned unhandled channel count")
                return -1
            }
        }

        LimeLog.info("Audio channel config: " + String.format("0x%X", channelConfig))

        bytesPerFrame = audioConfiguration.channelCount * samplesPerFrame * 2

        for (i in 0 until 4) {
            if (phase !== opening) return -2
            var candidate: AudioTrack? = null
            try {
                val output = openTrack(channelConfig, sampleRate, bytesPerFrame, i) ?: continue
                candidate = output
                if (phase !== opening) return -2
                systemAudioHaptics.attach(output)
                if (resume) check(applyVolume(output) == AudioTrack.SUCCESS) { "AudioTrack volume restore failed" }
                if (phase !== opening) return -2
                output.play()
                if (phase !== opening) return -2
                logSpatializer(output)

                synchronized(decodedWriter) {
                    synchronized(intent) {
                        if (phase !== opening) return -2
                        // Dropped callbacks during open/play belong to the stream offset
                        // of this track. No writer can enter between reset and publication.
                        presentationClock.reset(output, decodedStreamFramePosition)
                        allowRouteRetry = !opening.automatic
                        track = output
                        phase = Phase.Playing
                        candidate = null
                    }
                }
                if (resume && enableAudioFx) audioEffect(output, true)
                return 0
            } catch (e: Exception) {
                LimeLog.warning("AudioTrack prepare failed: ${e.message}")
            } finally {
                candidate?.let { rejected ->
                    audioCleanup("detach haptics") { systemAudioHaptics.detach() }
                    audioCleanup("release") { rejected.release() }
                }
            }
        }
        return -2
    }

    private fun logSpatializer(output: AudioTrack) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && enableSpatializer) {
            audioCleanup("query spatializer") {
                val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val spatializer = manager.spatializer
                if (spatializer.isAvailable && spatializer.canBeSpatialized(output.audioAttributes, output.format)) {
                    LimeLog.info("Spatializer enabled: ${spatializer.isEnabled}, level: ${spatializer.immersiveAudioLevel}")
                } else {
                    LimeLog.info("Spatializer unavailable for this track")
                }
            }
        }
    }

    override fun setup(audioConfiguration: MoonBridge.AudioConfiguration, sampleRate: Int, samplesPerFrame: Int, codec: Int, bitrate: Int): Int {
        // This renderer only handles Opus PCM output. Non-Opus codecs (AC3 / E-AC3)
        // are routed through a dedicated passthrough renderer; refuse setup so the
        // caller can fall back appropriately.
        if (codec != MoonBridge.AUDIO_CODEC_OPUS) {
            LimeLog.warning("AndroidAudioRenderer: refusing non-Opus codec=$codec (use Ac3PassthroughRenderer)")
            return -1
        }
        val opening = requestOpen() ?: return -1
        presentationClock.clear()
        // 保存配置，供 resume 时使用
        this.savedAudioConfig = audioConfiguration
        this.savedSampleRate = sampleRate
        this.savedSamplesPerFrame = samplesPerFrame
        this.savedChannelCount = audioConfiguration.channelCount
        synchronized(decodedWriter) { decodedStreamFramePosition = 0L }
        return openOutput(opening, false)
    }

    private fun requestOpen(): Phase.Opening? = synchronized(intent) {
        if (phase !== Phase.Paused && phase !is Phase.Failed && phase !is Phase.Retry && phase !is Phase.Pausing) return null
        Phase.Opening().also { phase = it }
    }

    // Reserve this foreground request before releasing the dispatcher's lock.
    // A later background/stop request invalidates it even during a HAL call.
    internal fun requestResume(): (() -> Unit)? {
        val opening = requestOpen() ?: return null
        return { openOutput(opening, true) }
    }

    fun pauseProcessing() { requestPause()?.invoke() }
    internal fun requestPause(): (() -> Unit)? = requestSuspension(retire = false)
    fun resumeProcessing() { requestResume()?.invoke() }

    private fun requestSuspension(retire: Boolean): (() -> Unit)? {
        val pausing = Phase.Pausing()
        val output = synchronized(intent) {
            if (!retire && phase === Phase.Stopped) return null
            phase = if (retire) Phase.Stopped else pausing
            presentationClock.clear()
            track
        }
        return {
            interruptAudioWrite(output)
            synchronized(lifecycle) {
                if (retire || phase === pausing) {
                    releaseCurrentOutput()
                    synchronized(intent) { if (phase === pausing) phase = Phase.Paused }
                }
            }
        }
    }

    // The lifecycle owner releases the published track only after its writer exits.
    private fun releaseCurrentOutput() {
        val output = synchronized(decodedWriter) { track.also { track = null } } ?: return
        if (enableAudioFx) audioEffect(output, false)
        audioCleanup("detach haptics") { systemAudioHaptics.detach() }
        releaseAudioOutput(output::flush, output::release)
    }

    override fun playDecodedAudio(audioData: ShortArray) {
        val failed = synchronized(decodedWriter) { writeDecodedAudio(audioData) }
        if (failed == null) {
            // A resumed route may wake after the last Surface callback. Retained audio
            // drives sparse retries only while foreground, without a timer or polling a healthy track.
            val pending = phase as? Phase.Retry ?: return
            if (monotonicNanos() - pending.deadline < 0) return
            val next = synchronized(intent) {
                if (phase !== pending) return
                Phase.Opening(automatic = true, retryDelayMs = minOf(4000L, pending.delayMs * 2)).also { phase = it }
            }
            openOutput(next, true)
            return
        }
        synchronized(lifecycle) {
            if (phase !== failed) return
            releaseCurrentOutput()
            val next = synchronized(intent) {
                if (phase !== failed) return
                if (!failed.retry) { phase = waitForRoute(250); return }
                Phase.Opening(automatic = true).also { phase = it }
            }
            openOutput(next, true)
        }
    }

    private fun writeDecodedAudio(audioData: ShortArray): Phase.Failed? {
        val frameCount = if (savedChannelCount > 0) {
            audioData.size.toLong() / savedChannelCount
        } else {
            0L
        }
        val currentTrack = track
        if (phase !== Phase.Playing || currentTrack == null) {
            decodedStreamFramePosition += frameCount
            return null
        }

        var lostTrack = false
        val writtenSamples = try {
            writeAudioFully(audioData.size, { phase === Phase.Playing && track === currentTrack }) { offset, remaining ->
                currentTrack.write(audioData, offset, remaining).also {
                    if (it == AudioTrack.ERROR_DEAD_OBJECT || it == AudioTrack.ERROR_INVALID_OPERATION) lostTrack = true
                }
            }
        } catch (e: IllegalStateException) {
            LimeLog.warning("AudioTrack write interrupted: ${e.message}")
            lostTrack = true
            0
        }
        if (writtenSamples != audioData.size) {
            // Once samples are omitted, one constant track/stream offset no
            // longer describes the queued audio. Resume the clock on rebuild.
            presentationClock.invalidate(currentTrack)
        }
        if (writtenSamples == audioData.size && writtenSamples > 0 &&
            phase === Phase.Playing && track === currentTrack &&
            savedSampleRate > 0 && currentTrack.getTimestamp(audioTimestamp)) {
            presentationClock.publish(
                currentTrack,
                audioTimestamp.framePosition,
                audioTimestamp.nanoTime,
                savedSampleRate
            )
        }
        decodedStreamFramePosition += frameCount
        if (writtenSamples == audioData.size && writtenSamples > 0) allowRouteRetry = true
        if (!lostTrack) return null
        return synchronized(intent) {
            if (phase !== Phase.Playing || track !== currentTrack) return null
            Phase.Failed(allowRouteRetry).also { phase = it }
        }
    }

    private fun audioEffect(output: AudioTrack, open: Boolean) {
        audioCleanup(if (open) "open effects" else "close effects") {
            val intent = Intent(if (open) AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
                                else AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, output.audioSessionId)
            intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            if (open) intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME)
            context.sendBroadcast(intent)
        }
    }

    override fun start() {
        synchronized(lifecycle) {
            if (enableAudioFx && phase === Phase.Playing) track?.let { audioEffect(it, true) }
        }
    }

    override fun stop() { requestSuspension(retire = true)?.invoke() }

    fun detachSystemAudioHaptics(): Boolean = synchronized(lifecycle) {
        systemAudioHaptics.detach()
        true
    }

    fun attachSystemAudioHaptics(): Boolean = synchronized(lifecycle) {
        val output = track ?: return false
        phase === Phase.Playing && systemAudioHaptics.attach(output)
    }

    override fun cleanup() {
        stop()
        synchronized(lifecycle) { audioCleanup("close haptics") { systemAudioHaptics.close() } }
    }

    private fun applyVolume(output: AudioTrack): Int {
        val volume = if (isMuted) 0f else mTargetVolume
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            output.setVolume(volume)
        } else {
            @Suppress("DEPRECATION")
            output.setStereoVolume(volume, volume)
        }
    }

    fun setMuted(muted: Boolean) {
        synchronized(lifecycle) {
            if (isMuted == muted) return
            isMuted = muted
            track?.let { output -> audioCleanup("set volume") { applyVolume(output) } }
        }
    }
}
