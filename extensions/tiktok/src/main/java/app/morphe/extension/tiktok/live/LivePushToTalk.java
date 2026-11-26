/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * The hold to talk button that sits over the LIVE while push to talk is on.
 *
 * <p>It follows the activity in front, so it is there from the broadcast screen to a sheet the
 * host opens over it and back. Releasing the button, leaving the activity or ending the LIVE
 * all return the microphone to silence, so a lost touch cannot leave the room listening.
 */
public final class LivePushToTalk {
    private static final int SIZE_DP = 64;
    private static final int MARGIN_DP = 12;

    private static boolean active;
    private static boolean talking;
    private static boolean following;
    private static Application application;
    private static WeakReference<View> buttonReference = new WeakReference<>(null);
    private static WeakReference<Activity> hostReference = new WeakReference<>(null);

    private static final Application.ActivityLifecycleCallbacks CALLBACKS =
            new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity activity) {
                    if (active) attach(activity);
                }

                @Override public void onActivityPaused(Activity activity) {
                    if (activity == hostReference.get()) detach();
                }

                @Override public void onActivityCreated(Activity activity, Bundle state) { }
                @Override public void onActivityStarted(Activity activity) { }
                @Override public void onActivityStopped(Activity activity) { }
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
                @Override public void onActivityDestroyed(Activity activity) {
                    if (activity == hostReference.get()) detach();
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
            talking = false;
            follow();
            attach(Utils.getVisibleActivity());
        });
    }

    public static void stop() {
        Utils.runOnMainThread(() -> {
            if (!active) {
                return;
            }
            active = false;
            talking = false;
            detach();
            if (application != null) {
                application.unregisterActivityLifecycleCallbacks(CALLBACKS);
            }
            application = null;
            following = false;
        });
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

    private static void attach(Activity activity) {
        if (!active) {
            return;
        }
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            ViewGroup root = activity.findViewById(android.R.id.content);
            if (root == null) {
                return;
            }
            View existing = buttonReference.get();
            if (existing != null && existing.getParent() == root) {
                return;
            }
            detach();
            View button = create(activity);
            int size = dp(activity, SIZE_DP);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    size, size, Gravity.START | Gravity.CENTER_VERTICAL);
            params.leftMargin = dp(activity, MARGIN_DP);
            root.addView(button, params);
            buttonReference = new WeakReference<>(button);
            hostReference = new WeakReference<>(activity);
            showState(button);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not show the LIVE push to talk button", failure);
        }
    }

    private static View create(Activity activity) {
        TextView button = new TextView(activity);
        button.setGravity(Gravity.CENTER);
        button.setText(L10n.t(activity, "Talk"));
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setClickable(true);
        button.setFocusable(true);
        button.setElevation(dp(activity, 6));
        button.setContentDescription(L10n.t(activity, "Hold to talk"));
        button.setOnTouchListener(LivePushToTalk::onTouch);
        return button;
    }

    private static boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                talk(view, true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                talk(view, false);
                return true;
            default:
                return true;
        }
    }

    private static void talk(View view, boolean value) {
        if (talking == value) {
            return;
        }
        talking = value;
        LiveMicrophone.setTalking(value);
        if (value) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        }
        showState(view);
    }

    private static void showState(View button) {
        if (!(button instanceof TextView)) {
            return;
        }
        TextView text = (TextView) button;
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(talking ? 0xffd32f2f : 0xcc202124);
        shape.setCornerRadius(dp(button.getContext(), SIZE_DP) / 2f);
        shape.setStroke(dp(button.getContext(), 2), talking ? 0xffffffff : 0x66ffffff);
        button.setBackground(shape);
        text.setText(L10n.t(button.getContext(), "Talk"));
        button.setContentDescription(L10n.t(button.getContext(), talking ? "Talking" : "Hold to talk"));
    }

    private static void detach() {
        if (talking) {
            talking = false;
            LiveMicrophone.setTalking(false);
        }
        View button = buttonReference.get();
        if (button != null && button.getParent() instanceof ViewGroup) {
            ((ViewGroup) button.getParent()).removeView(button);
        }
        buttonReference = new WeakReference<>(null);
        hostReference = new WeakReference<>(null);
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }
}
