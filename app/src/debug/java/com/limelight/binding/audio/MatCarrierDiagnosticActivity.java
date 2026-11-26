package com.limelight.binding.audio;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;
import com.limelight.LimeLog;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** ADB-only, debug-only playback of an explicitly selected recorded carrier.
 * Uses the production sink/player; never participates in live source selection.
 */
public final class MatCarrierDiagnosticActivity extends Activity {
    private Thread playback;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status = new TextView(this);
        status.setTextSize(22);
        status.setPadding(48, 48, 48, 48);
        setContentView(status);
        String sample = getIntent().getStringExtra("sample");
        String sha256 = getIntent().getStringExtra("sha256");
        int skip = getIntent().getIntExtra("skip_cycles", 0);
        int seconds = getIntent().getIntExtra("replay_seconds", 0);
        update("MAT sample comparison\n" + sample + "\nPreparing playback…");
        playback = new Thread(() -> play(sample, sha256, skip, seconds), "mat-sample-diagnostic");
        playback.start();
    }

    private void update(String value) {
        runOnUiThread(() -> status.setText(value));
    }

    private static String digest(byte[] bytes) throws Exception {
        return MatCarrierPlayer.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void play(String sample, String expected, int skip, int seconds) {
        JSONObject result = new JSONObject();
        MatPlaybackSession session = null;
        try {
            if (sample == null || !sample.matches("[A-Za-z0-9_-]+\\.iec") ||
                    expected == null || !expected.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Explicit sample basename and SHA256 required");
            File file = new File(new File(getFilesDir(), "mat-diagnostic"), sample);
            long length = file.length();
            if (length == 0 || length > 60L * 50 * MatCarrierPlayer.CYCLE_BYTES ||
                    length % MatCarrierPlayer.CYCLE_BYTES != 0 || skip < 0 || skip >= length / MatCarrierPlayer.CYCLE_BYTES)
                throw new IllegalArgumentException("Expected 1..3000 complete carrier cycles and a valid skip");
            byte[] bytes = new byte[(int) length];
            try (InputStream input = new FileInputStream(file)) {
                int offset = 0, n;
                while (offset < bytes.length && (n = input.read(bytes, offset, bytes.length - offset)) > 0) offset += n;
                if (offset != bytes.length || input.read() != -1) throw new IllegalStateException("Sample length changed");
            }
            String sourceSha256 = digest(bytes);
            if (!expected.equals(sourceSha256)) throw new IllegalStateException("Sample SHA256 mismatch");
            int first = skip * MatCarrierPlayer.CYCLE_BYTES;
            for (int offset = first; offset < bytes.length; offset += MatCarrierPlayer.CYCLE_BYTES)
                if (!MatCarrierPlayer.validCarrier(bytes, offset, MatCarrierPlayer.CYCLE_BYTES))
                    throw new IllegalStateException("Invalid carrier at byte " + offset);
            String selectedSha256 = digest(Arrays.copyOfRange(bytes, first, bytes.length));
            int recordedCycles = (bytes.length - first) / MatCarrierPlayer.CYCLE_BYTES;
            if (seconds < 0 || seconds > 1800) throw new IllegalArgumentException("Invalid replay duration");
            int count = seconds == 0 ? recordedCycles : seconds * 50;
            MessageDigest replayDigest = MessageDigest.getInstance("SHA-256");
            result.put("sample", sample).put("sourceSha256", sourceSha256).put("skipCycles", skip)
                    .put("selectedSha256", selectedSha256).put("cycles", count)
                    .put("receiverAtmosVerified", false).put("startedAtMs", System.currentTimeMillis());
            LimeLog.info("MAT_DIAGNOSTIC_BEGIN " + result);
            update("MAT sample comparison\n" + sample + "\nSkip cycles: " + skip +
                    "\nPlaying " + (count * .02) + " seconds\nReceiver Atmos recognition is not measured here.");
            session = new MatPlaybackSession(new MatAudioTrackSink(this), true);
            byte[] cycle = new byte[MatCarrierPlayer.CYCLE_BYTES];
            long nextCycle = System.nanoTime();
            for (int index = 0; index < count; index++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Diagnostic cancelled");
                if (seconds > 0) {
                    TimeUnit.NANOSECONDS.sleep(nextCycle - System.nanoTime());
                    nextCycle += TimeUnit.MILLISECONDS.toNanos(20);
                }
                System.arraycopy(bytes, first + (index % recordedCycles) * cycle.length, cycle, 0, cycle.length);
                if (seconds > 0) replayDigest.update(cycle);
                if (!session.offerMatCycle(cycle, cycle.length, 1, 1, index, index * 960L))
                    throw new IllegalStateException(session.player().error());
            }
            if (!session.endMatStream(count)) throw new IllegalStateException(session.player().error());
            MatCarrierPlayer player = session.player();
            result.put("submittedSha256", player.submittedSha256()).put("writtenCycles", player.writtenCycles())
                    .put("submittedBytes", player.submittedBytes()).put("state", player.state().name())
                    .put("maxCopyUs", player.maxCopyUs()).put("maxEnqueueUs", player.maxEnqueueUs());
            String expectedSubmission = seconds == 0 ? selectedSha256 : MatCarrierPlayer.hex(replayDigest.digest());
            if (!expectedSubmission.equals(player.submittedSha256()))
                throw new IllegalStateException("Submitted bytes differ from selected sample");
            result.put("result", "PLAYED_BYTE_EXACT");
        } catch (Exception e) {
            try { result.put("result", "FAILED").put("error", e.toString()); }
            catch (Exception ignored) { }
        } finally {
            if (session != null) {
                MatCarrierPlayer player = session.player();
                try {
                    result.put("acceptedCycles", player.receivedCycles()).put("peakQueue", player.peakQueue())
                            .put("writtenCycles", player.writtenCycles()).put("submittedBytes", player.submittedBytes())
                            .put("primedFrames", player.primedFrames()).put("maxEnqueueUs", player.maxEnqueueUs());
                } catch (Exception ignored) { }
                try { session.close(); }
                catch (Exception e) {
                    try { result.put("result", "FAILED").put("closeError", e.toString()); }
                    catch (Exception ignored) { }
                }
            }
        }
        try {
            result.put("finishedAtMs", System.currentTimeMillis());
            File directory = new File(getFilesDir(), "mat-diagnostic");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Result directory unavailable");
            try (FileOutputStream output = new FileOutputStream(new File(directory, "result.json"))) {
                output.write((result.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) { LimeLog.warning("MAT diagnostic result write: " + e); }
        LimeLog.info("MAT_DIAGNOSTIC_END " + result);
        update("MAT sample comparison\n" + result + "\n\nThis checks playback submission, not receiver Atmos recognition.");
    }

    @Override public void onDestroy() {
        if (playback != null) playback.interrupt();
        super.onDestroy();
    }
}
