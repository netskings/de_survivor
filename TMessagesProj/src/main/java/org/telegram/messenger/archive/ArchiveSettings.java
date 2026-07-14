package org.telegram.messenger.archive;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/** Internal feature flag. No archive resources are touched while it is disabled. */
public final class ArchiveSettings {
    private static final String PREFS_NAME = "custom_app_settings";
    private static final String KEY_ENABLED = "local_archive_enabled";
    private static volatile Boolean testOverride;

    private ArchiveSettings() {
    }

    public static boolean isEnabled() {
        Boolean override = testOverride;
        if (override != null) {
            return override;
        }
        if (ApplicationLoader.applicationContext == null) {
            return false;
        }
        try {
            SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, 0);
            return preferences.getBoolean(KEY_ENABLED, false);
        } catch (Throwable error) {
            FileLog.e("Local archive feature flag read failed: " + error.getClass().getSimpleName());
            return false;
        }
    }

    /** Internal/test seam; the production default remains false. */
    public static void setEnabled(boolean enabled) {
        if (ApplicationLoader.applicationContext != null) {
            ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, 0)
                    .edit().putBoolean(KEY_ENABLED, enabled).apply();
        }
    }

    static void setTestOverride(Boolean enabled) {
        testOverride = enabled;
    }
}
