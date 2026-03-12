package org.telegram.ui.Feed;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

public class CustomSettings {

    private static final String PREFS_NAME = "custom_app_settings";

    private static SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS_NAME, 0);
    }

    public static boolean hideAds() {
        return getPrefs().getBoolean("hide_ads", false);
    }

    public static void setHideAds(boolean value) {
        getPrefs().edit().putBoolean("hide_ads", value).apply();
    }

    public static boolean hideProxySponsor() {
        return getPrefs().getBoolean("hide_proxy_sponsor", false);
    }

    public static void setHideProxySponsor(boolean value) {
        getPrefs().edit().putBoolean("hide_proxy_sponsor", value).apply();
    }
}