package com.limelight.binding.audio;

/** Complete, validated IEC carrier cycles only. The byte array is borrowed until return. */
public interface MatCarrierConsumer {
    boolean offerMatCycle(byte[] bytes, int length, long epoch, long domain, long index, long pts48k);
    /** Input EOS only. Implementations must drain before reporting playback complete. */
    boolean endMatStream(long cycles);
    /** Bit 0: current playback advances; bit 1: stable for 30 seconds; -1: failed. */
    default int matPlaybackProgress(long epoch, long domain) { return 0; }
}
