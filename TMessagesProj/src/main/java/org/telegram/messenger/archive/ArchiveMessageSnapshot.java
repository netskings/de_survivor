package org.telegram.messenger.archive;

import java.util.Arrays;

/** Immutable, normalized message data safe to pass between Telegram and archive queues. */
public final class ArchiveMessageSnapshot {
    public final int accountEnvironment;
    public final long accountId;
    public final long dialogId;
    public final long topicId;
    public final int messageId;
    public final long senderId;
    public final int messageDate;
    public final int editDate;
    public final long savedAt;
    public final String text;
    public final String entitiesJson;
    public final String messageType;
    public final int replyToMessageId;
    public final long groupedId;
    public final int rawFormatVersion;
    private final byte[] rawPayload;
    public final String contentHash;

    public ArchiveMessageSnapshot(int accountEnvironment, long accountId, long dialogId, long topicId,
                                  int messageId, long senderId, int messageDate, int editDate, long savedAt,
                                  String text, String entitiesJson, String messageType, int replyToMessageId,
                                  long groupedId, int rawFormatVersion, byte[] rawPayload, String contentHash) {
        this.accountEnvironment = accountEnvironment;
        this.accountId = accountId;
        this.dialogId = dialogId;
        this.topicId = topicId;
        this.messageId = messageId;
        this.senderId = senderId;
        this.messageDate = messageDate;
        this.editDate = editDate;
        this.savedAt = savedAt;
        this.text = text;
        this.entitiesJson = entitiesJson;
        this.messageType = messageType;
        this.replyToMessageId = replyToMessageId;
        this.groupedId = groupedId;
        this.rawFormatVersion = rawFormatVersion;
        this.rawPayload = rawPayload == null ? null : Arrays.copyOf(rawPayload, rawPayload.length);
        this.contentHash = contentHash;
    }

    public byte[] copyRawPayload() {
        return rawPayload == null ? null : Arrays.copyOf(rawPayload, rawPayload.length);
    }
}
