package org.telegram.messenger.archive;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** Captures original updates without getting ahead of Telegram's pts/seq acceptance. */
public final class ArchiveEventObserver {
    private static final Map<TLObject, OutgoingEditCapture> outgoingEditCaptures =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<TLRPC.Update, CaptureState> updateCaptureStates =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ArchiveEventObserver() {
    }

    /**
     * Freezes archive data before container plugin hooks. No queue or database work is started
     * here because Telegram may still defer this update in a pts/seq queue.
     */
    public static void prepareUpdates(int accountSlot, TLRPC.Updates updates) {
        if (!ArchiveSettings.isEnabled() || updates == null) return;
        try {
            if (updates instanceof TLRPC.TL_updates || updates instanceof TLRPC.TL_updatesCombined) {
                prepareUpdateArray(accountSlot, updates.updates, updates.users, updates.chats);
            } else if (updates instanceof TLRPC.TL_updateShort && updates.update != null) {
                prepareUpdate(accountSlot, updates.update, null, null);
            }
        } catch (Throwable e) {
            logFailure(e);
        }
    }

    public static void observeUpdates(int accountSlot, TLRPC.Updates updates) {
        prepareUpdates(accountSlot, updates);
        if (updates instanceof TLRPC.TL_updates || updates instanceof TLRPC.TL_updatesCombined) {
            captureAcceptedUpdateArray(accountSlot, updates.updates, updates.users, updates.chats);
        } else if (updates instanceof TLRPC.TL_updateShort && updates.update != null) {
            ArrayList<TLRPC.Update> single = new ArrayList<>(1);
            single.add(updates.update);
            captureAcceptedUpdateArray(accountSlot, single, null, null);
        }
    }

    public static void observeUpdateArray(int accountSlot, ArrayList<TLRPC.Update> updates) {
        observeUpdateArray(accountSlot, updates, null, null);
    }

    public static void observeUpdateArray(int accountSlot, ArrayList<TLRPC.Update> updates,
                                          ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats) {
        prepareUpdateArray(accountSlot, updates, users, chats);
        captureAcceptedUpdateArray(accountSlot, updates, users, chats);
    }

    private static void prepareUpdateArray(int accountSlot, ArrayList<TLRPC.Update> updates,
                                           ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats) {
        if (!ArchiveSettings.isEnabled() || updates == null) return;
        for (TLRPC.Update update : updates) {
            try {
                prepareUpdate(accountSlot, update, users, chats);
            } catch (Throwable e) {
                logFailure(e);
            }
        }
    }

    /** Called only after Telegram has accepted this update array for processing. */
    public static void captureAcceptedUpdateArray(int accountSlot, ArrayList<TLRPC.Update> updates,
                                                   ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats) {
        if (!ArchiveSettings.isEnabled() || updates == null || updates.isEmpty()) return;
        ArrayList<CaptureEvent> events = new ArrayList<>();
        for (TLRPC.Update update : updates) {
            try {
                if (update == null) continue;
                CaptureState state = captureState(update);
                boolean prepared;
                synchronized (state) {
                    if (state.committed) continue;
                    prepared = state.prepared;
                }
                if (!prepared) {
                    prepareUpdate(accountSlot, update, users, chats);
                }
                CaptureEvent event;
                synchronized (state) {
                    if (state.committed) continue;
                    state.committed = true;
                    event = state.event;
                }
                if (event != null) events.add(event);
            } catch (Throwable e) {
                logFailure(e);
            }
        }
        if (!events.isEmpty()) {
            MessagesStorage.getInstance(accountSlot).captureLocalArchiveBatch(events);
        }
    }

    /** Short-message containers are converted by MessagesController into a complete Message first. */
    public static void captureAcceptedMessage(int accountSlot, TLRPC.Message message, boolean channelMessage) {
        if (!ArchiveSettings.isEnabled() || message == null) return;
        try {
            ArchiveMediaStore.getInstance().maybeDownloadIfEnabled(accountSlot, message);
            int accountEnvironment = ConnectionsManager.getInstance(accountSlot).isTestBackend() ? 1 : 0;
            long accountId = UserConfig.getInstance(accountSlot).getClientUserId();
            ArchiveMessageSnapshot snapshot = map(accountSlot, message, null, null,
                    accountEnvironment, accountId);
            if (snapshot == null) return;
            ArrayList<CaptureEvent> events = new ArrayList<>(1);
            events.add(CaptureEvent.message(CaptureEvent.NEW, snapshot, channelMessage));
            MessagesStorage.getInstance(accountSlot).captureLocalArchiveBatch(events);
        } catch (Throwable e) {
            logFailure(e);
        }
    }

    /**
     * Captures a deletion initiated by this client before Telegram removes the row from messages_v2.
     * The storageQueue FIFO ordering is intentional: this event must be posted before
     * MessagesStorage.markMessagesAsDeleted().
     */
    public static void captureLocalDeletion(int accountSlot, long dialogId,
                                            ArrayList<Integer> messageIds, boolean forAll) {
        if (!ArchiveSettings.isEnabled() || messageIds == null || messageIds.isEmpty()
                || dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)) {
            return;
        }
        try {
            ArrayList<Integer> sentMessageIds = new ArrayList<>(messageIds.size());
            for (Integer messageId : messageIds) {
                if (messageId != null && messageId > 0) sentMessageIds.add(messageId);
            }
            if (sentMessageIds.isEmpty()) return;
            long accountId = UserConfig.getInstance(accountSlot).getClientUserId();
            if (accountId == 0) return;
            int accountEnvironment = ConnectionsManager.getInstance(accountSlot).isTestBackend() ? 1 : 0;
            CaptureEvent event = deletionEvent(accountEnvironment, accountId, dialogId, sentMessageIds,
                    0, 0, forAll ? "local-revoke" : "local-only");
            if (event == null) return;
            ArrayList<CaptureEvent> events = new ArrayList<>(1);
            events.add(event);
            MessagesStorage.getInstance(accountSlot).captureLocalArchiveBatch(events);
        } catch (Throwable e) {
            logFailure(e);
        }
    }

    /** Freezes the server-visible version before SendMessagesHelper overwrites its local object. */
    public static OutgoingEditCapture prepareOutgoingEdit(int accountSlot, TLRPC.Message previousMessage) {
        if (!ArchiveSettings.isEnabled() || previousMessage == null) return null;
        try {
            long accountId = UserConfig.getInstance(accountSlot).getClientUserId();
            if (accountId == 0) return null;
            ArchiveMessageSnapshot previous = ArchiveMessageMapper.map(accountSlot, previousMessage);
            return previous == null ? null : new OutgoingEditCapture(previous);
        } catch (Throwable e) {
            logFailure(e);
            return null;
        }
    }

    public static void registerOutgoingEditRequest(TLObject request, OutgoingEditCapture capture) {
        if (request != null && capture != null) outgoingEditCaptures.put(request, capture);
    }

    public static void discardOutgoingEditRequest(TLObject request) {
        if (request != null) outgoingEditCaptures.remove(request);
    }

    /** Uses the frozen pre-request version for the matching edit update and suppresses fallback capture. */
    public static void captureOutgoingEditResponse(int accountSlot, TLObject request, TLRPC.Updates updates) {
        OutgoingEditCapture capture = request == null ? null : outgoingEditCaptures.remove(request);
        if (!ArchiveSettings.isEnabled() || capture == null || updates == null) return;
        ArrayList<TLRPC.Update> responseUpdates;
        if (updates instanceof TLRPC.TL_updateShort && updates.update != null) {
            responseUpdates = new ArrayList<>(1);
            responseUpdates.add(updates.update);
        } else {
            responseUpdates = updates.updates;
        }
        if (responseUpdates == null) return;
        try {
            for (TLRPC.Update update : responseUpdates) {
                TLRPC.Message currentMessage;
                boolean channelMessage;
                if (update instanceof TLRPC.TL_updateEditMessage) {
                    currentMessage = ((TLRPC.TL_updateEditMessage) update).message;
                    channelMessage = false;
                } else if (update instanceof TLRPC.TL_updateEditChannelMessage) {
                    currentMessage = ((TLRPC.TL_updateEditChannelMessage) update).message;
                    channelMessage = true;
                } else {
                    continue;
                }
                if (!ArchiveMessageMapper.isVisibleEdit(currentMessage)) continue;
                ArchiveMessageSnapshot current = map(accountSlot, currentMessage, updates.users, updates.chats,
                        capture.previous.accountEnvironment, capture.previous.accountId);
                if (!sameMessage(capture.previous, current)) continue;

                CaptureEvent event = CaptureEvent.message(CaptureEvent.EDIT, current,
                        channelMessage, capture.previous);
                CaptureState state = captureState(update);
                synchronized (state) {
                    state.prepared = true;
                    state.committed = true;
                    state.event = event;
                }
                ArrayList<CaptureEvent> events = new ArrayList<>(1);
                events.add(event);
                MessagesStorage.getInstance(accountSlot).captureLocalArchiveBatch(events);
                return;
            }
        } catch (Throwable e) {
            logFailure(e);
        }
    }

    private static boolean sameMessage(ArchiveMessageSnapshot first, ArchiveMessageSnapshot second) {
        return first != null && second != null
                && first.accountEnvironment == second.accountEnvironment
                && first.accountId == second.accountId
                && first.dialogId == second.dialogId
                && first.topicId == second.topicId
                && first.messageId == second.messageId;
    }

    private static CaptureState captureState(TLRPC.Update update) {
        synchronized (updateCaptureStates) {
            CaptureState state = updateCaptureStates.get(update);
            if (state == null) {
                state = new CaptureState();
                updateCaptureStates.put(update, state);
            }
            return state;
        }
    }

    private static void prepareUpdate(int accountSlot, TLRPC.Update update,
                                      ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats) {
        if (update == null) return;
        CaptureState state = captureState(update);
        synchronized (state) {
            if (state.prepared) return;
            state.prepared = true;
            long accountId = UserConfig.getInstance(accountSlot).getClientUserId();
            if (accountId == 0) return;
            int accountEnvironment = ConnectionsManager.getInstance(accountSlot).isTestBackend() ? 1 : 0;
            CaptureEvent event = null;
            if (update instanceof TLRPC.TL_updateNewMessage) {
                event = messageEvent(accountSlot, CaptureEvent.NEW,
                        ((TLRPC.TL_updateNewMessage) update).message, false, users, chats,
                        accountEnvironment, accountId);
            } else if (update instanceof TLRPC.TL_updateNewChannelMessage) {
                event = messageEvent(accountSlot, CaptureEvent.NEW,
                        ((TLRPC.TL_updateNewChannelMessage) update).message, true, users, chats,
                        accountEnvironment, accountId);
            } else if (update instanceof TLRPC.TL_updateEditMessage) {
                event = messageEvent(accountSlot, CaptureEvent.EDIT,
                        ((TLRPC.TL_updateEditMessage) update).message, false, users, chats,
                        accountEnvironment, accountId);
            } else if (update instanceof TLRPC.TL_updateEditChannelMessage) {
                event = messageEvent(accountSlot, CaptureEvent.EDIT,
                        ((TLRPC.TL_updateEditChannelMessage) update).message, true, users, chats,
                        accountEnvironment, accountId);
            } else if (update instanceof TLRPC.TL_updateDeleteMessages) {
                TLRPC.TL_updateDeleteMessages deletion = (TLRPC.TL_updateDeleteMessages) update;
                event = deletionEvent(accountEnvironment, accountId, 0, deletion.messages,
                        deletion.pts, deletion.pts_count, "messages");
            } else if (update instanceof TLRPC.TL_updateDeleteChannelMessages) {
                TLRPC.TL_updateDeleteChannelMessages deletion = (TLRPC.TL_updateDeleteChannelMessages) update;
                event = deletionEvent(accountEnvironment, accountId, -deletion.channel_id, deletion.messages,
                        deletion.pts, deletion.pts_count, "channel");
            }
            state.event = event;
        }
    }

    private static CaptureEvent messageEvent(int accountSlot, int type, TLRPC.Message message,
                                             boolean channelMessage, ArrayList<TLRPC.User> users,
                                             ArrayList<TLRPC.Chat> chats, int accountEnvironment,
                                             long accountId) {
        if (type == CaptureEvent.EDIT && !ArchiveMessageMapper.isVisibleEdit(message)) return null;
        ArchiveMediaStore.getInstance().captureAlreadyAvailable(accountSlot, message);
        ArchiveMediaStore.getInstance().maybeDownloadIfEnabled(accountSlot, message);
        ArchiveMessageSnapshot snapshot = map(accountSlot, message, users, chats,
                accountEnvironment, accountId);
        return snapshot == null ? null : CaptureEvent.message(type, snapshot, channelMessage);
    }

    private static ArchiveMessageSnapshot map(int accountSlot, TLRPC.Message message,
                                              ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats,
                                              int accountEnvironment, long accountId) {
        long dialogId = message == null ? 0 : MessageObject.getDialogId(message);
        int forumTypeFlags = 0;
        if (dialogId < 0 && chats != null) {
            long chatId = -dialogId;
            for (TLRPC.Chat chat : chats) {
                if (chat != null && chat.id == chatId) {
                    if (chat.forum) forumTypeFlags |= MessagesStorage.FORUM_TYPE_CHAT;
                    if (chat.monoforum) forumTypeFlags |= MessagesStorage.FORUM_TYPE_DIRECT;
                    break;
                }
            }
        } else if (dialogId > 0 && users != null) {
            for (TLRPC.User user : users) {
                if (user != null && user.id == dialogId) {
                    if (user.bot_forum_view) forumTypeFlags |= MessagesStorage.FORUM_TYPE_BOT;
                    break;
                }
            }
        }
        return ArchiveMessageMapper.map(accountSlot, message, forumTypeFlags, accountEnvironment, accountId);
    }

    private static CaptureEvent deletionEvent(int accountEnvironment, long accountId, long dialogId,
                                              ArrayList<Integer> ids, int pts, int ptsCount, String kind) {
        if (ids == null || ids.isEmpty()) return null;
        ArrayList<Integer> stableIds = new ArrayList<>(ids);
        Collections.sort(stableIds);
        String sourceEventId = deletionSourceEventId(kind, dialogId, pts, ptsCount, stableIds);
        return CaptureEvent.deletion(accountEnvironment, accountId, dialogId, stableIds,
                sourceEventId, System.currentTimeMillis() / 1000L);
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
        FileLog.e("Local archive update capture failed: " + error.getClass().getSimpleName());
    }

    /** Immutable hand-off from stageQueue to storageQueue. */
    public static final class CaptureEvent {
        public static final int NEW = 1;
        public static final int EDIT = 2;
        public static final int DELETE = 3;

        public final int type;
        public final ArchiveMessageSnapshot previousSnapshot;
        public final ArchiveMessageSnapshot snapshot;
        public final boolean channelMessage;
        public final int accountEnvironment;
        public final long accountId;
        public final long dialogId;
        public final ArrayList<Integer> messageIds;
        public final String sourceEventId;
        public final long deletedAt;

        private CaptureEvent(int type, ArchiveMessageSnapshot previousSnapshot,
                             ArchiveMessageSnapshot snapshot, boolean channelMessage,
                             int accountEnvironment, long accountId, long dialogId,
                             ArrayList<Integer> messageIds, String sourceEventId, long deletedAt) {
            this.type = type;
            this.previousSnapshot = previousSnapshot;
            this.snapshot = snapshot;
            this.channelMessage = channelMessage;
            this.accountEnvironment = accountEnvironment;
            this.accountId = accountId;
            this.dialogId = dialogId;
            this.messageIds = messageIds;
            this.sourceEventId = sourceEventId;
            this.deletedAt = deletedAt;
        }

        private static CaptureEvent message(int type, ArchiveMessageSnapshot snapshot, boolean channelMessage) {
            return message(type, snapshot, channelMessage, null);
        }

        private static CaptureEvent message(int type, ArchiveMessageSnapshot snapshot, boolean channelMessage,
                                            ArchiveMessageSnapshot previousSnapshot) {
            return new CaptureEvent(type, previousSnapshot, snapshot, channelMessage, snapshot.accountEnvironment,
                    snapshot.accountId, snapshot.dialogId, null, null, 0);
        }

        private static CaptureEvent deletion(int accountEnvironment, long accountId, long dialogId,
                                             ArrayList<Integer> messageIds, String sourceEventId,
                                             long deletedAt) {
            return new CaptureEvent(DELETE, null, null, dialogId < 0, accountEnvironment, accountId,
                    dialogId, new ArrayList<>(messageIds), sourceEventId, deletedAt);
        }
    }

    public static final class OutgoingEditCapture {
        private final ArchiveMessageSnapshot previous;

        private OutgoingEditCapture(ArchiveMessageSnapshot previous) {
            this.previous = previous;
        }
    }

    private static final class CaptureState {
        boolean prepared;
        boolean committed;
        CaptureEvent event;
    }
}
