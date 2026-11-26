package com.limelight.binding.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRouting;
import android.media.AudioTrack;
import android.media.AudioTimestamp;
import android.os.Build;
import androidx.annotation.RequiresApi;
import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** IEC only. No legacy PCM fallback, DSP, resampler, spatializer or TrueHD repacking. */
@RequiresApi(Build.VERSION_CODES.N)
public final class MatAudioTrackSink implements MatCarrierPlayer.Sink {
    private final Context context;
    private AudioTrack track;
    private long wraps, previousLow;
    private int routeId = -1;
    private long nextRouteCheck;
    private final AtomicBoolean routeChanged = new AtomicBoolean(true);
    private final AudioRouting.OnRoutingChangedListener routingListener = ignored -> routeChanged.set(true);
    private int routeQueries;
    private long nextProgressLog;
    private long submittedFrames, firstWriteTime, lastWriteTime;
    private long maxWriteGap, maxWriteCall, maxRouteQuery;
    private long minimumQueued = Long.MAX_VALUE, maximumQueued;
    private long previousProgressTime, previousSubmitted, previousPlayed;
    private long startThresholdFrames, playTime, firstHeadTime;
    private long nextTimestampQuery, timestampQueryTime;
    private boolean timestampValid;
    private int previousUnderruns;
    private final AudioTimestamp timestamp = new AudioTimestamp();
    private boolean closed, focusOwned, listenerAttached;
    private BooleanSupplier cancelled = () -> false;
    private void checkCancelled() throws InterruptedException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new InterruptedException("MAT HDMI preparation cancelled");
    }
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
    @Override public String preparationDetail() { return diagnostics; }
    @Override public void prepare() throws InterruptedException { prepare(() -> false); }
    @Override public void prepare(BooleanSupplier cancelled) throws InterruptedException {
        this.cancelled = cancelled;
        checkCancelled();
        if (closed || track != null) throw new IllegalStateException("MAT sink cannot be reused");
        MoonBridge.bridgeMatAudioThreadStart(2);
        AudioManager manager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) throw new IllegalStateException("AudioManager unavailable");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MatPlaybackSession.HDMI_READY_TIMEOUT_MS);
        long openStart = System.nanoTime();
        diagnostics = "waiting for Android HDMI output";
        int min, buffer, direct;
        try (MatHdmiDevices devices = new MatHdmiDevices(manager)) {
            long revision = -1;
            for (;;) {
                checkCancelled();
                revision = devices.availability.await(revision, deadline);
                if (revision < 0) throw new IllegalStateException(diagnostics);
                min = AudioTrack.getMinBufferSize(MatCarrierPlayer.RATE,
                        AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, AudioFormat.ENCODING_IEC61937);
                if (min <= 0) {
                    diagnostics = "192kHz/7.1/IEC61937 minimum buffer unavailable: " + min;
                    continue;
                }
                direct = -1;
                if (Build.VERSION.SDK_INT >= 33) {
                    direct = AudioManager.getDirectPlaybackSupport(format(), attributes());
                    if ((direct & AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED) == 0) {
                        diagnostics = "Route does not advertise IEC bitstream playback: " + direct;
                        continue;
                    }
                }
                long cycles = Math.max(2L, (min + (long)MatCarrierPlayer.CYCLE_BYTES - 1) / MatCarrierPlayer.CYCLE_BYTES);
                if (cycles > 16) throw new IllegalStateException("IEC minimum exceeds 320ms diagnostic limit");
                buffer = (int)cycles * MatCarrierPlayer.CYCLE_BYTES;
                diagnostics = "opening Android HDMI AudioTrack";
                checkCancelled();
                try {
                    track = new AudioTrack.Builder().setAudioAttributes(attributes()).setAudioFormat(format())
                            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(buffer).build();
                } catch (UnsupportedOperationException | IllegalStateException e) {
                    diagnostics = "IEC AudioTrack open failed: " + e;
                    continue;
                }
                if (track.getState() == AudioTrack.STATE_INITIALIZED) {
                    checkCancelled();
                    break;
                }
                diagnostics = "IEC track not initialized";
                track.release();
                track = null;
            }
        }
        long openUs = (System.nanoTime() - openStart) / 1000;
        checkCancelled();
        track.addOnRoutingChangedListener(routingListener, null);
        listenerAttached = true;
        track.pause(); track.flush();
        wraps = previousLow = 0;
        // Encoded audio must stop on focus loss instead of ducking the bitstream.
        long focusStart = System.nanoTime();
        focusLost = false;
        checkCancelled();
        if (manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw new IllegalStateException("MAT audio focus denied");
        focusOwned = true;
        checkCancelled();
        long focusUs = (System.nanoTime() - focusStart) / 1000;
        // IEC61937 has proportional 16-bit carrier frames, unlike AC3's byte units.
        // Older platforms expose capacity but not an explicit start threshold.
        startThresholdFrames = Build.VERSION.SDK_INT >= 31 ? track.getStartThresholdInFrames() :
                track.getBufferSizeInFrames();
        diagnostics = "IEC61937 192000Hz 7.1; requestedBuffer=" + buffer +
                "; openUs=" + openUs + "; focusUs=" + focusUs +
                "; minimumBufferBytes=" + min + "; actualBufferFrames=" + track.getBufferSizeInFrames() +
                "; capacityFrames=" + track.getBufferCapacityInFrames() +
                "; primeTargetCarrierFrames=" + startThresholdFrames +
                "; actualEncoding=" + track.getAudioFormat() + "; actualRate=" + track.getSampleRate() +
                "; actualChannels=" + track.getChannelCount() + "; direct=" + direct +
                "; receiver Atmos recognition NOT verified";
        LimeLog.info("MAT IEC track initialized: " + diagnostics);
    }
    @Override public long startThresholdFrames() { return startThresholdFrames; }
    @Override public void play() throws InterruptedException {
        if (closed || track == null || playTime != 0) throw new IllegalStateException("Invalid MAT play transition");
        checkRoute();
        long playCallStart = System.nanoTime();
        checkCancelled();
        track.play();
        playTime = System.nanoTime();
        LimeLog.info("MAT IEC play: primedCarrierFrames=" + submittedFrames +
                "; primeTargetCarrierFrames=" + startThresholdFrames + "; playCallUs=" + (playTime - playCallStart) / 1000 +
                "; firstWriteToPlayUs=" + (firstWriteTime == 0 ? -1 : (playTime - firstWriteTime) / 1000));
    }
    private volatile Consumer<String> failureListener;
    @Override public void setFailureListener(Consumer<String> listener) { failureListener = listener; }
    private volatile boolean focusLost;
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change < 0) {
            focusLost = true;
            Consumer<String> listener = failureListener;
            if (listener != null) listener.accept("MAT audio focus lost");
        }
    };
    private void checkRoute() {
        if (focusLost) throw new IllegalStateException("MAT audio focus lost");
        long now = System.nanoTime();
        // A stable HDMI route took 295 ms to query under load. Validate at startup
        // and again on Android's route notification. Retry while the device is unknown.
        if (routeChanged.getAndSet(false) || now >= nextRouteCheck) {
            validateRoute(now);
        }
        if (routeId != -1 && now >= nextProgressLog) {
            logProgress("progress");
            nextProgressLog = now + 5_000_000_000L;
        }
    }
    private void validateRoute(long now) {
        AudioDeviceInfo device = track.getRoutedDevice();
        routeQueries++;
        maxRouteQuery = Math.max(maxRouteQuery, System.nanoTime() - now);
        if (device == null) {
            nextRouteCheck = now + 50_000_000;
            return;
        }
        int type = device.getType();
        if (!MatHdmiDevices.isHdmi(type)) throw new IllegalStateException("Refusing MAT on non-HDMI output: " + type);
        if (routeId != -1 && routeId != device.getId()) throw new IllegalStateException("MAT HDMI route changed; reconnect");
        if (routeId == -1) {
            LimeLog.info("MAT IEC routed device: id=" + device.getId() + "; type=" + type +
                    "; name=" + device.getProductName() + "; encodings=" + Arrays.toString(device.getEncodings()));
        }
        routeId = device.getId();
        nextRouteCheck = Long.MAX_VALUE;
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
                "; maxWriteCallUs=" + maxWriteCall / 1000 + "; maxRouteQueryUs=" + maxRouteQuery / 1000 +
                "; routeQueries=" + routeQueries +
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
        maxWriteGap = maxWriteCall = maxRouteQuery = 0;
    }
    @Override public int write(short[] words, int offset, int count) {
        checkRoute();
        long before = System.nanoTime();
        int written = track.write(words, offset, count, AudioTrack.WRITE_NON_BLOCKING);
        if (written == AudioTrack.ERROR_DEAD_OBJECT)
            throw new IllegalStateException("HDMI AudioTrack invalidated by Android (ERROR_DEAD_OBJECT -6)");
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
    private static RuntimeException attempt(RuntimeException first, Runnable action) {
        try { action.run(); }
        catch (RuntimeException failure) {
            if (first == null) return failure;
            first.addSuppressed(failure);
        }
        return first;
    }
    static void logClosingProgress(BooleanSupplier initialized, Runnable readProgress) {
        try {
            if (initialized.getAsBoolean()) readProgress.run();
        } catch (RuntimeException detail) {
            LimeLog.warning("MAT closing playback diagnostics unavailable: " + detail);
        }
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        failureListener = null;
        RuntimeException first = null;
        if (track != null) {
            if (listenerAttached) first = attempt(first, () -> track.removeOnRoutingChangedListener(routingListener));
            logClosingProgress(() -> track.getState() == AudioTrack.STATE_INITIALIZED,
                    () -> logProgress("closing"));
            first = attempt(first, () -> {
                if (track.getState() == AudioTrack.STATE_INITIALIZED) track.pause();
            });
            first = attempt(first, () -> {
                if (track.getState() == AudioTrack.STATE_INITIALIZED) track.flush();
            });
            first = attempt(first, track::release);
            track = null;
            timestampValid = false;
        }
        if (focusOwned) {
            focusOwned = false;
            first = attempt(first, () -> {
                AudioManager manager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
                manager.abandonAudioFocus(focusListener);
            });
        }
        if (first != null) throw first;
    }
}
