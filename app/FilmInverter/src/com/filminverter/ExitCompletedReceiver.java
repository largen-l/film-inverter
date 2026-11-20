package com.filminverter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The camera broadcasts DAConnectionManagerService.ExitCompleted when it closes the app; kill the
 * process so nothing lingers. From ma1co/PMCADemo (MIT License, Copyright (c) 2015 ma1co).
 */
public class ExitCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
