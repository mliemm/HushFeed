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

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The host side of a LIVE: which device captures, whether the LIVE engine isolates background
 * noise, and whether push to talk keeps the microphone silent between presses.
 *
 * <p>The recording bridge calls {@link #begin} before the engine starts, {@link #started} once
 * the native start returned success, and {@link #end} for every stop or release, including the
 * stop the engine schedules itself.
 */
public final class LiveMicrophone {
    private static final Set<Object> started = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Object> pending = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

    private static volatile boolean noiseSuppression;
    private static volatile boolean pushToTalk;
    private static volatile boolean talking;
    private static volatile boolean pendingPushToTalk;
    private static byte[] silence;

    private LiveMicrophone() {
    }

    public static boolean begin(Object recorder) {
        boolean pendingAdded = false;
        synchronized (started) {
            if (Settings.LIVE_PUSH_TO_TALK.get() && !started.contains(recorder)) {
                pendingAdded = pending.add(recorder);
                pendingPushToTalk = !pending.isEmpty();
            }
        }
        boolean routeAdded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Route.begin(recorder);
        return pendingAdded || routeAdded;
    }

    /**
     * A Lyrax capture outside the engine module {@link #begin} follows is starting: the Java
     * recorder picking its source, or a native capture start in the audio core, live core or
     * interact client. In communication mode Android routes their microphone input to the
     * communication device.
     */
    public static void route(Object recorder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Route.begin(recorder);
        }
    }

    /** A capture {@link #route} followed is stopping or being released. */
    public static void unroute(Object recorder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Route.end(recorder);
        }
    }

    /** The engine has started; the settings that run on top of capture can be applied now. */
    public static void started(Object recorder) {
        boolean first;
        synchronized (started) {
            first = started.add(recorder);
        }
        if (!first) {
            return;
        }
        if (Settings.LIVE_SOUND_ISOLATION.get()) {
            noiseSuppression = true;
            invoke(recorder, "enableAudioNoiseDetectionCfg", new Class<?>[]{boolean.class}, true);
            invoke(recorder, "enableANS", new Class<?>[]{boolean.class}, true);
        }
        if (Settings.LIVE_PUSH_TO_TALK.get()) {
            pushToTalk = true;
            talking = false;
            LivePushToTalk.start();
        }
        synchronized (started) {
            pending.remove(recorder);
            pendingPushToTalk = !pending.isEmpty();
        }
    }

    public static void end(Object recorder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Route.end(recorder);
        }
        boolean last;
        synchronized (started) {
            pending.remove(recorder);
            pendingPushToTalk = !pending.isEmpty();
            started.remove(recorder);
            last = started.isEmpty();
        }
        if (!last) {
            return;
        }
        if (noiseSuppression) {
            noiseSuppression = false;
            invoke(recorder, "enableANS", new Class<?>[]{boolean.class}, false);
            invoke(recorder, "enableAudioNoiseDetectionCfg", new Class<?>[]{boolean.class}, false);
        }
        if (pushToTalk) {
            pushToTalk = false;
            talking = false;
            LivePushToTalk.stop();
        }
    }

    /** True while push to talk is on and the button is not held. */
    public static boolean isSilent() {
        return pendingPushToTalk || (pushToTalk && !talking);
    }

    public static void setTalking(boolean value) {
        talking = value;
    }

    /**
     * The engine hands the captured PCM to this before the encoder sees it. Push to talk zeroes
     * the frame between presses; the app's own mute still zeroes it when TikTok is muted.
     */
    public static void processFrame(ByteBuffer buffer) {
        if (!isSilent() || buffer == null) {
            return;
        }
        int capacity = buffer.capacity();
        byte[] zeros = silence;
        if (zeros == null || zeros.length != capacity) {
            zeros = new byte[capacity];
            silence = zeros;
        }
        buffer.clear();
        buffer.put(zeros);
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

    /**
     * Calls one of the engine's public controls. The recorder is the audio engine's own module;
     * the extension compiles separately, so the methods are reached by name.
     */
    private static void invoke(Object recorder, String name, Class<?>[] parameters, Object value) {
        try {
            String key = name + java.util.Arrays.toString(parameters);
            Method method = METHODS.get(key);
            if (method == null) {
                method = recorder.getClass().getMethod(name, parameters);
                method.setAccessible(true);
                METHODS.put(key, method);
            }
            method.invoke(recorder, value);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not call the LIVE audio control " + name, failure);
        }
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
