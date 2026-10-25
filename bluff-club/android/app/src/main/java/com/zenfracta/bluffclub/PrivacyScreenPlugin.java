package com.zenfracta.bluffclub;

import android.app.Activity;
import android.os.Build;
import android.view.WindowManager;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Minimal bridge around WindowManager.LayoutParams.FLAG_SECURE, used while a round is
 * in progress so screenshots, screen recording and the recent-apps preview cannot
 * capture a private card. It cannot stop someone photographing the screen, and it is
 * not protection on rooted or modified devices.
 */
@CapacitorPlugin(name = "PrivacyScreen")
public class PrivacyScreenPlugin extends Plugin {

    @PluginMethod
    public void enable(PluginCall call) {
        apply(call, true);
    }

    @PluginMethod
    public void disable(PluginCall call) {
        apply(call, false);
    }

    private void apply(PluginCall call, boolean secure) {
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("No activity");
            return;
        }
        activity.runOnUiThread(() -> {
            if (secure) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            } else {
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Also keep the task snapshot out of Recents on Android 13+.
                activity.setRecentsScreenshotEnabled(!secure);
            }
            call.resolve();
        });
    }
}
