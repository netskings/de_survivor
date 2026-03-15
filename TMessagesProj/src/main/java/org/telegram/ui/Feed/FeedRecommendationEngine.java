package org.telegram.ui.Feed;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Custom.CustomSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FeedRecommendationEngine {

    private static final long SCAN_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final int MAX_CHANNELS_TO_SCAN = 8;
    public static int getRecommendationInterval() {
        return CustomSettings.feedRecommendationFrequency();
    }

    private static final float SIMILAR_WEIGHT = 3.0f;
    private static final float FORWARD_WEIGHT = 2.0f;
    private static final float MENTION_WEIGHT = 1.5f;

    private static final int MAX_DISCOVERED = 30;
    private static final int POSTS_PER_BATCH = 5;

    private final List<RecommendedChannel> allDiscovered = new ArrayList<>();
    private int loadedBatchIndex = 0;
    private boolean isLoadingMore = false;
    private boolean noMoreRecommendations = false;
    private final Map<Long, Set<Long>> dismissedSourceMap = new HashMap<>();
    private int scannedChannelOffset = 0;

    private static final Pattern TG_LINK_PATTERN =
            Pattern.compile("(?:t\\.me|telegram\\.me)/([a-zA-Z][a-zA-Z0-9_]{3,31})");

    private static final FeedRecommendationEngine[] instances =
            new FeedRecommendationEngine[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
    private final List<RecommendedChannel> recommendations = new ArrayList<>();
    private final Set<Long> dismissedIds = new HashSet<>();
    private final Set<String> seenRecPostIds = new HashSet<>();
    private long lastScanTime = 0;
    private boolean isScanning = false;

    private Runnable onScanComplete;

    private final List<FeedController.FeedItem> recommendedPosts = new ArrayList<>();
    public static class RecommendedChannel {
        public long channelId;
        public TLRPC.Chat chat;
        public float score;
        public int similarSources;
        public int forwardSources;
        public int mentionSources;
        public String reason;
        public Set<Long> similarSourceIds = new HashSet<>();
        public Set<Long> forwardSourceIds = new HashSet<>();
        public Set<Long> mentionSourceIds = new HashSet<>();
    }

    public static FeedRecommendationEngine getInstance(int account) {
        FeedRecommendationEngine local = instances[account];
        if (local == null) {
            synchronized (FeedRecommendationEngine.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new FeedRecommendationEngine(account);
                }
            }
        }
        return local;
    }

    private FeedRecommendationEngine(int account) {
        this.currentAccount = account;
        loadDismissed();
    }

    public boolean isEnabled() {
        return CustomSettings.feedRecommendations();
    }

    public List<RecommendedChannel> getRecommendations() {
        return recommendations;
    }

    public boolean hasRecommendations() {
        return isEnabled() && !recommendations.isEmpty();
    }

    public void requestScan() {
        lastScanTime = 0;
        scanIfNeeded(null);
    }

    public void scanIfNeeded(Runnable onComplete) {
        if (!isEnabled() || isScanning) {
            if (onComplete != null) onComplete.run();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanTime < SCAN_INTERVAL_MS && !recommendations.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        this.onScanComplete = onComplete;
        startScan();
    }

    public void dismiss(long channelId) {
        dismissedIds.add(channelId);

        for (RecommendedChannel rec : allDiscovered) {
            if (rec.channelId == channelId) {
                Set<Long> sources = new HashSet<>();
                sources.addAll(rec.similarSourceIds);
                sources.addAll(rec.forwardSourceIds);
                sources.addAll(rec.mentionSourceIds);
                dismissedSourceMap.put(channelId, sources);
                break;
            }
        }

        seenRecPostIds.removeIf(uid -> uid.startsWith(channelId + "_"));

        allDiscovered.removeIf(r -> r.channelId == channelId);
        recommendations.removeIf(r -> r.channelId == channelId);
        recommendedPosts.removeIf(p -> p.recommendedChannelId == channelId);
        saveDismissed();
    }

    private void startScan() {
        isScanning = true;

        MessagesController controller = MessagesController.getInstance(currentAccount);
        List<TLRPC.Dialog> allDialogs = controller.getAllDialogs();

        Set<Long> subscribedChatIds = new HashSet<>();
        List<Long> channelsToScan = new ArrayList<>();

        for (TLRPC.Dialog dialog : allDialogs) {
            if (dialog == null || dialog.id >= 0) continue;
            long chatId = -dialog.id;
            TLRPC.Chat chat = controller.getChat(chatId);
            if (chat == null || !chat.broadcast || chat.megagroup) continue;
            subscribedChatIds.add(chatId);
            channelsToScan.add(chatId);
        }

        if (channelsToScan.isEmpty()) {
            finishScan();
            return;
        }

        channelsToScan.sort((a, b) -> {
            TLRPC.Dialog da = controller.dialogs_dict.get(-a);
            TLRPC.Dialog db = controller.dialogs_dict.get(-b);
            int dateA = da != null ? da.last_message_date : 0;
            int dateB = db != null ? db.last_message_date : 0;
            return Integer.compare(dateB, dateA);
        });
        if (channelsToScan.size() > MAX_CHANNELS_TO_SCAN) {
            channelsToScan = channelsToScan.subList(0, MAX_CHANNELS_TO_SCAN);
        }

        Map<Long, ChannelScore> scores = new HashMap<>();
        FeedController feed = FeedController.getInstance(currentAccount);

        if (feed.hasCachedFeed()) {
            analyzeForwards(feed.getCachedFeed(), subscribedChatIds, scores);
            analyzeMentions(feed.getCachedFeed(), subscribedChatIds, scores, controller);
        }

        final AtomicInteger pending = new AtomicInteger(channelsToScan.size());
        final Map<Long, ChannelScore> finalScores = scores;
        final Set<Long> finalSubscribed = subscribedChatIds;

        for (int i = 0; i < channelsToScan.size(); i++) {
            long chatId = channelsToScan.get(i);
            long delay = i * 200L;

            AndroidUtilities.runOnUIThread(() -> {
                TLRPC.TL_channels_getChannelRecommendations req =
                        new TLRPC.TL_channels_getChannelRecommendations();
                req.flags |= 1;
                req.channel = controller.getInputChannel(chatId);

                if (req.channel == null) {
                    if (pending.decrementAndGet() <= 0) {
                        processResults(finalScores, finalSubscribed, controller);
                    }
                    return;
                }

                ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) ->
                        AndroidUtilities.runOnUIThread(() -> {
                            if (res instanceof TLRPC.messages_Chats) {
                                ArrayList<TLRPC.Chat> chats =
                                        ((TLRPC.messages_Chats) res).chats;
                                controller.putChats(chats, false);

                                for (TLRPC.Chat similar : chats) {
                                    if (similar == null || similar.id == 0) continue;
                                    if (finalSubscribed.contains(similar.id)) continue;

                                    ChannelScore cs = finalScores.get(similar.id);
                                    if (cs == null) {
                                        cs = new ChannelScore();
                                        cs.channelId = similar.id;
                                        finalScores.put(similar.id, cs);
                                    }
                                    cs.similarSourceIds.add(chatId);
                                }
                            }
                            if (pending.decrementAndGet() <= 0) {
                                processResults(finalScores, finalSubscribed, controller);
                            }
                        })
                );
            }, delay);
        }
    }

    private void analyzeForwards(List<FeedController.FeedItem> feed,
                                 Set<Long> subscribed,
                                 Map<Long, ChannelScore> scores) {
        for (FeedController.FeedItem item : feed) {
            long sourceChat = Math.abs(item.channelId);
            for (MessageObject msg : item.messages) {
                if (msg.messageOwner.fwd_from == null) continue;
                TLRPC.Peer fromId = msg.messageOwner.fwd_from.from_id;
                if (fromId == null) continue;

                long fwdChatId = fromId.channel_id;
                if (fwdChatId == 0 || fwdChatId == sourceChat) continue;
                if (subscribed.contains(fwdChatId)) continue;

                ChannelScore cs = scores.get(fwdChatId);
                if (cs == null) {
                    cs = new ChannelScore();
                    cs.channelId = fwdChatId;
                    scores.put(fwdChatId, cs);
                }
                cs.forwardSourceIds.add(sourceChat);
            }
        }
    }

    private void analyzeMentions(List<FeedController.FeedItem> feed,
                                 Set<Long> subscribed,
                                 Map<Long, ChannelScore> scores,
                                 MessagesController controller) {
        Map<String, Long> usernameMap = new HashMap<>();
        for (Long chatId : subscribed) {
            TLRPC.Chat chat = controller.getChat(chatId);
            if (chat != null && !TextUtils.isEmpty(chat.username)) {
                usernameMap.put(chat.username.toLowerCase(), chatId);
            }
        }

        for (FeedController.FeedItem item : feed) {
            long sourceChat = Math.abs(item.channelId);
            for (MessageObject msg : item.messages) {
                String text = msg.messageOwner.message;
                if (TextUtils.isEmpty(text)) continue;

                Matcher matcher = TG_LINK_PATTERN.matcher(text);
                while (matcher.find()) {
                    String username = matcher.group(1);
                    if (username == null) continue;
                    username = username.toLowerCase();

                    TLRPC.Chat resolved = resolveUsername(username, controller);
                    if (resolved == null) continue;
                    if (!resolved.broadcast || resolved.megagroup) continue;
                    if (subscribed.contains(resolved.id)) continue;
                    if (resolved.id == sourceChat) continue;

                    ChannelScore cs = scores.get(resolved.id);
                    if (cs == null) {
                        cs = new ChannelScore();
                        cs.channelId = resolved.id;
                        scores.put(resolved.id, cs);
                    }
                    cs.mentionSourceIds.add(sourceChat);
                }
            }
        }
    }

    private TLRPC.Chat resolveUsername(String username, MessagesController controller) {
        for (TLRPC.Dialog dialog : controller.getAllDialogs()) {
            if (dialog == null || dialog.id >= 0) continue;
            TLRPC.Chat chat = controller.getChat(-dialog.id);
            if (chat != null && username.equalsIgnoreCase(chat.username)) {
                return chat;
            }
            if (chat != null && chat.usernames != null) {
                for (TLRPC.TL_username u : chat.usernames) {
                    if (u.active && username.equalsIgnoreCase(u.username)) {
                        return chat;
                    }
                }
            }
        }
        return null;
    }

    private void processResults(Map<Long, ChannelScore> scores,
                                Set<Long> subscribed,
                                MessagesController controller) {
        List<RecommendedChannel> result = new ArrayList<>();

        for (Map.Entry<Long, ChannelScore> entry : scores.entrySet()) {
            long channelId = entry.getKey();
            ChannelScore cs = entry.getValue();

            if (subscribed.contains(channelId)) continue;
            if (dismissedIds.contains(channelId)) continue;
            if (FeedController.getInstance(currentAccount).isChannelHidden(channelId)) continue;

            TLRPC.Chat chat = controller.getChat(channelId);
            if (chat == null) continue;
            if (!chat.broadcast || chat.megagroup) continue;

            float score = cs.similarSourceIds.size() * SIMILAR_WEIGHT
                    + cs.forwardSourceIds.size() * FORWARD_WEIGHT
                    + cs.mentionSourceIds.size() * MENTION_WEIGHT;

            int totalSources = cs.similarSourceIds.size()
                    + cs.forwardSourceIds.size()
                    + cs.mentionSourceIds.size();
            if (totalSources < 2) continue;

            float penalty = calculateDismissPenalty(cs);
            score *= (1f - penalty);
            if (score <= 0.5f) continue;

            if (chat.participants_count > 0) {
                score += (float) (Math.log10(chat.participants_count) * 0.5);
            }

            RecommendedChannel rec = new RecommendedChannel();
            rec.channelId = channelId;
            rec.chat = chat;
            rec.score = score;
            rec.similarSources = cs.similarSourceIds.size();
            rec.forwardSources = cs.forwardSourceIds.size();
            rec.mentionSources = cs.mentionSourceIds.size();
            rec.similarSourceIds = new HashSet<>(cs.similarSourceIds);
            rec.forwardSourceIds = new HashSet<>(cs.forwardSourceIds);
            rec.mentionSourceIds = new HashSet<>(cs.mentionSourceIds);
            rec.reason = buildReason(cs, controller);
            result.add(rec);
        }

        result.sort((a, b) -> Float.compare(b.score, a.score));

        if (result.size() > MAX_DISCOVERED) {
            result = new ArrayList<>(result.subList(0, MAX_DISCOVERED));
        }

        allDiscovered.clear();
        allDiscovered.addAll(result);
        recommendations.clear();
        recommendations.addAll(result);
        loadedBatchIndex = 0;
        noMoreRecommendations = false;
        lastScanTime = System.currentTimeMillis();

        finishScan();
    }

    private float calculateDismissPenalty(ChannelScore cs) {
        if (dismissedSourceMap.isEmpty()) return 0f;

        Set<Long> channelSources = new HashSet<>();
        channelSources.addAll(cs.similarSourceIds);
        channelSources.addAll(cs.forwardSourceIds);
        channelSources.addAll(cs.mentionSourceIds);

        if (channelSources.isEmpty()) return 0f;

        float maxOverlap = 0f;
        for (Map.Entry<Long, Set<Long>> dismissed : dismissedSourceMap.entrySet()) {
            Set<Long> dismissedSrc = dismissed.getValue();
            if (dismissedSrc == null || dismissedSrc.isEmpty()) continue;

            Set<Long> intersection = new HashSet<>(channelSources);
            intersection.retainAll(dismissedSrc);

            float overlap = (float) intersection.size() / channelSources.size();
            maxOverlap = Math.max(maxOverlap, overlap);
        }

        return maxOverlap * 0.75f;
    }

    private String buildReason(ChannelScore cs, MessagesController controller) {
        List<String> parts = new ArrayList<>();

        if (!cs.similarSourceIds.isEmpty()) {
            List<String> names = new ArrayList<>();
            int count = 0;
            for (Long id : cs.similarSourceIds) {
                if (count >= 2) break;
                TLRPC.Chat c = controller.getChat(id);
                if (c != null) {
                    names.add(c.title);
                    count++;
                }
            }
            if (!names.isEmpty()) {
                parts.add("Similar to " + TextUtils.join(", ", names));
            }
        }

        if (!cs.forwardSourceIds.isEmpty() && parts.isEmpty()) {
            parts.add("Referenced by " + cs.forwardSourceIds.size()
                    + " channel" + (cs.forwardSourceIds.size() != 1 ? "s" : "")
                    + " you follow");
        }

        if (!cs.mentionSourceIds.isEmpty() && parts.isEmpty()) {
            parts.add("Mentioned by " + cs.mentionSourceIds.size()
                    + " channel" + (cs.mentionSourceIds.size() != 1 ? "s" : "")
                    + " you follow");
        }

        if (parts.isEmpty()) {
            return "Recommended for you";
        }
        return parts.get(0);
    }

    private void finishScan() {
        isScanning = false;

        if (!allDiscovered.isEmpty()) {
            loadPostsBatch(0, this::notifyComplete);
        } else {
            recommendedPosts.clear();
            notifyComplete();
        }
    }

    private void loadPostsBatch(int fromIndex, Runnable onDone) {
        int toIndex = Math.min(fromIndex + POSTS_PER_BATCH, allDiscovered.size());
        if (fromIndex >= allDiscovered.size() || fromIndex >= toIndex) {
            noMoreRecommendations = true;
            isLoadingMore = false;
            if (onDone != null) onDone.run();
            return;
        }

        List<RecommendedChannel> batch = allDiscovered.subList(fromIndex, toIndex);
        MessagesController controller = MessagesController.getInstance(currentAccount);
        AtomicInteger pending = new AtomicInteger(batch.size());

        for (int i = 0; i < batch.size(); i++) {
            RecommendedChannel rec = batch.get(i);
            long delay = i * 150L;

            AndroidUtilities.runOnUIThread(() -> {
                TLRPC.InputPeer peer = controller.getInputPeer(-rec.channelId);

                if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) {
                    if (pending.decrementAndGet() <= 0) {
                        loadedBatchIndex = toIndex;
                        isLoadingMore = false;
                        if (onDone != null) onDone.run();
                    }
                    return;
                }

                TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
                req.peer = peer;
                req.limit = 15;
                req.offset_id = 0;
                req.max_id = 0;
                req.min_id = 0;

                ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) ->
                        AndroidUtilities.runOnUIThread(() -> {
                            if (response instanceof TLRPC.messages_Messages) {
                                TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) response;
                                controller.putUsers(msgs.users, false);
                                controller.putChats(msgs.chats, false);

                                FeedController.FeedItem bestPost = pickBestPost(
                                        msgs.messages, rec);

                                if (bestPost != null) {
                                    synchronized (recommendedPosts) {
                                        recommendedPosts.add(bestPost);
                                    }
                                }
                            }

                            if (pending.decrementAndGet() <= 0) {
                                loadedBatchIndex = toIndex;
                                isLoadingMore = false;
                                if (onDone != null) onDone.run();
                            }
                        })
                );
            }, delay);
        }
    }

    public void loadMore(Runnable onDone) {
        if (isLoadingMore || noMoreRecommendations) {
            if (onDone != null) onDone.run();
            return;
        }

        if (loadedBatchIndex >= allDiscovered.size()) {
            expandScan(onDone);
            return;
        }

        isLoadingMore = true;
        loadPostsBatch(loadedBatchIndex, onDone);
    }

    public boolean canLoadMore() {
        return isEnabled() && !isLoadingMore && !noMoreRecommendations;
    }

    private void notifyComplete() {
        if (onScanComplete != null) {
            Runnable r = onScanComplete;
            onScanComplete = null;
            r.run();
        }
    }

    private FeedController.FeedItem pickBestPost(
            ArrayList<TLRPC.Message> messages,
            RecommendedChannel rec) {

        if (messages == null || messages.isEmpty()) return null;

        TLRPC.Message best = null;
        int bestScore = -1;

        for (TLRPC.Message msg : messages) {
            if (msg == null) continue;
            if (msg.action != null) continue;

            String postUid = rec.channelId + "_" + msg.id;
            if (seenRecPostIds.contains(postUid)) continue;

            int score = 0;

            if (msg.message != null && msg.message.length() > 50) score += 3;
            else if (msg.message != null && !msg.message.isEmpty()) score += 1;

            if (msg.media instanceof TLRPC.TL_messageMediaPhoto) score += 2;
            if (msg.media instanceof TLRPC.TL_messageMediaDocument) score += 2;

            if (msg.views > 0) score += Math.min(3, (int) Math.log10(msg.views));

            int age = ConnectionsManager.getInstance(currentAccount).getCurrentTime() - msg.date;
            if (age > 3 * 24 * 3600) continue;
            if (age < 24 * 3600) score += 2;

            if (score > bestScore) {
                bestScore = score;
                best = msg;
            }
        }

        if (best == null) return null;

        String bestUid = rec.channelId + "_" + best.id;
        seenRecPostIds.add(bestUid);

        MessageObject msgObj = new MessageObject(currentAccount, best, true, true);
        if (msgObj.isOut() || msgObj.messageOwner.action != null) return null;

        List<MessageObject> msgList = new ArrayList<>();
        msgList.add(msgObj);

        if (best.grouped_id != 0) {
            for (TLRPC.Message msg : messages) {
                if (msg.grouped_id == best.grouped_id && msg.id != best.id) {
                    seenRecPostIds.add(rec.channelId + "_" + msg.id);
                    msgList.add(new MessageObject(currentAccount, msg, true, true));
                }
            }
            Collections.sort(msgList, Comparator.comparingInt(MessageObject::getId));
        }

        saveDismissed();

        long dialogId = -rec.channelId;
        FeedController.FeedItem item = new FeedController.FeedItem(dialogId, msgList, best.date);
        item.isRecommendation = true;
        item.recommendationReason = rec.reason;
        item.recommendedChat = rec.chat;
        item.recommendedChannelId = rec.channelId;
        item.isRead = true;

        return item;
    }

    private SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(
                "feed_rec_" + currentAccount, 0);
    }

    private void loadDismissed() {
        SharedPreferences prefs = getPrefs();
        Set<String> set = prefs.getStringSet("dismissed", new HashSet<>());
        for (String s : set) {
            try {
                dismissedIds.add(Long.parseLong(s));
            } catch (NumberFormatException ignored) {}
        }

        Set<String> sourceSet = prefs.getStringSet("dismissed_sources", new HashSet<>());
        for (String s : sourceSet) {
            try {
                String[] parts = s.split(",");
                if (parts.length < 2) continue;
                long channelId = Long.parseLong(parts[0]);
                Set<Long> sources = new HashSet<>();
                for (int i = 1; i < parts.length; i++) {
                    sources.add(Long.parseLong(parts[i]));
                }
                dismissedSourceMap.put(channelId, sources);
            } catch (Exception ignored) {}
        }

        Set<String> seenSet = prefs.getStringSet("seen_rec_posts", new HashSet<>());
        seenRecPostIds.addAll(seenSet);
    }

    private void saveDismissed() {
        Set<String> set = new HashSet<>();
        for (Long id : dismissedIds) set.add(String.valueOf(id));

        Set<String> sourceSet = new HashSet<>();
        for (Map.Entry<Long, Set<Long>> entry : dismissedSourceMap.entrySet()) {
            StringBuilder sb = new StringBuilder();
            sb.append(entry.getKey());
            for (Long src : entry.getValue()) {
                sb.append(",").append(src);
            }
            sourceSet.add(sb.toString());
        }

        Set<String> seenToSave = new HashSet<>(seenRecPostIds);
        if (seenToSave.size() > 500) {
            List<String> list = new ArrayList<>(seenToSave);
            seenToSave = new HashSet<>(list.subList(list.size() - 500, list.size()));
        }

        getPrefs().edit()
                .putStringSet("dismissed", set)
                .putStringSet("dismissed_sources", sourceSet)
                .putStringSet("seen_rec_posts", seenToSave)
                .apply();
    }

    private static class ChannelScore {
        long channelId;
        final Set<Long> similarSourceIds = new HashSet<>();
        final Set<Long> forwardSourceIds = new HashSet<>();
        final Set<Long> mentionSourceIds = new HashSet<>();
    }

    public List<FeedController.FeedItem> getRecommendedPosts() {
        return recommendedPosts;
    }

    public boolean hasRecommendedPosts() {
        return isEnabled() && !recommendedPosts.isEmpty();
    }

    public void dismissPost(FeedController.FeedItem item) {
        if (item == null) return;
        dismiss(item.recommendedChannelId);
    }

    private void expandScan(Runnable onDone) {
        MessagesController controller = MessagesController.getInstance(currentAccount);
        List<TLRPC.Dialog> allDialogs = controller.getAllDialogs();

        Set<Long> subscribedChatIds = new HashSet<>();
        List<Long> channelsToScan = new ArrayList<>();

        for (TLRPC.Dialog dialog : allDialogs) {
            if (dialog == null || dialog.id >= 0) continue;
            long chatId = -dialog.id;
            TLRPC.Chat chat = controller.getChat(chatId);
            if (chat == null || !chat.broadcast || chat.megagroup) continue;
            subscribedChatIds.add(chatId);
            channelsToScan.add(chatId);
        }

        channelsToScan.sort((a, b) -> {
            TLRPC.Dialog da = controller.dialogs_dict.get(-a);
            TLRPC.Dialog db = controller.dialogs_dict.get(-b);
            int dateA = da != null ? da.last_message_date : 0;
            int dateB = db != null ? db.last_message_date : 0;
            return Integer.compare(dateB, dateA);
        });

        int newOffset = scannedChannelOffset + MAX_CHANNELS_TO_SCAN;
        if (newOffset >= channelsToScan.size()) {
            noMoreRecommendations = true;
            if (onDone != null) onDone.run();
            return;
        }

        scannedChannelOffset = newOffset;
        int end = Math.min(newOffset + MAX_CHANNELS_TO_SCAN, channelsToScan.size());
        List<Long> nextBatch = channelsToScan.subList(newOffset, end);

        isLoadingMore = true;
        Map<Long, ChannelScore> scores = new HashMap<>();

        for (RecommendedChannel rec : allDiscovered) {
            ChannelScore cs = new ChannelScore();
            cs.channelId = rec.channelId;
            cs.similarSourceIds.addAll(rec.similarSourceIds);
            cs.forwardSourceIds.addAll(rec.forwardSourceIds);
            cs.mentionSourceIds.addAll(rec.mentionSourceIds);
            scores.put(rec.channelId, cs);
        }

        AtomicInteger pending = new AtomicInteger(nextBatch.size());
        Set<Long> finalSubscribed = subscribedChatIds;

        for (int i = 0; i < nextBatch.size(); i++) {
            long chatId = nextBatch.get(i);
            long delay = i * 200L;

            AndroidUtilities.runOnUIThread(() -> {
                TLRPC.TL_channels_getChannelRecommendations req =
                        new TLRPC.TL_channels_getChannelRecommendations();
                req.flags |= 1;
                req.channel = controller.getInputChannel(chatId);

                if (req.channel == null) {
                    if (pending.decrementAndGet() <= 0) {
                        processExpandResults(scores, finalSubscribed, controller, onDone);
                    }
                    return;
                }

                ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) ->
                        AndroidUtilities.runOnUIThread(() -> {
                            if (res instanceof TLRPC.messages_Chats) {
                                ArrayList<TLRPC.Chat> chats =
                                        ((TLRPC.messages_Chats) res).chats;
                                controller.putChats(chats, false);

                                for (TLRPC.Chat similar : chats) {
                                    if (similar == null || similar.id == 0) continue;
                                    if (finalSubscribed.contains(similar.id)) continue;

                                    ChannelScore cs = scores.get(similar.id);
                                    if (cs == null) {
                                        cs = new ChannelScore();
                                        cs.channelId = similar.id;
                                        scores.put(similar.id, cs);
                                    }
                                    cs.similarSourceIds.add(chatId);
                                }
                            }
                            if (pending.decrementAndGet() <= 0) {
                                processExpandResults(scores, finalSubscribed, controller, onDone);
                            }
                        })
                );
            }, delay);
        }
    }

    private void processExpandResults(Map<Long, ChannelScore> scores,
                                      Set<Long> subscribed,
                                      MessagesController controller,
                                      Runnable onDone) {
        Set<Long> existingIds = new HashSet<>();
        for (RecommendedChannel rec : allDiscovered) {
            existingIds.add(rec.channelId);
        }
        for (FeedController.FeedItem post : recommendedPosts) {
            existingIds.add(post.recommendedChannelId);
        }

        List<RecommendedChannel> newChannels = new ArrayList<>();

        for (Map.Entry<Long, ChannelScore> entry : scores.entrySet()) {
            long channelId = entry.getKey();
            if (existingIds.contains(channelId)) continue;
            if (subscribed.contains(channelId)) continue;
            if (dismissedIds.contains(channelId)) continue;

            ChannelScore cs = entry.getValue();
            TLRPC.Chat chat = controller.getChat(channelId);
            if (chat == null || !chat.broadcast || chat.megagroup) continue;

            float score = cs.similarSourceIds.size() * SIMILAR_WEIGHT
                    + cs.forwardSourceIds.size() * FORWARD_WEIGHT
                    + cs.mentionSourceIds.size() * MENTION_WEIGHT;

            int totalSources = cs.similarSourceIds.size()
                    + cs.forwardSourceIds.size()
                    + cs.mentionSourceIds.size();
            if (totalSources < 1) continue;

            float penalty = calculateDismissPenalty(cs);
            score *= (1f - penalty);
            if (score <= 0.3f) continue;

            RecommendedChannel rec = new RecommendedChannel();
            rec.channelId = channelId;
            rec.chat = chat;
            rec.score = score;
            rec.similarSources = cs.similarSourceIds.size();
            rec.forwardSources = cs.forwardSourceIds.size();
            rec.mentionSources = cs.mentionSourceIds.size();
            rec.similarSourceIds = new HashSet<>(cs.similarSourceIds);
            rec.forwardSourceIds = new HashSet<>(cs.forwardSourceIds);
            rec.mentionSourceIds = new HashSet<>(cs.mentionSourceIds);
            rec.reason = buildReason(cs, controller);
            newChannels.add(rec);
        }

        newChannels.sort((a, b) -> Float.compare(b.score, a.score));

        if (newChannels.isEmpty()) {
            noMoreRecommendations = true;
            isLoadingMore = false;
            if (onDone != null) onDone.run();
            return;
        }

        allDiscovered.addAll(newChannels);
        recommendations.addAll(newChannels);

        loadPostsBatch(loadedBatchIndex, onDone);
    }
}