package org.telegram.messenger.archive;

import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/** Observes original updates before plugin and UI filtering. */
public final class ArchiveEventObserver {
    private ArchiveEventObserver() {
    }

    public static void observeUpdates(int accountSlot, TLRPC.Updates updates) {
        if (!ArchiveSettings.isEnabled() || updates == null) return;
        try {
            if (updates instanceof TLRPC.TL_updates || updates instanceof TLRPC.TL_updatesCombined) {
                observeUpdateArray(accountSlot, updates.updates, updates.users, updates.chats);
            } else if (updates instanceof TLRPC.TL_updateShort && updates.update != null) {
                observeUpdate(accountSlot, updates.update);
            } else if (updates instanceof TLRPC.TL_updateShortMessage || updates instanceof TLRPC.TL_updateShortChatMessage) {
                TLRPC.TL_message message = new TLRPC.TL_message();
                message.id = updates.id;
                message.from_id = new TLRPC.TL_peerUser();
                message.from_id.user_id = updates instanceof TLRPC.TL_updateShortMessage && updates.out
                        ? org.telegram.messenger.UserConfig.getInstance(accountSlot).getClientUserId() : updates.from_id;
                if (updates instanceof TLRPC.TL_updateShortMessage) {
                    message.peer_id = new TLRPC.TL_peerUser();
                    message.peer_id.user_id = updates.user_id;
                    message.dialog_id = updates.user_id;
                } else {
                    message.peer_id = new TLRPC.TL_peerChat();
                    message.peer_id.chat_id = updates.chat_id;
                    message.dialog_id = -updates.chat_id;
                }
                message.message = updates.message;
                message.entities = updates.entities;
                message.date = updates.date;
                message.reply_to = updates.reply_to;
                message.ttl_period = updates.ttl_period;
                message.media = new TLRPC.TL_messageMediaEmpty();
                ArchiveService.getInstance().saveMessage(ArchiveMessageMapper.map(accountSlot, message));
            }
        } catch (Throwable e) {
            logFailure(e);
        }
    }

    public static void observeUpdateArray(int accountSlot, ArrayList<TLRPC.Update> updates) {
        observeUpdateArray(accountSlot, updates, null, null);
    }

    public static void observeUpdateArray(int accountSlot, ArrayList<TLRPC.Update> updates,
                                          ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats) {
        if (!ArchiveSettings.isEnabled() || updates == null) return;
        for (TLRPC.Update update : updates) {
            try {
                observeUpdate(accountSlot, update, users, chats);
            } catch (Throwable e) {
                logFailure(e);
            }
        }
    }

    private static void observeUpdate(int accountSlot, TLRPC.Update update) {
        observeUpdate(accountSlot, update, null, null);
    }

    private static void observeUpdate(int accountSlot, TLRPC.Update update,
                                      ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats) {
        if (update instanceof TLRPC.TL_updateNewMessage) {
            TLRPC.Message message = ((TLRPC.TL_updateNewMessage) update).message;
            ArchiveService.getInstance().saveMessage(map(accountSlot, message, users, chats));
        } else if (update instanceof TLRPC.TL_updateNewChannelMessage) {
            TLRPC.Message message = ((TLRPC.TL_updateNewChannelMessage) update).message;
            ArchiveService.getInstance().saveMessage(map(accountSlot, message, users, chats));
        } else if (update instanceof TLRPC.TL_updateEditMessage) {
            TLRPC.Message message = ((TLRPC.TL_updateEditMessage) update).message;
            MessagesStorage.getInstance(accountSlot).captureMessageEditForLocalArchive(
                    map(accountSlot, message, users, chats));
        } else if (update instanceof TLRPC.TL_updateEditChannelMessage) {
            TLRPC.Message message = ((TLRPC.TL_updateEditChannelMessage) update).message;
            MessagesStorage.getInstance(accountSlot).captureMessageEditForLocalArchive(
                    map(accountSlot, message, users, chats));
        } else if (update instanceof TLRPC.TL_updateDeleteMessages) {
            TLRPC.TL_updateDeleteMessages deletion = (TLRPC.TL_updateDeleteMessages) update;
            captureDeletion(accountSlot, 0, deletion.messages, deletion.pts, deletion.pts_count, "messages");
        } else if (update instanceof TLRPC.TL_updateDeleteChannelMessages) {
            TLRPC.TL_updateDeleteChannelMessages deletion = (TLRPC.TL_updateDeleteChannelMessages) update;
            captureDeletion(accountSlot, -deletion.channel_id, deletion.messages, deletion.pts, deletion.pts_count, "channel");
        }
    }

    private static ArchiveMessageSnapshot map(int accountSlot, TLRPC.Message message,
                                              ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats) {
        long dialogId = message == null ? 0 : MessageObject.getDialogId(message);
        int forumTypeFlags = 0;
        if (dialogId < 0 && chats != null) {
            long chatId = -dialogId;
            for (TLRPC.Chat chat : chats) {
                if (chat != null && chat.id == chatId) {
                    if (chat.forum) forumTypeFlags |= MessagesStorage.FORUM_TYPE_CHAT;
                    if (chat.monoforum) forumTypeFlags |= MessagesStorage.FORUM_TYPE_DIRECT;
                    return ArchiveMessageMapper.map(accountSlot, message, forumTypeFlags);
                }
            }
        } else if (dialogId > 0 && users != null) {
            for (TLRPC.User user : users) {
                if (user != null && user.id == dialogId) {
                    if (user.bot_forum_view) forumTypeFlags |= MessagesStorage.FORUM_TYPE_BOT;
                    return ArchiveMessageMapper.map(accountSlot, message, forumTypeFlags);
                }
            }
        }
        return ArchiveMessageMapper.map(accountSlot, message);
    }

    private static void captureDeletion(int accountSlot, long dialogId, ArrayList<Integer> ids, int pts, int ptsCount, String kind) {
        if (ids == null || ids.isEmpty()) return;
        long accountId = UserConfig.getInstance(accountSlot).getClientUserId();
        if (accountId == 0) return;
        int accountEnvironment = ConnectionsManager.getInstance(accountSlot).isTestBackend() ? 1 : 0;
        ArrayList<Integer> stableIds = new ArrayList<>(ids);
        Collections.sort(stableIds);
        String sourceEventId = deletionSourceEventId(kind, dialogId, pts, ptsCount, stableIds);
        MessagesStorage.getInstance(accountSlot).captureMessagesForLocalArchiveDeletion(accountEnvironment,
                accountId, dialogId, stableIds, sourceEventId, System.currentTimeMillis() / 1000L);
    }

    private static String deletionSourceEventId(String kind, long dialogId, int pts, int ptsCount,
                                                ArrayList<Integer> stableIds) {
        return "delete:" + kind + ':' + dialogId + ':' + pts + ':' + ptsCount + ':'
                + digest(stableIds.toString());
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int i = 0; i < 8; i++) result.append(String.format(Locale.US, "%02x", hash[i] & 0xff));
            return result.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static void logFailure(Throwable error) {
        // Never include update objects, message text or raw payload in diagnostics.
        FileLog.e("Local archive update capture failed: " + error.getClass().getSimpleName());
    }
}
