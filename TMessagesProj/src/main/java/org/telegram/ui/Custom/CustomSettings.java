package org.telegram.ui.Custom;

import android.content.SharedPreferences;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.ui.Feed.FeedAlbumMode;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
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
    private static final String KEY_SAVE_TEMPORARY_MEDIA = "save_temporary_media";
    private static final String KEY_KEEP_TEMPORARY_MEDIA_IN_CHAT = "keep_temporary_media_in_chat";
    private static final String KEY_KEEP_KICKED_CHATS_CACHE = "keep_kicked_chats_cache";
    private static final String KEY_HIDE_ONLINE_STATUS = "hide_online_status";
    private static final String KEY_HIDE_TYPING_STATUS = "hide_typing_status";
    private static final String KEY_KEEP_LAST_SEEN_UPDATED_IN_GHOST_MODE = "send_offline_status_in_ghost_mode";

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

    public static boolean saveTemporaryMedia() { return getPrefs().getBoolean(KEY_SAVE_TEMPORARY_MEDIA, true); }
    public static void setSaveTemporaryMedia(boolean v) { getPrefs().edit().putBoolean(KEY_SAVE_TEMPORARY_MEDIA, v).apply(); }

    public static boolean keepTemporaryMediaInChat() { return getPrefs().getBoolean(KEY_KEEP_TEMPORARY_MEDIA_IN_CHAT, false); }
    public static void setKeepTemporaryMediaInChat(boolean v) { getPrefs().edit().putBoolean(KEY_KEEP_TEMPORARY_MEDIA_IN_CHAT, v).apply(); }

    public static boolean keepKickedChatsCache() { return getPrefs().getBoolean(KEY_KEEP_KICKED_CHATS_CACHE, true); }
    public static void setKeepKickedChatsCache(boolean v) { getPrefs().edit().putBoolean(KEY_KEEP_KICKED_CHATS_CACHE, v).apply(); }

    public static boolean hideOnlineStatus() { return getPrefs().getBoolean(KEY_HIDE_ONLINE_STATUS, false); }
    public static void setHideOnlineStatus(boolean v) { getPrefs().edit().putBoolean(KEY_HIDE_ONLINE_STATUS, v).apply(); }

    public static boolean hideTypingStatus() { return getPrefs().getBoolean(KEY_HIDE_TYPING_STATUS, false); }
    public static void setHideTypingStatus(boolean v) { getPrefs().edit().putBoolean(KEY_HIDE_TYPING_STATUS, v).apply(); }

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
