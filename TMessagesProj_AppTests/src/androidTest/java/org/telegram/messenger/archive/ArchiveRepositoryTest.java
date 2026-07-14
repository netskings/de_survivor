package org.telegram.messenger.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public class ArchiveRepositoryTest {
    private File file;
    private ArchiveDatabase database;
    private ArchiveRepository repository;
    private ArchiveService service;

    @Before
    public void setUp() throws Exception {
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "archive-tests");
        directory.mkdirs();
        file = new File(directory, "archive-" + System.nanoTime() + ".db");
        database = new ArchiveDatabase(file);
        repository = new ArchiveRepository(database);
    }

    @After
    public void tearDown() {
        if (service != null) service.recycleForTests();
        if (database != null) database.close();
        deleteDatabaseFiles(file);
        ArchiveSettings.setTestOverride(null);
    }

    @Test
    public void savesNewMessage() throws Exception {
        repository.saveMessage(snapshot(0, 1, 10, 0, 5, 0, "one"));
        assertEquals(1, repository.count("archive_messages"));
    }

    @Test
    public void repeatedNewMessageIsIdempotent() throws Exception {
        ArchiveMessageSnapshot value = snapshot(0, 1, 10, 0, 5, 0, "one");
        repository.saveMessage(value);
        repository.saveMessage(value);
        assertEquals(1, repository.count("archive_messages"));
        assertEquals(0, repository.count("archive_message_revisions"));
    }

    @Test
    public void editStoresPreviousRevision() throws Exception {
        ArchiveMessageSnapshot old = snapshot(0, 1, 10, 0, 5, 0, "one");
        ArchiveMessageSnapshot current = snapshot(0, 1, 10, 0, 5, 2, "two");
        repository.saveMessage(old);
        repository.saveEdit(old, current);
        assertEquals(1, repository.count("archive_message_revisions"));
        assertTrue(repository.hasRevision(old));
        assertEquals(current.contentHash, repository.messageHash(current));
    }

    @Test
    public void repeatedEditIsIdempotent() throws Exception {
        ArchiveMessageSnapshot old = snapshot(0, 1, 10, 0, 5, 0, "one");
        ArchiveMessageSnapshot current = snapshot(0, 1, 10, 0, 5, 2, "two");
        repository.saveMessage(old);
        repository.saveEdit(old, current);
        repository.saveEdit(old, current);
        assertEquals(1, repository.count("archive_message_revisions"));
    }

    @Test
    public void multipleEditsStoreImmutableRevisions() throws Exception {
        ArchiveMessageSnapshot first = snapshot(0, 1, 10, 0, 5, 0, "one");
        ArchiveMessageSnapshot second = snapshot(0, 1, 10, 0, 5, 2, "two");
        ArchiveMessageSnapshot third = snapshot(0, 1, 10, 0, 5, 3, "three");
        repository.saveMessage(first);
        repository.saveEdit(first, second);
        repository.saveEdit(second, third);
        assertEquals(2, repository.count("archive_message_revisions"));
        assertTrue(repository.hasRevision(first));
        assertTrue(repository.hasRevision(second));
        assertEquals(third.contentHash, repository.messageHash(third));
    }

    @Test
    public void differentEditsInSameSecondArePreserved() throws Exception {
        ArchiveMessageSnapshot first = snapshot(0, 1, 10, 0, 5, 2, "one");
        ArchiveMessageSnapshot second = snapshot(0, 1, 10, 0, 5, 2, "two");
        ArchiveMessageSnapshot third = snapshot(0, 1, 10, 0, 5, 2, "three");
        repository.saveMessage(first);
        repository.saveEdit(first, second);
        repository.saveEdit(second, third);
        assertEquals(2, repository.count("archive_message_revisions"));
        assertEquals(third.contentHash, repository.messageHash(third));
    }

    @Test
    public void deletionMarksMessage() throws Exception {
        ArchiveMessageSnapshot value = snapshot(0, 1, 10, 0, 5, 0, "one");
        repository.saveMessage(value);
        repository.saveDeletion(value, value, "delete:10:a", 100);
        assertTrue(repository.isDeleted(value));
        assertEquals(1, repository.count("archive_deletion_events"));
    }

    @Test
    public void repeatedDeletionEventIsIdempotent() throws Exception {
        ArchiveMessageSnapshot value = snapshot(0, 1, 10, 0, 5, 0, "one");
        repository.saveDeletion(value, value, "delete:10:a", 100);
        repository.saveDeletion(value, value, "delete:10:a", 200);
        assertEquals(1, repository.count("archive_deletion_events"));
        assertEquals(100, repository.messageDeletedAt(value));
    }

    @Test
    public void deletionWithoutSnapshotKeepsEvent() throws Exception {
        ArchiveMessageSnapshot key = snapshot(0, 1, 10, 0, 5, 0, "missing");
        repository.saveDeletion(null, key, "delete:10:a", 100);
        assertEquals(0, repository.count("archive_messages"));
        assertEquals(1, repository.count("archive_deletion_events"));
    }

    @Test
    public void sameMessageIdInDifferentDialogsIsSeparated() throws Exception {
        repository.saveMessage(snapshot(0, 1, 10, 0, 5, 0, "a"));
        repository.saveMessage(snapshot(0, 1, 11, 0, 5, 0, "b"));
        assertEquals(2, repository.count("archive_messages"));
    }

    @Test
    public void sameMessageIdInDifferentAccountsIsSeparated() throws Exception {
        repository.saveMessage(snapshot(0, 1, 10, 0, 5, 0, "a"));
        repository.saveMessage(snapshot(0, 2, 10, 0, 5, 0, "b"));
        assertEquals(2, repository.count("archive_messages"));
    }

    @Test
    public void topicIsPartOfMessageIdentity() throws Exception {
        repository.saveMessage(snapshot(0, 1, 10, 1, 5, 0, "a"));
        repository.saveMessage(snapshot(0, 1, 10, 2, 5, 0, "b"));
        assertEquals(2, repository.count("archive_messages"));
    }

    @Test
    public void featureFlagDefaultsToDisabled() {
        ArchiveSettings.setTestOverride(null);
        InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("custom_app_settings", 0).edit().remove("local_archive_enabled").commit();
        assertFalse(ArchiveSettings.isEnabled());
    }

    @Test
    public void malformedFeatureFlagFailsClosed() {
        ArchiveSettings.setTestOverride(null);
        android.content.SharedPreferences preferences = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getSharedPreferences("custom_app_settings", 0);
        preferences.edit().putString("local_archive_enabled", "invalid").commit();
        try {
            assertFalse(ArchiveSettings.isEnabled());
        } finally {
            preferences.edit().remove("local_archive_enabled").commit();
        }
    }

    @Test
    public void openingFailureDoesNotDeleteUnrelatedFile() throws Exception {
        File blocker = new File(file.getParentFile(), "not-a-directory-" + System.nanoTime());
        assertTrue(blocker.createNewFile());
        File child = new File(blocker, "archive.db");
        assertThrows(Exception.class, () -> new ArchiveDatabase(child));
        assertTrue(blocker.exists());
        blocker.delete();
    }

    @Test
    public void writeFailureIsReportedToCaller() throws Exception {
        database.close();
        assertThrows(Exception.class, () -> repository.saveMessage(snapshot(0, 1, 10, 0, 5, 0, "one")));
        database = null;
    }

    @Test
    public void newerSchemaIsRejectedWithoutDeletion() throws Exception {
        database.sqlite().executeFast("PRAGMA user_version = 99").stepThis().dispose();
        database.close();
        database = null;
        assertThrows(ArchiveDatabase.UnsupportedSchemaException.class, () -> new ArchiveDatabase(file));
        assertTrue(file.exists());
    }

    @Test
    public void partialVersionZeroSchemaIsCompletedWithoutDataLoss() throws Exception {
        ArchiveMessageSnapshot value = snapshot(0, 1, 10, 0, 5, 0, "preserved");
        repository.saveMessage(value);
        database.sqlite().executeFast("DROP TABLE archive_metadata").stepThis().dispose();
        database.sqlite().executeFast("PRAGMA user_version = 0").stepThis().dispose();
        database.close();
        database = new ArchiveDatabase(file);
        repository = new ArchiveRepository(database);
        assertTrue(database.sqlite().tableExists("archive_metadata"));
        assertEquals(value.contentHash, repository.messageHash(value));
    }

    @Test
    public void preservesNewEditDeleteOrder() throws Exception {
        ArchiveMessageSnapshot first = snapshot(0, 1, 10, 0, 5, 0, "one");
        ArchiveMessageSnapshot second = snapshot(0, 1, 10, 0, 5, 2, "two");
        repository.saveMessage(first);
        repository.saveEdit(first, second);
        repository.saveDeletion(second, second, "delete:10:a", 100);
        assertEquals(second.contentHash, repository.messageHash(second));
        assertTrue(repository.isDeleted(second));
        assertEquals(1, repository.count("archive_message_revisions"));
        assertEquals(1, repository.count("archive_deletion_events"));
    }

    @Test
    public void deletionBeforeDelayedEditKeepsEditedMessageDeleted() throws Exception {
        ArchiveMessageSnapshot first = snapshot(0, 1, 10, 0, 5, 0, "one");
        ArchiveMessageSnapshot second = snapshot(0, 1, 10, 0, 5, 2, "two");
        repository.saveMessage(first);
        repository.saveDeletion(first, first, "delete:10:a", 100);
        repository.saveEdit(first, second);
        assertEquals(second.contentHash, repository.messageHash(second));
        assertTrue(repository.isDeleted(second));
        assertEquals(1, repository.count("archive_message_revisions"));
    }

    @Test
    public void failedEditTransactionDoesNotBlockNextWrite() throws Exception {
        database.sqlite().executeFast("CREATE TRIGGER fail_archive_revision BEFORE INSERT ON archive_message_revisions " +
                "BEGIN SELECT RAISE(ABORT, 'forced test failure'); END").stepThis().dispose();
        ArchiveMessageSnapshot first = snapshot(0, 1, 10, 0, 5, 0, "one");
        ArchiveMessageSnapshot second = snapshot(0, 1, 10, 0, 5, 2, "two");
        assertThrows(Exception.class, () -> repository.saveEdit(first, second));
        database.sqlite().executeFast("DROP TRIGGER fail_archive_revision").stepThis().dispose();
        repository.saveMessage(first);
        assertEquals(1, repository.count("archive_messages"));
        assertEquals(0, repository.count("archive_message_revisions"));
    }

    @Test
    public void contentHashIsIndependentOfSavedAt() {
        String first = ArchiveMessageMapper.contentHash(0, 1, 10, 0, 5, 7,
                1, 2, "caption", "photo", 0, 9, "entities", "media", "reply", "forward", "action");
        String later = ArchiveMessageMapper.contentHash(0, 1, 10, 0, 5, 7,
                1, 2, "caption", "photo", 0, 9, "entities", "media", "reply", "forward", "action");
        assertEquals(first, later);
    }

    @Test
    public void explicitIdentityDoesNotFollowReusedAccountSlot() {
        UserConfig config = UserConfig.getInstance(0);
        long originalAccountId = config.clientUserId;
        try {
            config.clientUserId = 222;
            TLRPC.TL_message message = new TLRPC.TL_message();
            message.id = 5;
            message.date = 1;
            message.message = "account-bound";
            message.peer_id = new TLRPC.TL_peerUser();
            message.peer_id.user_id = 42;
            message.from_id = new TLRPC.TL_peerUser();
            message.from_id.user_id = 42;
            ArchiveMessageSnapshot snapshot = ArchiveMessageMapper.map(0, message, 0, 1, 111);
            assertNotNull(snapshot);
            assertEquals(111, snapshot.accountId);
            assertEquals(1, snapshot.accountEnvironment);
        } finally {
            config.clientUserId = originalAccountId;
        }
    }

    @Test
    public void mapperFailureDoesNotPreventFollowingUpdateCapture() {
        ArchiveSettings.setTestOverride(true);
        UserConfig config = UserConfig.getInstance(0);
        long originalAccountId = config.clientUserId;
        AtomicBoolean secondMapperReached = new AtomicBoolean();
        try {
            config.clientUserId = 111;
            ArrayList<TLRPC.Update> updates = new ArrayList<>();
            TLRPC.TL_updateNewMessage first = new TLRPC.TL_updateNewMessage();
            first.message = throwingMessage(null);
            updates.add(first);
            TLRPC.TL_updateNewMessage second = new TLRPC.TL_updateNewMessage();
            second.message = throwingMessage(secondMapperReached);
            updates.add(second);
            ArchiveEventObserver.observeUpdateArray(0, updates);
            assertTrue(secondMapperReached.get());
        } finally {
            config.clientUserId = originalAccountId;
        }
    }

    @Test
    public void servicePreservesNewEditDeleteAcrossSourceQueues() throws Exception {
        ArchiveSettings.setTestOverride(true);
        RecordingArchiveRepository recordingRepository = new RecordingArchiveRepository(database);
        service = new ArchiveService(recordingRepository);
        DispatchQueue newSource = new DispatchQueue("archiveNewSourceTest");
        DispatchQueue storageSource = new DispatchQueue("archiveStorageSourceTest");
        CountDownLatch newWasPosted = new CountDownLatch(1);
        CountDownLatch sourcesFinished = new CountDownLatch(2);
        ArchiveMessageSnapshot first = snapshot(0, 1, 10, 0, 5, 0, "one");
        ArchiveMessageSnapshot second = snapshot(0, 1, 10, 0, 5, 2, "two");
        try {
            newSource.postRunnable(() -> {
                service.saveMessage(first);
                newWasPosted.countDown();
                sourcesFinished.countDown();
            });
            storageSource.postRunnable(() -> {
                try {
                    assertTrue(newWasPosted.await(5, TimeUnit.SECONDS));
                    service.saveEdit(first, second);
                    service.saveDeletion(second, second, "delete:10:ordered", 100);
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                } finally {
                    sourcesFinished.countDown();
                }
            });
            assertTrue(sourcesFinished.await(5, TimeUnit.SECONDS));
            service.awaitIdleForTests();
        } finally {
            newSource.recycle();
            storageSource.recycle();
        }
        assertEquals(second.contentHash, repository.messageHash(second));
        assertTrue(repository.isDeleted(second));
        assertEquals(1, repository.count("archive_message_revisions"));
        assertEquals(1, repository.count("archive_deletion_events"));
        assertEquals(Arrays.asList("message:one", "edit", "message:two", "delete", "message:two"),
                recordingRepository.operations);
    }

    @Test
    public void serviceContinuesAfterFailedSqlTransaction() throws Exception {
        ArchiveSettings.setTestOverride(true);
        service = new ArchiveService(repository);
        database.sqlite().executeFast("CREATE TRIGGER fail_service_revision BEFORE INSERT ON archive_message_revisions " +
                "BEGIN SELECT RAISE(ABORT, 'forced service failure'); END").stepThis().dispose();
        ArchiveMessageSnapshot first = snapshot(0, 1, 10, 0, 5, 0, "one");
        ArchiveMessageSnapshot second = snapshot(0, 1, 10, 0, 5, 2, "two");
        ArchiveMessageSnapshot independent = snapshot(0, 1, 11, 0, 6, 0, "still works");
        service.saveEdit(first, second);
        service.saveMessage(independent);
        service.awaitIdleForTests();
        assertEquals(independent.contentHash, repository.messageHash(independent));
        assertEquals(0, repository.count("archive_message_revisions"));
    }

    @Test
    public void olderMessageDoesNotOverwriteNewerEdit() throws Exception {
        ArchiveMessageSnapshot older = snapshot(0, 1, 10, 0, 5, 1, "old");
        ArchiveMessageSnapshot newer = snapshot(0, 1, 10, 0, 5, 3, "new");
        repository.saveMessage(newer);
        repository.saveMessage(older);
        assertEquals(newer.contentHash, repository.messageHash(newer));
    }

    @Test
    public void archiveReadListsAreAccountDialogAndTopicIsolated() throws Exception {
        ArchiveMessageSnapshot wanted = snapshot(0, 1, 10, 7, 5, 0, "wanted");
        ArchiveMessageSnapshot otherTopic = snapshot(0, 1, 10, 8, 6, 0, "other topic");
        ArchiveMessageSnapshot otherAccount = snapshot(0, 2, 10, 7, 7, 0, "other account");
        repository.saveDeletion(wanted, wanted, "delete:wanted", 100);
        repository.saveDeletion(otherTopic, otherTopic, "delete:topic", 101);
        repository.saveDeletion(otherAccount, otherAccount, "delete:account", 102);

        ArrayList<ArchiveMessageRecord> topic = repository.listDeleted(0, 1, 10, 7);
        assertEquals(1, topic.size());
        assertEquals("wanted", topic.get(0).text);
        assertEquals(100, topic.get(0).deletedAt);

        ArrayList<ArchiveMessageRecord> allTopics = repository.listDeleted(0, 1, 10, -1);
        assertEquals(2, allTopics.size());
    }

    @Test
    public void editedListReturnsLatestSummaryAndRevisionCount() throws Exception {
        ArchiveMessageSnapshot first = snapshot(0, 1, 10, 0, 5, 0, "one");
        ArchiveMessageSnapshot second = snapshot(0, 1, 10, 0, 5, 2, "two");
        ArchiveMessageSnapshot third = snapshot(0, 1, 10, 0, 5, 3, "three");
        repository.saveMessage(first);
        repository.saveEdit(first, second);
        repository.saveEdit(second, third);

        ArrayList<ArchiveMessageRecord> result = repository.listEdited(0, 1, 10, -1);
        assertEquals(1, result.size());
        assertEquals("three", result.get(0).text);
        assertEquals("two", result.get(0).previousText);
        assertEquals(2, result.get(0).revisionCount);
    }

    @Test
    public void messageHistoryReturnsChronologicalBeforeAfterVersions() throws Exception {
        ArchiveMessageSnapshot first = snapshot(0, 1, 10, 0, 5, 0, "one");
        ArchiveMessageSnapshot second = snapshot(0, 1, 10, 0, 5, 2, "two");
        ArchiveMessageSnapshot third = snapshot(0, 1, 10, 0, 5, 3, "three");
        repository.saveMessage(first);
        repository.saveEdit(first, second);
        repository.saveEdit(second, third);

        ArrayList<ArchiveMessageRecord> result = repository.messageHistory(0, 1, 10, 0, 5);
        assertEquals(3, result.size());
        assertEquals("one", result.get(0).text);
        assertEquals("two", result.get(1).text);
        assertEquals("three", result.get(2).text);
    }

    private static ArchiveMessageSnapshot snapshot(int environment, long account, long dialog, long topic,
                                                    int message, int editDate, String text) {
        String hash = environment + ":" + account + ":" + dialog + ":" + topic + ":" + message + ":" + editDate + ":" + text;
        return new ArchiveMessageSnapshot(environment, account, dialog, topic, message, 7, 1, editDate,
                10 + editDate, text, "[]", "text", 0, 0, ArchiveSchema.RAW_FORMAT_VERSION,
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8), hash);
    }

    private static void deleteDatabaseFiles(File file) {
        if (file == null) return;
        file.delete();
        new File(file.getAbsolutePath() + "-wal").delete();
        new File(file.getAbsolutePath() + "-shm").delete();
    }

    private static TLRPC.TL_message throwingMessage(AtomicBoolean reached) {
        TLRPC.TL_message message = new TLRPC.TL_message() {
            @Override
            public void serializeToStream(OutputSerializedData stream) {
                if (reached != null) reached.set(true);
                throw new IllegalStateException("forced mapper failure");
            }
        };
        message.id = 5;
        message.date = 1;
        message.message = "not logged";
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = 42;
        return message;
    }

    private static final class RecordingArchiveRepository extends ArchiveRepository {
        final List<String> operations = Collections.synchronizedList(new ArrayList<>());

        RecordingArchiveRepository(ArchiveDatabase database) {
            super(database);
        }

        @Override
        void saveMessage(ArchiveMessageSnapshot snapshot) throws Exception {
            operations.add("message:" + snapshot.text);
            super.saveMessage(snapshot);
        }

        @Override
        void saveEdit(ArchiveMessageSnapshot previous, ArchiveMessageSnapshot current) throws Exception {
            operations.add("edit");
            super.saveEdit(previous, current);
        }

        @Override
        void saveDeletion(ArchiveMessageSnapshot snapshot, ArchiveMessageSnapshot key,
                          String sourceEventId, long deletedAt) throws Exception {
            operations.add("delete");
            super.saveDeletion(snapshot, key, sourceEventId, deletedAt);
        }
    }
}
