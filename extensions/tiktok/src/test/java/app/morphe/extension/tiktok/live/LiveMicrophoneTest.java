package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder.AudioSource;
import android.os.Looper;
import android.app.Activity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.shadows.ShadowAudioManager;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.shadows.ShadowWindowManagerImpl;

import java.nio.ByteBuffer;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LiveMicrophoneTest {
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
        Settings.LIVE_SOUND_ISOLATION.save(false);
        Settings.LIVE_PUSH_TO_TALK.save(false);
        ShadowSettings.setCanDrawOverlays(false);
    }

    @After public void tearDown() {
        LiveMicrophone.end(first);
        LiveMicrophone.end(second);
        shadowOf(Looper.getMainLooper()).idle();
        Settings.LIVE_BLUETOOTH_MICROPHONE.save(false);
        Settings.LIVE_SOUND_ISOLATION.save(false);
        Settings.LIVE_PUSH_TO_TALK.save(false);
        ShadowSettings.setCanDrawOverlays(false);
    }

    @Test public void classicHeadsetUsesVoiceCaptureUntilTheLastRecorderEnds() {
        AudioDeviceInfo headset = availableDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        assertTrue(LiveMicrophone.begin(first));
        assertTrue(LiveMicrophone.begin(second));
        assertFalse(LiveMicrophone.begin(first));
        assertSame(headset, audio.getCommunicationDevice());
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.getMode());
        assertEquals(AudioSource.VOICE_COMMUNICATION, LiveMicrophone.captureSource(AudioSource.CAMCORDER));
        assertEquals(AudioSource.REMOTE_SUBMIX, LiveMicrophone.captureSource(AudioSource.REMOTE_SUBMIX));

        LiveMicrophone.end(first);
        assertSame(headset, audio.getCommunicationDevice());
        LiveMicrophone.end(second);
        assertNull(audio.getCommunicationDevice());
        assertEquals(AudioManager.MODE_NORMAL, audio.getMode());
        assertEquals(AudioSource.CAMCORDER, LiveMicrophone.captureSource(AudioSource.CAMCORDER));
    }

    @Test public void reconnectSelectsAnLeAudioHeadset() {
        AudioDeviceInfo headset = availableDevice(AudioDeviceInfo.TYPE_BLE_HEADSET);
        assertTrue(LiveMicrophone.begin(first));
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
        assertTrue(LiveMicrophone.begin(first));

        assertNull(audio.getCommunicationDevice());
        assertEquals(AudioManager.MODE_NORMAL, audio.getMode());
        assertEquals(AudioSource.MIC, LiveMicrophone.captureSource(AudioSource.MIC));
    }

    @Test public void settingOffLeavesTheRecorderAndRouteAlone() {
        availableDevice(AudioDeviceInfo.TYPE_BLE_HEADSET);
        Settings.LIVE_BLUETOOTH_MICROPHONE.save(false);
        assertFalse(LiveMicrophone.begin(first));

        assertNull(audio.getCommunicationDevice());
        assertEquals(AudioManager.MODE_NORMAL, audio.getMode());
        assertEquals(AudioSource.MIC, LiveMicrophone.captureSource(AudioSource.MIC));
    }

    @Test public void aPhoneCallKeepsControlOfItsAudio() {
        availableDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        audio.setMode(AudioManager.MODE_IN_CALL);
        assertTrue(LiveMicrophone.begin(first));
        assertNull(audio.getCommunicationDevice());
        assertEquals(AudioManager.MODE_IN_CALL, audio.getMode());
        LiveMicrophone.end(first);

        assertNull(audio.getCommunicationDevice());
        assertEquals(AudioManager.MODE_IN_CALL, audio.getMode());
    }

    @Test public void anExistingBluetoothCallRouteStaysSelected() {
        AudioDeviceInfo headset = availableDevice(AudioDeviceInfo.TYPE_BLE_HEADSET);
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        assertTrue(audio.setCommunicationDevice(headset));

        assertTrue(LiveMicrophone.begin(first));
        LiveMicrophone.end(first);
        assertSame(headset, audio.getCommunicationDevice());
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.getMode());
    }

    @Test public void pushToTalkSilencesFramesUntilTheButtonIsHeld() {
        Settings.LIVE_PUSH_TO_TALK.save(true);
        Object recorder = new Object();
        LiveMicrophone.started(recorder);
        shadowOf(Looper.getMainLooper()).idle();

        ByteBuffer frame = ByteBuffer.allocateDirect(16);
        for (int i = 0; i < frame.capacity(); i++) {
            frame.put(i, (byte) (i + 1));
        }
        LiveMicrophone.processFrame(frame);
        for (int i = 0; i < frame.capacity(); i++) {
            assertEquals(0, frame.get(i));
        }

        LiveMicrophone.setTalking(true);
        frame.put(0, (byte) 7);
        LiveMicrophone.processFrame(frame);
        assertEquals(7, frame.get(0));

        LiveMicrophone.setTalking(false);
        LiveMicrophone.processFrame(frame);
        assertEquals(0, frame.get(0));

        LiveMicrophone.end(recorder);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void pushToTalkSilencesFramesDuringNativeStartAndClearsAfterFailure() {
        Settings.LIVE_BLUETOOTH_MICROPHONE.save(false);
        Settings.LIVE_PUSH_TO_TALK.save(true);
        assertTrue(LiveMicrophone.begin(first));
        ByteBuffer frame = ByteBuffer.allocateDirect(8);
        frame.put(0, (byte) 5);
        LiveMicrophone.processFrame(frame);
        assertEquals(0, frame.get(0));
        LiveMicrophone.end(first);
        frame.put(0, (byte) 5);
        LiveMicrophone.processFrame(frame);
        assertEquals(5, frame.get(0));
    }

    @Test public void pushToTalkDoesNothingWhenTheSettingIsOff() {
        Object recorder = new Object();
        LiveMicrophone.started(recorder);
        shadowOf(Looper.getMainLooper()).idle();

        ByteBuffer frame = ByteBuffer.allocateDirect(8);
        frame.put(0, (byte) 5);
        LiveMicrophone.processFrame(frame);
        assertEquals(5, frame.get(0));

        LiveMicrophone.end(recorder);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void soundIsolationUsesTheEngineNoiseSuppression() {
        Settings.LIVE_SOUND_ISOLATION.save(true);
        FakeAudioDevice recorder = new FakeAudioDevice();
        LiveMicrophone.started(recorder);
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(recorder.configuration);
        assertTrue(recorder.suppression);

        LiveMicrophone.end(recorder);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(recorder.configuration);
        assertFalse(recorder.suppression);
    }

    @Test public void pushToTalkShowsAButtonWhileTheLiveRuns() {
        Settings.LIVE_PUSH_TO_TALK.save(true);
        ActivityController<TestActivity> controller =
                Robolectric.buildActivity(TestActivity.class).setup();
        Activity activity = controller.get();
        Utils.setActivity(activity);
        Object recorder = new Object();
        LiveMicrophone.started(recorder);
        shadowOf(Looper.getMainLooper()).idle();

        View button = findTalkButton(activity);
        assertNotNull(button);
        assertTrue(LiveMicrophone.isSilent());

        button.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1, 1, 0));
        assertFalse(LiveMicrophone.isSilent());
        button.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 1, 1, 0));
        assertTrue(LiveMicrophone.isSilent());

        LiveMicrophone.end(recorder);
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(findTalkButton(activity));
        controller.pause().stop().destroy();
    }

    @Test public void pushToTalkUsesASystemOverlayWhenAllowed() {
        Settings.LIVE_PUSH_TO_TALK.save(true);
        ShadowSettings.setCanDrawOverlays(true);
        Object recorder = new Object();
        LiveMicrophone.started(recorder);
        shadowOf(Looper.getMainLooper()).idle();

        WindowManager manager = (WindowManager) RuntimeEnvironment.getApplication()
                .getSystemService(Context.WINDOW_SERVICE);
        ShadowWindowManagerImpl shadowWindow = (ShadowWindowManagerImpl) shadowOf(manager);
        List<View> views = shadowWindow.getViews();
        assertEquals(1, views.size());
        View button = views.get(0);
        assertTrue(button instanceof TextView);
        assertEquals("Talk", ((TextView) button).getText().toString());

        button.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1, 1, 0));
        assertFalse(LiveMicrophone.isSilent());
        button.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 1, 1, 0));
        assertTrue(LiveMicrophone.isSilent());

        LiveMicrophone.end(recorder);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, shadowWindow.getViews().size());
    }

    @Test public void volumeUpTalksWithoutTheOverlayPermission() {
        Settings.LIVE_PUSH_TO_TALK.save(true);
        ActivityController<TestActivity> controller =
                Robolectric.buildActivity(TestActivity.class).setup();
        Activity activity = controller.get();
        Utils.setActivity(activity);
        Object recorder = new Object();
        LiveMicrophone.started(recorder);
        shadowOf(Looper.getMainLooper()).idle();

        Window.Callback callback = activity.getWindow().getCallback();
        assertTrue(callback.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)));
        assertFalse(LiveMicrophone.isSilent());
        assertTrue(callback.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP)));
        assertTrue(LiveMicrophone.isSilent());

        LiveMicrophone.end(recorder);
        shadowOf(Looper.getMainLooper()).idle();
        controller.pause().stop().destroy();
    }

    @Test public void pushToTalkPutsTheButtonBackWhenTheRoomRebuildsItsView() {
        Settings.LIVE_PUSH_TO_TALK.save(true);
        ActivityController<TestActivity> controller =
                Robolectric.buildActivity(TestActivity.class).setup();
        Activity activity = controller.get();
        Utils.setActivity(activity);
        Object recorder = new Object();
        LiveMicrophone.started(recorder);
        shadowOf(Looper.getMainLooper()).idle();

        View button = findTalkButton(activity);
        assertNotNull(button);
        ViewGroup root = activity.findViewById(android.R.id.content);
        root.removeView(button);
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        assertNotNull(findTalkButton(activity));

        controller.pause();
        assertNull(findTalkButton(activity));
        controller.resume();
        assertNotNull(findTalkButton(activity));

        LiveMicrophone.end(recorder);
        shadowOf(Looper.getMainLooper()).idle();
        controller.pause().stop().destroy();
    }

    @Test public void pushToTalkButtonStaysAwayWhenTheSettingIsOff() {
        ActivityController<TestActivity> controller =
                Robolectric.buildActivity(TestActivity.class).setup();
        Activity activity = controller.get();
        Utils.setActivity(activity);
        Object recorder = new Object();
        LiveMicrophone.started(recorder);
        shadowOf(Looper.getMainLooper()).idle();

        assertNull(findTalkButton(activity));

        LiveMicrophone.end(recorder);
        shadowOf(Looper.getMainLooper()).idle();
        controller.pause().stop().destroy();
    }

    public static final class TestActivity extends Activity {
    }

    private static View findTalkButton(Activity activity) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) {
            return null;
        }
        for (int index = 0; index < root.getChildCount(); index++) {
            View child = root.getChildAt(index);
            if (child instanceof TextView
                    && "Talk".contentEquals(((TextView) child).getText())) {
                return child;
            }
        }
        return null;
    }

    public static final class FakeAudioDevice {
        public boolean configuration;
        public boolean suppression;

        public void enableAudioNoiseDetectionCfg(boolean value) {
            configuration = value;
        }

        public void enableANS(boolean value) {
            suppression = value;
        }
    }

    private AudioDeviceInfo availableDevice(int type) {
        AudioDeviceInfo device = AudioDeviceInfoBuilder.newBuilder().setType(type).build();
        shadowAudio.setAvailableCommunicationDevices(List.of(device));
        return device;
    }
}
