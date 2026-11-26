package com.limelight.binding.audio;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;
import com.limelight.LimeLog;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            update("MAT sample playback requires Android 7.0 (API 24) or later.");
            return;
        }
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

    private void play(String sample, String expected, int skip, int seconds) {
        JSONObject result = new JSONObject();
        MatPlaybackSession session = null;
        try {
            if (sample == null || !sample.matches("[A-Za-z0-9_-]+\\.iec") ||
                    expected == null || !expected.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Explicit sample basename and SHA256 required");
            File file = new File(new File(getFilesDir(), "mat-diagnostic"), sample);
            if (seconds < 0 || seconds > 1800) throw new IllegalArgumentException("Invalid replay duration");
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                MatCarrierSample recording = new MatCarrierSample(input, expected, skip);
                int count = seconds == 0 ? recording.cycles : seconds * 50;
                MessageDigest replayDigest = MessageDigest.getInstance("SHA-256");
                result.put("sample", sample).put("sourceSha256", recording.sourceSha256).put("skipCycles", skip)
                        .put("selectedSha256", recording.selectedSha256).put("cycles", count)
                        .put("receiverAtmosVerified", false).put("startedAtMs", System.currentTimeMillis());
                LimeLog.info("MAT_DIAGNOSTIC_BEGIN " + result);
                update("MAT sample comparison\n" + sample + "\nSkip cycles: " + skip +
                        "\nPlaying " + (count * .02) + " seconds\nReceiver Atmos recognition is not measured here.");
                session = new MatPlaybackSession(new MatAudioTrackSink(this), true);
                long nextCycle = System.nanoTime();
                for (int index = 0; index < count; index++) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Diagnostic cancelled");
                    if (seconds > 0) {
                        TimeUnit.NANOSECONDS.sleep(nextCycle - System.nanoTime());
                        nextCycle += TimeUnit.MILLISECONDS.toNanos(20);
                    }
                    recording.readReplayCycle(index);
                    byte[] cycle = recording.cycle;
                    if (seconds > 0) replayDigest.update(cycle);
                    if (!session.offerMatCycle(cycle, cycle.length, 1, 1, index, index * 960L))
                        throw new IllegalStateException(session.player().error());
                }
                if (!session.endMatStream(count)) throw new IllegalStateException(session.player().error());
                MatCarrierPlayer player = session.player();
                result.put("submittedSha256", player.submittedSha256()).put("writtenCycles", player.writtenCycles())
                        .put("submittedBytes", player.submittedBytes()).put("state", player.state().name())
                        .put("maxCopyUs", player.maxCopyUs()).put("maxEnqueueUs", player.maxEnqueueUs());
                String expectedSubmission = seconds == 0 ? recording.selectedSha256 : MatCarrierPlayer.hex(replayDigest.digest());
                if (!expectedSubmission.equals(player.submittedSha256()))
                    throw new IllegalStateException("Submitted bytes differ from selected sample");
                result.put("result", "PLAYED_BYTE_EXACT");
            }
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
