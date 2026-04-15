package org.telegram.ui.Feed;

import android.content.SharedPreferences;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.support.LongSparseIntArray;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Custom.CustomSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

public class FeedController implements NotificationCenter.NotificationCenterDelegate {
    private boolean observing = false;
    private final List<Runnable> newPostListeners = new ArrayList<>();

    private static final int CHANNEL_BATCH_MESSAGES = 20;
    private static final int INITIAL_GLOBAL_ITEMS = 40;
    private static final int LOAD_MORE_GLOBAL_ITEMS = 30;

    private static final int HYDRATION_CHANNELS_PER_ROUND = 3;
    private static final int HYDRATION_BATCH_MESSAGES = 20;
    private static final int MAX_HYDRATION_ROUNDS = 2;
    private static final int SNAPSHOT_MAX_ITEMS = 200;

    private final FeedHistoryHydrator historyHydrator;
    private final FeedDiskCache diskCache;
    private Runnable saveSnapshotRunnable;

    private final LinkedHashMap<Long, ChannelCursorState> channelCursors = new LinkedHashMap<>();
    private int displayVersion = 0;

    private final FeedRecommendationEngine recommendationEngine;

    public int getDisplayVersion() {
        return displayVersion;
    }

    @FunctionalInterface
    public interface FeedAppendCallback {
        void onAppended(int addedDisplayItems, boolean hasMore);
    }

    private static class ChannelCursorState {
        final long dialogId;
        final int readMaxId;
        final int topMessageId;

        int lastConsumedMid;
        boolean exhausted;

        final ArrayList<FeedItem> buffer = new ArrayList<>();

        ChannelCursorState(long dialogId, int readMaxId, int topMessageId) {
            this.dialogId = dialogId;
            this.readMaxId = readMaxId;
            this.topMessageId = topMessageId;
            this.lastConsumedMid = readMaxId;
        }
    }

    private static class DbChunkResult {
        final ArrayList<MessageObject> validMessages = new ArrayList<>();
        int consumedToMid;
        boolean hasAnyRows;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didReceiveNewMessages) {
            if (!feedLoaded) return;

            long dialogId = (Long) args[0];
            if (dialogId >= 0) return;

            MessagesController controller = MessagesController.getInstance(currentAccount);
            TLRPC.Chat chat = controller.getChat(-dialogId);
            if (chat == null || !chat.broadcast || chat.megagroup) return;
            if (isChannelHidden(-dialogId)) return;
            if (CustomSettings.hideProxySponsor() && controller.isPromoDialog(dialogId, false))
                return;

            ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
            if (messages == null || messages.isEmpty()) return;

            List<MessageObject> validMessages = new ArrayList<>();
            for (MessageObject obj : messages) {
                if (obj.isOut() || obj.messageOwner.action != null) continue;

                boolean hasContent = (obj.messageOwner.message != null
                        && !obj.messageOwner.message.isEmpty())
                        || obj.messageOwner.media != null;

                boolean isAlbumPart = obj.messageOwner.grouped_id != 0;

                if (!hasContent && !isAlbumPart) continue;
                validMessages.add(obj);
            }
            if (validMessages.isEmpty()) return;

            List<FeedItem> newItems = groupIntoItems(validMessages, dialogId);
            boolean changed = false;

            for (FeedItem newItem : newItems) {
                MessageObject primary = newItem.getPrimaryMessage();
                long groupedId = primary.messageOwner.grouped_id;

                if (groupedId != 0) {
                    FeedItem existing = findExistingAlbum(dialogId, groupedId);
                    if (existing != null) {
                        if (mergeAlbumMessages(existing, newItem)) {
                            for (MessageObject msg : newItem.messages) {
                                loadedItemIds.add(dialogId + "_" + msg.getId());
                            }
                            mergedItems.add(existing);
                            changed = true;
                        }
                        continue;
                    }
                }

                String uid = newItem.getUniqueId();
                if (loadedItemIds.contains(uid)) continue;

                loadedItemIds.add(uid);
                for (MessageObject msg : newItem.messages) {
                    loadedItemIds.add(dialogId + "_" + msg.getId());
                }
                newItem.isRead = false;
                newItem.isBookmarked = isBookmarked(uid);
                cachedFeed.add(newItem);
                pendingNewItems.add(newItem);
                changed = true;
            }

            if (changed) {
                Collections.sort(cachedFeed, Comparator.comparingLong(a -> a.sortDate));
                scheduleSnapshotSave();
                AndroidUtilities.runOnUIThread(this::notifyNewPostListeners);
            }
        }
    }

    public void startObserving() {
        if (observing) return;
        observing = true;
        NotificationCenter.getInstance(currentAccount)
                .addObserver(this, NotificationCenter.didReceiveNewMessages);
    }

    public void stopObserving() {
        if (!observing) return;
        observing = false;
        NotificationCenter.getInstance(currentAccount)
                .removeObserver(this, NotificationCenter.didReceiveNewMessages);
    }

    public void addNewPostListener(Runnable listener) {
        if (!newPostListeners.contains(listener)) {
            newPostListeners.add(listener);
        }
    }

    public void removeNewPostListener(Runnable listener) {
        newPostListeners.remove(listener);
    }

    private void notifyNewPostListeners() {
        for (Runnable r : newPostListeners) {
            r.run();
        }
    }

    @FunctionalInterface
    public interface FeedLoadCallback {
        void onLoaded(List<FeedItem> items, boolean hasMore);
    }

    public static class FeedItem {
        public final long channelId;
        public final List<MessageObject> messages;
        public boolean isRead;
        public boolean isBookmarked;
        public long sortDate;

        public boolean isRecommendation = false;
        public String recommendationReason;
        public TLRPC.Chat recommendedChat;
        public long recommendedChannelId;

        public boolean textExpanded = false;
        public final java.util.HashSet<Integer> expandedQuoteOffsets = new java.util.HashSet<>();

        public boolean translationShown = false;

        public FeedItem(long channelId, List<MessageObject> messages, long date) {
            this.channelId = channelId;
            this.messages = messages;
            this.sortDate = date;
        }

        public MessageObject getPrimaryMessage() {
            if (messages == null || messages.isEmpty()) return null;

            MessageObject best = messages.get(0);
            for (int i = 1; i < messages.size(); i++) {
                MessageObject msg = messages.get(i);
                boolean bestHasText = best.messageOwner.message != null
                        && !best.messageOwner.message.isEmpty();
                boolean msgHasText = msg.messageOwner.message != null
                        && !msg.messageOwner.message.isEmpty();

                if (!bestHasText && msgHasText) {
                    best = msg;
                } else if (!bestHasText && !msgHasText) {
                    if (msg.getId() < best.getId()) {
                        best = msg;
                    }
                }
            }
            return best;
        }
        public boolean isAlbum() { return messages.size() > 1; }
        public int getMessageId() {
            if (messages == null || messages.isEmpty()) return 0;
            int minId = messages.get(0).getId();
            for (int i = 1; i < messages.size(); i++) {
                int id = messages.get(i).getId();
                if (id < minId) minId = id;
            }
            return minId;
        }

        public String getUniqueId() {
            return channelId + "_" + getMessageId();
        }
    }

    private static final FeedController[] instances = new FeedController[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
    private final Set<String> localReadIds = new HashSet<>();
    private final Set<String> bookmarkedIds = new HashSet<>();
    private final Set<Long> hiddenChannelIds = new HashSet<>();
    private final List<FeedItem> cachedFeed = new ArrayList<>();
    private boolean isLoading = false;
    private boolean feedLoaded = false;
    private Runnable saveRunnable;

    private boolean noMorePosts = false;
    private final Set<String> loadedItemIds = new HashSet<>();

    private final List<Object> displayItems = new ArrayList<>();
    private int postsSinceLastRec = 0;
    private int recPostsUsedIndex = 0;
    private final List<FeedItem> pendingNewItems = new ArrayList<>();
    private final List<FeedItem> mergedItems = new ArrayList<>();

    public static FeedController getInstance(int account) {
        FeedController local = instances[account];
        if (local == null) {
            synchronized (FeedController.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new FeedController(account);
                }
            }
        }
        return local;
    }

    private FeedController(int account) {
        this.currentAccount = account;
        loadPersistedData();
        this.recommendationEngine = FeedRecommendationEngine.getInstance(account);
        this.historyHydrator = new FeedHistoryHydrator(account);
        this.diskCache = new FeedDiskCache(account);
    }

    private SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("feed_v6_" + currentAccount, 0);
    }

    private void loadPersistedData() {
        SharedPreferences p = getPrefs();
        localReadIds.addAll(p.getStringSet("read", new HashSet<>()));
        bookmarkedIds.addAll(p.getStringSet("bookmarks", new HashSet<>()));

        Set<String> hiddenSet = p.getStringSet("hidden_channels", new HashSet<>());
        for (String s : hiddenSet) {
            try {
                hiddenChannelIds.add(Long.parseLong(s));
            } catch (NumberFormatException ignored) {}
        }
    }

    private void saveNow() {
        if (saveRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(saveRunnable);
            saveRunnable = null;
        }
        performSave();
    }

    private void performSave() {
        Set<String> r = new HashSet<>(localReadIds);
        if (r.size() > 50000) {
            List<String> l = new ArrayList<>(r);
            r = new HashSet<>(l.subList(l.size() - 50000, l.size()));
        }

        Set<String> hiddenStrs = new HashSet<>();
        for (Long id : hiddenChannelIds) {
            hiddenStrs.add(String.valueOf(id));
        }

        getPrefs().edit()
                .putStringSet("read", r)
                .putStringSet("bookmarks", new HashSet<>(bookmarkedIds))
                .putStringSet("hidden_channels", hiddenStrs)
                .apply();
    }

    public void markAsRead(FeedItem item) {
        if (item == null) return;
        String uid = item.getUniqueId();
        if (localReadIds.contains(uid)) return;
        item.isRead = true;

        int maxId = 0;
        for (MessageObject msg : item.messages) {
            localReadIds.add(item.channelId + "_" + msg.getId());
            maxId = Math.max(maxId, msg.getId());
        }
        localReadIds.add(uid);

        saveNow();
        scheduleSnapshotSave();

        final int finalMaxId = maxId;
        final long dialogId = item.channelId;

        AndroidUtilities.runOnUIThread(() -> {
            MessagesController controller = MessagesController.getInstance(currentAccount);
            long chatId = -dialogId;
            TLRPC.Chat chat = controller.getChat(chatId);

            if (chat == null) return;

            TLRPC.TL_channels_readHistory req = new TLRPC.TL_channels_readHistory();
            req.channel = MessagesController.getInputChannel(chat);
            req.max_id = finalMaxId;
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
                if (error != null) {
                    android.util.Log.w("FeedController",
                            "readHistory failed for " + dialogId + ": " + error.text);
                }
            });

            TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
            if (dialog != null) {
                int oldMaxId = dialog.read_inbox_max_id;

                if (finalMaxId > oldMaxId) {
                    dialog.read_inbox_max_id = finalMaxId;
                }

                int actuallyRead = 0;
                for (MessageObject msg : item.messages) {
                    if (msg.getId() > oldMaxId) actuallyRead++;
                }
                if (actuallyRead > 0) {
                    dialog.unread_count = Math.max(0, dialog.unread_count - actuallyRead);
                }

                LongSparseIntArray inbox = new LongSparseIntArray();
                inbox.put(dialogId, finalMaxId);
                MessagesStorage.getInstance(currentAccount).updateDialogsWithReadMessages(
                        inbox, null, null, null, true
                );

                NotificationCenter.getInstance(currentAccount).postNotificationName(
                        NotificationCenter.updateInterfaces,
                        MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE);
                NotificationCenter.getInstance(currentAccount).postNotificationName(
                        NotificationCenter.dialogsNeedReload);
            }
        });
    }

    public boolean isLocallyRead(long channelId, int messageId) {
        return localReadIds.contains(channelId + "_" + messageId);
    }

    public boolean isBookmarked(String uid) { return bookmarkedIds.contains(uid); }

    public boolean hasCachedFeed() {
        return !cachedFeed.isEmpty();
    }
    public List<FeedItem> getCachedFeed() { return cachedFeed; }
    public boolean isLoading() { return isLoading; }

    public void loadFeed(boolean force, FeedLoadCallback callback) {
        if (!force && feedLoaded) {
            callback.onLoaded(new ArrayList<>(cachedFeed), hasMore());
            return;
        }
        if (isLoading) return;

        isLoading = true;

        MessagesController controller = MessagesController.getInstance(currentAccount);
        List<TLRPC.Dialog> channels = collectUnreadChannels(controller);

        if (channels.isEmpty()) {
            channelCursors.clear();
            clearReloadedFeedDataButKeepCursors();
            feedLoaded = true;
            isLoading = false;
            rebuildDisplayList();
            diskCache.clear();
            callback.onLoaded(new ArrayList<>(), false);
            return;
        }

        final Set<String> readSnapshot = new HashSet<>(localReadIds);
        final LinkedHashMap<Long, ChannelCursorState> workingCursors = new LinkedHashMap<>();
        for (TLRPC.Dialog dialog : channels) {
            workingCursors.put(dialog.id,
                    new ChannelCursorState(dialog.id, dialog.read_inbox_max_id, dialog.top_message));
        }

        loadMergedPageWithHydration(
                workingCursors,
                readSnapshot,
                INITIAL_GLOBAL_ITEMS,
                MAX_HYDRATION_ROUNDS,
                new ArrayList<>(),
                (page, finalCursors, hasMore) -> {
                    channelCursors.clear();
                    channelCursors.putAll(finalCursors);

                    clearReloadedFeedDataButKeepCursors();
                    appendPageToCache(page);

                    feedLoaded = true;
                    isLoading = false;
                    noMorePosts = !hasMore;

                    rebuildDisplayList();
                    scheduleSnapshotSave();
                    callback.onLoaded(new ArrayList<>(cachedFeed), hasMore);

                    new FeedStatsRefresher(currentAccount).refreshReactions(page, () ->
                            AndroidUtilities.runOnUIThread(this::notifyNewPostListeners)
                    );

                    recommendationEngine.ensureReady(() ->
                            AndroidUtilities.runOnUIThread(() -> {
                                rebuildDisplayList();
                                scheduleSnapshotSave();
                                notifyNewPostListeners();
                            })
                    );
                }
        );
    }

    private List<TLRPC.Dialog> collectUnreadChannels(MessagesController controller) {
        List<TLRPC.Dialog> channels = new ArrayList<>();
        for (TLRPC.Dialog dialog : controller.getAllDialogs()) {
            if (dialog == null || dialog.id >= 0) continue;
            TLRPC.Chat chat = controller.getChat(-dialog.id);
            if (chat == null || !chat.broadcast || chat.megagroup) continue;
            if (dialog.unread_count <= 0) continue;
            if (dialog.read_inbox_max_id <= 0) continue;
            if (dialog.top_message <= dialog.read_inbox_max_id) continue;
            if (isChannelHidden(-dialog.id)) continue;
            if (CustomSettings.hideProxySponsor()
                    && controller.isPromoDialog(dialog.id, false)) continue;
            channels.add(dialog);
        }
        return channels;
    }

    private List<FeedItem> groupIntoItems(List<MessageObject> messages, long dialogId) {
        LinkedHashMap<String, List<MessageObject>> groups = new LinkedHashMap<>();

        for (MessageObject msg : messages) {
            long gid = msg.messageOwner.grouped_id;
            String key = gid != 0
                    ? "g_" + dialogId + "_" + gid
                    : "s_" + dialogId + "_" + msg.getId();

            List<MessageObject> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
            }
            group.add(msg);
        }

        List<FeedItem> items = new ArrayList<>();
        for (List<MessageObject> group : groups.values()) {
            Collections.sort(group, Comparator.comparingInt(MessageObject::getId));

            long date = group.get(0).messageOwner.date;

            FeedItem item = new FeedItem(dialogId, group, date);
            item.isRead = false;
            item.isBookmarked = isBookmarked(item.getUniqueId());
            items.add(item);
        }
        return items;
    }

    public void hideChannel(long channelId) {
        hiddenChannelIds.add(channelId);
        cachedFeed.removeIf(
                item -> item.channelId == -channelId
                        || item.channelId == channelId);
        saveNow();
        rebuildDisplayList();
        scheduleSnapshotSave();
    }

    public void unhideChannel(long channelId) {
        hiddenChannelIds.remove(channelId);
        saveNow();
    }

    public boolean isChannelHidden(long channelId) {
        return hiddenChannelIds.contains(channelId);
    }

    public Set<Long> getHiddenChannelIds() {
        return new HashSet<>(hiddenChannelIds);
    }

    public boolean hasMore() {
        return hasRemainingChannelsInternal(channelCursors);
    }

    public void resetLoadMore() {
        noMorePosts = false;
        channelCursors.clear();
    }

    public void loadMore(FeedAppendCallback callback) {
        if (!feedLoaded || !hasMore()) {
            callback.onAppended(0, false);
            return;
        }
        if (isLoading) return;

        isLoading = true;

        final Set<String> readSnapshot = new HashSet<>(localReadIds);
        final LinkedHashMap<Long, ChannelCursorState> workingCursors = copyCursorMap();

        loadMergedPageWithHydration(
                workingCursors,
                readSnapshot,
                LOAD_MORE_GLOBAL_ITEMS,
                MAX_HYDRATION_ROUNDS,
                new ArrayList<>(),
                (page, finalCursors, hasMore) -> {
                    channelCursors.clear();
                    channelCursors.putAll(finalCursors);

                    appendPageToCache(page);
                    int addedDisplayItems = appendItemsToDisplay(page);

                    isLoading = false;
                    noMorePosts = !hasMore;

                    new FeedStatsRefresher(currentAccount).refreshReactions(page, () ->
                            AndroidUtilities.runOnUIThread(this::notifyNewPostListeners)
                    );

                    callback.onAppended(addedDisplayItems, hasMore);
                }
        );
    }

    public FeedRecommendationEngine getRecommendationEngine() {
        return recommendationEngine;
    }

    public List<Object> getDisplayItems() {
        return displayItems;
    }

    public void rebuildDisplayList() {
        displayItems.clear();
        postsSinceLastRec = 0;
        recPostsUsedIndex = 0;

        List<FeedItem> recPosts = recommendationEngine.hasRecommendedPosts()
                ? recommendationEngine.getRecommendedPosts()
                : Collections.emptyList();

        int interval = FeedRecommendationEngine.getRecommendationInterval();

        for (FeedItem item : cachedFeed) {
            displayItems.add(item);
            postsSinceLastRec++;

            if (postsSinceLastRec >= interval
                    && recPostsUsedIndex < recPosts.size()) {
                displayItems.add(recPosts.get(recPostsUsedIndex++));
                postsSinceLastRec = 0;
            }
        }

        while (recPostsUsedIndex < recPosts.size()) {
            displayItems.add(recPosts.get(recPostsUsedIndex++));
        }
        displayVersion++;
    }

    public List<FeedItem> flushPendingNewItems() {
        List<FeedItem> result = new ArrayList<>(pendingNewItems);
        pendingNewItems.clear();
        return result;
    }

    public int appendItemsToDisplay(List<FeedItem> items) {
        int added = 0;
        List<FeedItem> recPosts = recommendationEngine.hasRecommendedPosts()
                ? recommendationEngine.getRecommendedPosts()
                : Collections.emptyList();
        int interval = FeedRecommendationEngine.getRecommendationInterval();

        for (FeedItem item : items) {
            displayItems.add(item);
            added++;
            postsSinceLastRec++;

            if (postsSinceLastRec >= interval
                    && recPostsUsedIndex < recPosts.size()) {
                displayItems.add(recPosts.get(recPostsUsedIndex++));
                postsSinceLastRec = 0;
                added++;
            }
        }
        if (added > 0) {
            displayVersion++;
        }
        return added;
    }

    public int appendNewRecommendationsToDisplay() {
        List<FeedItem> recPosts = recommendationEngine.hasRecommendedPosts()
                ? recommendationEngine.getRecommendedPosts()
                : Collections.emptyList();

        int added = 0;
        while (recPostsUsedIndex < recPosts.size()) {
            displayItems.add(recPosts.get(recPostsUsedIndex++));
            added++;
        }
        if (added > 0) {
            displayVersion++;
        }
        return added;
    }

    public static class FeedSeparator {
        public final int type;
        public FeedSeparator(int type) {
            this.type = type;
        }
    }

    public void loadMoreRecommendations(FeedLoadCallback callback) {
        if (!recommendationEngine.canLoadMore()) {
            callback.onLoaded(cachedFeed, false);
            return;
        }

        recommendationEngine.loadMore(() ->
                AndroidUtilities.runOnUIThread(() ->
                        callback.onLoaded(cachedFeed,
                                recommendationEngine.canLoadMore())
                )
        );
    }

    private FeedItem findExistingAlbum(long dialogId, long groupedId) {
        for (FeedItem item : cachedFeed) {
            if (item.channelId != dialogId) continue;
            for (MessageObject msg : item.messages) {
                if (msg.messageOwner.grouped_id == groupedId) {
                    return item;
                }
            }
        }
        return null;
    }

    private boolean mergeAlbumMessages(FeedItem existing, FeedItem incoming) {
        Set<Integer> existingIds = new HashSet<>();
        for (MessageObject msg : existing.messages) {
            existingIds.add(msg.getId());
        }

        boolean added = false;
        for (MessageObject msg : incoming.messages) {
            if (!existingIds.contains(msg.getId())) {
                existing.messages.add(msg);
                added = true;
            }
        }

        if (added) {
            Collections.sort(existing.messages,
                    Comparator.comparingInt(MessageObject::getId));
        }
        return added;
    }

    public List<FeedItem> flushMergedItems() {
        List<FeedItem> result = new ArrayList<>(mergedItems);
        mergedItems.clear();
        return result;
    }

    private boolean isValidFeedMessage(MessageObject obj, long dialogId, Set<String> readSnapshot) {
        if (obj == null) return false;
        if (obj.isOut()) return false;
        if (obj.messageOwner.action != null) return false;

        boolean hasContent = (obj.messageOwner.message != null
                && !obj.messageOwner.message.isEmpty())
                || obj.messageOwner.media != null;

        boolean isAlbumPart = obj.messageOwner.grouped_id != 0;

        if (!hasContent && !isAlbumPart) return false;

        if (isAlbumPart) {
            return true;
        }

        return !isLocallyRead(readSnapshot, dialogId, obj.getId());
    }

    private static int compareFeedItemsOrder(FeedItem a, FeedItem b) {
        int c = Long.compare(a.sortDate, b.sortDate);
        if (c != 0) return c;

        c = Long.compare(a.channelId, b.channelId);
        if (c != 0) return c;

        return Integer.compare(a.getMessageId(), b.getMessageId());
    }

    private void clearReloadedFeedDataButKeepCursors() {
        cachedFeed.clear();
        loadedItemIds.clear();
        pendingNewItems.clear();
        mergedItems.clear();
        displayItems.clear();
        postsSinceLastRec = 0;
        recPostsUsedIndex = 0;
        noMorePosts = false;
    }

    private void appendPageToCache(List<FeedItem> pageItems) {
        for (FeedItem item : pageItems) {
            String uid = item.getUniqueId();
            if (loadedItemIds.contains(uid)) continue;

            item.isRead = false;
            item.isBookmarked = isBookmarked(uid);

            cachedFeed.add(item);
            loadedItemIds.add(uid);

            for (MessageObject msg : item.messages) {
                loadedItemIds.add(item.channelId + "_" + msg.getId());
            }
        }
    }

    private boolean hasRemainingChannelsInternal(LinkedHashMap<Long, ChannelCursorState> cursors) {
        for (ChannelCursorState state : cursors.values()) {
            if (!state.buffer.isEmpty() || !state.exhausted) {
                return true;
            }
        }
        return false;
    }

    private DbChunkResult loadNextChunkFromDb(MessagesStorage storage,
                                              ChannelCursorState state,
                                              long selfId,
                                              Set<String> readSnapshot) {
        DbChunkResult result = new DbChunkResult();
        SQLiteCursor cursor = null;
        long lastGroupedId = 0;

        try {
            cursor = storage.getDatabase().queryFinalized(
                    "SELECT data, mid, date FROM messages_v2" +
                            " WHERE uid = " + state.dialogId +
                            " AND mid > " + state.lastConsumedMid +
                            " AND mid <= " + state.topMessageId +
                            " ORDER BY mid ASC" +
                            " LIMIT " + CHANNEL_BATCH_MESSAGES
            );

            while (cursor.next()) {
                result.hasAnyRows = true;

                NativeByteBuffer data = cursor.byteBufferValue(0);
                int mid = cursor.intValue(1);
                int date = cursor.intValue(2);

                result.consumedToMid = mid;

                if (data == null) {
                    continue;
                }

                try {
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(
                            data, data.readInt32(false), false);
                    if (message == null) {
                        continue;
                    }

                    message.readAttachPath(data, selfId);
                    message.id = mid;
                    message.date = date;
                    message.dialog_id = state.dialogId;

                    if (message.peer_id == null) {
                        TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
                        peer.channel_id = -state.dialogId;
                        message.peer_id = peer;
                    }

                    lastGroupedId = message.grouped_id;

                    MessageObject obj = new MessageObject(currentAccount, message, true, true);
                    if (isValidFeedMessage(obj, state.dialogId, readSnapshot)) {
                        result.validMessages.add(obj);
                    }
                } finally {
                    data.reuse();
                }
            }
        } catch (Exception e) {
            android.util.Log.e("FeedController", "loadNextChunkFromDb failed", e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }

        if (!result.hasAnyRows) {
            return result;
        }

        if (lastGroupedId != 0 && result.consumedToMid > 0) {
            while (true) {
                SQLiteCursor extra = null;
                try {
                    extra = storage.getDatabase().queryFinalized(
                            "SELECT data, mid, date FROM messages_v2" +
                                    " WHERE uid = " + state.dialogId +
                                    " AND mid > " + result.consumedToMid +
                                    " AND mid <= " + state.topMessageId +
                                    " ORDER BY mid ASC LIMIT 1"
                    );

                    if (!extra.next()) {
                        break;
                    }

                    NativeByteBuffer data = extra.byteBufferValue(0);
                    int mid = extra.intValue(1);
                    int date = extra.intValue(2);

                    if (data == null) {
                        break;
                    }

                    try {
                        TLRPC.Message message = TLRPC.Message.TLdeserialize(
                                data, data.readInt32(false), false);
                        if (message == null) {
                            break;
                        }

                        if (message.grouped_id != lastGroupedId) {
                            break;
                        }

                        message.readAttachPath(data, selfId);
                        message.id = mid;
                        message.date = date;
                        message.dialog_id = state.dialogId;

                        if (message.peer_id == null) {
                            TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
                            peer.channel_id = -state.dialogId;
                            message.peer_id = peer;
                        }

                        result.consumedToMid = mid;

                        MessageObject obj = new MessageObject(currentAccount, message, true, true);
                        if (isValidFeedMessage(obj, state.dialogId, readSnapshot)) {
                            result.validMessages.add(obj);
                        }
                    } finally {
                        data.reuse();
                    }
                } catch (Exception e) {
                    android.util.Log.e("FeedController", "album tail read failed", e);
                    break;
                } finally {
                    if (extra != null) {
                        extra.dispose();
                    }
                }
            }
        }

        return result;
    }

    private void fillCursorBuffer(MessagesStorage storage,
                                  ChannelCursorState state,
                                  long selfId,
                                  Set<String> readSnapshot) {
        while (!state.exhausted && state.buffer.isEmpty()) {
            DbChunkResult chunk = loadNextChunkFromDb(storage, state, selfId, readSnapshot);

            if (!chunk.hasAnyRows || chunk.consumedToMid <= state.lastConsumedMid) {
                state.exhausted = true;
                break;
            }

            state.lastConsumedMid = chunk.consumedToMid;

            if (!chunk.validMessages.isEmpty()) {
                List<FeedItem> groupedItems = groupIntoItems(chunk.validMessages, state.dialogId);

                groupedItems.removeIf(item -> {
                    if (item.isAlbum()) {
                        boolean allRead = true;
                        for (MessageObject msg : item.messages) {
                            if (!isLocallyRead(readSnapshot, state.dialogId, msg.getId())) {
                                allRead = false;
                                break;
                            }
                        }
                        return allRead;
                    }
                    return false;
                });

                state.buffer.addAll(groupedItems);
            }

            if (state.lastConsumedMid >= state.topMessageId) {
                state.exhausted = true;
            }
        }
    }

    private List<FeedItem> buildNextMergedPage(MessagesStorage storage,
                                               int targetItems,
                                               long selfId,
                                               Set<String> readSnapshot,
                                               LinkedHashMap<Long, ChannelCursorState> cursors) {
        ArrayList<FeedItem> result = new ArrayList<>();

        while (result.size() < targetItems) {
            for (ChannelCursorState state : cursors.values()) {
                if (state.buffer.isEmpty() && !state.exhausted) {
                    fillCursorBuffer(storage, state, selfId, readSnapshot);
                }
            }

            ChannelCursorState bestState = null;
            FeedItem bestItem = null;

            for (ChannelCursorState state : cursors.values()) {
                if (state.buffer.isEmpty()) continue;

                FeedItem candidate = state.buffer.get(0);
                if (bestItem == null || compareFeedItemsOrder(candidate, bestItem) < 0) {
                    bestItem = candidate;
                    bestState = state;
                }
            }

            if (bestState == null || bestItem == null) {
                break;
            }

            bestState.buffer.remove(0);
            result.add(bestItem);
        }

        return result;
    }

    private static ChannelCursorState copyCursor(ChannelCursorState src) {
        ChannelCursorState dst = new ChannelCursorState(src.dialogId, src.readMaxId, src.topMessageId);
        dst.lastConsumedMid = src.lastConsumedMid;
        dst.exhausted = src.exhausted;
        dst.buffer.addAll(src.buffer);
        return dst;
    }

    private LinkedHashMap<Long, ChannelCursorState> copyCursorMap() {
        LinkedHashMap<Long, ChannelCursorState> copy = new LinkedHashMap<>();
        for (ChannelCursorState state : channelCursors.values()) {
            copy.put(state.dialogId, copyCursor(state));
        }
        return copy;
    }

    private static boolean isLocallyRead(Set<String> readSnapshot, long channelId, int messageId) {
        return readSnapshot.contains(channelId + "_" + messageId);
    }

    @FunctionalInterface
    public interface FeedSnapshotCallback {
        void onLoaded(List<FeedItem> items);
    }

    private ArrayList<FeedDiskCache.SnapshotItem> buildSnapshotData() {
        ArrayList<FeedDiskCache.SnapshotItem> result = new ArrayList<>();
        for (FeedItem item : cachedFeed) {
            if (item == null || item.isRecommendation || item.messages == null || item.messages.isEmpty()) {
                continue;
            }

            int[] mids = new int[item.messages.size()];
            for (int i = 0; i < item.messages.size(); i++) {
                mids[i] = item.messages.get(i).getId();
            }

            result.add(new FeedDiskCache.SnapshotItem(item.channelId, item.sortDate, mids));
            if (result.size() >= SNAPSHOT_MAX_ITEMS) {
                break;
            }
        }
        return result;
    }

    private void scheduleSnapshotSave() {
        if (saveSnapshotRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(saveSnapshotRunnable);
        }

        saveSnapshotRunnable = () -> {
            saveSnapshotRunnable = null;
            diskCache.save(buildSnapshotData());
        };

        AndroidUtilities.runOnUIThread(saveSnapshotRunnable, 1200);
    }

    public void loadFeedSnapshot(FeedSnapshotCallback callback) {
        if (!cachedFeed.isEmpty()) {
            callback.onLoaded(new ArrayList<>(cachedFeed));
            return;
        }

        diskCache.load(snapshotItems -> {
            if (snapshotItems == null || snapshotItems.isEmpty()) {
                callback.onLoaded(Collections.emptyList());
                return;
            }

            MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
            storage.getStorageQueue().postRunnable(() -> {
                ArrayList<FeedItem> restored = new ArrayList<>();
                long selfId = UserConfig.getInstance(currentAccount).getClientUserId();

                for (FeedDiskCache.SnapshotItem snap : snapshotItems) {
                    if (snap == null || snap.messageIds == null || snap.messageIds.length == 0) {
                        continue;
                    }

                    StringBuilder mids = new StringBuilder();
                    for (int i = 0; i < snap.messageIds.length; i++) {
                        if (i > 0) mids.append(",");
                        mids.append(snap.messageIds[i]);
                    }

                    SQLiteCursor cursor = null;
                    try {
                        cursor = storage.getDatabase().queryFinalized(
                                "SELECT data, mid, date FROM messages_v2" +
                                        " WHERE uid = " + snap.channelId +
                                        " AND mid IN(" + mids + ")" +
                                        " ORDER BY mid ASC"
                        );

                        ArrayList<MessageObject> messages = new ArrayList<>();

                        while (cursor.next()) {
                            NativeByteBuffer data = cursor.byteBufferValue(0);
                            if (data == null) continue;

                            try {
                                TLRPC.Message message = TLRPC.Message.TLdeserialize(
                                        data, data.readInt32(false), false);
                                if (message == null) continue;

                                message.readAttachPath(data, selfId);
                                message.id = cursor.intValue(1);
                                message.date = cursor.intValue(2);
                                message.dialog_id = snap.channelId;

                                if (message.peer_id == null) {
                                    TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
                                    peer.channel_id = -snap.channelId;
                                    message.peer_id = peer;
                                }

                                MessageObject obj = new MessageObject(currentAccount, message, true, true);
                                messages.add(obj);
                            } finally {
                                data.reuse();
                            }
                        }

                        if (messages.size() != snap.messageIds.length) {
                            continue;
                        }

                        FeedItem item = new FeedItem(snap.channelId, messages, snap.sortDate);
                        item.isRead = localReadIds.contains(item.getUniqueId());
                        item.isBookmarked = isBookmarked(item.getUniqueId());
                        restored.add(item);

                    } catch (Exception ignore) {
                    } finally {
                        if (cursor != null) {
                            cursor.dispose();
                        }
                    }
                }

                Collections.sort(restored, FeedController::compareFeedItemsOrder);

                AndroidUtilities.runOnUIThread(() -> {
                    if (cachedFeed.isEmpty() && !restored.isEmpty()) {
                        clearReloadedFeedDataButKeepCursors();

                        for (FeedItem item : restored) {
                            cachedFeed.add(item);
                            loadedItemIds.add(item.getUniqueId());
                            for (MessageObject msg : item.messages) {
                                loadedItemIds.add(item.channelId + "_" + msg.getId());
                            }
                        }

                        rebuildDisplayList();
                    }

                    callback.onLoaded(new ArrayList<>(restored));
                });
            });
        });
    }

    private ArrayList<FeedHistoryHydrator.Request> collectHydrationRequests(
            LinkedHashMap<Long, ChannelCursorState> cursors,
            int remainingNeeded) {

        ArrayList<FeedHistoryHydrator.Request> result = new ArrayList<>();
        int batch = Math.max(HYDRATION_BATCH_MESSAGES, Math.min(remainingNeeded * 2, 40));

        for (ChannelCursorState state : cursors.values()) {
            if (!state.buffer.isEmpty()) continue;
            if (!state.exhausted) continue;
            if (state.lastConsumedMid >= state.topMessageId) continue;

            result.add(new FeedHistoryHydrator.Request(
                    state.dialogId,
                    state.lastConsumedMid,
                    state.topMessageId,
                    batch
            ));

            if (result.size() >= HYDRATION_CHANNELS_PER_ROUND) {
                break;
            }
        }

        return result;
    }

    private void reopenHydratedDialogs(LinkedHashMap<Long, ChannelCursorState> cursors,
                                       Set<Long> dialogIds) {
        if (dialogIds == null || dialogIds.isEmpty()) return;

        for (Long dialogId : dialogIds) {
            ChannelCursorState state = cursors.get(dialogId);
            if (state == null) continue;
            if (state.lastConsumedMid >= state.topMessageId) continue;

            state.exhausted = false;
            state.buffer.clear();
        }
    }

    private interface PageReadyCallback {
        void onReady(ArrayList<FeedItem> page,
                     LinkedHashMap<Long, ChannelCursorState> finalCursors,
                     boolean hasMore);
    }

    private void loadMergedPageWithHydration(
            LinkedHashMap<Long, ChannelCursorState> workingCursors,
            Set<String> readSnapshot,
            int targetItems,
            int roundsLeft,
            ArrayList<FeedItem> accumulator,
            PageReadyCallback callback) {

        MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        storage.getStorageQueue().postRunnable(() -> {
            long selfId = UserConfig.getInstance(currentAccount).getClientUserId();

            if (accumulator.size() < targetItems) {
                accumulator.addAll(buildNextMergedPage(
                        storage,
                        targetItems - accumulator.size(),
                        selfId,
                        readSnapshot,
                        workingCursors
                ));
            }

            boolean hasMore = hasRemainingChannelsInternal(workingCursors);
            ArrayList<FeedHistoryHydrator.Request> hydrationRequests = new ArrayList<>();

            if (accumulator.size() < targetItems && roundsLeft > 0) {
                hydrationRequests = collectHydrationRequests(
                        workingCursors,
                        targetItems - accumulator.size()
                );
            }

            final ArrayList<FeedHistoryHydrator.Request> finalRequests = hydrationRequests;
            final boolean finalHasMore = hasMore;

            AndroidUtilities.runOnUIThread(() -> {
                if (!finalRequests.isEmpty()) {
                    historyHydrator.hydrate(finalRequests, dialogs -> {
                        reopenHydratedDialogs(workingCursors, dialogs);
                        loadMergedPageWithHydration(
                                workingCursors,
                                readSnapshot,
                                targetItems,
                                roundsLeft - 1,
                                accumulator,
                                callback
                        );
                    });
                } else {
                    callback.onReady(accumulator, workingCursors, finalHasMore);
                }
            });
        });
    }
}