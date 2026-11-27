package com.limelight.binding.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class Ac3Iec61937RendererTest {
    @Suppress("DEPRECATION")
    private class Track : AudioTrack(AudioManager.STREAM_MUSIC, 48000, AudioFormat.CHANNEL_OUT_STEREO,
        AudioFormat.ENCODING_IEC61937, 6144, AudioTrack.MODE_STREAM) {
        var onPlay: () -> Unit = {}
        var onPause: () -> Unit = {}
        @Volatile var playing = false
        var pauses = 0
        var flushes = 0
        var releases = 0
        val bursts = mutableListOf<Short>()
        var acceptWrites = true
        override fun getState() = STATE_INITIALIZED
        override fun play() { onPlay(); playing = true }
        override fun pause() { playing = false; pauses++; onPause() }
        override fun flush() { flushes++ }
        override fun release() { releases++ }
        override fun write(words: ShortArray, offset: Int, count: Int): Int {
            bursts += words[offset + 2]
            return if (acceptWrites) count else 0
        }
    }

    private val frame = ByteArray(128).apply {
        this[0] = 0x0b
        this[1] = 0x77
        this[5] = ((8 shl 3) or 1).toByte()
    }

    @Test fun partialBackgroundHeadersRecoverButAForegroundFormatErrorStillStopsPlayback() {
        for (n in 1..5) {
            val player = Ac3Iec61937Renderer(0)
            val track = Track()
            player.startOutput { track }
            player.pauseProcessing()
            player.playEncodedAudio(ByteArray(n), n)
            player.resumeProcessing()
            player.playEncodedAudio(frame, frame.size)
            assertEquals("background prefix of $n bytes", listOf(0x101.toShort()), track.bursts)

            val wrongRate = frame.clone().apply { this[4] = 0x40 }
            player.playEncodedAudio(wrongRate, wrongRate.size)
            player.playEncodedAudio(frame, frame.size)
            assertEquals(1, track.bursts.size)
            assertFalse(track.playing)
            player.cleanup()
        }
    }

    @Test fun lossDiscardsPartialInputAndFailedWriteStopsTheRealRenderer() {
        val player = Ac3Iec61937Renderer(0)
        val track = Track()
        player.startOutput { track }
        player.playEncodedAudio(frame, 30)
        player.playEncodedAudio(frame, 0)
        player.playEncodedAudio(frame, frame.size)
        assertEquals(listOf(0x101.toShort()), track.bursts)
        track.acceptWrites = false
        player.playEncodedAudio(frame, frame.size)
        player.playEncodedAudio(frame, frame.size)
        assertEquals(2, track.bursts.size)
        assertFalse(track.playing)
        player.cleanup()
    }

    @Test fun cleanupTwiceOnlyTouchesTheTrackOnce() {
        val player = Ac3Iec61937Renderer(0)
        val track = Track()
        player.startOutput { track }
        player.cleanup()
        val pauses = track.pauses
        val flushes = track.flushes
        player.cleanup()
        assertEquals(1, track.releases)
        assertEquals(pauses, track.pauses)
        assertEquals(flushes, track.flushes)
    }

    @Test fun stopInterruptsTheReplacementEvenWhenAnEarlierPauseHasNotReturned() {
        val old = Track()
        val replacement = Track()
        val player = Ac3Iec61937Renderer(0)
        var opens = 0
        player.startOutput { if (++opens == 1) old else replacement }
        player.pauseProcessing()
        old.onPlay = { error("old route lost") }

        val opening = CountDownLatch(1)
        val pauseEntered = CountDownLatch(1)
        val stopEntered = CountDownLatch(1)
        val finishPause = CountDownLatch(1)
        val pauser = AtomicReference<Thread>()
        val stopper = AtomicReference<Thread>()
        val pool = Executors.newFixedThreadPool(3)
        replacement.onPlay = {
            opening.countDown()
            check(stopEntered.await(3, TimeUnit.SECONDS))
        }
        replacement.onPause = {
            when (Thread.currentThread()) {
                pauser.get() -> { pauseEntered.countDown(); check(finishPause.await(3, TimeUnit.SECONDS)) }
                stopper.get() -> stopEntered.countDown()
            }
        }
        try {
            val foreground = pool.submit { player.resumeProcessing() }
            assertTrue(opening.await(3, TimeUnit.SECONDS))
            val background = pool.submit { pauser.set(Thread.currentThread()); player.pauseProcessing() }
            assertTrue(pauseEntered.await(3, TimeUnit.SECONDS))
            val stop = pool.submit { stopper.set(Thread.currentThread()); player.stop() }
            stop.get(3, TimeUnit.SECONDS)
            foreground.get(3, TimeUnit.SECONDS)
            assertFalse("replacement still playing after stop returned", replacement.playing)
            finishPause.countDown()
            background.get(3, TimeUnit.SECONDS)
            player.resumeProcessing()
            player.cleanup()
            player.cleanup()
            assertEquals(2, opens)
            assertEquals(1, old.releases)
            assertEquals(1, replacement.releases)
        } finally {
            finishPause.countDown()
            pool.shutdownNow()
        }
    }
}
