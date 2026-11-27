/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Window;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Hold Volume Up to talk while push to talk is on. The key is read from the visible activity's
 * window, so the room's own views and windows cannot get in the way and no permission is needed.
 *
 * <p>Releasing the key, pausing the activity or ending the LIVE all return the microphone to
 * silence, so a lost key up cannot leave the room listening.
 */
public final class LivePushToTalk {
    private static boolean active;
    private static boolean talking;
    private static boolean following;
    private static Application application;
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
                }

                @Override public void onActivityCreated(Activity activity, Bundle state) { }
                @Override public void onActivityStarted(Activity activity) { }
                @Override public void onActivityStopped(Activity activity) { }
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
                @Override public void onActivityDestroyed(Activity activity) { }
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
            watchKeys(Utils.getVisibleActivity());
        });
    }

    public static void stop() {
        Utils.runOnMainThread(() -> {
            if (!active) {
                return;
            }
            active = false;
            unwatchKeys();
            if (application != null) {
                application.unregisterActivityLifecycleCallbacks(CALLBACKS);
            }
            application = null;
            following = false;
        });
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

    private static void talkTo(boolean value) {
        if (talking == value) {
            return;
        }
        talking = value;
        LiveMicrophone.setTalking(value);
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
