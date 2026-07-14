package org.telegram.messenger.archive;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/** The only component that issues SQL against archive.db. */
class ArchiveRepository {
    private final SQLiteDatabase database;

    ArchiveRepository(ArchiveDatabase database) {
        this.database = database.sqlite();
    }

    void saveMessage(ArchiveMessageSnapshot snapshot) throws Exception {
        SQLitePreparedStatement statement = database.executeFast("INSERT INTO archive_messages(" +
                "account_environment,account_id,dialog_id,topic_id,message_id,sender_id,message_date,edit_date,saved_at," +
                "text,entities_json,message_type,reply_to_message_id,grouped_id,raw_format_version,raw_payload,content_hash,is_deleted,deleted_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,0) ON CONFLICT(account_environment,account_id,dialog_id,topic_id,message_id) DO UPDATE SET " +
                "sender_id=excluded.sender_id,message_date=excluded.message_date,edit_date=excluded.edit_date,saved_at=excluded.saved_at," +
                "text=excluded.text,entities_json=excluded.entities_json,message_type=excluded.message_type," +
                "reply_to_message_id=excluded.reply_to_message_id,grouped_id=excluded.grouped_id,raw_format_version=excluded.raw_format_version," +
                "raw_payload=excluded.raw_payload,content_hash=excluded.content_hash " +
                "WHERE excluded.edit_date > archive_messages.edit_date OR " +
                "(excluded.edit_date = archive_messages.edit_date AND excluded.content_hash <> archive_messages.content_hash)");
        try {
            bindSnapshot(statement, snapshot, false);
            statement.step();
        } finally {
            statement.dispose();
        }
    }

    void saveEdit(ArchiveMessageSnapshot previous, ArchiveMessageSnapshot current) throws Exception {
        if (previous.contentHash.equals(current.contentHash)) return;
        database.executeFast("SAVEPOINT archive_edit").stepThis().dispose();
        boolean successful = false;
        try {
            saveRevision(previous);
            saveMessage(current);
            successful = true;
        } finally {
            finishSavepoint("archive_edit", successful);
        }
    }

    void saveDeletion(ArchiveMessageSnapshot snapshot, ArchiveMessageSnapshot key, String sourceEventId, long deletedAt) throws Exception {
        database.executeFast("SAVEPOINT archive_delete").stepThis().dispose();
        boolean successful = false;
        try {
            if (snapshot != null) saveMessage(snapshot);
            SQLitePreparedStatement event = database.executeFast("INSERT OR IGNORE INTO archive_deletion_events(" +
                    "account_environment,account_id,dialog_id,topic_id,message_id,source_event_id,deleted_at,saved_at) VALUES(?,?,?,?,?,?,?,?)");
            try {
                bindKey(event, key);
                event.bindString(6, sourceEventId);
                event.bindLong(7, deletedAt);
                event.bindLong(8, System.currentTimeMillis() / 1000L);
                event.step();
            } finally {
                event.dispose();
            }
            long stableDeletedAt = deletionEventTime(key, sourceEventId);
            SQLitePreparedStatement mark = database.executeFast("UPDATE archive_messages SET is_deleted=1, deleted_at=MAX(deleted_at, ?) " +
                    "WHERE account_environment=? AND account_id=? AND dialog_id=? AND topic_id=? AND message_id=?");
            try {
                mark.bindLong(1, stableDeletedAt);
                mark.bindInteger(2, key.accountEnvironment);
                mark.bindLong(3, key.accountId);
                mark.bindLong(4, key.dialogId);
                mark.bindLong(5, key.topicId);
                mark.bindInteger(6, key.messageId);
                mark.step();
            } finally {
                mark.dispose();
            }
            successful = true;
        } finally {
            finishSavepoint("archive_delete", successful);
        }
    }

    private long deletionEventTime(ArchiveMessageSnapshot key, String sourceEventId) throws Exception {
        SQLiteCursor cursor = database.queryFinalized("SELECT deleted_at FROM archive_deletion_events WHERE " +
                        "account_environment=? AND account_id=? AND dialog_id=? AND topic_id=? AND message_id=? " +
                        "AND source_event_id=?",
                key.accountEnvironment, key.accountId, key.dialogId, key.topicId, key.messageId, sourceEventId);
        try {
            if (!cursor.next()) throw new IllegalStateException("Deletion event was not persisted");
            return cursor.longValue(0);
        } finally {
            cursor.dispose();
        }
    }

    private void finishSavepoint(String name, boolean successful) throws Exception {
        if (!successful) {
            database.executeFast("ROLLBACK TO " + name).stepThis().dispose();
        }
        database.executeFast("RELEASE " + name).stepThis().dispose();
    }

    private void saveRevision(ArchiveMessageSnapshot snapshot) throws Exception {
        SQLitePreparedStatement statement = database.executeFast("INSERT OR IGNORE INTO archive_message_revisions(" +
                "account_environment,account_id,dialog_id,topic_id,message_id,sender_id,message_date,edit_date,saved_at," +
                "text,entities_json,message_type,reply_to_message_id,grouped_id,raw_format_version,raw_payload,content_hash) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
        try {
            bindSnapshot(statement, snapshot, true);
            statement.step();
        } finally {
            statement.dispose();
        }
    }

    private static void bindSnapshot(SQLitePreparedStatement statement, ArchiveMessageSnapshot s, boolean revision) throws Exception {
        statement.bindInteger(1, s.accountEnvironment);
        statement.bindLong(2, s.accountId);
        statement.bindLong(3, s.dialogId);
        statement.bindLong(4, s.topicId);
        statement.bindInteger(5, s.messageId);
        statement.bindLong(6, s.senderId);
        statement.bindInteger(7, s.messageDate);
        statement.bindInteger(8, s.editDate);
        statement.bindLong(9, s.savedAt);
        statement.bindString(10, s.text);
        statement.bindString(11, s.entitiesJson);
        statement.bindString(12, s.messageType);
        statement.bindInteger(13, s.replyToMessageId);
        statement.bindLong(14, s.groupedId);
        statement.bindInteger(15, s.rawFormatVersion);
        byte[] raw = s.copyRawPayload();
        if (raw == null) statement.bindNull(16); else statement.bindByteBuffer(16, ByteBuffer.wrap(raw));
        statement.bindString(17, s.contentHash);
    }

    private static void bindKey(SQLitePreparedStatement statement, ArchiveMessageSnapshot key) throws Exception {
        statement.bindInteger(1, key.accountEnvironment);
        statement.bindLong(2, key.accountId);
        statement.bindLong(3, key.dialogId);
        statement.bindLong(4, key.topicId);
        statement.bindInteger(5, key.messageId);
    }

    ArrayList<ArchiveMessageRecord> listDeleted(int accountEnvironment, long accountId, long dialogId,
                                                 long topicId) throws Exception {
        String topicClause = topicId < 0 ? "" : " AND topic_id=?";
        Object[] args = topicId < 0
                ? new Object[]{accountEnvironment, accountId, dialogId}
                : new Object[]{accountEnvironment, accountId, dialogId, topicId};
        SQLiteCursor cursor = database.queryFinalized("SELECT account_environment,account_id,dialog_id,topic_id,message_id," +
                "sender_id,message_date,edit_date,saved_at,text,message_type,is_deleted,deleted_at " +
                "FROM archive_messages WHERE account_environment=? AND account_id=? AND dialog_id=? AND is_deleted=1" +
                topicClause + " ORDER BY deleted_at DESC,message_date DESC,message_id DESC", args);
        try {
            ArrayList<ArchiveMessageRecord> result = new ArrayList<>();
            while (cursor.next()) {
                result.add(readRecord(cursor, "", 0));
            }
            return result;
        } finally {
            cursor.dispose();
        }
    }

    ArrayList<ArchiveMessageRecord> listEdited(int accountEnvironment, long accountId, long dialogId,
                                                long topicId) throws Exception {
        String topicClause = topicId < 0 ? "" : " AND m.topic_id=?";
        Object[] args = topicId < 0
                ? new Object[]{accountEnvironment, accountId, dialogId}
                : new Object[]{accountEnvironment, accountId, dialogId, topicId};
        SQLiteCursor cursor = database.queryFinalized("SELECT m.account_environment,m.account_id,m.dialog_id,m.topic_id,m.message_id," +
                "m.sender_id,m.message_date,m.edit_date,m.saved_at,m.text,m.message_type,m.is_deleted,m.deleted_at," +
                "(SELECT r.text FROM archive_message_revisions r WHERE r.account_environment=m.account_environment " +
                "AND r.account_id=m.account_id AND r.dialog_id=m.dialog_id AND r.topic_id=m.topic_id AND r.message_id=m.message_id " +
                "ORDER BY r.saved_at DESC,r.revision_id DESC LIMIT 1)," +
                "(SELECT COUNT(*) FROM archive_message_revisions r WHERE r.account_environment=m.account_environment " +
                "AND r.account_id=m.account_id AND r.dialog_id=m.dialog_id AND r.topic_id=m.topic_id AND r.message_id=m.message_id) " +
                "FROM archive_messages m WHERE m.account_environment=? AND m.account_id=? AND m.dialog_id=?" + topicClause +
                " AND EXISTS(SELECT 1 FROM archive_message_revisions r WHERE r.account_environment=m.account_environment " +
                "AND r.account_id=m.account_id AND r.dialog_id=m.dialog_id AND r.topic_id=m.topic_id AND r.message_id=m.message_id) " +
                "ORDER BY m.edit_date DESC,m.saved_at DESC,m.message_id DESC", args);
        try {
            ArrayList<ArchiveMessageRecord> result = new ArrayList<>();
            while (cursor.next()) {
                result.add(readRecord(cursor, cursor.stringValue(13), cursor.intValue(14)));
            }
            return result;
        } finally {
            cursor.dispose();
        }
    }

    ArrayList<ArchiveMessageRecord> messageHistory(int accountEnvironment, long accountId, long dialogId,
                                                    long topicId, int messageId) throws Exception {
        ArrayList<ArchiveMessageRecord> result = new ArrayList<>();
        SQLiteCursor revisions = database.queryFinalized("SELECT account_environment,account_id,dialog_id,topic_id,message_id," +
                        "sender_id,message_date,edit_date,saved_at,text,message_type,0,0,raw_format_version,raw_payload " +
                        "FROM archive_message_revisions WHERE " +
                        "account_environment=? AND account_id=? AND dialog_id=? AND topic_id=? AND message_id=? " +
                        "ORDER BY saved_at ASC,revision_id ASC",
                accountEnvironment, accountId, dialogId, topicId, messageId);
        try {
            while (revisions.next()) {
                result.add(readHistoryRecord(revisions, 0));
            }
        } finally {
            revisions.dispose();
        }
        SQLiteCursor current = database.queryFinalized("SELECT account_environment,account_id,dialog_id,topic_id,message_id," +
                        "sender_id,message_date,edit_date,saved_at,text,message_type,is_deleted,deleted_at,raw_format_version,raw_payload " +
                        "FROM archive_messages WHERE " +
                        "account_environment=? AND account_id=? AND dialog_id=? AND topic_id=? AND message_id=?",
                accountEnvironment, accountId, dialogId, topicId, messageId);
        try {
            if (current.next() && !result.isEmpty()) {
                result.add(readHistoryRecord(current, result.size()));
            }
        } finally {
            current.dispose();
        }
        return result;
    }

    private static ArchiveMessageRecord readRecord(SQLiteCursor cursor, String previousText, int revisionCount) throws Exception {
        return new ArchiveMessageRecord(cursor.intValue(0), cursor.longValue(1), cursor.longValue(2),
                cursor.longValue(3), cursor.intValue(4), cursor.longValue(5), cursor.intValue(6),
                cursor.intValue(7), cursor.longValue(8), cursor.stringValue(9), previousText,
                cursor.stringValue(10), cursor.intValue(11) != 0, cursor.longValue(12), revisionCount,
                0, null);
    }

    private static ArchiveMessageRecord readHistoryRecord(SQLiteCursor cursor, int revisionCount) throws Exception {
        return new ArchiveMessageRecord(cursor.intValue(0), cursor.longValue(1), cursor.longValue(2),
                cursor.longValue(3), cursor.intValue(4), cursor.longValue(5), cursor.intValue(6),
                cursor.intValue(7), cursor.longValue(8), cursor.stringValue(9), "",
                cursor.stringValue(10), cursor.intValue(11) != 0, cursor.longValue(12), revisionCount,
                cursor.intValue(13), cursor.byteArrayValue(14));
    }

    int count(String table) throws Exception {
        SQLiteCursor cursor = database.queryFinalized("SELECT COUNT(*) FROM " + table);
        try {
            return cursor.next() ? cursor.intValue(0) : 0;
        } finally {
            cursor.dispose();
        }
    }

    String messageHash(ArchiveMessageSnapshot key) throws Exception {
        SQLiteCursor cursor = database.queryFinalized("SELECT content_hash FROM archive_messages WHERE " +
                        "account_environment=? AND account_id=? AND dialog_id=? AND topic_id=? AND message_id=?",
                key.accountEnvironment, key.accountId, key.dialogId, key.topicId, key.messageId);
        try {
            return cursor.next() ? cursor.stringValue(0) : null;
        } finally {
            cursor.dispose();
        }
    }

    boolean isDeleted(ArchiveMessageSnapshot key) throws Exception {
        SQLiteCursor cursor = database.queryFinalized("SELECT is_deleted FROM archive_messages WHERE " +
                        "account_environment=? AND account_id=? AND dialog_id=? AND topic_id=? AND message_id=?",
                key.accountEnvironment, key.accountId, key.dialogId, key.topicId, key.messageId);
        try {
            return cursor.next() && cursor.intValue(0) != 0;
        } finally {
            cursor.dispose();
        }
    }

    long messageDeletedAt(ArchiveMessageSnapshot key) throws Exception {
        SQLiteCursor cursor = database.queryFinalized("SELECT deleted_at FROM archive_messages WHERE " +
                        "account_environment=? AND account_id=? AND dialog_id=? AND topic_id=? AND message_id=?",
                key.accountEnvironment, key.accountId, key.dialogId, key.topicId, key.messageId);
        try {
            return cursor.next() ? cursor.longValue(0) : 0;
        } finally {
            cursor.dispose();
        }
    }

    boolean hasRevision(ArchiveMessageSnapshot revision) throws Exception {
        SQLiteCursor cursor = database.queryFinalized("SELECT 1 FROM archive_message_revisions WHERE " +
                        "account_environment=? AND account_id=? AND dialog_id=? AND topic_id=? AND message_id=? " +
                        "AND content_hash=? LIMIT 1",
                revision.accountEnvironment, revision.accountId, revision.dialogId, revision.topicId,
                revision.messageId, revision.contentHash);
        try {
            return cursor.next();
        } finally {
            cursor.dispose();
        }
    }
}
