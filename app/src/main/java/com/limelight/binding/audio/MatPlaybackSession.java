package com.limelight.binding.audio;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Shared live-session lifecycle, independently testable without Android hardware. */
public final class MatPlaybackSession implements MatCarrierConsumer, AutoCloseable {
    // A cold SHIELD HDMI output took almost five seconds to initialize with
    // 4K60 decode active. Leave two seconds of the recovery window for cleanup.
    static final int HDMI_READY_TIMEOUT_MS = 8000;
    public static final int CLEANUP_PENDING = -2001;
    private static MatCarrierPlayer owner;
    private final MatCarrierPlayer player;
    public MatPlaybackSession(MatCarrierPlayer.Sink sink) throws InterruptedException {
        this(sink, false);
    }
    MatPlaybackSession(MatCarrierPlayer.Sink sink, boolean verifyWrites) throws InterruptedException {
        this(sink, verifyWrites, () -> false);
    }
    MatPlaybackSession(MatCarrierPlayer.Sink sink, boolean verifyWrites, BooleanSupplier cancelled) throws InterruptedException {
        synchronized (MatPlaybackSession.class) {
            if (owner != null && owner.writerAlive()) throw new MatCarrierPlayer.CleanupPending();
            owner = player = new MatCarrierPlayer(sink, verifyWrites, cancelled);
            player.start();
        }
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HDMI_READY_TIMEOUT_MS);
            while (!cancelled.getAsBoolean()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                if (player.awaitReady(Math.min(50, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))))) {
                    if (!cancelled.getAsBoolean()) return;
                    break;
                }
                if (player.state() != MatCarrierPlayer.State.RUNNING) break;
            }
            if (cancelled.getAsBoolean()) throw new InterruptedException("MAT preparation cancelled");
            String cause = player.error();
            if (!cause.isEmpty()) throw new IllegalStateException("MAT sink not ready: " + cause);
            String detail = sink.preparationDetail();
            throw new IllegalStateException("MAT HDMI output did not become ready within " + HDMI_READY_TIMEOUT_MS +
                    " ms" + (detail.isEmpty() ? "" : " (" + detail + ")"));
        } catch (InterruptedException | RuntimeException e) {
            try { close(); } catch (MatCarrierPlayer.CleanupPending cleanup) { cleanup.addSuppressed(e); throw cleanup; }
            throw e;
        }
    }
    @Override public boolean offerMatCycle(byte[] bytes,int length,long epoch,long domain,long index,long pts48k) {
        return player.offerMatCycle(bytes,length,epoch,domain,index,pts48k);
    }
    @Override public boolean endMatStream(long cycles) {
        if (!player.endMatStream(cycles)) return false;
        try { return player.awaitCompletion(5000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }
    @Override public int matPlaybackProgress(long epoch, long domain) { return player.matPlaybackProgress(epoch, domain); }
    public MatCarrierPlayer player() { return player; }
    @Override public void close() {
        try { player.close(); }
        finally {
            synchronized (MatPlaybackSession.class) {
                if (owner == player && !player.writerAlive()) owner = null;
            }
        }
    }
}
