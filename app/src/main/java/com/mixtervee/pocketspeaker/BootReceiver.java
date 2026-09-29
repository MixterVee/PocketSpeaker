package com.mixtervee.pocketspeaker;

import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

public class BootReceiver extends BroadcastReceiver {
    static final String PREFS = "pocket_speaker";
    static final String PREF_AUTO_START_BOOT = "auto_start_boot";
    static final String EXTRA_BOOT_AUTO_START = "boot_auto_start";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        boolean enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_AUTO_START_BOOT, false);
        if (!enabled || !isTv(context)) return;

        // Best-effort launch. Android still requires the user to approve the
        // MediaProjection consent dialog for the new capture session.
        try {
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            launch.putExtra(EXTRA_BOOT_AUTO_START, true);
            context.startActivity(launch);
        } catch (Exception ignored) {
            // Some Android/Google TV builds may block background activity launches.
            // Opening Pocket Speaker manually will still auto-request capture while
            // this preference is enabled.
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
