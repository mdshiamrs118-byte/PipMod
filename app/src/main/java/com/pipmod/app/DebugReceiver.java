package com.pipmod.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives debug text sent by the SystemUI hook so it can be shown in the app. */
public class DebugReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String t = i.getStringExtra("text");
        if (t == null) return;
        c.getSharedPreferences("debug", Context.MODE_PRIVATE).edit()
                .putString("text", t)
                .putLong("time", System.currentTimeMillis())
                .apply();
    }
}
