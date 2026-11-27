package com.limelight.binding.audio

import com.limelight.LimeLog

/**
 * Streaming, bounded AC3-to-IEC61937 packer. No decoding or gain changes.
 * Supports normal-rate 48 kHz AC3 only; E-AC3 has a different burst format.
 * The consumer must finish using the reusable word buffer before returning.
 */
class Ac3Iec61937Packetizer {
    private val frame = ByteArray(3840)
    private val burst = ShortArray(BURST_WORDS)
    private var used = 0
    private var expected = 0

    fun interface Sink {
        fun write(words: ShortArray)
    }

    private var backgroundBytes = 0
    private var seekingSync = false

    /** Finish parsing a compressed frame which began in the background. */
    fun discardPartialFrame() { backgroundBytes = used }

    fun reset() {
        used = 0
        backgroundBytes = 0
        expected = 0
        seekingSync = false
    }

    fun packetLost() {
        reset()
        seekingSync = true
    }

    /** Retain a possible start of the next AC3 header, including a split sync word. */
    private fun seekNextHeader() {
        var start = 1
        while (start < used) {
            if (frame[start] == 0x0b.toByte() && (start + 1 == used || frame[start + 1] == 0x77.toByte())) break
            start++
        }
        used -= start
        backgroundBytes = (backgroundBytes - start).coerceAtLeast(0)
        if (used != 0) System.arraycopy(frame, start, frame, 0, used)
        seekingSync = true
    }

    /** Handles partial frames and multiple frames in one native callback. */
    fun append(bytes: ByteArray, length: Int, tolerateDamage: Boolean = false, sink: Sink) {
        if (length < 0 || length > bytes.size) {
            reset()
            throw IllegalArgumentException("Invalid AC3 callback length")
        }
        var offset = 0
        try {
            while (offset < length) {
                val target = if (expected == 0) 6 else expected
                val count = minOf(target - used, length - offset)
                System.arraycopy(bytes, offset, frame, used, count)
                used += count
                offset += count
                if (expected == 0 && used == 6) {
                    val fscod = (frame[4].toInt() and 255) ushr 6
                    val frmsizecod = frame[4].toInt() and 63
                    val bsid = (frame[5].toInt() and 255) ushr 3
                    val sync = frame[0] == 0x0b.toByte() && frame[1] == 0x77.toByte()
                    if (!sync || fscod != 0 || frmsizecod > 37 || bsid > 8) {
                        if (!tolerateDamage && !seekingSync && backgroundBytes == 0) {
                            throw IllegalArgumentException("Invalid 48 kHz AC3 frame header")
                        }
                        if (!seekingSync) LimeLog.warning("IEC61937 discarded an invalid background AC3 header")
                        seekNextHeader()
                        continue
                    }
                    seekingSync = false
                    expected = BITRATES[frmsizecod / 2] * 4
                }
                if (expected != 0 && used == expected) {
                    if (backgroundBytes != 0) {
                        reset()
                        continue
                    }
                    burst.fill(0)
                    burst[0] = 0xf872.toShort()
                    burst[1] = 0x4e1f.toShort()
                    burst[2] = (1 or ((frame[5].toInt() and 7) shl 8)).toShort() // AC3 + bsmod
                    burst[3] = (expected * 8).toShort() // payload size in bits
                    for (i in 0 until expected step 2) {
                        burst[4 + i / 2] = (((frame[i].toInt() and 255) shl 8) or
                            (frame[i + 1].toInt() and 255)).toShort()
                    }
                    // AudioTrack's short[] API writes these numeric words in native order.
                    sink.write(burst)
                    reset()
                }
            }
        } catch (e: RuntimeException) {
            reset()
            throw e
        }
    }

    companion object {
        const val SAMPLES_PER_FRAME = 1536
        const val BURST_BYTES = 6144
        private const val BURST_WORDS = BURST_BYTES / 2
        private val BITRATES = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 576, 640)
    }
}
