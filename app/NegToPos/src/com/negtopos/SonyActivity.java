package com.negtopos;

import android.app.Activity;
import android.content.Intent;
import android.view.KeyEvent;

import com.github.ma1co.openmemories.framework.DisplayManager;
import com.sony.scalar.sysutil.ScalarInput;

/**
 * Base activity with the Sony camera lifecycle glue every PMCA app needs.
 *
 * Adapted from ma1co/PMCADemo BaseActivity (MIT License, Copyright (c) 2015 ma1co):
 *  - on resume, broadcast DAConnectionManagerService.AppInfoReceive so the camera knows which app is in front
 *  - switch the display to high colour depth while we are shown
 *  - camera buttons arrive as Sony scan codes (ScalarInput.ISV_KEY_*), not reliably as Android key codes
 */
public abstract class SonyActivity extends Activity {

    private DisplayManager displayManager;

    @Override
    protected void onResume() {
        super.onResume();
        try {
            displayManager = DisplayManager.create(this);
            displayManager.setColorDepth(DisplayManager.ColorDepth.HIGH);
        } catch (Throwable t) {
            displayManager = null;   // not on a camera (or API missing): keep going
            logWarning("DisplayManager / colour depth", t);
        }
        try {
            notifyAppInfo();
        } catch (Throwable t) {
            logWarning("AppInfoReceive broadcast", t);
        }
    }

    /** Non-fatal problems in the Sony glue; subclasses route this to their log. */
    protected void logWarning(String what, Throwable t) {}

    @Override
    protected void onPause() {
        super.onPause();
        if (displayManager != null) {
            try {
                displayManager.setColorDepth(DisplayManager.ColorDepth.LOW);
                displayManager.release();
            } catch (Throwable ignored) {}
            displayManager = null;
        }
    }

    protected void notifyAppInfo() {
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getComponentName().getPackageName());
        intent.putExtra("class_name", getComponentName().getClassName());
        sendBroadcast(intent);
    }

    /** Camera button press, by Sony scan code. Return true if handled. */
    protected boolean onCameraKey(int scanCode) { return false; }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (onCameraKey(event.getScanCode())) return true;
        return super.onKeyDown(keyCode, event);
    }

    protected static boolean isEnter(int scanCode) { return scanCode == ScalarInput.ISV_KEY_ENTER; }

    protected static boolean isMenuOrBack(int scanCode) {
        return scanCode == ScalarInput.ISV_KEY_MENU || scanCode == ScalarInput.ISV_KEY_SK1
            || scanCode == ScalarInput.ISV_KEY_DELETE || scanCode == ScalarInput.ISV_KEY_SK2;
    }
}
