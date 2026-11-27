/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Push to talk in a LIVE room: hold the Talk chip or Volume Up to open the microphone, release
 * to mute. The chip is one of Hushfeed's stacked room buttons, like Record, and shows only while
 * a room page is in front and push to talk is on. Releasing, pausing the activity, leaving the
 * room or ending the LIVE all return the microphone to silence.
 */
public final class LivePushToTalk {
    private static final long TICK_MS = 1000;
    private static final int TOP_DP = 244;
    private static final int GAP_DP = 8;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable TICK = LivePushToTalk::tick;

    private static boolean active;
    private static boolean talking;
    private static boolean following;
    private static Application application;
    private static WeakReference<Activity> host = new WeakReference<>(null);
    private static TextView chip;
    private static Window keyWindow;
    private static Window.Callback keyInner;
    private static Window.Callback keyProxy;

    private static final Application.ActivityLifecycleCallbacks CALLBACKS =
            new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity activity) {
                    if (active) {
                        watchKeys(activity);
                    }
                }

                @Override public void onActivityPaused(Activity activity) {
                    if (activity.getWindow() == keyWindow) {
                        unwatchKeys();
                    }
                    if (activity == host.get()) {
                        HANDLER.removeCallbacks(TICK);
                        removeChip();
                    }
                }

                @Override public void onActivityCreated(Activity activity, Bundle state) { }
                @Override public void onActivityStarted(Activity activity) { }
                @Override public void onActivityStopped(Activity activity) { }
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
                @Override public void onActivityDestroyed(Activity activity) {
                    if (activity == host.get()) {
                        HANDLER.removeCallbacks(TICK);
                        removeChip();
                        host = new WeakReference<>(null);
                    }
                }
            };

    private LivePushToTalk() {
    }

    public static void start() {
        Utils.runOnMainThread(() -> {
            if (active) {
                return;
            }
            active = true;
            follow();
            Activity visible = Utils.getVisibleActivity();
            watchKeys(visible);
            if (visible != null) {
                host = new WeakReference<>(visible);
                tick();
            }
        });
    }

    public static void stop() {
        Utils.runOnMainThread(() -> {
            if (!active) {
                return;
            }
            active = false;
            HANDLER.removeCallbacks(TICK);
            removeChip();
            unwatchKeys();
            if (application != null) {
                application.unregisterActivityLifecycleCallbacks(CALLBACKS);
            }
            application = null;
            following = false;
        });
    }

    /** Called by the Record chip's room watch as a LIVE room page resumes in {@code activity}. */
    static void roomResumed(Activity activity) {
        try {
            if (!active || activity == null) {
                return;
            }
            host = new WeakReference<>(activity);
            tick();
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not set up the LIVE Talk button", failure);
        }
    }

    private static void tick() {
        HANDLER.removeCallbacks(TICK);
        try {
            Activity activity = host.get();
            if (!active || activity == null || LiveRecordChip.pageInFront(activity) == null) {
                removeChip();
                return;
            }
            showChip(activity);
            TextView view = chip;
            if (view != null) {
                view.setVisibility(LiveRecordChip.filmingScreen() ? View.INVISIBLE : View.VISIBLE);
                LiveRecordChip.restack((ViewGroup) view.getParent());
            }
            HANDLER.postDelayed(TICK, TICK_MS);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not check the LIVE room for the Talk button", failure);
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void showChip(Activity activity) {
        if (chip != null && chip.getContext() == activity && chip.getParent() != null
                && !LiveRecordChip.drawnAtOtherSize(chip)) {
            return;
        }
        removeChip();
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        TextView view = new TextView(activity);
        LiveRecordChip.styleChip(view);
        view.setOnTouchListener(LivePushToTalk::onTouch);
        LiveRecordChip.markStacked(view, LiveRecordChip.STACK_TALK, dp(activity, TOP_DP), dp(activity, GAP_DP));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        params.topMargin = dp(activity, TOP_DP);
        params.rightMargin = dp(activity, 12);
        content.addView(view, params);
        chip = view;
        showState();
    }

    private static void removeChip() {
        talkTo(false);
        TextView view = chip;
        chip = null;
        if (view != null && view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }

    private static boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (view.getParent() != null) {
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                }
                talkTo(true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                talkTo(false);
                return true;
            default:
                return true;
        }
    }

    private static void talkTo(boolean value) {
        if (talking == value) {
            return;
        }
        talking = value;
        LiveMicrophone.setTalking(value);
        showState();
    }

    private static void showState() {
        TextView view = chip;
        if (view == null) {
            return;
        }
        Context context = view.getContext();
        view.setText(L10n.t(context, talking ? "Talking" : "Hold to talk"));
        view.setContentDescription(L10n.t(context, talking ? "Talking" : "Hold to talk"));
        view.setAlpha(talking ? 1f : 0.85f);
    }

    private static void watchKeys(Activity activity) {
        if (keyProxy != null || activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null || window.getCallback() == null) {
            return;
        }
        Window.Callback inner = window.getCallback();
        try {
            Window.Callback proxy = (Window.Callback) Proxy.newProxyInstance(
                    Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class},
                    (object, method, args) -> {
                        if (active && args != null && args.length == 1
                                && args[0] instanceof KeyEvent
                                && "dispatchKeyEvent".equals(method.getName())) {
                            KeyEvent event = (KeyEvent) args[0];
                            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
                                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                                    talkTo(true);
                                    return true;
                                }
                                if (event.getAction() == KeyEvent.ACTION_UP) {
                                    talkTo(false);
                                    return true;
                                }
                            }
                        }
                        try {
                            return method.invoke(inner, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause() == null ? failure : failure.getCause();
                        }
                    });
            window.setCallback(proxy);
            keyWindow = window;
            keyInner = inner;
            keyProxy = proxy;
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not watch the LIVE volume key", failure);
        }
    }

    private static void unwatchKeys() {
        Window window = keyWindow;
        if (window != null && keyProxy != null && window.getCallback() == keyProxy) {
            window.setCallback(keyInner);
        }
        keyWindow = null;
        keyInner = null;
        keyProxy = null;
        talkTo(false);
    }

    private static void follow() {
        if (following) {
            return;
        }
        Context context = Utils.getContext();
        Context app = context == null ? null : context.getApplicationContext();
        if (!(app instanceof Application)) {
            return;
        }
        application = (Application) app;
        application.registerActivityLifecycleCallbacks(CALLBACKS);
        following = true;
    }
}
