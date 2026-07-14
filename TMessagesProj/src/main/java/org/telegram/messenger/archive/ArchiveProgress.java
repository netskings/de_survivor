package org.telegram.messenger.archive;

/** Immutable progress snapshot. Byte counters are scoped to the current phase. */
public final class ArchiveProgress {
    public final String phase;
    public final long processedRecords;
    public final long totalRecords;
    public final long processedBytes;
    public final long totalBytes;

    ArchiveProgress(String phase, long processedRecords, long totalRecords,
                    long processedBytes, long totalBytes) {
        this.phase = phase;
        this.processedRecords = processedRecords;
        this.totalRecords = totalRecords;
        this.processedBytes = processedBytes;
        this.totalBytes = totalBytes;
    }
}
