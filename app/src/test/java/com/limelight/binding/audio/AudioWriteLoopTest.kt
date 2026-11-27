package com.limelight.binding.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioWriteLoopTest {
    @Test fun positiveShortWritesPreserveTheEntirePcmPayload() {
        val source = ByteArray(24) { it.toByte() }
        val sink = ArrayList<Byte>()
        val accepted = writeAudioFully(source.size, { true }) { offset, remaining ->
            val count = minOf(4, remaining)
            for (index in offset until offset + count) sink.add(source[index])
            count
        }
        assertEquals(source.size, accepted)
        assertArrayEquals(source, sink.toByteArray())
    }

    @Test fun zeroAndNegativeResultsDoNotSpinOrReplayAcceptedSamples() {
        for (failure in listOf(0, -6)) {
            val offsets = ArrayList<Int>()
            val accepted = writeAudioFully(24, { true }) { offset, _ ->
                offsets.add(offset)
                if (offset == 0) 8 else failure
            }
            assertEquals(8, accepted)
            assertEquals(listOf(0, 8), offsets)
        }
    }

    @Test fun trackReplacementOrPauseStopsBetweenWrites() {
        var active = true
        val accepted = writeAudioFully(24, { active }) { _, _ ->
            active = false
            8
        }
        assertEquals(8, accepted)
        assertEquals(0, writeAudioFully(24, { false }) { _, _ -> error("must not write") })
    }

    @Test fun invalidOverReportedWriteCannotAdvancePastThePayload() {
        assertEquals(0, writeAudioFully(24, { true }) { _, _ -> 25 })
        assertEquals(0, writeAudioFully(0, { true }) { _, _ -> error("empty payload") })
    }

    @Test fun multichannelBuffersContainWholeSampleFrames() {
        assertEquals(8192, alignedPcmBufferSize(4096, 8192, 2))
        assertEquals(8196, alignedPcmBufferSize(4096, 8192, 6))
        assertEquals(8192, alignedPcmBufferSize(4096, 8192, 8))
        assertEquals(8208, alignedPcmBufferSize(4096, 8192, 12))
        assertEquals(12000, alignedPcmBufferSize(12000, 8192, 12))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidRouteBufferSize() {
        alignedPcmBufferSize(-2, 8192, 8)
    }

    @Test fun failedInterruptAndFlushStillRetireTheOutput() {
        val gate = AudioWriteGate()
        val operations = ArrayList<String>()
        try {
            gate.pause({
                operations += "pause"
                throw IllegalStateException("fixture pause failure")
            }, {
                assertTrue(gate.paused)
                releaseAudioOutput(
                    flush = { operations += "flush"; throw IllegalStateException("fixture flush failure") },
                    stop = { operations += "stop"; throw IllegalStateException("fixture stop failure") },
                    release = { operations += "release" }
                )
            })
            fail("pause failure should remain observable")
        } catch (failure: IllegalStateException) {
            assertEquals("fixture pause failure", failure.message)
        }
        assertEquals(listOf("pause", "flush", "stop", "release"), operations)
    }

    @Test fun foregroundPlayFailureReopensOnceAndKeepsFailedOutputMutedUntilNextResume() {
        val gate = AudioWriteGate()
        var opens = 0
        var releases = 0
        var writable = false
        gate.startPlayback({ releases++; writable = false }) {
            opens++
            if (opens == 2) throw IllegalStateException("route still unavailable")
            writable = true
        }
        gate.pause({}, {})
        val failedPlay = { throw IllegalStateException("old track invalid") }
        gate.resumePlayback(failedPlay)
        assertTrue(gate.paused)
        assertFalse(writable)
        assertEquals(2, opens)
        assertEquals(2, releases) // old track and failed replacement
        var ready = false
        gate.resumePlayback(failedPlay) { ready = true }
        assertFalse(gate.paused)
        assertTrue(ready && writable)
        assertEquals(3, opens)
        gate.pause({}, {})
        gate.resumePlayback({})
        assertEquals(3, opens)
        gate.pause({}, {}, retire = true)
        gate.resumePlayback(failedPlay)
        assertTrue(gate.paused)
        assertEquals(3, opens)
    }

    @Test fun pauseAndRetirementDuringReopenInterruptAndReleaseTheCurrentTrack() {
        for (retire in listOf(false, true)) {
            val gate = AudioWriteGate()
            val current = AtomicInteger(0)
            val openingReplacement = CountDownLatch(1)
            val interrupted = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            val released = mutableListOf<Int>()
            val interrupts = mutableListOf<Int>()
            var opens = 0
            fun release() {
                val track = current.getAndSet(0)
                if (track != 0) released += track
            }
            try {
                gate.startPlayback(::release) {
                    if (++opens == 2) {
                        openingReplacement.countDown()
                        check(interrupted.await(2, TimeUnit.SECONDS))
                    }
                    current.set(opens)
                }
                gate.pause({}, {})
                val resume = executor.submit {
                    gate.resumePlayback({ error("route lost") }) { error("canceled start published") }
                }
                assertTrue(openingReplacement.await(2, TimeUnit.SECONDS))
                val pause = executor.submit {
                    gate.pause({
                        interrupts += current.get()
                        interrupted.countDown()
                    }, {
                        if (retire) release()
                    }, retire)
                }
                pause.get(2, TimeUnit.SECONDS)
                resume.get(2, TimeUnit.SECONDS)
                assertTrue(gate.paused)
                assertEquals(listOf(0, 2), interrupts)
                assertEquals(if (retire) listOf(1, 2) else listOf(1), released)
                gate.resumePlayback({ check(current.get() == 2) })
                assertEquals(retire, gate.paused)
                assertEquals(2, opens)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun stoppingDuringPlayFailurePreventsReopeningTheRetiredTrack() {
        val gate = AudioWriteGate()
        val inPlay = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var opens = 0
        try {
            gate.startPlayback({}) { opens++ }
            gate.pause({}, {})
            val resume = executor.submit {
                gate.resumePlayback({
                    inPlay.countDown()
                    check(stopped.await(2, TimeUnit.SECONDS))
                    error("track was retired")
                })
            }
            assertTrue(inPlay.await(2, TimeUnit.SECONDS))
            gate.pause({ stopped.countDown() }, {}, retire = true)
            resume.get(2, TimeUnit.SECONDS)
            assertTrue(gate.paused)
            assertEquals(1, opens)
        } finally { executor.shutdownNow() }
    }

    @Test fun pauseInterruptsPartialWritesBeforeFlushingAndForegroundStartsANewFrame() {
        for (next in listOf(3, 0, -6)) {
            val gate = AudioWriteGate()
            gate.startPlayback({}) {}
            val inside = java.util.concurrent.CountDownLatch(1)
            val interrupted = java.util.concurrent.CountDownLatch(1)
            val offsets = mutableListOf<Int>()
            var flushed = false
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val inFlight = executor.submit {
                    gate.frame {
                        writeAudioFully(8, { !gate.paused }) { offset, _ ->
                            offsets.add(offset)
                            if (offset == 0) 2 else {
                                inside.countDown()
                                check(interrupted.await(2, java.util.concurrent.TimeUnit.SECONDS))
                                next
                            }
                        }
                    }
                }
                check(inside.await(2, java.util.concurrent.TimeUnit.SECONDS))
                gate.pause({ interrupted.countDown() }, { flushed = true })
                inFlight.get(2, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(listOf(0, 2), offsets)
                gate.frame { assertEquals(0, writeAudioFully(8, { !gate.paused }) { _, _ -> error("background write") }) }
                gate.resumePlayback({ check(flushed) })
                gate.frame { assertEquals(8, writeAudioFully(8, { !gate.paused }) { offset, size ->
                    assertEquals(0, offset)
                    size
                }) }
            } finally { executor.shutdownNow() }
        }
    }
}
