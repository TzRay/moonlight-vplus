package com.limelight.binding.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.AudioTimestamp;
import android.os.Build;
import androidx.annotation.RequiresApi;
import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;
import java.util.Arrays;

/** IEC only. No legacy PCM fallback, DSP, resampler, spatializer or TrueHD repacking. */
@RequiresApi(Build.VERSION_CODES.N)
public final class MatAudioTrackSink implements MatCarrierPlayer.Sink {
    private final Context context;
    private AudioTrack track;
    private long wraps, previousLow;
    private int routeId = -1;
    private long nextRouteCheck;
    private long nextProgressLog;
    private long submittedFrames, firstWriteTime, lastWriteTime;
    private long maxWriteGap, maxWriteCall;
    private long minimumQueued = Long.MAX_VALUE, maximumQueued;
    private long previousProgressTime, previousSubmitted, previousPlayed;
    private long startThresholdFrames, playTime, firstHeadTime;
    private long nextTimestampQuery, timestampQueryTime;
    private boolean timestampValid;
    private int previousUnderruns;
    private final AudioTimestamp timestamp = new AudioTimestamp();
    private boolean closed;
    private volatile String diagnostics = "not opened";
    public MatAudioTrackSink(Context context) { this.context = context.getApplicationContext(); }
    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
    }
    private static AudioFormat format() {
        return new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_IEC61937)
                .setSampleRate(MatCarrierPlayer.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND).build();
    }
    @Override public void prepare() {
        if (closed || track != null) throw new IllegalStateException("MAT sink cannot be reused");
        MoonBridge.bridgeMatAudioThreadStart(2);
        int min = AudioTrack.getMinBufferSize(MatCarrierPlayer.RATE,
                AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, AudioFormat.ENCODING_IEC61937);
        if (min <= 0) throw new IllegalStateException("192kHz/7.1/IEC61937 unsupported: " + min);
        int direct = -1;
        if (Build.VERSION.SDK_INT >= 33) {
            direct = AudioManager.getDirectPlaybackSupport(format(), attributes());
            if ((direct & AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED) == 0)
                throw new IllegalStateException("Route does not advertise IEC bitstream playback: " + direct);
        }
        long cycles = Math.max(2L, (min + (long)MatCarrierPlayer.CYCLE_BYTES - 1) / MatCarrierPlayer.CYCLE_BYTES);
        // A huge vendor minimum is not silently accepted as a low-latency route.
        if (cycles > 16) throw new IllegalStateException("IEC minimum exceeds 320ms diagnostic limit");
        int buffer = (int) cycles * MatCarrierPlayer.CYCLE_BYTES;
        track = new AudioTrack.Builder().setAudioAttributes(attributes()).setAudioFormat(format())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(buffer).build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("IEC track not initialized");
        track.pause(); track.flush();
        wraps = previousLow = 0;
        // Encoded audio must stop on focus loss instead of ducking the bitstream.
        AudioManager manager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        focusLost = false;
        if (manager == null || manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw new IllegalStateException("MAT audio focus denied");
        // IEC61937 has proportional 16-bit carrier frames, unlike AC3's byte units.
        // Older platforms expose capacity but not an explicit start threshold.
        startThresholdFrames = Build.VERSION.SDK_INT >= 31 ? track.getStartThresholdInFrames() :
                track.getBufferSizeInFrames();
        diagnostics = "IEC61937 192000Hz 7.1; requestedBuffer=" + buffer +
                "; minimumBufferBytes=" + min + "; actualBufferFrames=" + track.getBufferSizeInFrames() +
                "; capacityFrames=" + track.getBufferCapacityInFrames() +
                "; primeTargetCarrierFrames=" + startThresholdFrames +
                "; actualEncoding=" + track.getAudioFormat() + "; actualRate=" + track.getSampleRate() +
                "; actualChannels=" + track.getChannelCount() + "; direct=" + direct +
                "; receiver Atmos recognition NOT verified";
        LimeLog.info("MAT IEC track initialized: " + diagnostics);
    }
    @Override public long startThresholdFrames() { return startThresholdFrames; }
    @Override public void play() {
        if (closed || track == null || playTime != 0) throw new IllegalStateException("Invalid MAT play transition");
        checkRoute();
        track.play();
        playTime = System.nanoTime();
        LimeLog.info("MAT IEC play: primedCarrierFrames=" + submittedFrames +
                "; primeTargetCarrierFrames=" + startThresholdFrames +
                "; firstWriteToPlayUs=" + (firstWriteTime == 0 ? -1 : (playTime - firstWriteTime) / 1000));
    }
    private volatile boolean focusLost;
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change < 0) focusLost = true; // Do not duck/multiply a bitstream.
    };
    private void checkRoute() {
        if (focusLost) throw new IllegalStateException("MAT audio focus lost");
        long now = System.nanoTime();
        if (now < nextRouteCheck) return;
        nextRouteCheck = now + 50_000_000;
        AudioDeviceInfo device = track.getRoutedDevice();
        // Routing can be unknown until the first write; wait for a real route.
        if (device == null) return;
        int type = device.getType();
        boolean hdmi = type == AudioDeviceInfo.TYPE_HDMI || type == AudioDeviceInfo.TYPE_HDMI_ARC ||
                (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_HDMI_EARC);
        if (!hdmi) throw new IllegalStateException("Refusing MAT on non-HDMI output: " + type);
        if (routeId != -1 && routeId != device.getId()) throw new IllegalStateException("MAT HDMI route changed; reconnect");
        if (routeId == -1) {
            LimeLog.info("MAT IEC routed device: id=" + device.getId() + "; type=" + type +
                    "; name=" + device.getProductName() + "; encodings=" + Arrays.toString(device.getEncodings()));
        }
        routeId = device.getId();
        if (now >= nextProgressLog) {
            logProgress("progress");
            nextProgressLog = now + 5_000_000_000L;
        }
    }
    private void logProgress(String phase) {
        long now = System.nanoTime();
        long played = readPlaybackHead();
        long queued = submittedFrames - played;
        double seconds = (now - previousProgressTime) / 1_000_000_000.0;
        boolean hasInterval = previousProgressTime != 0;
        int underruns = track.getUnderrunCount();
        // getTimestamp can be expensive on direct outputs. Keep hardware queries
        // separate from the 50ms playhead checks and the 5s diagnostic reports.
        if (playTime != 0 && now >= nextTimestampQuery) {
            timestampValid = track.getTimestamp(timestamp);
            timestampQueryTime = now;
            nextTimestampQuery = now + 10_000_000_000L;
        }
        LimeLog.info("MAT IEC " + phase + ": route=" + routeId +
                "; playedCarrierFrames=" + played +
                "; underruns=" + underruns + "; underrunDelta=" + (underruns - previousUnderruns) +
                "; bufferFrames=" + track.getBufferSizeInFrames() +
                "; submittedCarrierFrames=" + submittedFrames +
                "; queuedCarrierFrames=" + queued +
                "; minQueuedCarrierFrames=" + (minimumQueued == Long.MAX_VALUE ? queued : minimumQueued) +
                "; maxQueuedCarrierFrames=" + maximumQueued +
                "; maxWriteGapUs=" + maxWriteGap / 1000 +
                "; maxWriteCallUs=" + maxWriteCall / 1000 +
                "; elapsedUs=" + (firstWriteTime == 0 ? 0 : (now - firstWriteTime) / 1000) +
                "; submittedFps=" + (hasInterval ? (submittedFrames - previousSubmitted) / seconds : 0) +
                "; playedFps=" + (hasInterval ? (played - previousPlayed) / seconds : 0) +
                "; timestampValid=" + timestampValid +
                "; timestampFrames=" + (timestampValid ? timestamp.framePosition : -1) +
                "; timestampNanos=" + (timestampValid ? timestamp.nanoTime : -1) +
                "; timestampQueryAgeMs=" + (timestampQueryTime == 0 ? -1 : (now - timestampQueryTime) / 1_000_000) +
                "; playing=" + (playTime != 0));
        previousProgressTime = now; previousSubmitted = submittedFrames; previousPlayed = played;
        previousUnderruns = underruns;
        minimumQueued = Long.MAX_VALUE; maximumQueued = 0;
        maxWriteGap = maxWriteCall = 0;
    }
    @Override public int write(short[] words, int offset, int count) {
        checkRoute();
        long before = System.nanoTime();
        int written = track.write(words, offset, count, AudioTrack.WRITE_NON_BLOCKING);
        long now = System.nanoTime();
        maxWriteCall = Math.max(maxWriteCall, now - before);
        if (written > 0) {
            if (lastWriteTime != 0) maxWriteGap = Math.max(maxWriteGap, now - lastWriteTime);
            if (firstWriteTime == 0) firstWriteTime = now;
            lastWriteTime = now;
            submittedFrames += written / MatCarrierPlayer.CHANNELS;
        }
        return written;
    }
    @Override public long playedFrames() {
        checkRoute();
        long played = readPlaybackHead();
        long queued = submittedFrames - played;
        minimumQueued = Math.min(minimumQueued, queued);
        maximumQueued = Math.max(maximumQueued, queued);
        return played;
    }
    private long readPlaybackHead() {
        long low = track.getPlaybackHeadPosition() & 0xffffffffL;
        if (low < previousLow) {
            if (previousLow < 0xf0000000L || low > 0x10000000L)
                throw new IllegalStateException("IEC playhead reset unexpectedly");
            wraps += 1L << 32;
        }
        previousLow = low;
        long played = wraps + low;
        if (playTime != 0 && firstHeadTime == 0 && played > 0) {
            firstHeadTime = System.nanoTime();
            LimeLog.info("MAT IEC first advancing playhead: carrierFrames=" + played +
                    "; playToObservedHeadUs=" + (firstHeadTime - playTime) / 1000 +
                    "; firstWriteToObservedHeadUs=" + (firstHeadTime - firstWriteTime) / 1000);
        }
        return played;
    }
    public String diagnostics() { return diagnostics; }
    @Override public void close() {
        closed = true;
        try {
            if (track != null) {
                try { logProgress("closing"); track.pause(); track.flush(); } finally {
                    timestampValid = false;
                    try { track.release(); } finally { track = null; }
                }
            }
        } finally {
            AudioManager manager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            if (manager != null) manager.abandonAudioFocus(focusListener);
        }
    }
}
