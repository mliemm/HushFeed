package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder.AudioSource;
import android.os.Looper;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.shadows.ShadowAudioManager;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LiveBluetoothMicrophoneTest {
    private final Object first = new Object();
    private final Object second = new Object();
    private AudioManager audio;
    private ShadowAudioManager shadowAudio;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        audio = context.getSystemService(AudioManager.class);
        shadowAudio = shadowOf(audio);
        Settings.LIVE_BLUETOOTH_MICROPHONE.save(true);
    }

    @After public void tearDown() {
        LiveBluetoothMicrophone.end(first);
        LiveBluetoothMicrophone.end(second);
        shadowAudio.lockCommunicationDevice(false);
        Settings.LIVE_BLUETOOTH_MICROPHONE.save(false);
    }

    @Test public void classicHeadsetUsesVoiceCaptureUntilTheLastRecorderEnds() {
        AudioDeviceInfo headset = availableDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        assertTrue(LiveBluetoothMicrophone.begin(first));
        assertTrue(LiveBluetoothMicrophone.begin(second));
        assertFalse(LiveBluetoothMicrophone.begin(first));
        assertSame(headset, audio.getCommunicationDevice());
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.getMode());
        assertEquals(AudioSource.VOICE_COMMUNICATION, LiveBluetoothMicrophone.captureSource(AudioSource.CAMCORDER));
        assertEquals(AudioSource.REMOTE_SUBMIX, LiveBluetoothMicrophone.captureSource(AudioSource.REMOTE_SUBMIX));

        LiveBluetoothMicrophone.end(first);
        assertSame(headset, audio.getCommunicationDevice());
        LiveBluetoothMicrophone.end(second);
        assertNull(audio.getCommunicationDevice());
        assertEquals(AudioManager.MODE_NORMAL, audio.getMode());
        assertEquals(AudioSource.CAMCORDER, LiveBluetoothMicrophone.captureSource(AudioSource.CAMCORDER));
    }

    @Test public void reconnectSelectsAnLeAudioHeadset() {
        AudioDeviceInfo headset = availableDevice(AudioDeviceInfo.TYPE_BLE_HEADSET);
        assertTrue(LiveBluetoothMicrophone.begin(first));
        assertSame(headset, audio.getCommunicationDevice());

        shadowAudio.removeAvailableCommunicationDevice(headset, true);
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(audio.getCommunicationDevice());
        assertEquals(AudioManager.MODE_NORMAL, audio.getMode());

        shadowAudio.addAvailableCommunicationDevice(headset, true);
        shadowOf(Looper.getMainLooper()).idle();
        assertSame(headset, audio.getCommunicationDevice());
    }

    @Test public void aRejectedRouteRestoresTheAudioMode() {
        availableDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        shadowAudio.lockCommunicationDevice(true);
        assertTrue(LiveBluetoothMicrophone.begin(first));

        assertNull(audio.getCommunicationDevice());
        assertEquals(AudioManager.MODE_NORMAL, audio.getMode());
        assertEquals(AudioSource.MIC, LiveBluetoothMicrophone.captureSource(AudioSource.MIC));
    }

    @Test public void settingOffLeavesTheRecorderAndRouteAlone() {
        availableDevice(AudioDeviceInfo.TYPE_BLE_HEADSET);
        Settings.LIVE_BLUETOOTH_MICROPHONE.save(false);
        assertFalse(LiveBluetoothMicrophone.begin(first));

        assertNull(audio.getCommunicationDevice());
        assertEquals(AudioManager.MODE_NORMAL, audio.getMode());
        assertEquals(AudioSource.MIC, LiveBluetoothMicrophone.captureSource(AudioSource.MIC));
    }

    @Test public void aPhoneCallKeepsControlOfItsAudio() {
        availableDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        audio.setMode(AudioManager.MODE_IN_CALL);
        assertTrue(LiveBluetoothMicrophone.begin(first));
        assertNull(audio.getCommunicationDevice());
        assertEquals(AudioManager.MODE_IN_CALL, audio.getMode());
        LiveBluetoothMicrophone.end(first);

        assertNull(audio.getCommunicationDevice());
        assertEquals(AudioManager.MODE_IN_CALL, audio.getMode());
    }

    @Test public void anExistingBluetoothCallRouteStaysSelected() {
        AudioDeviceInfo headset = availableDevice(AudioDeviceInfo.TYPE_BLE_HEADSET);
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        assertTrue(audio.setCommunicationDevice(headset));

        assertTrue(LiveBluetoothMicrophone.begin(first));
        LiveBluetoothMicrophone.end(first);
        assertSame(headset, audio.getCommunicationDevice());
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.getMode());
    }

    private AudioDeviceInfo availableDevice(int type) {
        AudioDeviceInfo device = AudioDeviceInfoBuilder.newBuilder().setType(type).build();
        shadowAudio.setAvailableCommunicationDevices(List.of(device));
        return device;
    }
}
