package org.telegram.ui.Feed;

import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.NotificationCenter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class FeedController {
    @FunctionalInterface
    public interface FeedLoadCallback {
        void onLoaded(List<FeedItem> items, boolean hasMore);
    }

    @FunctionalInterface
    public interface FeedStreamCallback {
        void onBatch(List<FeedItem> batch, boolean isLast);
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
    private final List<FeedItem> cachedFeed = new ArrayList<>();
    private boolean isLoading = false;
    private boolean feedLoaded = false;
    private Runnable saveRunnable;

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
    }

    private void scheduleSave() {
        if (saveRunnable != null) AndroidUtilities.cancelRunOnUIThread(saveRunnable);
        saveRunnable = () -> {
            Set<String> r = new HashSet<>(localReadIds);
            if (r.size() > 50000) {
                List<String> l = new ArrayList<>(r);
                r = new HashSet<>(l.subList(l.size() - 50000, l.size()));
            }
            getPrefs().edit()
                    .putStringSet("read", r)
                    .putStringSet("bookmarks", new HashSet<>(bookmarkedIds))
                    .apply();
        };
        AndroidUtilities.runOnUIThread(saveRunnable, 3000);
    }

    public void markAsRead(FeedItem item) {
        if (item == null) return;
        String uid = item.getUniqueId();
        if (localReadIds.contains(uid)) return;
        item.isRead = true;

        int maxId = 0;
        int messageCount = 0;
        for (MessageObject msg : item.messages) {
            localReadIds.add(item.channelId + "_" + msg.getId());
            maxId = Math.max(maxId, msg.getId());
            messageCount++;
        }
        localReadIds.add(uid);
        scheduleSave();

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
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {});

            TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
            if (dialog != null) {
                int oldMaxId = dialog.read_inbox_max_id;

                if (finalMaxId > oldMaxId) {
                    dialog.read_inbox_max_id = finalMaxId;
                }

                int actuallyRead = 0;
                for (MessageObject msg : item.messages) {
                    if (msg.getId() > oldMaxId) {
                        actuallyRead++;
                    }
                }
                if (actuallyRead > 0) {
                    dialog.unread_count = Math.max(0, dialog.unread_count - actuallyRead);
                }

                NotificationCenter.getInstance(currentAccount).postNotificationName(
                        NotificationCenter.updateInterfaces,
                        MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE
                );
                NotificationCenter.getInstance(currentAccount).postNotificationName(
                        NotificationCenter.dialogsNeedReload
                );
            }
        });
    }

    public boolean isLocallyRead(long channelId, int messageId) {
        return localReadIds.contains(channelId + "_" + messageId);
    }

    public boolean isBookmarked(String uid) { return bookmarkedIds.contains(uid); }

    public void trackClick() {}
    public void trackCommentClick() {}

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
        loadFeedStreaming(force, null, callback);
    }

    public void loadFeedStreaming(boolean force, FeedStreamCallback streamCb, FeedLoadCallback finalCb) {
        if (!force && !isLoading) {
            isLoading = true;
        }

        new Thread(() -> {
            MessagesController controller = MessagesController.getInstance(currentAccount);
            List<TLRPC.Dialog> allDialogs = controller.getAllDialogs();

            List<TLRPC.Dialog> channels = new ArrayList<>();
            for (TLRPC.Dialog dialog : allDialogs) {
                if (dialog == null || dialog.id >= 0) continue;
                TLRPC.Chat chat = controller.getChat(-dialog.id);
                if (chat == null || !chat.broadcast || chat.megagroup) continue;
                if (dialog.unread_count <= 0) continue;
                channels.add(dialog);
            }

            if (channels.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (force) { cachedFeed.clear(); feedLoaded = true; }
                    isLoading = false;
                    if (finalCb != null) finalCb.onLoaded(new ArrayList<>(), false);
                });
                return;
            }

            final List<FeedItem> allItems = Collections.synchronizedList(new ArrayList<>());
            final AtomicInteger completed = new AtomicInteger(0);
            final int totalChannels = channels.size();

            for (TLRPC.Dialog dialog : channels) {
                int readMaxId = dialog.read_inbox_max_id;
                int limit = Math.min(dialog.unread_count + 10, MAX_MESSAGES_PER_CHANNEL);

                TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
                req.peer = controller.getInputPeer(dialog.id);
                req.limit = limit;
                req.offset_id = 0;
                req.min_id = Math.max(0, readMaxId - 5);
                req.max_id = 0;

                ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
                    List<MessageObject> channelMessages = new ArrayList<>();

                    if (response instanceof TLRPC.messages_Messages) {
                        TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) response;
                        controller.putUsers(msgs.users, false);
                        controller.putChats(msgs.chats, false);

                        for (TLRPC.Message msg : msgs.messages) {
                            if (msg.id <= readMaxId) continue;
                            MessageObject obj = new MessageObject(currentAccount, msg, true, true);
                            if (obj.isOut() || obj.messageOwner.action != null) continue;

                            boolean hasContent = (obj.messageText != null && obj.messageText.length() > 0)
                                    || obj.messageOwner.media != null;
                            if (!hasContent) continue;
                            if (isLocallyRead(dialog.id, obj.getId())) continue;

                            channelMessages.add(obj);
                        }
                    }

                    List<FeedItem> channelItems = groupIntoItems(channelMessages, dialog.id);
                    allItems.addAll(channelItems);

                    int done = completed.incrementAndGet();
                    boolean isLast = done >= totalChannels;

                    if (streamCb != null && !channelItems.isEmpty()) {
                        Collections.sort(channelItems, (a, b) -> Long.compare(a.sortDate, b.sortDate));
                        AndroidUtilities.runOnUIThread(() -> streamCb.onBatch(channelItems, isLast));
                    }

                    if (isLast) {
                        List<FeedItem> sorted = new ArrayList<>(allItems);
                        Collections.sort(sorted, (a, b) -> Long.compare(a.sortDate, b.sortDate));

                        AndroidUtilities.runOnUIThread(() -> {
                            cachedFeed.clear();
                            cachedFeed.addAll(sorted);
                            feedLoaded = true;
                            isLoading = false;
                            if (finalCb != null) finalCb.onLoaded(sorted, false);
                        });
                    }
                });
            }
        }).start();
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
}