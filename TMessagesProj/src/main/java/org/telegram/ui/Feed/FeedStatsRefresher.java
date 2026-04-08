package org.telegram.ui.Feed;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FeedStatsRefresher {

    public interface Callback {
        void onStatsUpdated();
    }

    private final int currentAccount;

    public FeedStatsRefresher(int account) {
        this.currentAccount = account;
    }

    public void refreshReactions(List<FeedController.FeedItem> items, Callback callback) {
        if (items == null || items.isEmpty()) {
            if (callback != null) callback.onStatsUpdated();
            return;
        }

        Map<Long, List<Integer>> channelToIds = new HashMap<>();
        Map<String, FeedController.FeedItem> itemMap = new HashMap<>();

        for (FeedController.FeedItem item : items) {
            if (item.isRecommendation) continue;

            long channelId = item.channelId;
            List<Integer> ids = channelToIds.get(channelId);
            if (ids == null) {
                ids = new ArrayList<>();
                channelToIds.put(channelId, ids);
            }

            MessageObject primary = item.getPrimaryMessage();
            if (primary == null) continue;
            ids.add(primary.getId());
            itemMap.put(channelId + "_" + primary.getId(), item);
        }

        if (channelToIds.isEmpty()) {
            if (callback != null) callback.onStatsUpdated();
            return;
        }

        MessagesController controller = MessagesController.getInstance(currentAccount);
        ConnectionsManager cm = ConnectionsManager.getInstance(currentAccount);

        final int[] pending = {channelToIds.size()};

        for (Map.Entry<Long, List<Integer>> entry : channelToIds.entrySet()) {
            long dialogId = entry.getKey();
            List<Integer> msgIds = entry.getValue();

            TLRPC.Chat chat = controller.getChat(-dialogId);
            if (chat == null) {
                pending[0]--;
                if (pending[0] <= 0 && callback != null) callback.onStatsUpdated();
                continue;
            }

            TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
            req.channel = MessagesController.getInputChannel(chat);
            req.id = new ArrayList<>(msgIds);

            cm.sendRequest(req, (response, error) ->
                    AndroidUtilities.runOnUIThread(() -> {
                        if (response instanceof TLRPC.messages_Messages) {
                            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                            controller.putUsers(res.users, false);
                            controller.putChats(res.chats, false);

                            for (TLRPC.Message msg : res.messages) {
                                if (msg == null) continue;
                                String key = dialogId + "_" + msg.id;
                                FeedController.FeedItem item = itemMap.get(key);
                                if (item == null) continue;

                                MessageObject primary = item.getPrimaryMessage();
                                if (primary == null || primary.messageOwner == null) continue;

                                if (msg.reactions != null) {
                                    mergeReactions(primary.messageOwner, msg);
                                }

                                if (msg.views > primary.messageOwner.views) {
                                    primary.messageOwner.views = msg.views;
                                }
                                if (msg.forwards > primary.messageOwner.forwards) {
                                    primary.messageOwner.forwards = msg.forwards;
                                }
                                if (msg.replies != null) {
                                    if (primary.messageOwner.replies == null) {
                                        primary.messageOwner.replies = msg.replies;
                                    } else if (msg.replies.replies
                                            > primary.messageOwner.replies.replies) {
                                        primary.messageOwner.replies.replies =
                                                msg.replies.replies;
                                    }
                                }
                            }
                        }

                        pending[0]--;
                        if (pending[0] <= 0 && callback != null) callback.onStatsUpdated();
                    })
            );
        }
    }

    private void mergeReactions(TLRPC.Message local, TLRPC.Message remote) {
        if (remote.reactions == null) return;

        Map<String, Boolean> chosenMap = new HashMap<>();
        if (local.reactions != null && local.reactions.results != null) {
            for (TLRPC.ReactionCount rc : local.reactions.results) {
                if ((rc.flags & 1) != 0) {
                    chosenMap.put(reactionKey(rc.reaction), true);
                }
            }
        }

        local.reactions = remote.reactions;

        if (local.reactions.results != null) {
            for (TLRPC.ReactionCount rc : local.reactions.results) {
                if (chosenMap.containsKey(reactionKey(rc.reaction))) {
                    rc.flags |= 1;
                }
            }
        }
    }

    private String reactionKey(TLRPC.Reaction reaction) {
        if (reaction instanceof TLRPC.TL_reactionEmoji) {
            return "emoji_" + ((TLRPC.TL_reactionEmoji) reaction).emoticon;
        } else if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            return "custom_" + ((TLRPC.TL_reactionCustomEmoji) reaction).document_id;
        } else if (reaction instanceof TLRPC.TL_reactionPaid) {
            return "paid";
        }
        return "unknown";
    }
}