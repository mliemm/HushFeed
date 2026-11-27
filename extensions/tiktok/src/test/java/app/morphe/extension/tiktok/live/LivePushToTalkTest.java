package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.blockauthor.LiveRoomBlockTest;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** The Talk chip and Volume Up key of push to talk in a LIVE room. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LivePushToTalkTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ActivityController<LiveRoomBlockTest.RoomActivity> room;
    private LiveRoomInfoTest.Page front;
    private final Object recorder = new Object();

    @Before public void setUp() {
        Settings.LIVE_PUSH_TO_TALK.save(true);
        room = Robolectric.buildActivity(LiveRoomBlockTest.RoomActivity.class).setup().visible().windowFocusChanged(true);
        Utils.setContext(room.get());
        Utils.setActivity(room.get());
        LiveRoomBlockTest.Pages pages = new LiveRoomBlockTest.Pages(room.get());
        room.get().setContentView(pages);
        idle();
        int width = pages.getWidth() > 0 ? pages.getWidth() : 400;
        int height = pages.getHeight() > 0 ? pages.getHeight() : 800;
        front = new LiveRoomInfoTest.Page(room.get(), pages, "7301234567890123456");
        front.view.layout(0, 0, width, height);
    }

    @After public void tearDown() {
        LiveMicrophone.end(recorder);
        idle();
        Settings.LIVE_PUSH_TO_TALK.resetToDefault();
        room.close();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private TextView chip() {
        ViewGroup content = room.get().findViewById(android.R.id.content);
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child instanceof TextView && "Hold to talk".contentEquals(((TextView) child).getText())) {
                return (TextView) child;
            }
        }
        return null;
    }

    private static void touch(View view, int action) {
        view.dispatchTouchEvent(MotionEvent.obtain(0, 0, action, 1, 1, 0));
    }

    @Test public void holdingTheChipTalksAndReleasingMutes() {
        LiveMicrophone.started(recorder);
        LiveRecordChip.noteRoomFragment(front);
        idle();

        TextView chip = chip();
        assertNotNull(chip);
        assertTrue(LiveMicrophone.isSilent());
        touch(chip, MotionEvent.ACTION_DOWN);
        assertFalse(LiveMicrophone.isSilent());
        touch(chip, MotionEvent.ACTION_UP);
        assertTrue(LiveMicrophone.isSilent());
    }

    @Test public void theChipIsGoneWhenTheLiveEnds() {
        LiveMicrophone.started(recorder);
        LiveRecordChip.noteRoomFragment(front);
        idle();
        TextView chip = chip();
        touch(chip, MotionEvent.ACTION_DOWN);
        assertFalse(LiveMicrophone.isSilent());

        LiveMicrophone.end(recorder);
        idle();
        assertNull(chip());
        assertFalse(LiveMicrophone.isSilent());
    }

    @Test public void noChipShowsWithoutALiveRecording() {
        LiveRecordChip.noteRoomFragment(front);
        idle();
        assertNull(chip());
    }

    @Test public void volumeUpHoldsTheMicrophoneOpen() {
        LiveMicrophone.started(recorder);
        idle();
        Window.Callback callback = room.get().getWindow().getCallback();
        assertTrue(callback.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)));
        assertFalse(LiveMicrophone.isSilent());
        assertTrue(callback.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP)));
        assertTrue(LiveMicrophone.isSilent());
    }

    @Test public void pausingTheActivityReleasesTheKey() {
        LiveMicrophone.started(recorder);
        idle();
        Activity activity = room.get();
        activity.getWindow().getCallback()
                .dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(LiveMicrophone.isSilent());
        room.pause();
        assertTrue(LiveMicrophone.isSilent());
    }
}
