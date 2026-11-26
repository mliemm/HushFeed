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
import android.view.ViewTreeObserver;
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
 *
 * <p>The broadcast screen builds its view hierarchy after it resumes and can replace the
 * content root's children, which removed a button added once. The layout listener puts it back
 * and keeps it above anything TikTok adds later.
 */
public final class LivePushToTalk {
    private static final int SIZE_DP = 64;
    private static final int MARGIN_DP = 12;

    private static boolean active;
    private static boolean talking;
    private static boolean paused;
    private static boolean following;
    private static Application application;
    private static WeakReference<View> buttonReference = new WeakReference<>(null);
    private static WeakReference<Activity> hostReference = new WeakReference<>(null);
    private static WeakReference<ViewGroup> rootReference = new WeakReference<>(null);
    private static ViewTreeObserver.OnGlobalLayoutListener layoutListener;

    private static final Application.ActivityLifecycleCallbacks CALLBACKS =
            new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity activity) {
                    if (active) {
                        paused = false;
                        attach(activity);
                    }
                }

                @Override public void onActivityPaused(Activity activity) {
                    if (activity == hostReference.get()) {
                        paused = true;
                        detach();
                    }
                }

                @Override public void onActivityCreated(Activity activity, Bundle state) { }
                @Override public void onActivityStarted(Activity activity) { }
                @Override public void onActivityStopped(Activity activity) { }
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
                @Override public void onActivityDestroyed(Activity activity) {
                    if (activity == hostReference.get()) {
                        paused = true;
                        detach();
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
            talking = false;
            paused = false;
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
            paused = false;
            detach();
            removeLayoutListener();
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
        if (!active || paused) {
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
                toFront(existing);
                return;
            }
            detach();
            View button = create(activity);
            root.addView(button, params(activity));
            buttonReference = new WeakReference<>(button);
            hostReference = new WeakReference<>(activity);
            installLayoutListener(root);
            showState(button);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not show the LIVE push to talk button", failure);
        }
    }

    private static FrameLayout.LayoutParams params(Context context) {
        int size = dp(context, SIZE_DP);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                size, size, Gravity.START | Gravity.CENTER_VERTICAL);
        params.leftMargin = dp(context, MARGIN_DP);
        return params;
    }

    /**
     * The broadcast view can replace the content root's children. Every layout pass keeps the
     * button a child of the root and at the front, so the room's own views cannot cover it.
     */
    private static void installLayoutListener(ViewGroup root) {
        if (layoutListener != null && rootReference.get() == root) {
            return;
        }
        removeLayoutListener();
        rootReference = new WeakReference<>(root);
        layoutListener = () -> {
            if (!active || paused) {
                return;
            }
            ViewGroup current = rootReference.get();
            if (current == null) {
                return;
            }
            View button = buttonReference.get();
            if (button == null) {
                return;
            }
            if (button.getParent() != current) {
                if (button.getParent() instanceof ViewGroup) {
                    ((ViewGroup) button.getParent()).removeView(button);
                }
                current.addView(button, params(current.getContext()));
            }
            toFront(button);
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    /**
     * Brings the button to the front only when something was added over it, since
     * {@link View#bringToFront()} requests another layout and an unconditional call in the
     * layout listener would keep the screen laying out forever.
     */
    private static void toFront(View button) {
        if (!(button.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) button.getParent();
        if (parent.getChildAt(parent.getChildCount() - 1) != button) {
            button.bringToFront();
        }
    }

    private static void removeLayoutListener() {
        ViewGroup root = rootReference.get();
        if (root != null && layoutListener != null) {
            root.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        }
        layoutListener = null;
        rootReference = new WeakReference<>(null);
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
