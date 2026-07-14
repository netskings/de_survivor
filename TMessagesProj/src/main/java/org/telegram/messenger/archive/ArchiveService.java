package org.telegram.messenger.archive;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** Lazy process-wide boundary which isolates all archive failures from Telegram processing. */
public final class ArchiveService {
    private static volatile ArchiveService instance;

    public static ArchiveService getInstance() {
        ArchiveService local = instance;
        if (local == null) {
            synchronized (ArchiveService.class) {
                local = instance;
                if (local == null) instance = local = new ArchiveService();
            }
        }
        return local;
    }

    private final DispatchQueue archiveQueue;
    private ArchiveDatabase database;
    private ArchiveRepository repository;
    private volatile boolean permanentlyDisabled;
    private final CopyOnWriteArrayList<EditListener> editListeners = new CopyOnWriteArrayList<>();

    private ArchiveService() {
        this(null);
    }

    ArchiveService(ArchiveRepository repository) {
        archiveQueue = new DispatchQueue(repository == null ? "archiveQueue" : "archiveQueueTest");
        this.repository = repository;
    }

    public void saveMessage(ArchiveMessageSnapshot snapshot) {
        if (!ArchiveSettings.isEnabled() || snapshot == null || permanentlyDisabled) return;
        post(() -> repository().saveMessage(snapshot));
    }

    public void saveEdit(ArchiveMessageSnapshot previous, ArchiveMessageSnapshot current) {
        if (!ArchiveSettings.isEnabled() || previous == null || current == null || permanentlyDisabled) return;
        if (previous.contentHash.equals(current.contentHash)) return;
        post(() -> {
            repository().saveEdit(previous, current);
            AndroidUtilities.runOnUIThread(() -> notifyMessageEdited(current));
        });
    }

    public void saveDeletion(ArchiveMessageSnapshot snapshot, ArchiveMessageSnapshot key, String sourceEventId, long deletedAt) {
        if (!ArchiveSettings.isEnabled() || key == null || sourceEventId == null || permanentlyDisabled) return;
        post(() -> repository().saveDeletion(snapshot, key, sourceEventId, deletedAt));
    }

    public void loadDeletedMessages(int accountSlot, long dialogId, long topicId,
                                    Callback<ArrayList<ArchiveMessageRecord>> callback) {
        loadMessages(accountSlot, callback,
                (repository, environment, accountId) -> repository.listDeleted(environment, accountId, dialogId, topicId));
    }

    public void loadEditedMessages(int accountSlot, long dialogId, long topicId,
                                   Callback<ArrayList<ArchiveMessageRecord>> callback) {
        loadMessages(accountSlot, callback,
                (repository, environment, accountId) -> repository.listEdited(environment, accountId, dialogId, topicId));
    }

    public void loadMessageHistory(int accountSlot, long dialogId, long topicId, int messageId,
                                   Callback<ArrayList<ArchiveMessageRecord>> callback) {
        loadMessages(accountSlot, callback, (repository, environment, accountId) ->
                repository.messageHistory(environment, accountId, dialogId, topicId, messageId));
    }

    public void addEditListener(EditListener listener) {
        if (listener != null) editListeners.addIfAbsent(listener);
    }

    public void removeEditListener(EditListener listener) {
        editListeners.remove(listener);
    }

    private void notifyMessageEdited(ArchiveMessageSnapshot message) {
        for (EditListener listener : editListeners) {
            try {
                listener.onMessageEdited(message.accountEnvironment, message.accountId, message.dialogId,
                        message.topicId, message.messageId);
            } catch (Throwable e) {
                FileLog.e("Local archive edit listener failed: " + e.getClass().getSimpleName());
            }
        }
    }

    private void loadMessages(int accountSlot, Callback<ArrayList<ArchiveMessageRecord>> callback,
                              ArchiveReadOperation operation) {
        if (callback == null) return;
        if (!ArchiveSettings.isEnabled() || permanentlyDisabled) {
            AndroidUtilities.runOnUIThread(() -> callback.onResult(new ArrayList<>()));
            return;
        }
        final long accountId = UserConfig.getInstance(accountSlot).getClientUserId();
        final int environment = ConnectionsManager.getInstance(accountSlot).isTestBackend() ? 1 : 0;
        archiveQueue.postRunnable(() -> {
            ArrayList<ArchiveMessageRecord> result = new ArrayList<>();
            if (!permanentlyDisabled && accountId != 0) {
                try {
                    result = operation.run(repository(), environment, accountId);
                } catch (ArchiveDatabase.UnsupportedSchemaException e) {
                    permanentlyDisabled = true;
                    FileLog.e("Local archive disabled because its schema is newer than this app");
                } catch (Throwable e) {
                    FileLog.e("Local archive read failed: " + e.getClass().getSimpleName());
                }
            }
            ArrayList<ArchiveMessageRecord> delivered = result;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(delivered));
        });
    }

    private void post(ArchiveOperation operation) {
        archiveQueue.postRunnable(() -> {
            if (permanentlyDisabled) return;
            try {
                operation.run();
            } catch (ArchiveDatabase.UnsupportedSchemaException e) {
                permanentlyDisabled = true;
                FileLog.e("Local archive disabled because its schema is newer than this app");
            } catch (Throwable e) {
                // Never include a snapshot or payload in diagnostics.
                FileLog.e("Local archive operation failed: " + e.getClass().getSimpleName());
            }
        });
    }

    private ArchiveRepository repository() throws Exception {
        if (repository == null) {
            File root = new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "local_archive");
            database = new ArchiveDatabase(new File(root, "archive.db"));
            repository = new ArchiveRepository(database);
        }
        return repository;
    }

    private interface ArchiveOperation {
        void run() throws Exception;
    }

    private interface ArchiveReadOperation {
        ArrayList<ArchiveMessageRecord> run(ArchiveRepository repository, int environment, long accountId) throws Exception;
    }

    public interface Callback<T> {
        void onResult(T result);
    }

    public interface EditListener {
        void onMessageEdited(int accountEnvironment, long accountId, long dialogId, long topicId, int messageId);
    }

    void awaitIdleForTests() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        archiveQueue.postRunnable(latch::countDown);
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("Archive queue did not become idle");
        }
    }

    void recycleForTests() {
        archiveQueue.recycle();
    }
}
