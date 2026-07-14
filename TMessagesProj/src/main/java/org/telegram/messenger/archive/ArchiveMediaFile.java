package org.telegram.messenger.archive;

final class ArchiveMediaFile {
    final String contentHash;
    final String relativePath;
    final long sizeBytes;

    ArchiveMediaFile(String contentHash, String relativePath, long sizeBytes) {
        this.contentHash = contentHash;
        this.relativePath = relativePath;
        this.sizeBytes = sizeBytes;
    }
}
