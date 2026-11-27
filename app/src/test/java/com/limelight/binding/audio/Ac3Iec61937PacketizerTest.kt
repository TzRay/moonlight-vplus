package com.limelight.binding.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

class Ac3Iec61937PacketizerTest {
    private fun frame(code: Int, bytes: Int, bsmod: Int): ByteArray {
        val f = ByteArray(bytes)
        for (i in 6 until bytes) f[i] = (i * 17).toByte()
        f[0] = 0x0b
        f[1] = 0x77
        f[4] = code.toByte()
        f[5] = (0x40 or bsmod).toByte()
        return f
    }

    @Test fun packsPreamblePayloadAndPadding() {
        val f = frame(36, 2560, 3)
        val out = mutableListOf<ShortArray>()
        Ac3Iec61937Packetizer().append(f, f.size) { out.add(it.clone()) }
        val w = out[0]
        assertEquals(3072, w.size)
        assertEquals(0xf872, w[0].toInt() and 65535)
        assertEquals(0x4e1f, w[1].toInt() and 65535)
        assertEquals(0x301, w[2].toInt() and 65535)
        assertEquals(20480, w[3].toInt() and 65535)
        for (i in f.indices step 2) {
            assertEquals(((f[i].toInt() and 255) shl 8) or (f[i + 1].toInt() and 255), w[4 + i / 2].toInt() and 65535)
        }
        for (i in 4 + f.size / 2 until w.size) assertEquals(0, w[i].toInt())
    }

    @Test fun acceptsEveryPossibleSplitAndCoalescedFrames() {
        val f = frame(36, 2560, 0)
        for (split in 0..f.size) {
            val p = Ac3Iec61937Packetizer()
            val out = mutableListOf<ShortArray>()
            p.append(f.copyOfRange(0, split), split) { out.add(it.clone()) }
            p.append(f.copyOfRange(split, f.size), f.size - split) { out.add(it.clone()) }
            assertEquals(1, out.size)
        }
        val two = f + f
        val out = mutableListOf<ShortArray>()
        Ac3Iec61937Packetizer().append(two, two.size) { out.add(it.clone()) }
        assertEquals(2, out.size)
        assertArrayEquals(out[0], out[1])
    }

    @Test fun supportsAll48kFrameSizesAndClearsReusedBuffer() {
        val rates = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 576, 640)
        val p = Ac3Iec61937Packetizer()
        for (code in 37 downTo 0) {
            val f = frame(code, rates[code / 2] * 4, 0)
            val out = mutableListOf<ShortArray>()
            p.append(f, f.size) { out.add(it.clone()) }
            assertEquals(f.size * 8, out[0][3].toInt() and 65535)
            for (i in 4 + f.size / 2 until 3072) assertEquals(0, out[0][i].toInt())
        }
    }

    @Test fun rejectsInvalidHeadersAndCanReset() {
        for (variant in 0 until 4) {
            val f = frame(36, 2560, 0)
            when (variant) {
                0 -> f[0] = 0
                1 -> f[4] = 38
                2 -> f[4] = (36 or 64).toByte()
                3 -> f[5] = (16 shl 3).toByte()
            }
            val p = Ac3Iec61937Packetizer()
            assertThrows(IllegalArgumentException::class.java) { p.append(f, f.size) { fail() } }
            val good = frame(36, 2560, 0)
            val out = mutableListOf<ShortArray>()
            p.append(good, good.size) { out.add(it.clone()) }
            assertEquals(1, out.size)
        }
        val p = Ac3Iec61937Packetizer()
        val good = frame(36, 2560, 0)
        p.append(good, 10) { fail() }
        p.reset()
        val out = mutableListOf<ShortArray>()
        p.append(good, good.size) { out.add(it.clone()) }
        assertEquals(1, out.size)
        assertThrows(IllegalArgumentException::class.java) { p.append(good, -1) { fail() } }
        assertThrows(IllegalArgumentException::class.java) { p.append(good, 2561) { fail() } }
    }

    @Test fun writeFailureDiscardsPartialBurst() {
        val p = Ac3Iec61937Packetizer()
        val f = frame(36, 2560, 0)
        assertThrows(IllegalStateException::class.java) { p.append(f, f.size) { throw IllegalStateException() } }
        val out = mutableListOf<ShortArray>()
        p.append(f, f.size) { out.add(it.clone()) }
        assertEquals(1, out.size)
    }
    @Test fun backgroundDamageOrLossResynchronizesAtTheNextWholeForegroundFrame() {
        val old = frame(0, 128, 3)
        val fresh = frame(0, 128, 1)
        val p = Ac3Iec61937Packetizer()
        p.append(old, 32, tolerateDamage = true) { fail() }
        p.packetLost()
        p.append(ByteArray(17), 17, tolerateDamage = true) { fail() }
        p.discardPartialFrame()
        val garbageAndStart = byteArrayOf(0x22, 0x44, 0x33, 0x55, fresh[0])
        p.append(garbageAndStart, garbageAndStart.size) { fail() }
        val bursts = mutableListOf<ShortArray>()
        p.append(fresh.copyOfRange(1, fresh.size), fresh.size - 1) { bursts += it.clone() }
        assertEquals(0x101, bursts.single()[2].toInt())
        assertThrows(IllegalArgumentException::class.java) { p.append(ByteArray(6), 6) { fail() } }

        p.packetLost()
        p.append(ByteArray(3), 3, tolerateDamage = true) { fail() }
        p.discardPartialFrame()
        p.append(fresh, fresh.size) { bursts += it.clone() }
        assertEquals(2, bursts.size)

        p.append(ByteArray(23), 23, tolerateDamage = true) { fail() }
        p.append(old, 22, tolerateDamage = true) { fail() }
        p.discardPartialFrame()
        val tail = old.copyOfRange(22, old.size) + fresh
        p.append(tail, tail.size) { bursts += it.clone() }
        assertEquals(3, bursts.size)
        assertEquals(0x101, bursts.last()[2].toInt())
    }

    @Test fun resumeSkipsSplitBackgroundFrameAndProducesNextCompleteBurst() {
        val background = frame(36, 2560, 3)
        val foreground = frame(0, 128, 1)
        for (split in listOf(1, 6, 311, background.lastIndex)) {
            val packetizer = Ac3Iec61937Packetizer()
            packetizer.append(background, split) { fail() }
            packetizer.discardPartialFrame()
            val rest = background.copyOfRange(split, background.size) + foreground
            val bursts = mutableListOf<ShortArray>()
            packetizer.append(rest, rest.size) { bursts += it.clone() }
            assertEquals(1, bursts.size)
            assertEquals(0x101, bursts.single()[2].toInt())
            assertEquals(foreground.size * 8, bursts.single()[3].toInt())
        }
    }
}
