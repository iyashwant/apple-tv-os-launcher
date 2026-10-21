package com.gathin.tvhome;

import android.view.KeyEvent;

/** Foreground-only learning shared by the settings page and the key services in this process. */
final class RemoteLearning {
    interface Listener { void captured(String button); }
    private static volatile Listener listener;
    static void start(Listener value) { listener = value; }
    static void stop() { listener = null; }
    static boolean handle(KeyEvent event) {
        Listener callback = listener;
        if (callback == null || reserved(event.getKeyCode())) return false;
        if (event.getScanCode() <= 0 && event.getKeyCode() <= 0) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            listener = null;
            String button = event.getScanCode() > 0 ? "scan:" + event.getScanCode() : String.valueOf(event.getKeyCode());
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) callback.captured(button);
            else new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> callback.captured(button));
        }
        return true;
    }
    static boolean reserved(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_HOME: case KeyEvent.KEYCODE_ALL_APPS: case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP: case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE: case KeyEvent.KEYCODE_MUTE: return true;
            default: return false;
        }
    }
}
