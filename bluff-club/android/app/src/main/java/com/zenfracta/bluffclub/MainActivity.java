package com.zenfracta.bluffclub;

import android.os.Bundle;
import android.webkit.WebSettings;
import androidx.core.splashscreen.SplashScreen;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        registerPlugin(PrivacyScreenPlugin.class);
        super.onCreate(savedInstanceState);
        applySystemTextScale();
    }

    @Override
    public void onResume() {
        super.onResume();
        applySystemTextScale();
    }

    /** Honour the Android font-size setting inside the WebView (it defaults to 100%). */
    private void applySystemTextScale() {
        if (getBridge() == null || getBridge().getWebView() == null) return;
        WebSettings settings = getBridge().getWebView().getSettings();
        float scale = getResources().getConfiguration().fontScale;
        settings.setTextZoom(Math.round(scale * 100));
    }
}
