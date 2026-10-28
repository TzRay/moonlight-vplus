package com.limelight.binding.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPresentationClockTest {
    private data class Update(val frame: Long, val time: Long, val rate: Int)

    @Test fun oldShortWriteCannotInvalidateTheRebuiltTrackClock() {
        val updates = ArrayList<Update>()
        val clock = AudioPresentationClock { frame, time, rate -> updates.add(Update(frame, time, rate)) }
        val oldTrack = Any()
        val newTrack = Any()
        clock.reset(oldTrack, 0L)
        val writeStarted = CountDownLatch(1)
        val completeOldWrite = CountDownLatch(1)
        val audioThread = Executors.newSingleThreadExecutor()
        try {
            val oldWrite = audioThread.submit {
                writeStarted.countDown()
                check(completeOldWrite.await(5, TimeUnit.SECONDS))
                clock.invalidate(oldTrack)
            }
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
            clock.clear()
            clock.reset(newTrack, 240L)
            updates.clear()
            completeOldWrite.countDown()
            oldWrite.get(5, TimeUnit.SECONDS)
            clock.publish(newTrack, 10L, 1000L, 48000)
            assertEquals(listOf(Update(250L, 1000L, 48000)), updates)
        } finally {
            completeOldWrite.countDown()
            audioThread.shutdownNow()
        }
    }

    @Test fun currentTrackLossSuppressesTimestampsUntilRebuild() {
        val updates = ArrayList<Update>()
        val clock = AudioPresentationClock { frame, time, rate -> updates.add(Update(frame, time, rate)) }
        val track = Any()
        clock.reset(track, 240L)
        clock.publish(track, 10L, 1000L, 48000)
        clock.invalidate(track)
        clock.publish(track, 20L, 2000L, 48000)
        val rebuiltTrack = Any()
        clock.reset(rebuiltTrack, 480L)
        clock.publish(rebuiltTrack, 5L, 3000L, 48000)
        assertEquals(listOf(
            Update(250L, 1000L, 48000),
            Update(-1L, -1L, 0),
            Update(485L, 3000L, 48000)
        ), updates)
    }

    @Test fun delayedTimestampsCannotPublishAfterClearOrTrackReplacement() {
        val updates = ArrayList<Update>()
        val clock = AudioPresentationClock { frame, time, rate -> updates.add(Update(frame, time, rate)) }
        val oldTrack = Any()
        clock.reset(oldTrack, 0L)
        clock.clear()
        clock.publish(oldTrack, 10L, 1000L, 48000)
        val newTrack = Any()
        clock.reset(newTrack, 240L)
        clock.publish(oldTrack, 20L, 2000L, 48000)
        clock.publish(newTrack, 5L, 3000L, 48000)
        assertEquals(listOf(Update(-1L, -1L, 0), Update(245L, 3000L, 48000)), updates)
    }
}
