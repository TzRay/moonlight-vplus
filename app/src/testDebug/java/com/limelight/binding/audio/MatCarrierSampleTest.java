package com.limelight.binding.audio;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;

import static org.junit.Assert.*;

public class MatCarrierSampleTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    private static byte[] cycle(int marker) {
        byte[] b = new byte[MatCarrierPlayer.CYCLE_BYTES];
        b[0] = 0x72; b[1] = (byte) 0xf8; b[2] = 0x1f; b[3] = 0x4e;
        b[4] = 0x16; b[6] = 2; b[8] = (byte) marker;
        return b;
    }

    @Test public void maximumRecordingWrapsWithinSelectedRange() throws Exception {
        byte[] a = cycle(1), b = cycle(2);
        MessageDigest source = MessageDigest.getInstance("SHA-256");
        MessageDigest selected = MessageDigest.getInstance("SHA-256");
        try (RandomAccessFile file = new RandomAccessFile(files.newFile(), "rw")) {
            for (int i = 0; i < 3000; i++) {
                byte[] next = (i & 1) == 0 ? a : b;
                file.write(next);
                source.update(next);
                if (i >= 2998) selected.update(next);
            }
            String fullHash = MatCarrierPlayer.hex(source.digest());
            MatCarrierSample full = new MatCarrierSample(file, fullHash, 0);
            assertEquals(3000, full.cycles);
            assertEquals(fullHash, full.selectedSha256);
            MatCarrierSample tail = new MatCarrierSample(file, fullHash, 2998);
            assertEquals(MatCarrierPlayer.hex(selected.digest()), tail.selectedSha256);
            assertEquals(2, tail.cycles);
            for (int i = 0; i < 5; i++) {
                tail.readReplayCycle(i);
                assertArrayEquals((i & 1) == 0 ? a : b, tail.cycle);
            }
        }
    }

    @Test public void rejectsTruncationAndChangesAfterVerification() throws Exception {
        byte[] original = cycle(1);
        String hash = MatCarrierPlayer.hex(MessageDigest.getInstance("SHA-256").digest(original));
        try (RandomAccessFile file = new RandomAccessFile(files.newFile(), "rw")) {
            file.write(original);
            MatCarrierSample sample = new MatCarrierSample(file, hash, 0);
            file.seek(8);
            file.write(44);
            assertThrows(IOException.class, () -> sample.readReplayCycle(0));
            assertThrows(IOException.class, () -> new MatCarrierSample(file, hash, 0));
            file.setLength(original.length - 1);
            assertThrows(EOFException.class, () -> sample.readReplayCycle(0));
            assertThrows(IllegalArgumentException.class, () -> new MatCarrierSample(file, hash, 0));
        }
    }
}
