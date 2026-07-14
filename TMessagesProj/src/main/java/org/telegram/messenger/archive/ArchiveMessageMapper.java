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
                message.date, text, type, replyId, message.grouped_id,
                fingerprint(message.entities), stableMediaFingerprint(message.media), fingerprint(message.reply_to),
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

    /** Restores an immutable archive payload for an in-memory recalled-message UI overlay. */
    public static TLRPC.Message restore(ArchiveMessageRecord record) {
        if (record == null || record.messageId <= 0
                || record.rawFormatVersion != ArchiveSchema.RAW_FORMAT_VERSION) {
            return null;
        }
        byte[] raw = record.copyRawPayload();
        if (raw == null || raw.length < 4) return null;
        SerializedData data = null;
        try {
            data = new SerializedData(raw);
            TLRPC.Message message = TLRPC.Message.TLdeserialize(
                    data, data.readInt32(false), false);
            if (message == null || message instanceof TLRPC.TL_messageEmpty
                    || message.id != record.messageId) {
                return null;
            }
            long payloadDialogId = MessageObject.getDialogId(message);
            if (payloadDialogId != 0 && payloadDialogId != record.dialogId) return null;
            message.dialog_id = record.dialogId;
            message.is_recalled = record.deleted;
            return message;
        } catch (Throwable ignore) {
            return null;
        } finally {
            if (data != null) data.cleanup();
        }
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
                              int messageId, long senderId, int messageDate, String text,
                              String messageType, int replyToMessageId, long groupedId,
                              String entitiesFingerprint, String mediaFingerprint, String replyFingerprint,
                              String forwardFingerprint, String actionFingerprint) {
        return hash(accountEnvironment, accountId, dialogId, topicId, messageId, senderId,
                messageDate, text, messageType, replyToMessageId, groupedId,
                entitiesFingerprint, mediaFingerprint, replyFingerprint, forwardFingerprint,
                actionFingerprint);
    }

    private static String fingerprint(TLObject object) {
        return object == null ? "" : digest(serialize(object));
    }

    /**
     * Telegram refreshes file_reference, thumbnails and CDN metadata without a user edit. Those
     * transport fields must not turn a photo/document into a new archive revision.
     */
    private static String stableMediaFingerprint(TLRPC.MessageMedia media) {
        if (media == null) return "";
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            long photoId = media.photo == null ? 0 : media.photo.id;
            long liveDocumentId = media.document == null ? 0 : media.document.id;
            return "photo:" + photoId + ":" + media.spoiler + ":" + media.live_photo
                    + ":" + liveDocumentId;
        }
        if (media instanceof TLRPC.TL_messageMediaDocument) {
            long documentId = media.document == null ? 0 : media.document.id;
            long coverId = media.video_cover == null ? 0 : media.video_cover.id;
            return "document:" + documentId + ":" + media.spoiler + ":" + media.video
                    + ":" + media.round + ":" + media.voice + ":" + media.live_photo
                    + ":" + coverId + ":" + media.video_timestamp;
        }
        if (media instanceof TLRPC.TL_messageMediaWebPage) {
            // Web-page contents are hydrated asynchronously; only user-controlled layout flags
            // are stable edit content. The URL itself is already represented by text/entities.
            return "webpage:" + media.force_large_media + ":" + media.force_small_media
                    + ":" + media.manual;
        }
        if (media instanceof TLRPC.TL_messageMediaPoll) {
            TLRPC.Poll poll = ((TLRPC.TL_messageMediaPoll) media).poll;
            return "poll:" + (poll == null ? 0 : poll.id);
        }
        return fingerprint(media);
    }

    /** Same core condition used by MessageObject.isEdited() and Telegram's message UI. */
    static boolean isVisibleEdit(TLRPC.Message message) {
        return message != null
                && (message.flags & TLRPC.MESSAGE_FLAG_EDITED) != 0
                && message.edit_date != 0
                && !message.edit_hide;
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
