package org.telegram.messenger.archive;

final class ArchiveTransferException extends Exception {
    final ArchiveOperation.ErrorCode code;

    ArchiveTransferException(ArchiveOperation.ErrorCode code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    ArchiveTransferException(ArchiveOperation.ErrorCode code, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
    }
}
