package com.limelight.binding.audio

import android.content.ContextWrapper
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import com.limelight.nvstream.jni.MoonBridge
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AndroidAudioRendererTest {
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private class Track(val onPlay: () -> Unit = {}) : AudioTrack(
        AudioManager.STREAM_MUSIC, 48000, AudioFormat.CHANNEL_OUT_STEREO,
        AudioFormat.ENCODING_PCM_16BIT, 960, MODE_STREAM
    ) {
        var plays = 0
        var releases = 0
        var writes = 0
        var writeResult: Int? = null
        var volumeResult = SUCCESS
        var onPause: () -> Unit = {}
        var onWrite: () -> Unit = {}
        var timestampQueries = 0
        override fun play() {
            check(++plays == 1) { "track already started" }
            onPlay()
        }
        override fun release() { releases++ }
        override fun pause() = onPause()
        override fun flush() {}
        override fun setVolume(gain: Float) = volumeResult
        @Suppress("DEPRECATION")
        override fun setStereoVolume(left: Float, right: Float) = volumeResult
        override fun write(data: ShortArray, offset: Int, count: Int): Int {
            check(releases == 0)
            onWrite()
            check(releases == 0)
            writes++
            return writeResult ?: count
        }
        override fun getTimestamp(timestamp: AudioTimestamp): Boolean {
            timestampQueries++
            timestamp.framePosition = 120
            timestamp.nanoTime = 1000000000L
            return true
        }
    }

    private val packet = ShortArray(480)
    private val positions = mutableListOf<Long>()
    private var onInvalidClock: () -> Unit = {}
    private val context = ContextWrapper(null)
    private val dispatcher = SmartAudioRenderer(context, false, false)
    private val opus = AndroidAudioRenderer(context, false, false, onAudioPresentationClock = { frame, _, hz ->
        if (hz == 48000) positions.add(frame) else onInvalidClock()
    })

    private fun setup(factory: () -> Track) {
        opus.openTrack = { _, _, _, _ -> factory() }
        assertEquals(0, dispatcher.setupOpus(opus, MoonBridge.AudioConfiguration(2, 3), 48000, 240, 0))
        dispatcher.start()
    }

    @Test fun successfulResumePlaysOnceAndRepeatedForegroundIsIdempotent() {
        val old = Track()
        val next = Track()
        var builds = 0
        setup { if (++builds == 1) old else next }
        dispatcher.pauseProcessing()
        dispatcher.resumeProcessing()
        dispatcher.resumeProcessing()
        dispatcher.playDecodedAudio(packet)
        assertEquals(1, next.plays)
        assertEquals(1, next.writes)
        assertEquals(2, builds)
        dispatcher.stop()
        dispatcher.cleanup()
        assertEquals(1, old.releases)
        assertEquals(1, next.releases)
    }

    @Test fun failedPlayKeepsWritesClosedAndNextForegroundRetriesOnTheCurrentRoute() {
        val old = Track()
        val next = Track()
        var failing = false
        val rejected = mutableListOf<Track>()
        setup { if (failing) Track { error("route lost") }.also(rejected::add) else old }
        dispatcher.pauseProcessing()
        failing = true
        dispatcher.resumeProcessing()
        dispatcher.playDecodedAudio(packet)
        assertTrue(rejected.isNotEmpty())
        assertTrue(rejected.all { it.releases == 1 && it.writes == 0 })
        assertTrue(positions.isEmpty())
        opus.openTrack = { _, _, _, _ -> next }
        dispatcher.resumeProcessing()
        dispatcher.playDecodedAudio(packet)
        assertEquals(1, next.writes)
        assertEquals(listOf(360L), positions)
        dispatcher.cleanup()
    }

    @Test fun packetDuringRebuildBelongsToTheNewPresentationOffset() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val next = Track { entered.countDown(); check(finish.await(3, TimeUnit.SECONDS)) }
        var opens = 0
        setup { if (++opens == 1) Track() else next }
        dispatcher.pauseProcessing()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val resume = pool.submit { dispatcher.resumeProcessing() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            dispatcher.playDecodedAudio(packet)
            assertEquals(0, next.writes)
            finish.countDown()
            resume.get(3, TimeUnit.SECONDS)
            dispatcher.playDecodedAudio(packet)
            assertEquals(listOf(360L), positions)
        } finally {
            finish.countDown()
            pool.shutdownNow()
            dispatcher.cleanup()
        }
    }

    @Test fun stoppedGenerationCannotPublishAnOutputThatFinishesOpeningLate() {
        val opening = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val stopRequested = CountDownLatch(1)
        val late = Track()
        var opens = 0
        setup {
            if (++opens == 1) Track() else {
                opening.countDown()
                check(finish.await(4, TimeUnit.SECONDS))
                late
            }
        }
        dispatcher.pauseProcessing()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val resume = pool.submit { dispatcher.resumeProcessing() }
            assertTrue(opening.await(2, TimeUnit.SECONDS))
            onInvalidClock = stopRequested::countDown
            val stop = pool.submit { dispatcher.stop() }
            assertTrue(stopRequested.await(2, TimeUnit.SECONDS))
            dispatcher.playDecodedAudio(packet)
            finish.countDown()
            resume.get(2, TimeUnit.SECONDS)
            stop.get(2, TimeUnit.SECONDS)
            dispatcher.resumeProcessing()
            dispatcher.playDecodedAudio(packet)
            assertEquals(0, late.plays)
            assertEquals(0, late.writes)
            assertEquals(1, late.releases)
            assertEquals(2, opens)
        } finally {
            finish.countDown()
            pool.shutdownNow()
            dispatcher.cleanup()
        }
    }

    @Test fun deadWriteRetiresTrackAndFailedReplacementDoesNotReopenEveryPacket() {
        val old = Track().apply { writeResult = AudioTrack.ERROR_DEAD_OBJECT }
        val broken = Track().apply { writeResult = AudioTrack.ERROR_INVALID_OPERATION }
        val working = Track()
        val tracks = listOf(old, broken, working)
        var opens = 0
        setup { tracks[opens++] }
        repeat(4) { dispatcher.playDecodedAudio(packet) }
        assertEquals(2, opens)
        assertEquals(listOf(1, 1), listOf(old.writes, broken.writes))
        assertEquals(listOf(1, 1), listOf(old.releases, broken.releases))
        dispatcher.resumeProcessing()
        dispatcher.playDecodedAudio(packet)
        assertEquals(listOf(1080L), positions) // Four omitted 240-frame packets, then track frame 120.
        assertEquals(1, working.writes)
        dispatcher.cleanup()
    }

    @Test fun stoppingABlockingWriteDoesNotReopenTheRetiredGeneration() {
        val writing = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val track = Track().apply {
            onWrite = { writing.countDown(); check(interrupted.await(3, TimeUnit.SECONDS)) }
            onPause = interrupted::countDown
            writeResult = AudioTrack.ERROR_DEAD_OBJECT
        }
        var opens = 0
        setup { opens++; track }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val writer = pool.submit { dispatcher.playDecodedAudio(packet) }
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            dispatcher.stop()
            writer.get(2, TimeUnit.SECONDS)
            dispatcher.start()
            dispatcher.resumeProcessing()
            dispatcher.playDecodedAudio(packet)
            assertEquals(1, track.writes)
            assertEquals(1, opens)
            assertEquals(1, track.releases)
        } finally {
            interrupted.countDown()
            pool.shutdownNow()
            dispatcher.cleanup()
        }
    }

    @Test fun deadObjectAfterAPositiveShortWriteDoesNotPublishItsTimestampOrReplayItsPrefix() {
        val partial = Track().apply {
            onWrite = { writeResult = if (writes == 0) 120 else AudioTrack.ERROR_DEAD_OBJECT }
        }
        val next = Track()
        var opens = 0
        setup { if (++opens == 1) partial else next }
        dispatcher.playDecodedAudio(packet)
        assertEquals(2, partial.writes)
        assertEquals(0, partial.timestampQueries)
        assertEquals(1, partial.releases)
        assertEquals(0, next.writes)
        dispatcher.playDecodedAudio(packet)
        assertEquals(1, next.writes)
        assertEquals(listOf(360L), positions)
        dispatcher.cleanup()
    }

    @Test fun failedVolumeRestoreNeverStartsAnUnmutedCandidate() {
        val first = Track()
        setup { first }
        dispatcher.pauseProcessing()
        opus.setMuted(true)
        val rejected = mutableListOf<Track>()
        opus.openTrack = { _, _, _, _ -> Track().apply { volumeResult = AudioTrack.ERROR }.also(rejected::add) }
        dispatcher.resumeProcessing()
        dispatcher.playDecodedAudio(packet)
        assertEquals(4, rejected.size)
        assertTrue(rejected.all { it.plays == 0 && it.writes == 0 && it.releases == 1 })
        val working = Track()
        opus.openTrack = { _, _, _, _ -> working }
        dispatcher.resumeProcessing()
        dispatcher.playDecodedAudio(packet)
        assertEquals(1, working.writes)
        dispatcher.cleanup()
    }

    @Test fun singleForegroundSignalRetriesOnTimedAudioPacketsAndNeverDuringBackground() {
        var now = 0L
        opus.monotonicNanos = { TimeUnit.MILLISECONDS.toNanos(now) }
        setup { Track() }
        dispatcher.pauseProcessing()
        var attempts = 0
        var routeReady = false
        val working = Track()
        opus.openTrack = { _, _, _, _ ->
            attempts++
            if (routeReady) working else Track { error("route still waking") }
        }
        dispatcher.resumeProcessing() // The only Activity/Surface signal for this foreground.
        assertEquals(4, attempts)
        now = 249
        repeat(20) { dispatcher.playDecodedAudio(packet) }
        assertEquals(4, attempts)
        now = 250
        dispatcher.playDecodedAudio(packet)
        assertEquals(8, attempts)
        now = 749
        dispatcher.playDecodedAudio(packet)
        assertEquals(8, attempts)
        now = 750
        routeReady = true
        dispatcher.playDecodedAudio(packet) // This packet advances the stream before scheduling open.
        dispatcher.playDecodedAudio(packet)
        assertEquals(9, attempts)
        assertEquals(1, working.writes)
        assertEquals(listOf(23L * 240 + 120), positions)

        dispatcher.pauseProcessing()
        routeReady = false
        dispatcher.resumeProcessing()
        val atFailure = attempts
        dispatcher.pauseProcessing()
        now += 5000
        repeat(20) { dispatcher.playDecodedAudio(packet) }
        assertEquals(atFailure, attempts)
        dispatcher.cleanup()
    }

    @Test fun failedFirstSetupRetiresTheUninstalledOpusRenderer() {
        val rejected = mutableListOf<Track>()
        opus.openTrack = { _, _, _, _ -> Track { error("initial route unavailable") }.also(rejected::add) }
        assertEquals(-2, dispatcher.setupOpus(opus, MoonBridge.AudioConfiguration(2, 3), 48000, 240, 0))
        assertTrue(rejected.all { it.releases == 1 })
        assertNull(opus.requestResume())
        dispatcher.playDecodedAudio(packet)
        assertTrue(rejected.all { it.writes == 0 })
    }

    @Test fun backgroundWaitingForAnOpeningTrackLeavesTheDispatcherAvailableForStop() {
        val opening = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val pauseRequested = CountDownLatch(1)
        val stopRequested = CountDownLatch(1)
        val late = Track()
        setup { Track() }
        dispatcher.pauseProcessing()
        opus.openTrack = { _, _, _, _ -> opening.countDown(); check(finish.await(4, TimeUnit.SECONDS)); late }
        onInvalidClock = {
            when (Thread.currentThread().name) {
                "background" -> pauseRequested.countDown()
                "stop" -> stopRequested.countDown()
            }
        }
        val pool = Executors.newFixedThreadPool(3)
        try {
            val openingCall = pool.submit { dispatcher.resumeProcessing() }
            assertTrue(opening.await(2, TimeUnit.SECONDS))
            val background = pool.submit { Thread.currentThread().name = "background"; dispatcher.pauseProcessing() }
            assertTrue(pauseRequested.await(2, TimeUnit.SECONDS))
            val stop = pool.submit { Thread.currentThread().name = "stop"; dispatcher.stop() }
            assertTrue("stop reached the child before HAL returned", stopRequested.await(2, TimeUnit.SECONDS))
            finish.countDown()
            for (call in listOf(openingCall, background, stop)) call.get(2, TimeUnit.SECONDS)
            assertEquals(0, late.plays)
            assertEquals(1, late.releases)
        } finally {
            finish.countDown()
            pool.shutdownNow()
            dispatcher.cleanup()
        }
    }
}
