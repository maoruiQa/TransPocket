/*
 * Copyright 2016 Luca Martino.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.tools;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

/**
 * Keeps a Bluetooth media link awake for the whole simultaneous session.
 * An idle headset drops the first few hundred milliseconds of the next stream.
 * A stream that is already playing, including this one, does not.
 * The samples are far below conversational level so the headset does not speak them.
 */
public final class BluetoothRouteWake {
    private static final String TAG = "tts_engine";
    private static final int SAMPLE_RATE = 48000;
    /** About -72 dB. High enough that the mixer still emits packets, too low to hear. */
    private static final int AMPLITUDE = 8;

    private final Object lock = new Object();
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    @Nullable
    private AudioManager audioManager;
    @Nullable
    private AudioDeviceCallback callback;
    @Nullable
    private AudioTrack track;
    @Nullable
    private Thread thread;
    private int generation;
    private volatile boolean running;

    /** Starts the loop when a Bluetooth output is already connected, and follows connect and disconnect. */
    public void attach(Context context) {
        detach();
        AudioManager manager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        audioManager = manager;
        if (manager == null) {
            return;
        }
        callback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                sync();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                sync();
            }
        };
        manager.registerAudioDeviceCallback(callback, new Handler(Looper.getMainLooper()));
        sync();
    }

    public void detach() {
        AudioManager manager = audioManager;
        AudioDeviceCallback current = callback;
        if (manager != null && current != null) {
            manager.unregisterAudioDeviceCallback(current);
        }
        callback = null;
        audioManager = null;
        stopLoop();
    }

    /** Opens or closes the loop to match the outputs connected right now. */
    public void sync() {
        if (bluetoothOutputConnected()) {
            startLoop();
        } else {
            stopLoop();
        }
    }

    private boolean bluetoothOutputConnected() {
        AudioManager manager = audioManager;
        if (manager == null) {
            return false;
        }
        AudioDeviceInfo[] devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (int i = 0; i < devices.length; i++) {
            switch (devices[i].getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                case AudioDeviceInfo.TYPE_HEARING_AID:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    private void startLoop() {
        synchronized (lock) {
            if (running && thread != null && thread.isAlive()) {
                return;
            }
            running = true;
            final int token = ++generation;
            Thread worker = new Thread(() -> runLoop(token), "bt-route-wake");
            thread = worker;
            worker.start();
        }
    }

    private void stopLoop() {
        AudioTrack current;
        synchronized (lock) {
            running = false;
            generation++;
            current = track;
        }
        if (current == null) {
            return;
        }
        try {
            current.pause();
            current.stop();
        } catch (IllegalStateException ignored) {
            // The track is already stopping.
        }
    }

    private void runLoop(int token) {
        AudioTrack local = buildTrack();
        if (local == null) {
            return;
        }
        synchronized (lock) {
            if (token != generation || !running) {
                local.release();
                return;
            }
            track = local;
        }
        byte[] chunk = quietChunk();
        try {
            local.play();
            Log.i(TAG, "bluetooth route wake looping");
            while (token == generation && running) {
                int written = local.write(chunk, 0, chunk.length);
                if (written < 0) {
                    break;
                }
            }
        } catch (IllegalStateException ignored) {
            // stop() closed the track.
        } finally {
            synchronized (lock) {
                if (track == local) {
                    track = null;
                }
            }
            try {
                local.release();
            } catch (IllegalStateException ignored) {
                // Already released.
            }
        }
    }

    @Nullable
    private AudioTrack buildTrack() {
        int channel = AudioFormat.CHANNEL_OUT_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, channel, encoding);
        int chunkBytes = quietChunk().length;
        int bytes = Math.max(minBytes, chunkBytes * 4);
        if (bytes <= 0) {
            return null;
        }
        try {
            AudioTrack built = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(channel)
                            .build())
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (built.getState() != AudioTrack.STATE_INITIALIZED) {
                built.release();
                Log.w(TAG, "bluetooth route wake was not created");
                return null;
            }
            return built;
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "bluetooth route wake was not created");
            return null;
        }
    }

    private static byte[] quietChunk() {
        int frames = SAMPLE_RATE / 50;
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < pcm.length; i += 2) {
            pcm[i] = (byte) ((i / 2 % 2 == 0) ? AMPLITUDE : -AMPLITUDE);
        }
        return pcm;
    }
}
