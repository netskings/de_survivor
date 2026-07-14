package org.telegram.messenger.archive;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.json.TLJsonBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Converts mutable Telegram protocol objects into stable immutable snapshots. */
public final class ArchiveMessageMapper {
    private ArchiveMessageMapper() {
    }

    public static ArchiveMessageSnapshot map(int accountSlot, TLRPC.Message message) {
        long dialogId = message == null ? 0 : MessageObject.getDialogId(message);
        int forumTypeFlags = 0;
        if (dialogId < 0) {
            TLRPC.Chat chat = MessagesController.getInstance(accountSlot).getChat(-dialogId);
            if (chat != null && chat.forum) forumTypeFlags |= MessagesStorage.FORUM_TYPE_CHAT;
            if (chat != null && chat.monoforum) forumTypeFlags |= MessagesStorage.FORUM_TYPE_DIRECT;
        } else if (dialogId > 0) {
            TLRPC.User user = MessagesController.getInstance(accountSlot).getUser(dialogId);
            if (user != null && user.bot_forum_view) forumTypeFlags |= MessagesStorage.FORUM_TYPE_BOT;
        }
        return map(accountSlot, message, forumTypeFlags);
    }

    public static ArchiveMessageSnapshot map(int accountSlot, TLRPC.Message message, int forumTypeFlags) {
        long accountId = UserConfig.getInstance(accountSlot).getClientUserId();
        int environment = ConnectionsManager.getInstance(accountSlot).isTestBackend() ? 1 : 0;
        return map(accountSlot, message, forumTypeFlags, environment, accountId);
    }

    public static ArchiveMessageSnapshot map(int accountSlot, TLRPC.Message message, int forumTypeFlags,
                                             int accountEnvironment, long accountId) {
        if (message == null || message instanceof TLRPC.TL_messageEmpty || message.id <= 0) {
            return null;
        }
        long dialogId = MessageObject.getDialogId(message);
        if (accountId == 0 || dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)
                || message.ttl_period != 0 || MessageObject.isSecretMedia(message)
                || MessageObject.isQuickReply(message)) {
            return null;
        }

        long topicId = MessageObject.getTopicId(accountSlot, message, forumTypeFlags);
        long senderId = message.from_id == null ? 0 : MessageObject.getPeerId(message.from_id);
        int replyId = message.reply_to == null ? 0 : message.reply_to.reply_to_msg_id;
        String text = message.message == null ? "" : message.message;
        String entities = mapEntities(message.entities);
        String type = message.action != null ? message.action.getClass().getSimpleName()
                : message.media != null ? message.media.getClass().getSimpleName() : message.getClass().getSimpleName();
        byte[] rawPayload = serialize(message);
        long savedAt = System.currentTimeMillis() / 1000L;
        String hash = contentHash(accountEnvironment, accountId, dialogId, topicId, message.id, senderId,
                message.date, message.edit_date, text, type, replyId, message.grouped_id,
                fingerprint(message.entities), fingerprint(message.media), fingerprint(message.reply_to),
                fingerprint(message.fwd_from), fingerprint(message.action));
        return new ArchiveMessageSnapshot(accountEnvironment, accountId, dialogId, topicId, message.id, senderId,
                message.date, message.edit_date, savedAt, text, entities, type, replyId, message.grouped_id,
                ArchiveSchema.RAW_FORMAT_VERSION, rawPayload, hash);
    }

    public static ArchiveMessageSnapshot key(int accountSlot, long dialogId, long topicId, int messageId) {
        long accountId = UserConfig.getInstance(accountSlot).getClientUserId();
        int environment = ConnectionsManager.getInstance(accountSlot).isTestBackend() ? 1 : 0;
        return key(environment, accountId, dialogId, topicId, messageId);
    }

    public static ArchiveMessageSnapshot key(int accountEnvironment, long accountId, long dialogId,
                                             long topicId, int messageId) {
        if (accountId == 0 || DialogObject.isEncryptedDialog(dialogId)) return null;
        return new ArchiveMessageSnapshot(accountEnvironment, accountId, dialogId, topicId, messageId, 0,
                0, 0, System.currentTimeMillis() / 1000L, "", "[]", "unknown", 0, 0,
                ArchiveSchema.RAW_FORMAT_VERSION, null,
                hash(accountEnvironment, accountId, dialogId, topicId, messageId, "deletion-key"));
    }

    private static String mapEntities(java.util.ArrayList<TLRPC.MessageEntity> entities) {
        JSONArray array = new JSONArray();
        if (entities != null) {
            for (TLRPC.MessageEntity entity : entities) {
                JSONObject object = TLJsonBuilder.serialize(entity);
                if (object != null) {
                    array.put(object);
                }
            }
        }
        return array.toString();
    }

    private static byte[] serialize(TLRPC.Message message) {
        SerializedData data = new SerializedData(message.getObjectSize());
        try {
            message.serializeToStream(data);
            return data.toByteArray();
        } finally {
            data.cleanup();
        }
    }

    private static String hash(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            byte[] result = digest.digest();
            StringBuilder builder = new StringBuilder(result.length * 2);
            for (byte b : result) builder.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    static String contentHash(int accountEnvironment, long accountId, long dialogId, long topicId,
                              int messageId, long senderId, int messageDate, int editDate, String text,
                              String messageType, int replyToMessageId, long groupedId,
                              String entitiesFingerprint, String mediaFingerprint, String replyFingerprint,
                              String forwardFingerprint, String actionFingerprint) {
        return hash(accountEnvironment, accountId, dialogId, topicId, messageId, senderId,
                messageDate, editDate, text, messageType, replyToMessageId, groupedId,
                entitiesFingerprint, mediaFingerprint, replyFingerprint, forwardFingerprint,
                actionFingerprint);
    }

    private static String fingerprint(TLObject object) {
        return object == null ? "" : digest(serialize(object));
    }

    private static String fingerprint(java.util.ArrayList<? extends TLObject> objects) {
        if (objects == null || objects.isEmpty()) return "";
        StringBuilder result = new StringBuilder(objects.size() * 64);
        for (TLObject object : objects) {
            result.append(fingerprint(object));
        }
        return result.toString();
    }

    private static byte[] serialize(TLObject object) {
        SerializedData data = new SerializedData(object.getObjectSize());
        try {
            object.serializeToStream(data);
            return data.toByteArray();
        } finally {
            data.cleanup();
        }
    }

    private static String digest(byte[] value) {
        try {
            byte[] result = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder builder = new StringBuilder(result.length * 2);
            for (byte b : result) builder.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
