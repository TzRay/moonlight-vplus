package com.limelight.binding.audio;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/** Verifies the selected recording before playback and reuses one carrier buffer for replay. */
final class MatCarrierSample {
    private static final int CYCLE = MatCarrierPlayer.CYCLE_BYTES;
    private static final int SHA256_BYTES = 32;
    final int cycles;
    final String sourceSha256;
    final String selectedSha256;
    final byte[] cycle = new byte[CYCLE];
    private final RandomAccessFile input;
    private final long start;
    private final byte[] cycleHashes;
    private final MessageDigest cycleDigest = MessageDigest.getInstance("SHA-256");
    private final byte[] actualHash = new byte[SHA256_BYTES];

    MatCarrierSample(RandomAccessFile input, String expected, int skip) throws IOException, GeneralSecurityException, InterruptedException {
        this.input = input;
        long length = input.length();
        if (length == 0 || length > 3000L * CYCLE || length % CYCLE != 0 || skip < 0 || skip >= length / CYCLE)
            throw new IllegalArgumentException("Expected 1..3000 complete carrier cycles and a valid skip");
        start = (long) skip * CYCLE;
        cycles = (int) (length / CYCLE) - skip;
        cycleHashes = new byte[cycles * SHA256_BYTES];
        MessageDigest source = MessageDigest.getInstance("SHA-256");
        MessageDigest selected = MessageDigest.getInstance("SHA-256");
        input.seek(0);
        for (int index = 0; index < length / CYCLE; index++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Diagnostic cancelled");
            input.readFully(cycle);
            source.update(cycle);
            if (index < skip) continue;
            if (!MatCarrierPlayer.validCarrier(cycle, 0, CYCLE))
                throw new IOException("Invalid carrier at byte " + (long) index * CYCLE);
            selected.update(cycle);
            cycleDigest.update(cycle);
            cycleDigest.digest(cycleHashes, (index - skip) * SHA256_BYTES, SHA256_BYTES);
        }
        sourceSha256 = MatCarrierPlayer.hex(source.digest());
        if (!sourceSha256.equals(expected) || input.length() != length) throw new IOException("Sample SHA256 mismatch");
        selectedSha256 = MatCarrierPlayer.hex(selected.digest());
    }

    void readReplayCycle(int index) throws IOException, GeneralSecurityException {
        int offset = index % cycles;
        if (offset == 0) input.seek(start);
        input.readFully(cycle);
        // adb can replace the contents of an open sample between verification and playback.
        cycleDigest.update(cycle);
        cycleDigest.digest(actualHash, 0, SHA256_BYTES);
        for (int i = 0; i < SHA256_BYTES; i++) {
            if (actualHash[i] != cycleHashes[offset * SHA256_BYTES + i]) throw new IOException("Sample changed during replay");
        }
    }
}
