/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.RequiresApi;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

public final class LiveBluetoothMicrophone {
    private LiveBluetoothMicrophone() {
    }

    public static boolean begin(Object recorder) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Route.begin(recorder);
    }

    public static void end(Object recorder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Route.end(recorder);
        }
    }

    public static int captureSource(int source) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Route.isSelected()) {
            switch (source) {
                case MediaRecorder.AudioSource.DEFAULT:
                case MediaRecorder.AudioSource.MIC:
                case MediaRecorder.AudioSource.CAMCORDER:
                case MediaRecorder.AudioSource.VOICE_RECOGNITION:
                case MediaRecorder.AudioSource.UNPROCESSED:
                case MediaRecorder.AudioSource.VOICE_PERFORMANCE:
                    return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            }
        }
        return source;
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static final class Route {
        private static final Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());
        private static final AudioDeviceCallback devices = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                refresh();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                refresh();
            }
        };

        private static AudioManager audio;
        private static boolean listening;
        private static boolean ownsMode;
        private static boolean ownsDevice;
        private static int selectedDevice = -1;

        static synchronized boolean begin(Object recorder) {
            if (!Settings.LIVE_BLUETOOTH_MICROPHONE.get() || active.contains(recorder)) {
                return false;
            }
            try {
                if (audio == null) {
                    Context context = Utils.getContext();
                    if (context == null) {
                        return false;
                    }
                    audio = context.getSystemService(AudioManager.class);
                    if (audio == null) {
                        return false;
                    }
                    audio.registerAudioDeviceCallback(devices, new Handler(Looper.getMainLooper()));
                    listening = true;
                }
                active.add(recorder);
                refresh();
                return true;
            } catch (RuntimeException failure) {
                Logger.printException(() -> "Could not start LIVE Bluetooth routing", failure);
                if (active.isEmpty()) {
                    close();
                }
                return false;
            }
        }

        static synchronized void end(Object recorder) {
            if (active.remove(recorder) && active.isEmpty()) {
                close();
            }
        }

        static synchronized boolean isSelected() {
            return selectedDevice != -1;
        }

        private static synchronized void refresh() {
            if (active.isEmpty()) {
                return;
            }
            try {
                int mode = audio.getMode();
                if (!Settings.LIVE_BLUETOOTH_MICROPHONE.get()
                        || (mode != AudioManager.MODE_NORMAL
                        && mode != AudioManager.MODE_IN_COMMUNICATION)) {
                    clearSelection();
                    return;
                }

                AudioDeviceInfo current = audio.getCommunicationDevice();
                AudioDeviceInfo headset = null;
                for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices()) {
                    int type = device.getType();
                    if (type != AudioDeviceInfo.TYPE_BLE_HEADSET && type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        continue;
                    }
                    if (mode == AudioManager.MODE_IN_COMMUNICATION && current != null
                            && current.getId() == device.getId()) {
                        selectedDevice = device.getId();
                        return;
                    }
                    if (headset == null || type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                        headset = device;
                    }
                }
                if (headset == null) {
                    clearSelection();
                    return;
                }
                if (mode == AudioManager.MODE_IN_COMMUNICATION && headset.getId() == selectedDevice && ownsDevice) {
                    return;
                }
                if (mode == AudioManager.MODE_NORMAL) {
                    audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    ownsMode = true;
                }
                if (audio.setCommunicationDevice(headset)) {
                    selectedDevice = headset.getId();
                    ownsDevice = true;
                    final int type = headset.getType();
                    Logger.printDebug(() -> "Requested LIVE Bluetooth audio device type " + type);
                } else {
                    clearSelection();
                    Logger.printDebug(() -> "Android declined the LIVE Bluetooth audio route");
                }
            } catch (RuntimeException failure) {
                clearSelection();
                Logger.printException(() -> "Could not select a LIVE Bluetooth audio route", failure);
            }
        }

        private static void clearSelection() {
            selectedDevice = -1;
            if (ownsDevice) {
                ownsDevice = false;
                try {
                    audio.clearCommunicationDevice();
                } catch (RuntimeException failure) {
                    Logger.printException(() -> "Could not clear the LIVE Bluetooth audio route", failure);
                }
            }
            if (ownsMode) {
                ownsMode = false;
                try {
                    audio.setMode(AudioManager.MODE_NORMAL);
                } catch (RuntimeException failure) {
                    Logger.printException(() -> "Could not restore the audio mode after LIVE", failure);
                }
            }
        }

        private static void close() {
            clearSelection();
            if (listening) {
                listening = false;
                try {
                    audio.unregisterAudioDeviceCallback(devices);
                } catch (RuntimeException failure) {
                    Logger.printException(() -> "Could not unregister the LIVE audio device callback", failure);
                }
            }
            audio = null;
        }
    }
}
