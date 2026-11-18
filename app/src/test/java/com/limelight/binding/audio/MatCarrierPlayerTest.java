package com.limelight.binding.audio;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Real player/session with a fake final audio device; no Dolby fixture or hardware. */
public class MatCarrierPlayerTest {
    private static class Sink implements MatCarrierPlayer.Sink {
        final ByteArrayOutputStream accepted = new ByteArrayOutputStream();
        final CountDownLatch entered = new CountDownLatch(1);
        volatile boolean zero, negative, invalidCount, headBlocked, closed, failStart, prepared, playing;
        volatile long bytes;
        int writes, playCalls;

        @Override public void prepare() throws Exception {
            if (failStart) throw new IllegalStateException("unsupported HDMI route");
            prepared = true;
        }
        @Override public void play() { playCalls++; playing = true; }

        @Override public int write(short[] words, int offset, int count) {
            entered.countDown();
            if (negative) return -6;
            if (invalidCount) return 1;
            if (zero || ++writes % 7 == 0) return 0;
            int n = Math.min(count, 512);
            for (int i = 0; i < n; i++) {
                accepted.write(words[offset + i] & 255);
                accepted.write((words[offset + i] >>> 8) & 255);
            }
            bytes += n * 2L;
            return n;
        }

        @Override public long playedFrames() { return headBlocked || !playing ? 0 : bytes / 16; }
        @Override public void close() { closed = true; }
    }
    /** Models a paused device whose actual buffer can split an authenticated cycle. */
    private static class PrimingSink extends Sink {
        final int capacityFrames;
        final boolean exposeThreshold;
        volatile long bytesAtPlay;
        final CountDownLatch firstCycleWritten = new CountDownLatch(1);
        PrimingSink(int capacityFrames, boolean exposeThreshold) {
            this.capacityFrames = capacityFrames; this.exposeThreshold = exposeThreshold;
        }
        @Override public long startThresholdFrames() { return exposeThreshold ? capacityFrames : 0; }
        @Override public void play() { bytesAtPlay = bytes; super.play(); }
        @Override public int write(short[] words, int offset, int count) {
            int available = playing ? count : (int) ((capacityFrames * 16L - bytes) / 2);
            int n = Math.min(count, available);
            for (int i = 0; i < n; i++) {
                accepted.write(words[offset + i] & 255);
                accepted.write((words[offset + i] >>> 8) & 255);
            }
            bytes += n * 2L;
            if (bytes >= MatCarrierPlayer.CYCLE_BYTES) firstCycleWritten.countDown();
            return n;
        }
    }

    private static byte[] carrier(int seed) {
        byte[] bytes = new byte[MatCarrierPlayer.CYCLE_BYTES];
        for (int i = 8; i < bytes.length; i++) bytes[i] = (byte)(i * 31 + seed);
        bytes[0] = 0x72; bytes[1] = (byte)0xf8; bytes[2] = 0x1f; bytes[3] = 0x4e;
        bytes[4] = 0x16; bytes[6] = 2;
        return bytes;
    }

    private static boolean offer(MatCarrierConsumer consumer, byte[] bytes, long index) {
        return consumer.offerMatCycle(bytes, bytes.length, 1, 2, index, index * 960);
    }

    @Test(timeout = 5000) public void recoveryProgressRequiresCurrentEpochAndAdvancingHead() throws Exception {
        Sink sink = new Sink(); sink.headBlocked = true;
        try (MatPlaybackSession session = new MatPlaybackSession(sink)) {
            assertEquals(0, session.matPlaybackProgress(1, 2));
            assertTrue(offer(session, carrier(8), 0));
            assertTrue(sink.entered.await(1, TimeUnit.SECONDS));
            assertEquals(0, session.matPlaybackProgress(1, 2));
            sink.headBlocked = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (session.matPlaybackProgress(1, 2) == 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(1, session.matPlaybackProgress(1, 2));
            assertEquals(0, session.matPlaybackProgress(3, 2));
            assertEquals(0, session.matPlaybackProgress(1, 3));
            session.close();
            assertEquals(0, session.matPlaybackProgress(1, 2));
        }
    }

    @Test public void stableRequiresThirtySecondsOfObservedAdvancesAfterEveryGap() {
        MatCarrierPlayer.PlaybackProgress progress = new MatCarrierPlayer.PlaybackProgress();
        long second = TimeUnit.SECONDS.toNanos(1);
        assertEquals(0, progress.report(0));
        for (int i = 0; i <= 30; i++) progress.advanced(i * second);
        assertEquals(3, progress.report(30 * second));
        long resume = 30 * second + TimeUnit.MILLISECONDS.toNanos(1230);
        assertEquals(0, progress.report(resume));
        for (int i = 0; i < 30; i++) {
            progress.advanced(resume + i * second);
            assertEquals(1, progress.report(resume + i * second));
        }
        progress.advanced(resume + 30 * second);
        assertEquals(3, progress.report(resume + 30 * second));
    }

    @Test(timeout = 6000) public void refillAfterIdleHeadDoesNotReportPlaying() throws Exception {
        Sink sink = new Sink() {
            @Override public long playedFrames() {
                return Math.min(super.playedFrames(), MatCarrierPlayer.CYCLE_BYTES / 16);
            }
        };
        try (MatPlaybackSession session = new MatPlaybackSession(sink)) {
            assertTrue(offer(session, carrier(8), 0));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (session.player().firstPlayedNanos() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(1, session.matPlaybackProgress(1, 2));
            Thread.sleep(1230);
            assertTrue(offer(session, carrier(9), 1));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (session.player().writtenCycles() < 2 && System.nanoTime() < deadline) Thread.sleep(2);
            assertEquals(2, session.player().writtenCycles());
            assertEquals(0, session.matPlaybackProgress(1, 2));
        }
    }

    @Test(timeout = 5000) public void copiesBorrowedCyclesAndPreservesEveryByteAcrossShortWrites() throws Exception {
        Sink sink = new Sink();
        byte[] input = carrier(7), expected = input.clone();
        try (MatPlaybackSession session = new MatPlaybackSession(sink)) {
            assertTrue(offer(session, input, 0));
            Arrays.fill(input, (byte)0);
            assertTrue(session.endMatStream(1));
            assertArrayEquals(expected, sink.accepted.toByteArray());
            assertEquals(expected.length, session.player().submittedBytes());
            assertEquals("", session.player().submittedSha256());
            assertEquals(MatCarrierPlayer.State.COMPLETED, session.player().state());
        }
        assertTrue(sink.closed);
    }

    @Test(timeout = 5000) public void rejectsMalformedCycleAndNonSequentialMetadata() throws Exception {
        for (int kind = 0; kind < 4; kind++) {
            Sink sink = new Sink();
            try (MatCarrierPlayer player = new MatCarrierPlayer(sink)) {
                player.start();
                assertTrue(player.awaitReady(1000));
                byte[] bytes = carrier(0);
                if (kind == 0) bytes[0] = 0;
                assertFalse(player.offerMatCycle(bytes, kind == 1 ? bytes.length - 1 : bytes.length,
                        1, 2, kind == 2 ? 1 : 0, kind == 3 ? 960 : 0));
                assertEquals(MatCarrierPlayer.State.FAILED, player.state());
            }
            assertTrue(sink.closed);
        }
    }

    @Test(timeout = 5000) public void doesNotMixDifferentSourceEpochs() throws Exception {
        try (MatCarrierPlayer player = new MatCarrierPlayer(new Sink())) {
            player.start();
            byte[] bytes = carrier(0);
            assertTrue(offer(player, bytes, 0));
            assertFalse(player.offerMatCycle(bytes, bytes.length, 3, 2, 1, 960));
            assertEquals(MatCarrierPlayer.State.FAILED, player.state());
        }
    }

    @Test(timeout = 5000) public void transientFullQueueWaitsForTheSinkWithoutLosingACycle() throws Exception {
        Sink sink = new Sink(); sink.zero = true;
        try (MatCarrierPlayer player = new MatCarrierPlayer(sink, 1, 1000, 1000)) {
            player.start();
            byte[] bytes = carrier(5);
            assertTrue(offer(player, bytes, 0));
            assertTrue(sink.entered.await(1, TimeUnit.SECONDS));
            assertTrue(offer(player, bytes, 1));
            boolean[] accepted = { false };
            Thread producer = new Thread(() -> accepted[0] = offer(player, bytes, 2));
            producer.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (producer.isAlive() && producer.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline)
                Thread.yield();
            assertEquals(Thread.State.TIMED_WAITING, producer.getState());
            sink.zero = false;
            producer.join(1000);
            assertFalse(producer.isAlive());
            assertTrue(accepted[0]);
            assertTrue(player.endMatStream(3));
            assertTrue(player.awaitCompletion(1000));
            assertEquals(3, player.writtenCycles());
            assertEquals(3L * MatCarrierPlayer.CYCLE_BYTES, player.submittedBytes());
            byte[] expected = new byte[3 * bytes.length];
            for (int i = 0; i < 3; i++) System.arraycopy(bytes, 0, expected, i * bytes.length, bytes.length);
            assertArrayEquals(expected, sink.accepted.toByteArray());
        }
    }

    @Test(timeout = 5000) public void queueOverflowFailsInsteadOfDroppingACycle() throws Exception {
        Sink sink = new Sink(); sink.zero = true;
        try (MatCarrierPlayer player = new MatCarrierPlayer(sink, 1, 1000, 1000)) {
            player.start();
            byte[] bytes = carrier(0);
            assertTrue(offer(player, bytes, 0));
            assertTrue(sink.entered.await(1, TimeUnit.SECONDS));
            assertTrue(offer(player, bytes, 1));
            assertFalse(offer(player, bytes, 2));
            assertFalse(player.awaitCompletion(1000));
            assertEquals(MatCarrierPlayer.State.FAILED, player.state());
        }
        assertTrue(sink.closed);
    }

    @Test(timeout = 5000) public void idleWriterCanDrainWhileProducerHoldsTheEnqueueMonitor() throws Exception {
        Thread[] outputThread = new Thread[1];
        Sink sink = new Sink() {
            @Override public void prepare() { outputThread[0] = Thread.currentThread(); }
        };
        try (MatCarrierPlayer player = new MatCarrierPlayer(sink, 1, 1000, 1000)) {
            byte[] input = carrier(9);
            // Hold the same monitor as offerMatCycle while the empty queue poll
            // expires. The consumer must remain able to drain a subsequent burst
            // when a producer waits for space with this monitor held.
            synchronized (player) {
                player.start();
                assertTrue(player.awaitReady(1000));
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
                while (outputThread[0].getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
                    Thread.sleep(1);
                assertTrue(offer(player, input, 0));
                assertTrue("Idle consumer blocked behind the producer", offer(player, input, 1));
                assertTrue(player.endMatStream(2));
            }
            assertTrue(player.awaitCompletion(1000));
            assertEquals(2, player.writtenCycles());
            byte[] expected = new byte[2 * input.length];
            System.arraycopy(input, 0, expected, 0, input.length);
            System.arraycopy(input, 0, expected, input.length, input.length);
            assertArrayEquals(expected, sink.accepted.toByteArray());
        }
        assertTrue(sink.closed);
    }

    @Test(timeout = 5000) public void zeroProgressNegativeAndMisalignedWritesFailAndReleaseTheSink() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            Sink sink = new Sink();
            sink.zero = kind == 0; sink.negative = kind == 1; sink.invalidCount = kind == 2;
            try (MatCarrierPlayer player = new MatCarrierPlayer(sink, 4, 30, 30)) {
                player.start();
                assertTrue(offer(player, carrier(0), 0));
                assertFalse(player.awaitCompletion(1000));
                assertEquals(MatCarrierPlayer.State.FAILED, player.state());
            }
            assertTrue(sink.closed);
        }
    }

    @Test(timeout = 5000) public void endWaitsForTheHardwarePlayheadBeforeClosing() throws Exception {
        Sink sink = new Sink(); sink.headBlocked = true;
        try (MatCarrierPlayer player = new MatCarrierPlayer(sink)) {
            player.start();
            assertTrue(offer(player, carrier(0), 0));
            assertTrue(player.endMatStream(1));
            assertFalse(player.awaitCompletion(50));
            assertFalse(sink.closed);
            sink.headBlocked = false;
            assertTrue(player.awaitCompletion(1000));
            assertEquals(MatCarrierPlayer.State.COMPLETED, player.state());
        }
        assertTrue(sink.closed);
    }

    @Test(timeout = 5000) public void stuckPlayheadCannotReportSuccessfulEnd() throws Exception {
        Sink sink = new Sink(); sink.headBlocked = true;
        try (MatCarrierPlayer player = new MatCarrierPlayer(sink, 4, 1000, 30)) {
            player.start();
            assertTrue(offer(player, carrier(0), 0));
            assertTrue(player.endMatStream(1));
            assertFalse(player.awaitCompletion(1000));
            assertEquals(MatCarrierPlayer.State.FAILED, player.state());
            assertTrue(player.error().contains("drain"));
        }
    }

    @Test(timeout = 5000) public void cancellationDoesNotSynthesizeSuccessfulEnd() throws Exception {
        Sink sink = new Sink(); sink.zero = true;
        MatCarrierPlayer player = new MatCarrierPlayer(sink);
        player.start();
        try {
            assertTrue(offer(player, carrier(0), 0));
            assertTrue(sink.entered.await(1, TimeUnit.SECONDS));
        } finally { player.close(); }
        assertEquals(MatCarrierPlayer.State.STOPPED, player.state());
        assertFalse(player.awaitCompletion(100));
        assertTrue(sink.closed);
        assertFalse(offer(player, carrier(0), 1));
    }

    @Test(timeout = 5000) public void rejectsEmptyAndMismatchedEndCounts() throws Exception {
        for (long count : new long[]{0, 2}) {
            try (MatCarrierPlayer player = new MatCarrierPlayer(new Sink())) {
                player.start();
                assertTrue(offer(player, carrier(0), 0));
                assertFalse(player.endMatStream(count));
                assertEquals(MatCarrierPlayer.State.FAILED, player.state());
            }
        }
    }

    @Test(timeout = 5000) public void failedSinkStartupDoesNotLeaveAnOutputThread() throws Exception {
        Sink sink = new Sink(); sink.failStart = true;
        try {
            new MatPlaybackSession(sink);
            fail("Unsupported output must fail setup");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("unsupported HDMI route"));
        }
        assertTrue(sink.closed);
    }

    @Test(timeout = 5000) public void closePreservesTheCallingThreadsInterrupt() throws Exception {
        MatCarrierPlayer player = new MatCarrierPlayer(new Sink());
        player.start();
        assertTrue(player.awaitReady(1000));
        try {
            Thread.currentThread().interrupt();
            player.close();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            player.close();
        }
    }

    @Test(timeout = 5000) public void setupIsReadyWithoutWaitingForPayloadOrStartingPlayback() throws Exception {
        PrimingSink sink = new PrimingSink(16388, true);
        try (MatPlaybackSession session = new MatPlaybackSession(sink)) {
            assertTrue(sink.prepared);
            assertEquals(0, sink.playCalls);
            assertEquals(0, session.player().submittedBytes());
        }
        assertTrue(sink.closed);
    }

    @Test(timeout = 5000) public void primesActualCapacityAndContinuesTheSamePartialCycle() throws Exception {
        for (int capacityFrames : new int[]{7680, 16388, 24000}) {
            PrimingSink sink = new PrimingSink(capacityFrames, true);
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            int cycles = (capacityFrames + MatCarrierPlayer.FRAMES_PER_CYCLE - 1) /
                    MatCarrierPlayer.FRAMES_PER_CYCLE + 1;
            try (MatPlaybackSession session = new MatPlaybackSession(sink, true)) {
                for (int i = 0; i < cycles; i++) {
                    byte[] input = carrier(i);
                    expected.write(input);
                    assertTrue(offer(session, input, i));
                    Arrays.fill(input, (byte) 0); // The JNI input array may be reused immediately.
                }
                assertTrue(session.endMatStream(cycles));
                assertEquals(1, sink.playCalls);
                assertEquals(capacityFrames * 16L, sink.bytesAtPlay);
                assertEquals(capacityFrames, session.player().primedFrames());
                assertArrayEquals(expected.toByteArray(), sink.accepted.toByteArray());
                assertEquals(MatCarrierPlayer.hex(MessageDigest.getInstance("SHA-256").digest(expected.toByteArray())),
                        session.player().submittedSha256());
                assertTrue(session.player().playNanos() >= session.player().firstPayloadNanos());
                assertTrue(session.player().firstPlayedNanos() >= session.player().playNanos());
            }
        }
    }

    @Test(timeout = 5000) public void zeroWriteAtCapacityStartsPlaybackWithoutDroppingTheTail() throws Exception {
        PrimingSink sink = new PrimingSink(7680, false);
        try (MatPlaybackSession session = new MatPlaybackSession(sink)) {
            for (int i = 0; i < 3; i++) assertTrue(offer(session, carrier(i), i));
            assertTrue(session.endMatStream(3));
            assertEquals(1, sink.playCalls);
            assertEquals(7680 * 16L, sink.bytesAtPlay);
            assertEquals(3L * MatCarrierPlayer.CYCLE_BYTES, sink.bytes);
        }
    }

    @Test(timeout = 5000) public void shortEosStartsPlaybackEvenWithoutAShortWrite() throws Exception {
        PrimingSink sink = new PrimingSink(16388, true);
        byte[] input = carrier(4);
        try (MatPlaybackSession session = new MatPlaybackSession(sink)) {
            assertTrue(offer(session, input, 0));
            assertTrue(session.endMatStream(1));
            assertEquals(1, sink.playCalls);
            assertEquals(input.length, sink.bytesAtPlay);
            assertArrayEquals(input, sink.accepted.toByteArray());
        }
    }

    @Test(timeout = 5000) public void insufficientPrimeThatCannotPlayFailsItsBoundedDrain() throws Exception {
        PrimingSink sink = new PrimingSink(16388, true); sink.headBlocked = true;
        try (MatCarrierPlayer player = new MatCarrierPlayer(sink, 4, 1000, 30)) {
            player.start();
            assertTrue(offer(player, carrier(0), 0));
            assertTrue(player.endMatStream(1));
            assertFalse(player.awaitCompletion(1000));
            assertEquals(1, sink.playCalls);
            assertTrue(player.error().contains("drain"));
        }
    }

    @Test(timeout = 5000) public void cancellationDuringPreparationAndPrimingDoesNotStartPlayback() throws Exception {
        CountDownLatch preparing = new CountDownLatch(1);
        Sink preparingSink = new Sink() {
            @Override public void prepare() throws InterruptedException {
                preparing.countDown();
                new CountDownLatch(1).await();
            }
        };
        MatCarrierPlayer preparingPlayer = new MatCarrierPlayer(preparingSink);
        preparingPlayer.start();
        assertTrue(preparing.await(1, TimeUnit.SECONDS));
        preparingPlayer.close();
        assertTrue(preparingSink.closed);
        assertEquals(0, preparingSink.playCalls);

        PrimingSink primingSink = new PrimingSink(16388, true);
        try (MatCarrierPlayer player = new MatCarrierPlayer(primingSink)) {
            player.start();
            assertTrue(offer(player, carrier(0), 0));
            assertTrue(primingSink.firstCycleWritten.await(1, TimeUnit.SECONDS));
            player.close();
            assertEquals(MatCarrierPlayer.State.STOPPED, player.state());
        }
        assertTrue(primingSink.closed);
        assertEquals(0, primingSink.playCalls);
    }

    @Test(timeout = 5000) public void failedPartialSubmissionHashesOnlyTheAcceptedPrefix() throws Exception {
        Sink sink = new Sink() {
            @Override public int write(short[] words, int offset, int count) {
                if (bytes != 0) return -6;
                return super.write(words, offset, count);
            }
        };
        try (MatCarrierPlayer player = new MatCarrierPlayer(sink, true)) {
            player.start();
            assertTrue(offer(player, carrier(3), 0));
            assertFalse(player.awaitCompletion(1000));
            assertEquals(1024, player.submittedBytes());
            assertEquals(MatCarrierPlayer.hex(MessageDigest.getInstance("SHA-256").digest(sink.accepted.toByteArray())),
                    player.submittedSha256());
        }
    }

    @Test(timeout = 5000) public void failedPlayAfterPrimingReleasesTheTrackWithoutSuccessfulEos() throws Exception {
        PrimingSink sink = new PrimingSink(MatCarrierPlayer.FRAMES_PER_CYCLE, true) {
            @Override public void play() { throw new IllegalStateException("device refused play"); }
        };
        try (MatCarrierPlayer player = new MatCarrierPlayer(sink)) {
            player.start();
            assertTrue(player.awaitReady(1000));
            assertTrue(offer(player, carrier(0), 0));
            assertFalse(player.awaitCompletion(1000));
            assertEquals(MatCarrierPlayer.State.FAILED, player.state());
            assertTrue(player.error().contains("device refused play"));
            assertTrue(sink.closed);
        }
    }
}
