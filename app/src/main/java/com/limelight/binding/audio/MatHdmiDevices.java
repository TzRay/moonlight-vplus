package com.limelight.binding.audio;

import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.RequiresApi;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Observe the HDMI output before an AudioTrack exists, including during recovery. */
@RequiresApi(Build.VERSION_CODES.N)
final class MatHdmiDevices implements AutoCloseable {
    static boolean isHdmi(int type) {
        return type == AudioDeviceInfo.TYPE_HDMI || type == AudioDeviceInfo.TYPE_HDMI_ARC ||
                (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_HDMI_EARC);
    }

    static final class Availability {
        private int[] devices = new int[0];
        private long revision;

        synchronized void update(int[] current, boolean sawRemoval) {
            Arrays.sort(current);
            if (!sawRemoval && Arrays.equals(devices, current)) return;
            devices = current;
            ++revision;
            notifyAll();
        }

        synchronized long await(long previous, long deadline) throws InterruptedException {
            for (;;) {
                if (Thread.interrupted()) throw new InterruptedException("HDMI wait cancelled");
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return -1;
                if (devices.length != 0 && previous != revision) return revision;
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }
    }

    private final AudioManager manager;
    final Availability availability = new Availability();
    private boolean closed;
    private final AudioDeviceCallback callback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { changed(added, false); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { changed(removed, true); }
    };

    MatHdmiDevices(AudioManager manager) {
        this.manager = manager;
        manager.registerAudioDeviceCallback(callback, new Handler(Looper.getMainLooper()));
        try {
            refresh(false);
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    private void changed(AudioDeviceInfo[] devices, boolean removed) {
        for (AudioDeviceInfo device : devices) {
            if (device.isSink() && isHdmi(device.getType())) {
                refresh(removed);
                return;
            }
        }
    }

    private synchronized void refresh(boolean removed) {
        if (closed) return;
        AudioDeviceInfo[] devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        int[] ids = new int[devices.length];
        int count = 0;
        for (AudioDeviceInfo device : devices) {
            if (isHdmi(device.getType())) ids[count++] = device.getId();
        }
        availability.update(Arrays.copyOf(ids, count), removed);
    }

    @Override public void close() {
        synchronized (this) { closed = true; }
        manager.unregisterAudioDeviceCallback(callback);
    }
}
