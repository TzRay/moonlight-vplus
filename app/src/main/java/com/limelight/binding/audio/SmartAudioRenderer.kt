package com.limelight.binding.audio

import android.content.Context

import com.limelight.LimeLog
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge

/**
 * Composite renderer that picks between [AndroidAudioRenderer] (Opus / PCM)
 * and [Ac3PassthroughRenderer] (AC3 / E-AC3 bit-perfect) based on the
 * negotiated codec reported by the native layer in [setup].
 *
 * The non-chosen child is never initialized, so this is essentially a free
 * dispatcher with no behavioral overhead vs. the legacy direct usage.
 */
class SmartAudioRenderer(
    private val context: Context,
    private val enableAudioFx: Boolean,
    private val enableSpatializer: Boolean,
    private val passthroughBufferBytes: Int = 16 * 1024,
    private val enableSystemAudioHaptics: Boolean = false,
    private val onSystemAudioHapticsActiveChanged: (Boolean) -> Unit = {},
    private val onAudioPresentationClock: (framePosition: Long, systemNanoTime: Long, sampleRate: Int) -> Unit = { _, _, _ -> },
    private val useAc3Iec61937: Boolean = false
) : AudioRenderer, MatCarrierConsumer {

    @Volatile private var delegate: AudioRenderer? = null
    private var backgroundPaused = false
    private var stopped = false
    private var matSession = 0L
    private val legacyBackgroundReported = java.util.concurrent.atomic.AtomicBoolean()

    @Synchronized private fun install(output: AudioRenderer) {
        stopped = false
        matSession = if (output is MatPassthroughRenderer) MoonBridge.getMatBackgroundSession() else 0L
        if (backgroundPaused && matSession != 0L) MoonBridge.setMatBackgroundPaused(matSession, true)
        if (backgroundPaused) pauseOutput(output)
        delegate = output
    }

    private fun pauseOutput(output: AudioRenderer?) {
        when (output) {
            is MatPassthroughRenderer -> if (matSession == 0L && legacyBackgroundReported.compareAndSet(false, true)) {
                LimeLog.warning("MAT background pause was not negotiated. Keeping the MAT stream active for fast resume.")
            }
            is AndroidAudioRenderer -> output.pauseProcessing()
            is PcmPassthroughRenderer -> output.pauseProcessing()
            is Ac3PassthroughRenderer -> output.pauseProcessing()
            is Ac3Iec61937Renderer -> output.pauseProcessing()
        }
    }

    override fun setup(
        audioConfiguration: MoonBridge.AudioConfiguration,
        sampleRate: Int,
        samplesPerFrame: Int,
        codec: Int,
        bitrate: Int
    ): Int {
        onAudioPresentationClock(-1L, -1L, 0)
        if (codec == MoonBridge.AUDIO_CODEC_MAT_LIVE) {
            val mat = MatPassthroughRenderer(context)
            val result = mat.setup(audioConfiguration, sampleRate, samplesPerFrame, codec, bitrate)
            if (result == 0) install(mat) else mat.cleanup()
            return result
        }
        // Route by negotiated codec:
        //   PCM_S16  -> PcmPassthroughRenderer (raw LPCM, lowest latency)
        //   AC3/EAC3 -> Ac3PassthroughRenderer (encoded bitstream to AVR)
        //   OPUS     -> AndroidAudioRenderer (decode + render)
        //
        // The user-facing 'audio passthrough' toggle is enforced upstream
        // in Game.setupAudio by forcing audioCodec=OPUS when disabled, so
        // we don't need to reinspect that preference here.
        if (codec == MoonBridge.AUDIO_CODEC_PCM_S16) {
            val pcmThru = PcmPassthroughRenderer(
                context,
                passthroughBufferBytes,
                enableSystemAudioHaptics,
                onSystemAudioHapticsActiveChanged
            )
            val res = pcmThru.setup(audioConfiguration, sampleRate, samplesPerFrame, codec, bitrate)
            if (res == 0) {
                LimeLog.info("SmartAudioRenderer: using PcmPassthroughRenderer")
                install(pcmThru)
                return 0
            }
            LimeLog.warning("SmartAudioRenderer: PcmPassthroughRenderer setup failed ($res)")
            pcmThru.cleanup()
            return res
        }

        if (codec != MoonBridge.AUDIO_CODEC_OPUS) {
            if (useAc3Iec61937 && codec == MoonBridge.AUDIO_CODEC_AC3) {
                val iec = Ac3Iec61937Renderer(passthroughBufferBytes)
                val res = iec.setup(audioConfiguration, sampleRate, samplesPerFrame, codec, bitrate)
                if (res == 0) install(iec) else iec.cleanup()
                return res
            }
            val passthrough = Ac3PassthroughRenderer(context, passthroughBufferBytes)
            val res = passthrough.setup(audioConfiguration, sampleRate, samplesPerFrame, codec, bitrate)
            if (res == 0) {
                LimeLog.info("SmartAudioRenderer: using Ac3PassthroughRenderer (codec=$codec)")
                install(passthrough)
                return 0
            }
            LimeLog.warning("SmartAudioRenderer: Ac3PassthroughRenderer setup failed ($res); native side already negotiated $codec, no PCM fallback available")
            passthrough.cleanup()
            return res
        }

        val pcm = AndroidAudioRenderer(
            context,
            enableAudioFx,
            enableSpatializer,
            enableSystemAudioHaptics,
            onSystemAudioHapticsActiveChanged,
            onAudioPresentationClock
        )
        return setupOpus(pcm, audioConfiguration, sampleRate, samplesPerFrame, bitrate)
    }

    internal fun setupOpus(pcm: AndroidAudioRenderer, configuration: MoonBridge.AudioConfiguration,
                           sampleRate: Int, samplesPerFrame: Int, bitrate: Int): Int {
        val res = pcm.setup(configuration, sampleRate, samplesPerFrame, MoonBridge.AUDIO_CODEC_OPUS, bitrate)
        if (res == 0) install(pcm) else pcm.cleanup()
        return res
    }

    override fun start() {
        delegate?.start()
    }

    override fun stop() {
        val output = synchronized(this) {
            stopped = true
            delegate
        }
        onAudioPresentationClock(-1L, -1L, 0)
        output?.stop()
    }

    override fun playDecodedAudio(audioData: ShortArray) {
        delegate?.playDecodedAudio(audioData)
    }

    override fun playEncodedAudio(audioData: ByteArray, length: Int) {
        delegate?.playEncodedAudio(audioData, length)
    }
    override fun matPlaybackProgress(epoch: Long, domain: Long): Int =
        (delegate as? MatCarrierConsumer)?.matPlaybackProgress(epoch, domain) ?: -1

    override fun offerMatCycle(bytes: ByteArray, length: Int, epoch: Long, domain: Long, index: Long, pts48k: Long): Boolean =
        (delegate as? MatCarrierConsumer)?.offerMatCycle(bytes, length, epoch, domain, index, pts48k) ?: false
    override fun endMatStream(cycles: Long): Boolean = (delegate as? MatCarrierConsumer)?.endMatStream(cycles) ?: false

    override fun cleanup() {
        val output = synchronized(this) {
            val current = delegate
            delegate = null
            stopped = true
            current
        }
        onAudioPresentationClock(-1L, -1L, 0)
        output?.cleanup()
    }

    fun pauseProcessing() {
        val pauseOpus = synchronized(this) {
            if (backgroundPaused) return
            backgroundPaused = true
            if (matSession != 0L) MoonBridge.setMatBackgroundPaused(matSession, true)
            if (stopped) return
            val output = delegate
            if (output is AndroidAudioRenderer) output.requestPause() else {
                pauseOutput(output)
                return
            }
        }
        pauseOpus?.invoke()
    }

    fun resumeProcessing() {
        val startOpus = synchronized(this) {
            val output = delegate
            if (backgroundPaused) {
                backgroundPaused = false
                // Match the connection even if the suspended MAT renderer has closed.
                if (matSession != 0L) MoonBridge.setMatBackgroundPaused(matSession, false)
            } else if (output !is AndroidAudioRenderer) {
                return
            }
            if (stopped) return
            when (output) {
                is AndroidAudioRenderer -> output.requestResume()
                is PcmPassthroughRenderer -> { output.resumeProcessing(); return }
                is Ac3PassthroughRenderer -> { output.resumeProcessing(); return }
                is Ac3Iec61937Renderer -> { output.resumeProcessing(); return }
                else -> return
            }
        }
        startOpus?.invoke()
    }

    fun detachSystemAudioHaptics(): Boolean = when (val current = delegate) {
        is AndroidAudioRenderer -> current.detachSystemAudioHaptics()
        is PcmPassthroughRenderer -> current.detachSystemAudioHaptics()
        else -> false
    }

    fun attachSystemAudioHaptics(): Boolean = when (val current = delegate) {
        is AndroidAudioRenderer -> current.attachSystemAudioHaptics()
        is PcmPassthroughRenderer -> current.attachSystemAudioHaptics()
        else -> false
    }
}
