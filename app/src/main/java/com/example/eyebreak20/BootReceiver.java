package com.example.eyebreak20;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences prefs = context.getSharedPreferences(ScreenMonitorService.PREFS, Context.MODE_PRIVATE);
        // elapsedRealtime restarts at boot; never carry a previous-boot countdown forward.
        prefs.edit().putLong(ScreenMonitorService.SESSION_START, 0)
                .putLong(ScreenMonitorService.NEXT_DUE, 0)
                .putLong(ScreenMonitorService.PAUSED_AT, 0)
                .putLong(ScreenMonitorService.BREAK_UNTIL, 0).apply();
        if (prefs.getBoolean(ScreenMonitorService.ENABLED, false)
                && prefs.getBoolean(ScreenMonitorService.AUTO_BOOT, false)) {
            try {
                Intent service = new Intent(context, ScreenMonitorService.class);
                service.setAction(ScreenMonitorService.ACTION_START);
                context.startForegroundService(service);
            } catch (RuntimeException e) {
                Log.w("EyeBreak20", "Auto start denied by Android/OEM", e);
            }
        }
    }
}
