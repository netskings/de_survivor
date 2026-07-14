package org.telegram.messenger.archive;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.util.Map;

/** Persistent, account-scoped UI state for recalled messages hidden from ChatActivity. */
public final class ArchiveHiddenMessages {
    private static final String PREFS_NAME = "local_archive_hidden_in_chat";

    private ArchiveHiddenMessages() {
    }

    public static void hide(int accountSlot, long dialogId, long topicId, int messageId) {
        setHidden(identity(accountSlot, dialogId, topicId, messageId), true);
    }

    public static void show(int accountSlot, long dialogId, long topicId, int messageId) {
        setHidden(identity(accountSlot, dialogId, topicId, messageId), false);
    }

    public static boolean isHidden(int accountSlot, long dialogId, long topicId, int messageId) {
        return isHidden(identity(accountSlot, dialogId, topicId, messageId));
    }

    public static void clear(int accountSlot, boolean allAccounts) {
        if (ApplicationLoader.applicationContext == null) return;
        try {
            SharedPreferences preferences = preferences();
            if (allAccounts) {
                if (!preferences.edit().clear().commit()) {
                    FileLog.e("Local archive hidden state was not cleared");
                }
                return;
            }
            long accountId = UserConfig.getInstance(accountSlot).getClientUserId();
            if (accountId == 0) return;
            int environment = ConnectionsManager.getInstance(accountSlot).isTestBackend() ? 1 : 0;
            String prefix = environment + ":" + accountId + ":";
            SharedPreferences.Editor editor = preferences.edit();
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (entry.getKey().startsWith(prefix)) editor.remove(entry.getKey());
            }
            if (!editor.commit()) FileLog.e("Local archive hidden state was not cleared");
        } catch (Throwable error) {
            FileLog.e("Local archive hidden state clear failed: " + error.getClass().getSimpleName());
        }
    }

    static void setHiddenForTests(int environment, long accountId, long dialogId,
                                  long topicId, int messageId, boolean hidden) {
        setHidden(key(environment, accountId, dialogId, topicId, messageId), hidden);
    }

    static boolean isHiddenForTests(int environment, long accountId, long dialogId,
                                    long topicId, int messageId) {
        return isHidden(key(environment, accountId, dialogId, topicId, messageId));
    }

    private static String identity(int accountSlot, long dialogId, long topicId, int messageId) {
        if (ApplicationLoader.applicationContext == null) return null;
        long accountId = UserConfig.getInstance(accountSlot).getClientUserId();
        if (accountId == 0) return null;
        int environment = ConnectionsManager.getInstance(accountSlot).isTestBackend() ? 1 : 0;
        return key(environment, accountId, dialogId, topicId, messageId);
    }

    private static String key(int environment, long accountId, long dialogId,
                              long topicId, int messageId) {
        return environment + ":" + accountId + ":" + dialogId + ":" + topicId + ":" + messageId;
    }

    private static boolean isHidden(String key) {
        if (key == null || ApplicationLoader.applicationContext == null) return false;
        try {
            return preferences().getBoolean(key, false);
        } catch (Throwable error) {
            FileLog.e("Local archive hidden state read failed: " + error.getClass().getSimpleName());
            return false;
        }
    }

    private static void setHidden(String key, boolean hidden) {
        if (key == null || ApplicationLoader.applicationContext == null) return;
        try {
            SharedPreferences.Editor editor = preferences().edit();
            if (hidden) {
                editor.putBoolean(key, true);
            } else {
                editor.remove(key);
            }
            if (!editor.commit()) {
                FileLog.e("Local archive hidden state was not persisted");
            }
        } catch (Throwable error) {
            FileLog.e("Local archive hidden state write failed: " + error.getClass().getSimpleName());
        }
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, 0);
    }
}
