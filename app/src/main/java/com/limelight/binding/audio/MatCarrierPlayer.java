package com.limelight.binding.audio;

import java.security.MessageDigest;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Android-independent output state machine for authenticated live MAT cycles. */
public final class MatCarrierPlayer implements MatCarrierConsumer, AutoCloseable {
    public static final int CYCLE_BYTES = 61440, RATE = 192000, CHANNELS = 8;
    public static final int FRAMES_PER_CYCLE = 3840;
    // SHIELD can spend 232 ms in one nonblocking HDMI write under 4K60 load.
    // 16 carrier periods absorb that stall without blocking the RTP consumer.
    // The writer polls immediately; capacity is not a playback start threshold.
    private static final int OUTPUT_QUEUE_CYCLES = 16;
    public interface Sink extends AutoCloseable {
        /** Open a paused track. Must not wait for input, which arrives after setup returns. */
        void prepare() throws Exception;
        default void prepare(BooleanSupplier cancelled) throws Exception { prepare(); }
        default void setFailureListener(Consumer<String> listener) {}
        default String preparationDetail() { return ""; }
        /** Carrier frames needed before play, or zero to prime until a short write. */
        default long startThresholdFrames() { return 0; }
        void play() throws Exception;
        /** Non-blocking; count and return value are 16-bit words, not bytes. */
        int write(short[] words, int offset, int count) throws Exception;
        /** Carrier frames since this track was opened; implementation handles 32-bit wrap. */
        long playedFrames() throws Exception;
        void close() throws Exception;
    }
    public interface FailureListener {
        void onFailure(long epoch, long domain);
    }
    private FailureListener failureListener;
    public synchronized void setFailureListener(FailureListener listener) {
        failureListener = listener;
    }
    private void publishFailure() {
        if (failureListener != null && receivedCycles != 0) {
            failureListener.onFailure(epoch, domain);
            failureListener = null;
        }
    }
    public static final class CleanupPending extends IllegalStateException {
        CleanupPending() { super("MAT writer did not stop"); }
    }
    public enum State { NEW, RUNNING, DRAINING, COMPLETED, FAILED, STOPPED }
    private static final class Slot {
        final short[] words = new short[CYCLE_BYTES / 2];
        final byte[] diagnosticBytes;

        Slot(boolean verifyWrites) {
            diagnosticBytes = verifyWrites ? new byte[CYCLE_BYTES] : null;
        }
    }
    private final Sink sink;
    private final BooleanSupplier cancelled;
    private final boolean verifyWrites;
    private final ArrayBlockingQueue<Slot> pending, free;
    private final long stallNanos, drainNanos;
    private final CountDownLatch done = new CountDownLatch(1);
    private final CountDownLatch ready = new CountDownLatch(1);
    private final Thread writer;
    private volatile State state = State.NEW;
    private volatile String error = "", closeError = "", submittedSha256 = "";
    private volatile boolean drained;
    private volatile long receivedCycles, writtenCycles, submittedBytes;
    private volatile int peakQueue;
    private volatile long maxCopyNanos, maxEnqueueNanos;
    private volatile long firstPayloadNanos, playNanos, firstPlayedNanos, primedFrames;
    private long lastHead, nextHeadCheck;
    private long stallSince;
    private final PlaybackProgress progress = new PlaybackProgress();

    static final class PlaybackProgress {
        private long lastAdvance, continuousSince;
        private boolean observed;

        synchronized void advanced(long now) {
            if (!observed || now - lastAdvance > TimeUnit.SECONDS.toNanos(1)) {
                continuousSince = now;
            }
            lastAdvance = now;
            observed = true;
        }

        synchronized int report(long now) {
            if (!observed || now - lastAdvance > TimeUnit.SECONDS.toNanos(1)) return 0;
            return lastAdvance - continuousSince >= TimeUnit.SECONDS.toNanos(30) ? 3 : 1;
        }
    }
    private long epoch, domain;

    public MatCarrierPlayer(Sink sink) { this(sink, false); }
    MatCarrierPlayer(Sink sink, boolean verifyWrites) { this(sink, verifyWrites, () -> false); }
    MatCarrierPlayer(Sink sink, boolean verifyWrites, BooleanSupplier cancelled) {
        this(sink, OUTPUT_QUEUE_CYCLES, 1000, 3000, verifyWrites, cancelled);
    }
    public MatCarrierPlayer(Sink sink, int capacity, long stallMs, long drainMs) {
        this(sink, capacity, stallMs, drainMs, false, () -> false);
    }
    private MatCarrierPlayer(Sink sink, int capacity, long stallMs, long drainMs, boolean verifyWrites, BooleanSupplier cancelled) {
        if (sink == null || capacity < 1 || capacity > OUTPUT_QUEUE_CYCLES || stallMs < 1 || drainMs < 1)
            throw new IllegalArgumentException("Invalid MAT output bounds");
        this.sink = sink;
        this.cancelled = cancelled;
        this.verifyWrites = verifyWrites;
        pending = new ArrayBlockingQueue<>(capacity);
        free = new ArrayBlockingQueue<>(capacity + 2);
        for (int i = 0; i < capacity + 2; i++) free.add(new Slot(verifyWrites));
        stallNanos = TimeUnit.MILLISECONDS.toNanos(stallMs);
        drainNanos = TimeUnit.MILLISECONDS.toNanos(drainMs);
        writer = new Thread(this::run, "mat-iec-writer");
        sink.setFailureListener(this::fail);
    }
    public synchronized void start() {
        if (state != State.NEW) throw new IllegalStateException("MAT player already started");
        state = State.RUNNING; writer.start();
    }
    public static boolean validCarrier(byte[] b, int offset, int length) {
        if (b == null || offset < 0 || length != CYCLE_BYTES || offset > b.length - length) return false;
        int pd = (b[offset + 6] & 255) | ((b[offset + 7] & 255) << 8);
        return b[offset] == 0x72 && b[offset + 1] == (byte) 0xf8 && b[offset + 2] == 0x1f &&
                b[offset + 3] == 0x4e && (b[offset + 4] & 31) == 0x16 &&
                (b[offset + 4] & 0x80) == 0 && pd >= 2 && pd <= CYCLE_BYTES - 8 && (pd & 1) == 0;
    }
    @Override public synchronized boolean offerMatCycle(byte[] b, int length, long e, long d,
                                                         long index, long pts48k) {
        if (state != State.RUNNING) return false;
        if (!validCarrier(b, 0, length) || e <= 0 || d <= 0 || index != receivedCycles ||
                index >= Long.MAX_VALUE / 960 || pts48k != index * 960 ||
                (receivedCycles != 0 && (e != epoch || d != domain))) return fail("Invalid MAT cycle metadata");
        long enqueueStart = System.nanoTime();
        long until = enqueueStart + TimeUnit.MILLISECONDS.toNanos(100);
        while (pending.remainingCapacity() == 0 && state == State.RUNNING) {
            long remaining = until - System.nanoTime();
            if (remaining <= 0) return fail("MAT output queue overflow");
            try { TimeUnit.NANOSECONDS.timedWait(this, remaining); }
            catch (InterruptedException e1) {
                Thread.currentThread().interrupt();
                return fail("MAT enqueue interrupted");
            }
        }
        maxEnqueueNanos = Math.max(maxEnqueueNanos, System.nanoTime() - enqueueStart);
        if (state != State.RUNNING) return false;
        if (index != receivedCycles || (index != 0 && (e != epoch || d != domain)))
            return fail("Invalid MAT cycle metadata");
        Slot slot = free.remove();
        long copyStart = System.nanoTime();
        if (verifyWrites) System.arraycopy(b, 0, slot.diagnosticBytes, 0, CYCLE_BYTES);
        for (int i = 0; i < slot.words.length; i++)
            slot.words[i] = (short) ((b[2 * i] & 255) | ((b[2 * i + 1] & 255) << 8));
        maxCopyNanos = Math.max(maxCopyNanos, System.nanoTime() - copyStart);
        if (receivedCycles == 0) firstPayloadNanos = copyStart;
        pending.add(slot);
        epoch = e; domain = d; receivedCycles++;
        peakQueue = Math.max(peakQueue, pending.size());
        return true;
    }
    @Override public synchronized boolean endMatStream(long cycles) {
        if (state != State.RUNNING) return false;
        if (cycles == 0 || cycles != receivedCycles) return fail("Invalid MAT EOS");
        state = State.DRAINING; notifyAll(); return true;
    }
    private synchronized boolean fail(String message) {
        if (active()) {
            error = message;
            state = State.FAILED;
            publishFailure();
        }
        notifyAll();
        ready.countDown();
        if (Thread.currentThread() != writer) writer.interrupt();
        return false;
    }
    private boolean active() { return state == State.RUNNING || state == State.DRAINING; }
    private void beginPlayback() throws Exception {
        if (!active() || cancelled.getAsBoolean() || playNanos != 0) return;
        primedFrames = submittedBytes / 16;
        sink.play();
        playNanos = System.nanoTime();
        stallSince = playNanos;
    }
    private void checkPlayhead(long now) throws Exception {
        if (playNanos == 0 || now < nextHeadCheck) return;
        long head = sink.playedFrames();
        if (head < lastHead) throw new IllegalStateException("IEC playhead moved backwards");
        if (head > lastHead) {
            lastHead = head;
            stallSince = now;
            progress.advanced(now);
            if (firstPlayedNanos == 0) firstPlayedNanos = now;
        } else if (submittedBytes > head * 16 && now - stallSince > stallNanos) {
            throw new IllegalStateException("IEC playhead stalled");
        }
        nextHeadCheck = now + TimeUnit.MILLISECONDS.toNanos(50);
    }
    private void run() {
        MessageDigest digest = null;
        try {
            if (verifyWrites) digest = MessageDigest.getInstance("SHA-256");
            sink.prepare(() -> !active() || cancelled.getAsBoolean());
            if (!active() || cancelled.getAsBoolean()) return;
            long startThresholdFrames = sink.startThresholdFrames();
            if (!active() || cancelled.getAsBoolean()) return;
            ready.countDown();
            while (active()) {
                Slot slot = pending.poll(10, TimeUnit.MILLISECONDS);
                if (slot == null) {
                    if (state == State.DRAINING && pending.isEmpty()) break;
                    checkPlayhead(System.nanoTime());
                    continue;
                }
                synchronized (this) { notifyAll(); }
                try {
                    int offset = 0;
                    long lastWrite = System.nanoTime();
                    while (offset < slot.words.length && active()) {
                        int requested = slot.words.length - offset;
                        int n = sink.write(slot.words, offset, requested);
                        if (n < 0 || n > requested || n % CHANNELS != 0)
                            throw new IllegalStateException("Invalid IEC write result: " + n);
                        long now = System.nanoTime();
                        if (n == 0) {
                            if (now - lastWrite > stallNanos) throw new IllegalStateException("IEC write made no progress");
                            Thread.sleep(1);
                        } else {
                            if (submittedBytes <= lastHead * 16) stallSince = now;
                            if (digest != null) digest.update(slot.diagnosticBytes, offset * 2, n * 2);
                            offset += n; submittedBytes += n * 2L; lastWrite = now;
                        }
                        if (active() && playNanos == 0 && submittedBytes > 0 &&
                                (n < requested || (startThresholdFrames > 0 &&
                                        submittedBytes / 16 >= startThresholdFrames))) {
                            // Real device capacity can split a cycle. Start before retrying
                            // its tail; waiting for the complete slot here would deadlock.
                            beginPlayback();
                        }
                        checkPlayhead(System.nanoTime());
                    }
                    if (offset == slot.words.length) writtenCycles++;
                } finally { free.add(slot); }
            }
            if (state == State.DRAINING) {
                // A short stream may reach EOS without ever filling the paused track.
                beginPlayback();
                long deadline = System.nanoTime() + drainNanos;
                while (state == State.DRAINING) {
                    long played = sink.playedFrames();
                    if (played > 0 && firstPlayedNanos == 0) firstPlayedNanos = System.nanoTime();
                    if (played >= submittedBytes / 16) break;
                    if (System.nanoTime() >= deadline) throw new IllegalStateException("IEC EOS drain timed out");
                    Thread.sleep(2);
                }
                synchronized (this) {
                    if (state == State.DRAINING) {
                        if (writtenCycles != receivedCycles) throw new IllegalStateException("MAT EOS lost cycles");
                        drained = true;
                    }
                }
            }
        } catch (InterruptedException e) {
            if (active()) fail("MAT writer interrupted");
            Thread.currentThread().interrupt();
        } catch (Exception e) { fail(e.toString()); }
        finally {
            if (digest != null) submittedSha256 = hex(digest.digest());
            try { sink.close(); }
            catch (Exception e) {
                StringBuilder detail = new StringBuilder(e.toString());
                for (Throwable secondary : e.getSuppressed()) detail.append("; ").append(secondary);
                closeError = detail.toString();
                synchronized (this) {
                    if (error.isEmpty()) error = "IEC close failed: " + closeError;
                    state = State.FAILED;
                    publishFailure();
                }
            }
            synchronized (this) {
                pending.drainTo(free);
                if (drained && state == State.DRAINING) state = State.COMPLETED;
                notifyAll();
                ready.countDown(); done.countDown();
            }
        }
    }
    public static String hex(byte[] b) {
        StringBuilder out = new StringBuilder(b.length * 2);
        for (byte value : b) out.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return out.toString();
    }
    public boolean awaitCompletion(long timeoutMs) throws InterruptedException {
        return done.await(timeoutMs, TimeUnit.MILLISECONDS) && state == State.COMPLETED;
    }
    public boolean awaitReady(long timeoutMs) throws InterruptedException {
        return ready.await(timeoutMs, TimeUnit.MILLISECONDS) && active() && !cancelled.getAsBoolean();
    }
    public State state() { return state; }
    public boolean drained() { return drained; }
    public String closeError() { return closeError; }
    boolean writerAlive() { return writer.isAlive(); }
    public String error() { return error; }
    public String submittedSha256() { return submittedSha256; }
    public long maxCopyUs() { return TimeUnit.NANOSECONDS.toMicros(maxCopyNanos); }
    public long maxEnqueueUs() { return TimeUnit.NANOSECONDS.toMicros(maxEnqueueNanos); }
    public long submittedBytes() { return submittedBytes; }
    public long receivedCycles() { return receivedCycles; }
    public long writtenCycles() { return writtenCycles; }
    public int peakQueue() { return peakQueue; }
    public long firstPayloadNanos() { return firstPayloadNanos; }
    public long playNanos() { return playNanos; }
    public long firstPlayedNanos() { return firstPlayedNanos; }
    @Override public synchronized int matPlaybackProgress(long expectedEpoch, long expectedDomain) {
        if (state == State.FAILED) return -1;
        if (state != State.RUNNING || receivedCycles == 0 || epoch != expectedEpoch || domain != expectedDomain) return 0;
        return progress.report(System.nanoTime());
    }
    public long primedFrames() { return primedFrames; }
    @Override public void close() {
        synchronized (this) {
            if (state == State.NEW) { state = State.STOPPED; ready.countDown(); done.countDown(); return; }
            if (active()) {
                state = State.STOPPED;
                publishFailure();
            }
            ready.countDown();
            notifyAll();
        }
        writer.interrupt();
        // Connection cancellation can interrupt its owner. Still join/release the sink,
        // then restore that interrupt instead of letting join abort immediately.
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            while (writer.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { writer.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))); }
                catch (InterruptedException e) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
        if (writer.isAlive()) throw new CleanupPending();
    }
}
