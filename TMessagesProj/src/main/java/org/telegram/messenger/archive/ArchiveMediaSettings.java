package org.telegram.messenger.archive;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/** User policy for the private media archive. Automatic network downloads remain opt-in. */
public final class ArchiveMediaSettings {
    public static final String PHOTO = "photo";
    public static final String VIDEO = "video";
    public static final String DOCUMENT = "document";
    public static final String VOICE = "voice";
    public static final String MUSIC = "music";
    public static final String STICKER = "sticker";
    public static final String ANIMATION = "animation";

    private static final String PREFS = "custom_app_settings";
    private static final long DEFAULT_MAX_FILE = 50L * 1024 * 1024;
    private static final long DEFAULT_MAX_TOTAL = 512L * 1024 * 1024;

    private ArchiveMediaSettings() {
    }

    public static boolean isEnabled() {
        SharedPreferences preferences = preferences();
        if (preferences == null) return false;
        try {
            return preferences.getBoolean("archive_media_enabled", true);
        } catch (Throwable ignore) {
            return false;
        }
    }

    public static void setEnabled(boolean value) {
        edit().putBoolean("archive_media_enabled", value).apply();
    }

    public static boolean autoDownloadMissing() {
        SharedPreferences preferences = preferences();
        if (preferences == null) return false;
        try {
            return preferences.getBoolean("archive_media_auto_download", false);
        } catch (Throwable ignore) {
            return false;
        }
    }

    public static void setAutoDownloadMissing(boolean value) {
        edit().putBoolean("archive_media_auto_download", value).apply();
    }

    public static boolean isTypeEnabled(String type) {
        boolean defaultValue = PHOTO.equals(type);
        SharedPreferences preferences = preferences();
        if (preferences == null) return false;
        try {
            return preferences.getBoolean("archive_media_type_" + type, defaultValue);
        } catch (Throwable ignore) {
            return defaultValue;
        }
    }

    public static void setTypeEnabled(String type, boolean value) {
        edit().putBoolean("archive_media_type_" + type, value).apply();
    }

    public static long maxFileBytes() {
        SharedPreferences preferences = preferences();
        if (preferences == null) return DEFAULT_MAX_FILE;
        try {
            return Math.max(1024 * 1024, preferences.getLong("archive_media_max_file", DEFAULT_MAX_FILE));
        } catch (Throwable ignore) {
            return DEFAULT_MAX_FILE;
        }
    }

    public static void setMaxFileBytes(long value) {
        edit().putLong("archive_media_max_file", Math.max(1024 * 1024, value)).apply();
    }

    public static long maxTotalBytes() {
        SharedPreferences preferences = preferences();
        if (preferences == null) return DEFAULT_MAX_TOTAL;
        try {
            return Math.max(16L * 1024 * 1024,
                    preferences.getLong("archive_media_max_total", DEFAULT_MAX_TOTAL));
        } catch (Throwable ignore) {
            return DEFAULT_MAX_TOTAL;
        }
    }

    public static void setMaxTotalBytes(long value) {
        edit().putLong("archive_media_max_total", Math.max(16L * 1024 * 1024, value)).apply();
    }

    /** Zero means no age-based eviction. */
    public static int retentionDays() {
        SharedPreferences preferences = preferences();
        if (preferences == null) return 0;
        try {
            return Math.max(0, preferences.getInt("archive_media_retention_days", 0));
        } catch (Throwable ignore) {
            return 0;
        }
    }

    public static void setRetentionDays(int value) {
        edit().putInt("archive_media_retention_days", Math.max(0, value)).apply();
    }

    private static SharedPreferences preferences() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    private static SharedPreferences.Editor edit() {
        SharedPreferences preferences = preferences();
        if (preferences == null) throw new IllegalStateException("Application context is unavailable");
        return preferences.edit();
    }
}
