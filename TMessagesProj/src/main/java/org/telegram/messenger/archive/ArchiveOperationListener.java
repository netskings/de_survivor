package org.telegram.messenger.archive;

/** Callbacks are delivered on Android's UI thread. Message contents are never included. */
public interface ArchiveOperationListener {
    default void onStateChanged(ArchiveOperation operation) {
    }

    default void onProgress(ArchiveOperation operation, ArchiveProgress progress) {
    }

    /** Called after phase-A validation and before archive.db can be modified. */
    default void onArchiveValidated(ArchiveOperation operation, ArchiveManifest manifest) {
    }
}
