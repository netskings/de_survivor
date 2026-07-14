package org.telegram.messenger.archive;

/** Explicit denial-of-service limits for untrusted .tgarchive input. */
final class ArchiveTransferLimits {
    static final long MAX_CONTAINER_BYTES = 1024L * 1024 * 1024;
    static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024;
    static final long MAX_ENTRY_BYTES = 1024L * 1024 * 1024;
    static final int MAX_ZIP_ENTRIES = 10_008;
    static final int MAX_MEDIA_FILES = 10_000;
    static final long MAX_MEDIA_LINKS = 500_000L;
    static final long MAX_MEDIA_FILE_BYTES = 1024L * 1024 * 1024;
    static final double MAX_COMPRESSION_RATIO = 200.0;
    static final int COMPRESSION_RATIO_MIN_BYTES = 1024 * 1024;
    static final int MAX_JSONL_LINE_BYTES = 8 * 1024 * 1024;
    static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    static final int MAX_RAW_PAYLOAD_BYTES = 4 * 1024 * 1024;
    static final long MAX_RECORDS = 5_000_000L;
    static final int MAX_SMALL_ENTRY_BYTES = 1024 * 1024;
    static final int MAX_CHECKSUM_ENTRY_BYTES = 4 * 1024 * 1024;
    static final int PAGE_SIZE = 500;

    private ArchiveTransferLimits() {
    }
}
