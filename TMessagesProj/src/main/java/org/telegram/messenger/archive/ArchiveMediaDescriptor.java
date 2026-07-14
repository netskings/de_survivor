package org.telegram.messenger.archive;

/** Immutable message/media identity frozen before work moves to archiveMediaQueue. */
final class ArchiveMediaDescriptor {
    final int accountEnvironment;
    final long accountId;
    final long dialogId;
    final long topicId;
    final int messageId;
    final String mediaType;
    final String mimeType;
    final String originalName;
    final int position;
    final String role;

    ArchiveMediaDescriptor(int accountEnvironment, long accountId, long dialogId, long topicId,
                           int messageId, String mediaType, String mimeType, String originalName,
                           int position, String role) {
        this.accountEnvironment = accountEnvironment;
        this.accountId = accountId;
        this.dialogId = dialogId;
        this.topicId = topicId;
        this.messageId = messageId;
        this.mediaType = mediaType;
        this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        this.originalName = originalName == null ? "" : originalName;
        this.position = position;
        this.role = role == null ? "primary" : role;
    }
}
