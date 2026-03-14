package org.telegram.ui.Feed;

import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Custom.CustomSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class FeedController implements NotificationCenter.NotificationCenterDelegate {
    private boolean observing = false;
    private final List<Runnable> newPostListeners = new ArrayList<>();

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
            if (controller.isPromoDialog(dialogId, false)) return;

            ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
            if (messages == null || messages.isEmpty()) return;

            boolean added = false;
            List<MessageObject> validMessages = new ArrayList<>();

            for (MessageObject obj : messages) {
                if (obj.isOut() || obj.messageOwner.action != null) continue;

                boolean hasContent = (obj.messageText != null && obj.messageText.length() > 0)
                        || obj.messageOwner.media != null;
                if (!hasContent) continue;

                String uid = dialogId + "_" + obj.getId();
                if (loadedItemIds.contains(uid)) continue;

                validMessages.add(obj);
            }

            if (validMessages.isEmpty()) return;

            List<FeedItem> newItems = groupIntoItems(validMessages, dialogId);

            for (FeedItem item : newItems) {
                String uid = item.getUniqueId();
                if (!loadedItemIds.contains(uid)) {
                    loadedItemIds.add(uid);
                    item.isRead = false;
                    item.isBookmarked = isBookmarked(uid);
                    cachedFeed.add(item);
                    added = true;
                }
            }

            if (added) {
                noMorePosts = false;
                Collections.sort(cachedFeed, Comparator.comparingLong(a -> a.sortDate));
                AndroidUtilities.runOnUIThread(this::notifyNewPostListeners);
            }
        }
    }


    public void startObserving() {
        if (observing) return;
        observing = true;
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagesDidLoad);
    }

    public void stopObserving() {
        if (!observing) return;
        observing = false;
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagesDidLoad);
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

        public FeedItem(long channelId, List<MessageObject> messages, long date) {
            this.channelId = channelId;
            this.messages = messages;
            this.sortDate = date;
        }

        public MessageObject getPrimaryMessage() { return messages.get(0); }
        public boolean isAlbum() { return messages.size() > 1; }
        public int getMessageId() { return getPrimaryMessage().getId(); }
        public String getUniqueId() { return channelId + "_" + getMessageId(); }
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

    private static final int MAX_MESSAGES_PER_CHANNEL = 50;

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
    }

    private SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("feed_v6_" + currentAccount, 0);
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

    private void scheduleSave() {
        if (saveRunnable != null) AndroidUtilities.cancelRunOnUIThread(saveRunnable);
        saveRunnable = () -> {
            performSave();
            saveRunnable = null;
        };
        AndroidUtilities.runOnUIThread(saveRunnable, 3000);
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

    public boolean hasCachedFeed() { return feedLoaded && !cachedFeed.isEmpty(); }
    public List<FeedItem> getCachedFeed() { return cachedFeed; }
    public boolean isLoading() { return isLoading; }

    public void loadFeed(boolean force, FeedLoadCallback callback) {
        if (isLoading) return;
        if (!force && feedLoaded && !cachedFeed.isEmpty()) {
            callback.onLoaded(cachedFeed, false);
            return;
        }
        isLoading = true;

        MessagesController controller = MessagesController.getInstance(currentAccount);
        List<TLRPC.Dialog> allDialogs = controller.getAllDialogs();

        List<TLRPC.Dialog> channels = new ArrayList<>();
        for (TLRPC.Dialog dialog : allDialogs) {
            if (dialog == null || dialog.id >= 0) continue;
            TLRPC.Chat chat = controller.getChat(-dialog.id);
            if (chat == null || !chat.broadcast || chat.megagroup) continue;
            if (dialog.unread_count <= 0) continue;
            if (dialog.read_inbox_max_id <= 0) continue;
            if (dialog.top_message <= dialog.read_inbox_max_id) continue;
            if (isChannelHidden(-dialog.id)) continue;
            if (CustomSettings.hideProxySponsor() && controller.isPromoDialog(dialog.id, false)) continue;

            channels.add(dialog);
        }

        if (channels.isEmpty()) {
            if (force) { cachedFeed.clear(); feedLoaded = true; }
            isLoading = false;
            callback.onLoaded(new ArrayList<>(), false);
            return;
        }

        final List<FeedItem> allItems = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger completed = new AtomicInteger(0);
        final int totalChannels = channels.size();

        for (TLRPC.Dialog dialog : channels) {
            final int readMaxId = dialog.read_inbox_max_id;
            int limit = Math.min(dialog.unread_count + 5, MAX_MESSAGES_PER_CHANNEL);

            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = controller.getInputPeer(dialog.id);
            req.limit = limit;
            req.offset_id = 0;
            req.max_id = 0;

            req.min_id = readMaxId;

            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
                List<MessageObject> channelMessages = new ArrayList<>();

                if (response instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) response;

                    AndroidUtilities.runOnUIThread(() -> {
                        controller.putUsers(msgs.users, false);
                        controller.putChats(msgs.chats, false);
                    });

                    for (TLRPC.Message msg : msgs.messages) {
                        if (msg.id <= readMaxId) continue;
                        if (isLocallyRead(dialog.id, msg.id)) continue;

                        MessageObject obj = new MessageObject(currentAccount, msg, true, true);
                        if (obj.isOut()) continue;
                        if (obj.messageOwner.action != null) continue;

                        boolean hasContent = (obj.messageText != null && obj.messageText.length() > 0)
                                || obj.messageOwner.media != null;
                        if (!hasContent) continue;

                        channelMessages.add(obj);
                    }
                }

                List<FeedItem> channelItems = groupIntoItems(channelMessages, dialog.id);
                allItems.addAll(channelItems);

                int done = completed.incrementAndGet();

                if (done >= totalChannels) {
                    List<FeedItem> sorted = new ArrayList<>(allItems);
                    Collections.sort(sorted, (a, b) -> Long.compare(a.sortDate, b.sortDate));

                    AndroidUtilities.runOnUIThread(() -> {
                        cachedFeed.clear();
                        cachedFeed.addAll(sorted);
                        feedLoaded = true;
                        isLoading = false;
                        noMorePosts = false;
                        loadedItemIds.clear();
                        for (FeedItem item : sorted) {
                            loadedItemIds.add(item.getUniqueId());
                        }
                        callback.onLoaded(sorted, true);
                    });
                }
            });
        }
    }

    private List<FeedItem> groupIntoItems(List<MessageObject> messages, long dialogId) {
        LinkedHashMap<String, List<MessageObject>> groups = new LinkedHashMap<>();
        for (MessageObject msg : messages) {
            long gid = msg.messageOwner.grouped_id;
            String key = gid != 0
                    ? "g_" + dialogId + "_" + gid
                    : "s_" + dialogId + "_" + msg.getId();
            List<MessageObject> group = groups.get(key);
            if (group == null) { group = new ArrayList<>(); groups.put(key, group); }
            group.add(msg);
        }

        List<FeedItem> items = new ArrayList<>();
        for (List<MessageObject> group : groups.values()) {
            Collections.sort(group, (a, b) -> Integer.compare(a.getId(), b.getId()));
            MessageObject primary = group.get(0);
            FeedItem item = new FeedItem(dialogId, group, primary.messageOwner.date);
            item.isRead = false;
            item.isBookmarked = isBookmarked(item.getUniqueId());
            items.add(item);
        }
        return items;
    }

    public void hideChannel(long channelId) {
        hiddenChannelIds.add(channelId);
        cachedFeed.removeIf(item -> item.channelId == -channelId || item.channelId == channelId);
        saveNow();
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
        return !noMorePosts;
    }

    public void resetLoadMore() {
        noMorePosts = false;
        loadedItemIds.clear();
    }

    public void loadMore(FeedLoadCallback callback) {
        if (isLoading || noMorePosts) {
            callback.onLoaded(cachedFeed, !noMorePosts);
            return;
        }
        isLoading = true;

        MessagesController controller = MessagesController.getInstance(currentAccount);
        List<TLRPC.Dialog> allDialogs = controller.getAllDialogs();

        List<TLRPC.Dialog> channels = new ArrayList<>();
        for (TLRPC.Dialog dialog : allDialogs) {
            if (dialog == null || dialog.id >= 0) continue;
            TLRPC.Chat chat = controller.getChat(-dialog.id);
            if (chat == null || !chat.broadcast || chat.megagroup) continue;
            if (isChannelHidden(-dialog.id)) continue;
            if (controller.isPromoDialog(dialog.id, false)) continue;
            channels.add(dialog);
        }

        if (channels.isEmpty()) {
            isLoading = false;
            noMorePosts = true;
            callback.onLoaded(cachedFeed, false);
            return;
        }

        int newestDate = 0;
        for (FeedItem item : cachedFeed) {
            newestDate = Math.max(newestDate, (int) item.sortDate);
        }

        final List<FeedItem> newItems = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger completed = new AtomicInteger(0);
        final int totalChannels = channels.size();
        final int finalNewestDate = newestDate;

        for (TLRPC.Dialog dialog : channels) {
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = controller.getInputPeer(dialog.id);
            req.limit = 10;
            req.offset_id = 0;
            req.offset_date = 0;
            req.add_offset = 0;
            req.max_id = 0;
            req.min_id = 0;

            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
                if (response instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) response;

                    AndroidUtilities.runOnUIThread(() -> {
                        controller.putUsers(msgs.users, false);
                        controller.putChats(msgs.chats, false);
                    });

                    List<MessageObject> channelMessages = new ArrayList<>();
                    for (TLRPC.Message msg : msgs.messages) {
                        if (msg.date <= finalNewestDate) continue;

                        MessageObject obj = new MessageObject(currentAccount, msg, true, true);
                        if (obj.isOut() || obj.messageOwner.action != null) continue;

                        boolean hasContent = (obj.messageText != null && obj.messageText.length() > 0)
                                || obj.messageOwner.media != null;
                        if (!hasContent) continue;

                        channelMessages.add(obj);
                    }

                    List<FeedItem> channelItems = groupIntoItems(channelMessages, dialog.id);

                    for (FeedItem item : channelItems) {
                        String uid = item.getUniqueId();
                        synchronized (loadedItemIds) {
                            if (!loadedItemIds.contains(uid)) {
                                loadedItemIds.add(uid);
                                item.isRead = isLocallyRead(item.channelId, item.getMessageId());
                                item.isBookmarked = isBookmarked(uid);
                                newItems.add(item);
                            }
                        }
                    }
                }

                int done = completed.incrementAndGet();
                if (done >= totalChannels) {
                    AndroidUtilities.runOnUIThread(() -> {
                        isLoading = false;

                        if (newItems.isEmpty()) {
                            noMorePosts = true;
                            callback.onLoaded(cachedFeed, false);
                            return;
                        }

                        cachedFeed.addAll(newItems);
                        Collections.sort(cachedFeed, Comparator.comparingLong(a -> a.sortDate));
                        noMorePosts = false;
                        callback.onLoaded(cachedFeed, true);
                    });
                }
            });
        }
    }
}