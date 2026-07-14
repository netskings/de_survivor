package org.telegram.messenger.archive;

import java.util.Arrays;

/** Immutable projection returned by the archive read API. */
public final class ArchiveMessageRecord {
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
    public final String previousText;
    public final String messageType;
    public final boolean deleted;
    public final long deletedAt;
    public final int revisionCount;
    public final int rawFormatVersion;
    public final boolean mediaSaved;
    private final byte[] rawPayload;

    ArchiveMessageRecord(int accountEnvironment, long accountId, long dialogId, long topicId,
                         int messageId, long senderId, int messageDate, int editDate, long savedAt,
                         String text, String previousText, String messageType, boolean deleted,
                         long deletedAt, int revisionCount, int rawFormatVersion, byte[] rawPayload) {
        this(accountEnvironment, accountId, dialogId, topicId, messageId, senderId, messageDate,
                editDate, savedAt, text, previousText, messageType, deleted, deletedAt, revisionCount,
                rawFormatVersion, rawPayload, false);
    }

    ArchiveMessageRecord(int accountEnvironment, long accountId, long dialogId, long topicId,
                         int messageId, long senderId, int messageDate, int editDate, long savedAt,
                         String text, String previousText, String messageType, boolean deleted,
                         long deletedAt, int revisionCount, int rawFormatVersion, byte[] rawPayload,
                         boolean mediaSaved) {
        this.accountEnvironment = accountEnvironment;
        this.accountId = accountId;
        this.dialogId = dialogId;
        this.topicId = topicId;
        this.messageId = messageId;
        this.senderId = senderId;
        this.messageDate = messageDate;
        this.editDate = editDate;
        this.savedAt = savedAt;
        this.text = text == null ? "" : text;
        this.previousText = previousText == null ? "" : previousText;
        this.messageType = messageType == null ? "unknown" : messageType;
        this.deleted = deleted;
        this.deletedAt = deletedAt;
        this.revisionCount = revisionCount;
        this.rawFormatVersion = rawFormatVersion;
        this.mediaSaved = mediaSaved;
        this.rawPayload = rawPayload == null ? null : Arrays.copyOf(rawPayload, rawPayload.length);
    }

    public boolean expectsMediaFile() {
        return messageType.contains("MediaPhoto") || messageType.contains("MediaDocument");
    }

    public byte[] copyRawPayload() {
        return rawPayload == null ? null : Arrays.copyOf(rawPayload, rawPayload.length);
    }
}
