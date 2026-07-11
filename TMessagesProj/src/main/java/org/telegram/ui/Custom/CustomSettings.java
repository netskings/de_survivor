package org.telegram.ui.Custom;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.ui.Feed.FeedAlbumMode;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class CustomSettings {

    private static final String PREFS_NAME = "custom_app_settings";
    private static final String KEY_FEED_ALBUM_MODE = "feed_album_mode";
    private static final String KEY_HIDE_ADS = "hide_ads";
    private static final String KEY_HIDE_PROXY_SPONSOR = "hide_proxy_sponsor";
    private static final String KEY_FEED_RECOMMENDATIONS = "feed_recommendations";
    private static final String KEY_FEED_RECOMMENDATION_FREQUENCY = "feed_rec_frequency";
    private static final String KEY_BYPASS_CONTENT_PROTECTION = "bypass_content_protection";
    private static final String KEY_ANTI_RECALL = "anti_recall";
    private static final String KEY_KEEP_MESSAGE_EDIT_HISTORY = "keep_message_edit_history";
    private static final String KEY_EDITED_MESSAGE_LABEL = "edited_message_label";
    private static final String KEY_EDITED_MESSAGE_LABEL_COLOR = "edited_message_label_color";
    private static final String KEY_EDITED_MESSAGE_LABEL_ICON = "edited_message_label_icon";
    private static final String KEY_DELETED_MESSAGE_LABEL = "deleted_message_label";
    private static final String KEY_DELETED_MESSAGE_LABEL_COLOR = "deleted_message_label_color";
    private static final String KEY_DELETED_MESSAGE_LABEL_ICON = "deleted_message_label_icon";
    private static final String KEY_SAVE_TEMPORARY_MEDIA = "save_temporary_media";
    private static final String KEY_SAVE_TEMPORARY_MEDIA_PATH = "save_temporary_media_path";
    private static final String KEY_SAVE_TEMPORARY_MEDIA_TREE_URI = "save_temporary_media_tree_uri";
    private static final String KEY_SAVE_TEMPORARY_MEDIA_TREE_DISPLAY_PATH = "save_temporary_media_tree_display_path";
    private static final String KEY_KEEP_TEMPORARY_MEDIA_IN_CHAT = "keep_temporary_media_in_chat";
    private static final String KEY_KEEP_KICKED_CHATS_CACHE = "keep_kicked_chats_cache";
    private static final String KEY_HIDE_ONLINE_STATUS = "hide_online_status";
    private static final String KEY_GO_OFFLINE_AUTOMATICALLY = "go_offline_automatically";
    private static final String KEY_HIDE_TYPING_STATUS = "hide_typing_status";
    private static final String KEY_HIDE_READ_STATUS = "hide_read_status";
    private static final String KEY_HIDE_STORY_VIEWS = "hide_story_views";
    private static final String KEY_ALERT_BEFORE_OPENING_STORY = "alert_before_opening_story";
    private static final String KEY_READ_ON_INTERACT = "read_on_interact";
    private static final String KEY_SCHEDULE_MESSAGES_IN_GHOST_MODE = "schedule_messages_in_ghost_mode";
    private static final String KEY_GHOST_MODE_EXCEPTIONS = "ghost_mode_exceptions";
    private static final String KEY_KEEP_LAST_SEEN_UPDATED_IN_GHOST_MODE = "send_offline_status_in_ghost_mode";
    private static final String DEFAULT_SAVE_TEMPORARY_MEDIA_RELATIVE_PATH = "TG/SavedAttachments";

    public static FeedAlbumMode feedAlbumMode() {
        String val = getPrefs().getString(KEY_FEED_ALBUM_MODE, FeedAlbumMode.CAROUSEL.name());
        try { return FeedAlbumMode.valueOf(val); }
        catch (Exception e) { return FeedAlbumMode.CAROUSEL; }
    }

    public static void setFeedAlbumMode(FeedAlbumMode mode) {
        getPrefs().edit().putString(KEY_FEED_ALBUM_MODE, mode.name()).apply();
    }

    private static SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, 0);
    }

    public static boolean hideAds() { return getPrefs().getBoolean(KEY_HIDE_ADS, false); }
    public static void setHideAds(boolean value) { getPrefs().edit().putBoolean(KEY_HIDE_ADS, value).apply(); }

    public static boolean hideProxySponsor() { return getPrefs().getBoolean(KEY_HIDE_PROXY_SPONSOR, true); }
    public static void setHideProxySponsor(boolean value) { getPrefs().edit().putBoolean(KEY_HIDE_PROXY_SPONSOR, value).apply(); }

    public static boolean feedRecommendations() { return getPrefs().getBoolean(KEY_FEED_RECOMMENDATIONS, false); }
    public static void setFeedRecommendations(boolean v) { getPrefs().edit().putBoolean(KEY_FEED_RECOMMENDATIONS, v).apply(); }

    public static int feedRecommendationFrequency() { return getPrefs().getInt(KEY_FEED_RECOMMENDATION_FREQUENCY, 8); }
    public static void setFeedRecommendationFrequency(int v) { getPrefs().edit().putInt(KEY_FEED_RECOMMENDATION_FREQUENCY, v).apply(); }

    public static boolean bypassContentProtection() { return getPrefs().getBoolean(KEY_BYPASS_CONTENT_PROTECTION, false); }
    public static void setBypassContentProtection(boolean v) { getPrefs().edit().putBoolean(KEY_BYPASS_CONTENT_PROTECTION, v).apply(); }

    public static boolean antiRecall() { return getPrefs().getBoolean(KEY_ANTI_RECALL, true); }
    public static void setAntiRecall(boolean v) { getPrefs().edit().putBoolean(KEY_ANTI_RECALL, v).apply(); }

    public static boolean keepMessageEditHistory() { return getPrefs().getBoolean(KEY_KEEP_MESSAGE_EDIT_HISTORY, true); }
    public static void setKeepMessageEditHistory(boolean v) { getPrefs().edit().putBoolean(KEY_KEEP_MESSAGE_EDIT_HISTORY, v).apply(); }

    public static String editedMessageLabel(String defaultValue) { return getPrefs().getString(KEY_EDITED_MESSAGE_LABEL, defaultValue); }
    public static void setEditedMessageLabel(String value) { getPrefs().edit().putString(KEY_EDITED_MESSAGE_LABEL, value).apply(); }
    public static int editedMessageLabelColor() { return getPrefs().getInt(KEY_EDITED_MESSAGE_LABEL_COLOR, 0); }
    public static void setEditedMessageLabelColor(int value) { getPrefs().edit().putInt(KEY_EDITED_MESSAGE_LABEL_COLOR, value).apply(); }
    public static int editedMessageLabelIcon() { return getPrefs().getInt(KEY_EDITED_MESSAGE_LABEL_ICON, 0); }
    public static void setEditedMessageLabelIcon(int value) { getPrefs().edit().putInt(KEY_EDITED_MESSAGE_LABEL_ICON, value).apply(); }

    public static String deletedMessageLabel() { return getPrefs().getString(KEY_DELETED_MESSAGE_LABEL, ""); }
    public static void setDeletedMessageLabel(String value) { getPrefs().edit().putString(KEY_DELETED_MESSAGE_LABEL, value).apply(); }
    public static int deletedMessageLabelColor() { return getPrefs().getInt(KEY_DELETED_MESSAGE_LABEL_COLOR, 0); }
    public static void setDeletedMessageLabelColor(int value) { getPrefs().edit().putInt(KEY_DELETED_MESSAGE_LABEL_COLOR, value).apply(); }
    public static int deletedMessageLabelIcon() { return getPrefs().getInt(KEY_DELETED_MESSAGE_LABEL_ICON, 0); }
    public static void setDeletedMessageLabelIcon(int value) { getPrefs().edit().putInt(KEY_DELETED_MESSAGE_LABEL_ICON, value).apply(); }

    public static void resetEditedMessageLabel() {
        getPrefs().edit().remove(KEY_EDITED_MESSAGE_LABEL).remove(KEY_EDITED_MESSAGE_LABEL_COLOR).remove(KEY_EDITED_MESSAGE_LABEL_ICON).apply();
    }

    public static void resetDeletedMessageLabel() {
        getPrefs().edit().remove(KEY_DELETED_MESSAGE_LABEL).remove(KEY_DELETED_MESSAGE_LABEL_COLOR).remove(KEY_DELETED_MESSAGE_LABEL_ICON).apply();
    }

    public static boolean saveTemporaryMedia() { return getPrefs().getBoolean(KEY_SAVE_TEMPORARY_MEDIA, false); }
    public static void setSaveTemporaryMedia(boolean v) { getPrefs().edit().putBoolean(KEY_SAVE_TEMPORARY_MEDIA, v).apply(); }

    public static String saveTemporaryMediaRelativePath() {
        return normalizeTemporaryMediaRelativePath(getPrefs().getString(KEY_SAVE_TEMPORARY_MEDIA_PATH, DEFAULT_SAVE_TEMPORARY_MEDIA_RELATIVE_PATH));
    }

    public static String saveTemporaryMediaDisplayPath() {
        String selectedFolder = getPrefs().getString(KEY_SAVE_TEMPORARY_MEDIA_TREE_DISPLAY_PATH, null);
        if (selectedFolder != null && selectedFolder.length() > 0) {
            return selectedFolder;
        }
        Uri treeUri = saveTemporaryMediaTreeUri();
        if (treeUri != null) {
            return describeSaveTemporaryMediaTreeUri(treeUri);
        }
        return "Downloads/" + saveTemporaryMediaRelativePath();
    }

    public static void setSaveTemporaryMediaRelativePath(String path) {
        String normalized = normalizeTemporaryMediaRelativePath(path);
        SharedPreferences.Editor editor = getPrefs().edit();
        editor.remove(KEY_SAVE_TEMPORARY_MEDIA_TREE_URI);
        editor.remove(KEY_SAVE_TEMPORARY_MEDIA_TREE_DISPLAY_PATH);
        if (DEFAULT_SAVE_TEMPORARY_MEDIA_RELATIVE_PATH.equals(normalized)) {
            editor.remove(KEY_SAVE_TEMPORARY_MEDIA_PATH);
        } else {
            editor.putString(KEY_SAVE_TEMPORARY_MEDIA_PATH, normalized);
        }
        editor.apply();
    }

    public static Uri saveTemporaryMediaTreeUri() {
        String uri = getPrefs().getString(KEY_SAVE_TEMPORARY_MEDIA_TREE_URI, null);
        if (uri == null || uri.length() == 0) {
            return null;
        }
        try {
            return Uri.parse(uri);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public static void setSaveTemporaryMediaTreeUri(Uri uri) {
        if (uri == null) {
            clearSaveTemporaryMediaTreeUri();
            return;
        }
        getPrefs().edit()
                .putString(KEY_SAVE_TEMPORARY_MEDIA_TREE_URI, uri.toString())
                .putString(KEY_SAVE_TEMPORARY_MEDIA_TREE_DISPLAY_PATH, describeSaveTemporaryMediaTreeUri(uri))
                .apply();
    }

    public static void clearSaveTemporaryMediaTreeUri() {
        getPrefs().edit()
                .remove(KEY_SAVE_TEMPORARY_MEDIA_TREE_URI)
                .remove(KEY_SAVE_TEMPORARY_MEDIA_TREE_DISPLAY_PATH)
                .apply();
    }

    public static void resetSaveTemporaryMediaRelativePath() {
        getPrefs().edit()
                .remove(KEY_SAVE_TEMPORARY_MEDIA_PATH)
                .remove(KEY_SAVE_TEMPORARY_MEDIA_TREE_URI)
                .remove(KEY_SAVE_TEMPORARY_MEDIA_TREE_DISPLAY_PATH)
                .apply();
    }

    public static String describeSaveTemporaryMediaTreeUri(Uri uri) {
        if (uri == null) {
            return "Downloads/" + DEFAULT_SAVE_TEMPORARY_MEDIA_RELATIVE_PATH;
        }
        try {
            String documentId = DocumentsContract.getTreeDocumentId(uri);
            if (documentId == null || documentId.length() == 0) {
                return uri.toString();
            }
            String volume = documentId;
            String path = "";
            int separator = documentId.indexOf(':');
            if (separator >= 0) {
                volume = documentId.substring(0, separator);
                path = documentId.substring(separator + 1);
            }
            String root;
            if ("primary".equalsIgnoreCase(volume)) {
                root = "Internal Storage";
            } else if ("home".equalsIgnoreCase(volume)) {
                root = "Documents";
            } else {
                root = volume;
            }
            if ("Download".equals(path)) {
                path = "Downloads";
            } else if (path.startsWith("Download/")) {
                path = "Downloads/" + path.substring("Download/".length());
            }
            return path.length() == 0 ? root : root + "/" + path;
        } catch (Exception e) {
            FileLog.e(e);
            return uri.toString();
        }
    }

    private static String normalizeTemporaryMediaRelativePath(String path) {
        String value = path == null ? "" : path.trim().replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        String downloads = Environment.DIRECTORY_DOWNLOADS;
        if (value.equalsIgnoreCase(downloads)) {
            value = "";
        } else if (value.regionMatches(true, 0, downloads + "/", 0, downloads.length() + 1)) {
            value = value.substring(downloads.length() + 1);
        } else if (value.equalsIgnoreCase("Downloads")) {
            value = "";
        } else if (value.regionMatches(true, 0, "Downloads/", 0, "Downloads/".length())) {
            value = value.substring("Downloads/".length());
        }
        StringBuilder result = new StringBuilder();
        String[] segments = value.split("/");
        for (String segment : segments) {
            String clean = segment == null ? "" : segment.trim()
                    .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
            if (clean.length() == 0 || ".".equals(clean) || "..".equals(clean)) {
                continue;
            }
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(clean);
        }
        return result.length() == 0 ? DEFAULT_SAVE_TEMPORARY_MEDIA_RELATIVE_PATH : result.toString();
    }

    public static boolean keepTemporaryMediaInChat() { return getPrefs().getBoolean(KEY_KEEP_TEMPORARY_MEDIA_IN_CHAT, false); }
    public static void setKeepTemporaryMediaInChat(boolean v) { getPrefs().edit().putBoolean(KEY_KEEP_TEMPORARY_MEDIA_IN_CHAT, v).apply(); }

    public static boolean keepKickedChatsCache() { return getPrefs().getBoolean(KEY_KEEP_KICKED_CHATS_CACHE, true); }
    public static void setKeepKickedChatsCache(boolean v) { getPrefs().edit().putBoolean(KEY_KEEP_KICKED_CHATS_CACHE, v).apply(); }

    public static boolean hideOnlineStatus() { return getPrefs().getBoolean(KEY_HIDE_ONLINE_STATUS, false); }
    public static void setHideOnlineStatus(boolean v) { getPrefs().edit().putBoolean(KEY_HIDE_ONLINE_STATUS, v).apply(); }

    public static boolean goOfflineAutomatically() { return getPrefs().getBoolean(KEY_GO_OFFLINE_AUTOMATICALLY, false); }
    public static void setGoOfflineAutomatically(boolean v) { getPrefs().edit().putBoolean(KEY_GO_OFFLINE_AUTOMATICALLY, v).apply(); }

    public static boolean hideTypingStatus() { return getPrefs().getBoolean(KEY_HIDE_TYPING_STATUS, false); }
    public static void setHideTypingStatus(boolean v) { getPrefs().edit().putBoolean(KEY_HIDE_TYPING_STATUS, v).apply(); }

    public static boolean hideReadStatus() { return getPrefs().getBoolean(KEY_HIDE_READ_STATUS, false); }
    public static void setHideReadStatus(boolean v) { getPrefs().edit().putBoolean(KEY_HIDE_READ_STATUS, v).apply(); }

    public static boolean hideStoryViews() { return getPrefs().getBoolean(KEY_HIDE_STORY_VIEWS, false); }
    public static void setHideStoryViews(boolean v) { getPrefs().edit().putBoolean(KEY_HIDE_STORY_VIEWS, v).apply(); }

    public static boolean alertBeforeOpeningStory() { return getPrefs().getBoolean(KEY_ALERT_BEFORE_OPENING_STORY, false); }
    public static void setAlertBeforeOpeningStory(boolean v) { getPrefs().edit().putBoolean(KEY_ALERT_BEFORE_OPENING_STORY, v).apply(); }

    public static boolean readOnInteract() { return getPrefs().getBoolean(KEY_READ_ON_INTERACT, false) && !scheduleMessagesInGhostMode(); }
    public static void setReadOnInteract(boolean v) {
        SharedPreferences.Editor editor = getPrefs().edit().putBoolean(KEY_READ_ON_INTERACT, v);
        if (v) {
            editor.putBoolean(KEY_SCHEDULE_MESSAGES_IN_GHOST_MODE, false);
        }
        editor.apply();
    }

    public static boolean scheduleMessagesInGhostMode() { return getPrefs().getBoolean(KEY_SCHEDULE_MESSAGES_IN_GHOST_MODE, false); }
    public static void setScheduleMessagesInGhostMode(boolean v) {
        SharedPreferences.Editor editor = getPrefs().edit().putBoolean(KEY_SCHEDULE_MESSAGES_IN_GHOST_MODE, v);
        if (v) {
            editor.putBoolean(KEY_READ_ON_INTERACT, false);
        }
        editor.apply();
    }

    public static boolean isFullGhostMode() {
        return hideOnlineStatus() && goOfflineAutomatically() && hideTypingStatus() && hideReadStatus();
    }

    public static void setFullGhostMode(boolean v) {
        getPrefs().edit()
                .putBoolean(KEY_HIDE_ONLINE_STATUS, v)
                .putBoolean(KEY_GO_OFFLINE_AUTOMATICALLY, v)
                .putBoolean(KEY_HIDE_TYPING_STATUS, v)
                .putBoolean(KEY_HIDE_READ_STATUS, v)
                .apply();
    }

    public static boolean shouldScheduleMessagesInGhostMode(long dialogId) {
        return scheduleMessagesInGhostMode() && isFullGhostMode() && !isGhostModeDisabledForDialog(dialogId);
    }

    public static boolean shouldHideTypingStatus(long dialogId) {
        return hideTypingStatus() && !isGhostModeDisabledForDialog(dialogId);
    }

    public static boolean shouldHideReadStatus(long dialogId) {
        return shouldHideReadStatus(dialogId, false);
    }

    public static boolean shouldHideReadStatus(long dialogId, boolean fromInteraction) {
        return hideReadStatus() && !isGhostModeDisabledForDialog(dialogId) && !(fromInteraction && readOnInteract());
    }

    public static boolean shouldHideStoryViews(long dialogId) {
        return (hideStoryViews() || hideReadStatus()) && !isGhostModeDisabledForDialog(dialogId);
    }

    public static HashSet<Long> getGhostModeExceptionDialogIds() {
        Set<String> stored = getPrefs().getStringSet(KEY_GHOST_MODE_EXCEPTIONS, Collections.emptySet());
        HashSet<Long> result = new HashSet<>();
        for (String value : stored) {
            try {
                result.add(Long.parseLong(value));
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return result;
    }

    public static int ghostModeExceptionsCount() {
        return getPrefs().getStringSet(KEY_GHOST_MODE_EXCEPTIONS, Collections.emptySet()).size();
    }

    public static boolean isGhostModeDisabledForDialog(long dialogId) {
        if (dialogId == 0) {
            return false;
        }
        return getPrefs().getStringSet(KEY_GHOST_MODE_EXCEPTIONS, Collections.emptySet()).contains(String.valueOf(dialogId));
    }

    public static void setGhostModeDisabledForDialog(long dialogId, boolean disabled) {
        if (dialogId == 0) {
            return;
        }
        Set<String> stored = getPrefs().getStringSet(KEY_GHOST_MODE_EXCEPTIONS, Collections.emptySet());
        HashSet<String> updated = new HashSet<>(stored);
        if (disabled) {
            updated.add(String.valueOf(dialogId));
        } else {
            updated.remove(String.valueOf(dialogId));
        }
        getPrefs().edit().putStringSet(KEY_GHOST_MODE_EXCEPTIONS, updated).apply();
    }

    public static void clearGhostModeExceptions() {
        getPrefs().edit().remove(KEY_GHOST_MODE_EXCEPTIONS).apply();
    }

    public static boolean keepLastSeenUpdatedInGhostMode() { return getPrefs().getBoolean(KEY_KEEP_LAST_SEEN_UPDATED_IN_GHOST_MODE, false); }
    public static void setKeepLastSeenUpdatedInGhostMode(boolean v) { getPrefs().edit().putBoolean(KEY_KEEP_LAST_SEEN_UPDATED_IN_GHOST_MODE, v).apply(); }

    public static class BanGroup {
        public String id;
        public String name;
        public boolean enabled;
        public List<String> phrases;

        public BanGroup(String id, String name, boolean enabled, List<String> phrases) {
            this.id = id != null ? id : UUID.randomUUID().toString();
            this.name = name;
            this.enabled = enabled;
            this.phrases = phrases != null ? phrases : new ArrayList<>();
        }
    }

    private static final String KEY_BAN_GROUPS = "ban_groups_json";

    public static List<BanGroup> getBanGroups() {
        List<BanGroup> result = new ArrayList<>();
        String json = getPrefs().getString(KEY_BAN_GROUPS, "");
        if (json.isEmpty()) return result;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                List<String> phrases = new ArrayList<>();
                JSONArray pArr = obj.optJSONArray("phrases");
                if (pArr != null) {
                    for (int j = 0; j < pArr.length(); j++) phrases.add(pArr.getString(j));
                }
                result.add(new BanGroup(obj.optString("id"), obj.getString("name"), obj.optBoolean("enabled", true), phrases));
            }
        } catch (Exception e) { FileLog.e(e); }
        return result;
    }

    public static void saveBanGroups(List<BanGroup> groups) {
        try {
            JSONArray arr = new JSONArray();
            for (BanGroup g : groups) {
                JSONObject obj = new JSONObject();
                obj.put("id", g.id);
                obj.put("name", g.name);
                obj.put("enabled", g.enabled);
                JSONArray pArr = new JSONArray();
                for (String p : g.phrases) pArr.put(p);
                obj.put("phrases", pArr);
                arr.put(obj);
            }
            getPrefs().edit().putString(KEY_BAN_GROUPS, arr.toString()).apply();
        } catch (Exception e) { FileLog.e(e); }
    }

    private static final String KEY_HIDDEN_LOG = "hidden_log_json";

    public static void addToHiddenLog(String uid, long channelId, int messageId, String textSnippet, long date) {
        try {
            JSONArray arr = new JSONArray(getPrefs().getString(KEY_HIDDEN_LOG, "[]"));
            JSONObject obj = new JSONObject();
            obj.put("uid", uid);
            obj.put("channelId", channelId);
            obj.put("messageId", messageId);
            obj.put("snippet", textSnippet != null ? textSnippet : "");
            obj.put("date", date);
            arr.put(obj);

            if (arr.length() > 100) {
                arr = new JSONArray(arr.toString().substring(arr.toString().indexOf(",") + 1));
            }
            getPrefs().edit().putString(KEY_HIDDEN_LOG, arr.toString()).apply();
        } catch (Exception e) { FileLog.e(e); }
    }

    public static JSONArray getHiddenLog() {
        try { return new JSONArray(getPrefs().getString(KEY_HIDDEN_LOG, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    public static void clearHiddenLog() {
        getPrefs().edit().putString(KEY_HIDDEN_LOG, "[]").apply();
    }
}
