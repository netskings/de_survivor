package org.telegram.ui.Custom;

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
        return getPrefs().getBoolean("hide_proxy_sponsor", true);
    }

    public static void setHideProxySponsor(boolean value) {
        getPrefs().edit().putBoolean("hide_proxy_sponsor", value).apply();
    }

    public static boolean feedRecommendations() {
        return getPrefs().getBoolean("feed_recommendations", false);
    }

    public static void setFeedRecommendations(boolean v) {
        getPrefs().edit().putBoolean("feed_recommendations", v).apply();
    }

    public static int feedRecommendationFrequency() {
        return getPrefs().getInt("feed_rec_frequency", 8);
    }

    public static void setFeedRecommendationFrequency(int v) {
        getPrefs().edit().putInt("feed_rec_frequency", v).apply();
    }
}