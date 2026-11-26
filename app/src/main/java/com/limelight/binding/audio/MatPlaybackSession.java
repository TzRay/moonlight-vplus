package com.limelight.binding.audio;

/** Shared live-session lifecycle, independently testable without Android hardware. */
public final class MatPlaybackSession implements MatCarrierConsumer, AutoCloseable {
    // A cold SHIELD HDMI output took almost five seconds to initialize with
    // 4K60 decode active. Leave two seconds of the recovery window for cleanup.
    static final int HDMI_READY_TIMEOUT_MS = 8000;
    private final MatCarrierPlayer player;
    public MatPlaybackSession(MatCarrierPlayer.Sink sink) throws InterruptedException {
        this(sink, false);
    }
    MatPlaybackSession(MatCarrierPlayer.Sink sink, boolean verifyWrites) throws InterruptedException {
        player = new MatCarrierPlayer(sink, verifyWrites);
        player.start();
        try {
            if (!player.awaitReady(HDMI_READY_TIMEOUT_MS)) {
                String cause = player.error();
                if (!cause.isEmpty()) throw new IllegalStateException("MAT sink not ready: " + cause);
                String detail = sink.preparationDetail();
                throw new IllegalStateException("MAT HDMI output did not become ready within " + HDMI_READY_TIMEOUT_MS +
                        " ms" + (detail.isEmpty() ? "" : " (" + detail + ")"));
            }
        } catch (InterruptedException | RuntimeException e) {
            try { player.close(); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
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
    @Override public void close() { player.close(); }
}
