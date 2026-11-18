package com.limelight.binding.audio

import android.content.Context
import android.os.Build
import com.limelight.LimeLog
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge

/** Only authenticated mat-live-v1 carrier cycles enter this sink. */
class MatPassthroughRenderer(private val context: Context) : AudioRenderer, MatCarrierConsumer {
    @Volatile private var session: MatPlaybackSession? = null
    override fun setup(audioConfiguration: MoonBridge.AudioConfiguration, sampleRate: Int,
                       samplesPerFrame: Int, codec: Int, bitrate: Int): Int {
        if (codec != MoonBridge.AUDIO_CODEC_MAT_LIVE || sampleRate != 48000 || samplesPerFrame != 960) return -1
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            LimeLog.warning("MAT IEC61937 output requires Android 7 or newer")
            return -1
        }
        return try {
            session = MatPlaybackSession(MatAudioTrackSink(context))
            LimeLog.info("MAT live output prepared: logical 48k/960, IEC61937 192k/8ch; awaiting authenticated payload")
            0
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            cleanup()
            LimeLog.severe("MAT IEC setup failed: $e")
            -1
        }
    }
    override fun start() {}
    override fun stop() { session?.close() }
    override fun cleanup() {
        session?.let { current ->
            current.close()
            val player = current.player()
            LimeLog.info("MAT IEC final state=${player.state()} received=${player.receivedCycles()} written=${player.writtenCycles()} submittedBytes=${player.submittedBytes()} SHA256=${player.submittedSha256()} peakQueue=${player.peakQueue()} error=${player.error()}")
            LimeLog.info("MAT IEC startup: firstPayloadNanos=${player.firstPayloadNanos()} playNanos=${player.playNanos()} firstObservedHeadNanos=${player.firstPlayedNanos()} primedCarrierFrames=${player.primedFrames()}")
        }
        session = null
    }
    override fun playDecodedAudio(audioData: ShortArray) { error("MAT must not enter PCM callback") }
    override fun playEncodedAudio(audioData: ByteArray, length: Int) { error("MAT must not enter AC3 callback") }
    override fun offerMatCycle(bytes: ByteArray, length: Int, epoch: Long, domain: Long, index: Long, pts48k: Long): Boolean {
        val current = session ?: return false
        val accepted = current.offerMatCycle(bytes, length, epoch, domain, index, pts48k)
        if (accepted && index == 0L) {
            LimeLog.info("MAT IEC first payload wirePrefix=${MatCarrierPlayer.hex(bytes.copyOfRange(8, 20))}; Atmos metadata not verified")
        }
        if (!accepted) LimeLog.severe("MAT IEC cycle rejected: index=$index state=${current.player().state()} error=${current.player().error()}")
        return accepted
    }
    override fun matPlaybackProgress(epoch: Long, domain: Long): Int =
        session?.matPlaybackProgress(epoch, domain) ?: -1
    override fun endMatStream(cycles: Long): Boolean {
        val current = session ?: return false
        val ok = current.endMatStream(cycles)
        LimeLog.info("MAT IEC EOS drained=$ok submittedBytes=${current.player().submittedBytes()} SHA256=${current.player().submittedSha256()} error=${current.player().error()}")
        return ok
    }
}
