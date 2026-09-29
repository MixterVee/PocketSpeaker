package com.mixtervee.pocketspeaker;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.SystemClock;

public class BootReceiver extends BroadcastReceiver {
    static final String PREFS = "pocket_speaker";
    static final String PREF_AUTO_START_BOOT = "auto_start_boot";
    static final String PREF_BOOT_START_PENDING = "boot_start_pending";
    static final String EXTRA_BOOT_AUTO_START = "boot_auto_start";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        boolean enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_AUTO_START_BOOT, false);
        if (!enabled || !isTv(context)) return;

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_BOOT_START_PENDING, true).apply();

        // Direct activity launches from BOOT_COMPLETED are commonly blocked on
        // modern Android TV / Google TV. Hand the launch to AlarmManager after the
        // launcher has had time to settle. Android will still require the normal
        // MediaProjection approval.
        try {
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            launch.putExtra(EXTRA_BOOT_AUTO_START, true);

            PendingIntent pending = PendingIntent.getActivity(
                    context,
                    7001,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            AlarmManager alarm =
                    (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarm != null) {
                long when = SystemClock.elapsedRealtime() + 12000L;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarm.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pending);
                } else {
                    alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pending);
                }
            }
        } catch (Exception ignored) {
            // If this TV firmware blocks even the delayed system launch, the pending
            // flag remains set so manually opening Pocket Speaker goes straight to
            // the capture approval prompt.
        }
    }

    static boolean isTv(Context context) {
        UiModeManager uiModeManager =
                (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        boolean tvMode = uiModeManager != null
                && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        return tvMode || context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }
}
