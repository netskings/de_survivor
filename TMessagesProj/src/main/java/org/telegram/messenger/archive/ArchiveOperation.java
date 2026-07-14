package org.telegram.messenger.archive;

import org.telegram.messenger.AndroidUtilities;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Cancellable handle shared by export and import. Cancellation completes only after cleanup. */
public final class ArchiveOperation {
    public enum State { PENDING, RUNNING, COMPLETED, CANCELLED, FAILED }

    public enum ErrorCode {
        NONE, CANCELLED, INVALID_ARGUMENT, IO_ERROR, INVALID_ZIP, INVALID_MANIFEST,
        UNSUPPORTED_VERSION, LIMIT_EXCEEDED, CHECKSUM_MISMATCH, INVALID_JSONL, DATABASE_ERROR
    }

    static final class CancelledException extends Exception {
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private final ArchiveOperationListener listener;
    private volatile ArchiveProgress progress = new ArchiveProgress("pending", 0, 0, 0, 0);
    private volatile ErrorCode errorCode = ErrorCode.NONE;

    ArchiveOperation(ArchiveOperationListener listener) {
        this.listener = listener;
    }

    public State getState() {
        return state.get();
    }

    public ArchiveProgress getProgress() {
        return progress;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public boolean cancel() {
        State current = state.get();
        if (current == State.COMPLETED || current == State.CANCELLED || current == State.FAILED) return false;
        cancellationRequested.set(true);
        return true;
    }

    boolean isCancellationRequested() {
        return cancellationRequested.get();
    }

    void throwIfCancelled() throws CancelledException {
        if (cancellationRequested.get()) throw new CancelledException();
    }

    void running() {
        if (state.compareAndSet(State.PENDING, State.RUNNING)) notifyState();
    }

    void completed() {
        state.set(State.COMPLETED);
        notifyState();
    }

    void cancelled() {
        errorCode = ErrorCode.CANCELLED;
        state.set(State.CANCELLED);
        notifyState();
    }

    void failed(ErrorCode code) {
        errorCode = code == null ? ErrorCode.IO_ERROR : code;
        state.set(State.FAILED);
        notifyState();
    }

    void progress(String phase, long processedRecords, long totalRecords,
                  long processedBytes, long totalBytes) {
        progress = new ArchiveProgress(phase, processedRecords, totalRecords, processedBytes, totalBytes);
        if (listener != null) {
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    listener.onProgress(this, progress);
                } catch (Throwable ignored) {
                }
            });
        }
    }

    void validated(ArchiveManifest manifest) {
        if (listener != null) {
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    listener.onArchiveValidated(this, manifest);
                } catch (Throwable ignored) {
                }
            });
        }
    }

    void validatedThen(ArchiveManifest manifest, Runnable continuation) {
        if (listener == null) {
            continuation.run();
        } else {
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    listener.onArchiveValidated(this, manifest);
                } catch (Throwable ignored) {
                } finally {
                    continuation.run();
                }
            });
        }
    }

    private void notifyState() {
        if (listener != null) AndroidUtilities.runOnUIThread(() -> {
            try {
                listener.onStateChanged(this);
            } catch (Throwable ignored) {
            }
        });
    }
}
