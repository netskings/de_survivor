package org.telegram.messenger.archive;

import android.util.Base64;

import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** The only component that issues SQL against archive.db. */
class ArchiveRepository {
    enum TransferTable { MESSAGES, REVISIONS, DELETIONS, METADATA, MEDIA }

    static final class ExportCounts {
        final long messages;
        final long revisions;
        final long deletions;
        final long metadata;
        final long mediaLinks;
        final long mediaFiles;
        final long mediaBytes;

        ExportCounts(long messages, long revisions, long deletions, long metadata,
                     long mediaLinks, long mediaFiles, long mediaBytes) {
            this.messages = messages;
            this.revisions = revisions;
            this.deletions = deletions;
            this.metadata = metadata;
            this.mediaLinks = mediaLinks;
            this.mediaFiles = mediaFiles;
            this.mediaBytes = mediaBytes;
        }

        long total() {
            return messages + revisions + deletions + metadata + mediaLinks;
        }
    }

    interface ExportRowSink {
        void accept(JSONObject row) throws Exception;
    }

    interface ImportSource {
        void importInto(ArchiveRepository repository) throws Exception;
    }

    private static final String VISIBLE_REVISION = " AND NOT (r.content_hash=m.content_hash AND r.revision_id=(" +
            "SELECT MAX(r2.revision_id) FROM archive_message_revisions r2 WHERE " +
            "r2.account_environment=m.account_environment AND r2.account_id=m.account_id " +
            "AND r2.dialog_id=m.dialog_id AND r2.topic_id=m.topic_id AND r2.message_id=m.message_id)) ";

    private final SQLiteDatabase database;

    ArchiveRepository(ArchiveDatabase database) {
        this.database = database.sqlite();
    }

    boolean linkMedia(ArchiveMediaDescriptor descriptor, String contentHash, long sizeBytes,
                      String relativePath) throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        return linkMedia(descriptor, contentHash, sizeBytes, relativePath, now, now);
    }

    private boolean linkMedia(ArchiveMediaDescriptor descriptor, String contentHash, long sizeBytes,
                              String relativePath, long createdAt, long lastUsedAt) throws Exception {
        database.executeFast("SAVEPOINT archive_media_link").stepThis().dispose();
        boolean successful = false;
        try {
            long mediaId = mediaId(contentHash);
            if (mediaId == 0) {
                if (totalMediaBytes() + sizeBytes > ArchiveMediaSettings.maxTotalBytes()) return false;
                SQLitePreparedStatement insert = database.executeFast("INSERT OR IGNORE INTO archive_media(" +
                        "content_hash,mime_type,original_name,size_bytes,local_path,media_type,copy_state,created_at,last_used_at) " +
                        "VALUES(?,?,?,?,?,?,1,?,?)");
                try {
                    insert.bindString(1, contentHash);
                    insert.bindString(2, descriptor.mimeType);
                    insert.bindString(3, descriptor.originalName);
                    insert.bindLong(4, sizeBytes);
                    insert.bindString(5, relativePath);
                    insert.bindString(6, descriptor.mediaType);
                    insert.bindLong(7, createdAt);
                    insert.bindLong(8, lastUsedAt);
                    insert.step();
                } finally {
                    insert.dispose();
                }
                mediaId = mediaId(contentHash);
            }
            if (mediaId == 0) return false;
            SQLitePreparedStatement link = database.executeFast("INSERT OR REPLACE INTO archive_message_media(" +
                    "account_environment,account_id,dialog_id,topic_id,message_id,media_id,position,role) " +
                    "VALUES(?,?,?,?,?,?,?,?)");
            try {
                link.bindInteger(1, descriptor.accountEnvironment);
                link.bindLong(2, descriptor.accountId);
                link.bindLong(3, descriptor.dialogId);
                link.bindLong(4, descriptor.topicId);
                link.bindInteger(5, descriptor.messageId);
                link.bindLong(6, mediaId);
                link.bindInteger(7, descriptor.position);
                link.bindString(8, descriptor.role);
                link.step();
            } finally {
                link.dispose();
            }
            SQLitePreparedStatement touch = database.executeFast("UPDATE archive_media SET " +
                    "created_at=MIN(created_at,?),last_used_at=MAX(last_used_at,?) WHERE media_id=?");
            try {
                touch.bindLong(1, createdAt);
                touch.bindLong(2, lastUsedAt);
                touch.bindLong(3, mediaId);
                touch.step();
            } finally {
                touch.dispose();
            }
            successful = true;
            return true;
        } finally {
            finishSavepoint("archive_media_link", successful);
        }
    }

    String mediaPath(int accountEnvironment, long accountId, long dialogId,
                     long topicId, int messageId) throws Exception {
        SQLiteCursor cursor = database.queryFinalized("SELECT m.media_id,m.local_path FROM archive_media m " +
                        "JOIN archive_message_media mm ON mm.media_id=m.media_id WHERE " +
                        "mm.account_environment=? AND mm.account_id=? AND mm.dialog_id=? AND mm.topic_id=? " +
                        "AND mm.message_id=? AND m.copy_state=1 ORDER BY mm.position,mm.role LIMIT 1",
                accountEnvironment, accountId, dialogId, topicId, messageId);
        try {
            if (!cursor.next()) return null;
            long mediaId = cursor.longValue(0);
            String path = cursor.stringValue(1);
            database.executeFast("UPDATE archive_media SET last_used_at=" +
                    (System.currentTimeMillis() / 1000L) + " WHERE media_id=" + mediaId).stepThis().dispose();
            return path;
        } finally {
            cursor.dispose();
        }
    }

    private long mediaId(String contentHash) throws Exception {
        SQLiteCursor cursor = database.queryFinalized(
                "SELECT media_id FROM archive_media WHERE content_hash=? LIMIT 1", contentHash);
        try {
            return cursor.next() ? cursor.longValue(0) : 0;
        } finally {
            cursor.dispose();
        }
    }

    boolean hasMediaHash(String contentHash) {
        try {
            return mediaId(contentHash) != 0;
        } catch (Throwable e) {
            return true;
        }
    }

    long totalMediaBytes() throws Exception {
        SQLiteCursor cursor = database.queryFinalized(
                "SELECT COALESCE(SUM(size_bytes),0) FROM archive_media WHERE copy_state=1");
        try {
            return cursor.next() ? cursor.longValue(0) : 0;
        } finally {
            cursor.dispose();
        }
    }

    ArrayList<String> evictMediaOlderThan(long cutoffSeconds) throws Exception {
        ArrayList<Long> ids = new ArrayList<>();
        ArrayList<String> paths = new ArrayList<>();
        SQLiteCursor cursor = database.queryFinalized(
                "SELECT media_id,local_path FROM archive_media WHERE last_used_at<?", cutoffSeconds);
        try {
            while (cursor.next()) {
                ids.add(cursor.longValue(0));
                paths.add(cursor.stringValue(1));
            }
        } finally {
            cursor.dispose();
        }
        if (ids.isEmpty()) return paths;
        database.executeFast("SAVEPOINT archive_media_retention").stepThis().dispose();
        boolean successful = false;
        try {
            for (Long id : ids) {
                database.executeFast("DELETE FROM archive_message_media WHERE media_id=" + id).stepThis().dispose();
                database.executeFast("DELETE FROM archive_media WHERE media_id=" + id).stepThis().dispose();
            }
            successful = true;
        } finally {
            finishSavepoint("archive_media_retention", successful);
        }
        return paths;
    }

    ExportCounts exportCounts(ArchiveExportOptions options) throws Exception {
        long[] media = options.includeMedia ? mediaCountsForExport(options) : new long[3];
        return new ExportCounts(countForExport("archive_messages", options),
                countForExport("archive_message_revisions", options),
                countForExport("archive_deletion_events", options), count("archive_metadata"),
                media[0], media[1], media[2]);
    }

    private long[] mediaCountsForExport(ArchiveExportOptions options) throws Exception {
        String where = options.scope == ArchiveExportOptions.Scope.ACCOUNT
                ? " WHERE mm.account_environment=? AND mm.account_id=?" : "";
        String sql = "SELECT COUNT(*),COUNT(DISTINCT m.media_id)," +
                "COALESCE((SELECT SUM(x.size_bytes) FROM (SELECT DISTINCT m2.media_id,m2.size_bytes " +
                "FROM archive_message_media mm2 JOIN archive_media m2 ON m2.media_id=mm2.media_id" +
                (options.scope == ArchiveExportOptions.Scope.ACCOUNT
                        ? " WHERE mm2.account_environment=? AND mm2.account_id=?" : "") + ") x),0) " +
                "FROM archive_message_media mm JOIN archive_media m ON m.media_id=mm.media_id" + where;
        SQLiteCursor cursor = options.scope == ArchiveExportOptions.Scope.ACCOUNT
                ? database.queryFinalized(sql, options.accountEnvironment, options.accountId,
                options.accountEnvironment, options.accountId)
                : database.queryFinalized(sql);
        try {
            return cursor.next() ? new long[]{cursor.longValue(0), cursor.longValue(1), cursor.longValue(2)} : new long[3];
        } finally {
            cursor.dispose();
        }
    }

    List<ArchiveAccountIdentity> exportAccounts(ArchiveExportOptions options) throws Exception {
        if (options.scope == ArchiveExportOptions.Scope.ACCOUNT) {
            ArrayList<ArchiveAccountIdentity> single = new ArrayList<>(1);
            single.add(new ArchiveAccountIdentity(options.accountEnvironment, options.accountId));
            return single;
        }
        LinkedHashSet<ArchiveAccountIdentity> identities = new LinkedHashSet<>();
        String[] tables = {"archive_messages", "archive_message_revisions", "archive_deletion_events"};
        for (String table : tables) {
            SQLiteCursor cursor = database.queryFinalized("SELECT DISTINCT account_environment,account_id FROM "
                    + table + " ORDER BY account_environment,account_id");
            try {
                while (cursor.next()) {
                    identities.add(new ArchiveAccountIdentity(cursor.intValue(0), cursor.longValue(1)));
                }
            } finally {
                cursor.dispose();
            }
        }
        if (options.includeMedia) {
            SQLiteCursor media = database.queryFinalized("SELECT DISTINCT account_environment,account_id " +
                    "FROM archive_message_media ORDER BY account_environment,account_id");
            try {
                while (media.next()) {
                    identities.add(new ArchiveAccountIdentity(media.intValue(0), media.longValue(1)));
                }
            } finally {
                media.dispose();
            }
        }
        return new ArrayList<>(identities);
    }

    void exportRows(TransferTable table, ArchiveExportOptions options, ExportRowSink sink) throws Exception {
        long lastRowId = 0;
        while (true) {
            SQLiteCursor cursor = exportPage(table, options, lastRowId);
            int rows = 0;
            try {
                while (cursor.next()) {
                    lastRowId = cursor.longValue(0);
                    sink.accept(exportRow(table, cursor, options.includeRawPayload, options.includeMedia));
                    rows++;
                }
            } finally {
                cursor.dispose();
            }
            if (rows < ArchiveTransferLimits.PAGE_SIZE) return;
        }
    }

    ArrayList<ArchiveMediaFile> exportMediaFiles(ArchiveExportOptions options) throws Exception {
        String where = options.scope == ArchiveExportOptions.Scope.ACCOUNT
                ? " WHERE mm.account_environment=? AND mm.account_id=?" : "";
        String sql = "SELECT DISTINCT m.content_hash,m.local_path,m.size_bytes FROM archive_media m " +
                "JOIN archive_message_media mm ON mm.media_id=m.media_id" + where + " ORDER BY m.content_hash";
        SQLiteCursor cursor = options.scope == ArchiveExportOptions.Scope.ACCOUNT
                ? database.queryFinalized(sql, options.accountEnvironment, options.accountId)
                : database.queryFinalized(sql);
        try {
            ArrayList<ArchiveMediaFile> files = new ArrayList<>();
            while (cursor.next()) files.add(new ArchiveMediaFile(
                    cursor.stringValue(0), cursor.stringValue(1), cursor.longValue(2)));
            return files;
        } finally {
            cursor.dispose();
        }
    }

    void importTransaction(ImportSource source) throws Exception {
        database.executeFast("SAVEPOINT archive_import").stepThis().dispose();
        boolean successful = false;
        try {
            source.importInto(this);
            successful = true;
        } finally {
            finishSavepoint("archive_import", successful);
        }
    }

    boolean importRow(TransferTable table, JSONObject row, boolean allowRawPayload) throws Exception {
        switch (table) {
            case MESSAGES:
                return mergeMessage(ImportedMessage.from(row, allowRawPayload));
            case REVISIONS:
                mergeRevision(ImportedMessage.from(row, allowRawPayload));
                return false;
            case DELETIONS:
                mergeDeletion(row);
                return false;
            case METADATA:
                mergeMetadata(row);
                return false;
            case MEDIA:
                mergeMedia(row);
                return false;
        }
        return false;
    }

    private long countForExport(String table, ArchiveExportOptions options) throws Exception {
        SQLiteCursor cursor = options.scope == ArchiveExportOptions.Scope.ACCOUNT
                ? database.queryFinalized("SELECT COUNT(*) FROM " + table
                + " WHERE account_environment=? AND account_id=?", options.accountEnvironment, options.accountId)
                : database.queryFinalized("SELECT COUNT(*) FROM " + table);
        try {
            return cursor.next() ? cursor.longValue(0) : 0;
        } finally {
            cursor.dispose();
        }
    }

    private SQLiteCursor exportPage(TransferTable table, ArchiveExportOptions options,
                                    long lastRowId) throws Exception {
        String id;
        String columns;
        String tableName;
        switch (table) {
            case MESSAGES:
                id = "rowid";
                tableName = "archive_messages";
                columns = "rowid,account_environment,account_id,dialog_id,topic_id,message_id,sender_id," +
                        "message_date,edit_date,saved_at,text,entities_json,message_type,reply_to_message_id," +
                        "grouped_id,raw_format_version,raw_payload,content_hash,is_deleted,deleted_at,length(raw_payload)";
                break;
            case REVISIONS:
                id = "revision_id";
                tableName = "archive_message_revisions";
                columns = "revision_id,account_environment,account_id,dialog_id,topic_id,message_id,sender_id," +
                        "message_date,edit_date,saved_at,text,entities_json,message_type,reply_to_message_id," +
                        "grouped_id,raw_format_version,raw_payload,content_hash,length(raw_payload)";
                break;
            case DELETIONS:
                id = "deletion_id";
                tableName = "archive_deletion_events";
                columns = "deletion_id,account_environment,account_id,dialog_id,topic_id,message_id," +
                        "source_event_id,deleted_at,saved_at";
                break;
            case MEDIA:
                id = "mm.rowid";
                tableName = "archive_message_media mm JOIN archive_media m ON m.media_id=mm.media_id";
                columns = "mm.rowid,mm.account_environment,mm.account_id,mm.dialog_id,mm.topic_id,mm.message_id," +
                        "m.content_hash,m.mime_type,m.original_name,m.size_bytes,m.media_type,mm.position,mm.role," +
                        "m.created_at,m.last_used_at";
                break;
            default:
                id = "rowid";
                tableName = "archive_metadata";
                columns = "rowid,key,value";
                break;
        }
        String sql = "SELECT " + columns + " FROM " + tableName + " WHERE " + id + ">?";
        if (table != TransferTable.METADATA && options.scope == ArchiveExportOptions.Scope.ACCOUNT) {
            sql += " AND account_environment=? AND account_id=?";
            sql += " ORDER BY " + id + " LIMIT " + ArchiveTransferLimits.PAGE_SIZE;
            return database.queryFinalized(sql, lastRowId, options.accountEnvironment, options.accountId);
        }
        sql += " ORDER BY " + id + " LIMIT " + ArchiveTransferLimits.PAGE_SIZE;
        return database.queryFinalized(sql, lastRowId);
    }

    private JSONObject exportRow(TransferTable table, SQLiteCursor cursor,
                                 boolean includeRawPayload, boolean includeMedia) throws Exception {
        JSONObject row = new JSONObject();
        if (table == TransferTable.METADATA) {
            String key = cursor.stringValue(1);
            row.put("key", key);
            row.put("value", !includeMedia && "database_schema_version".equals(key)
                    ? "1" : cursor.stringValue(2));
            return row;
        }
        row.put("account_environment", cursor.intValue(1));
        row.put("account_id", cursor.longValue(2));
        row.put("dialog_id", cursor.longValue(3));
        row.put("topic_id", cursor.longValue(4));
        row.put("message_id", cursor.intValue(5));
        if (table == TransferTable.MEDIA) {
            row.put("content_hash", cursor.stringValue(6));
            row.put("mime_type", cursor.stringValue(7));
            row.put("original_name", cursor.stringValue(8));
            row.put("size_bytes", cursor.longValue(9));
            row.put("media_type", cursor.stringValue(10));
            row.put("position", cursor.intValue(11));
            row.put("role", cursor.stringValue(12));
            row.put("created_at", cursor.longValue(13));
            row.put("last_used_at", cursor.longValue(14));
            return row;
        }
        if (table == TransferTable.DELETIONS) {
            row.put("source_event_id", cursor.stringValue(6));
            row.put("deleted_at", cursor.longValue(7));
            row.put("saved_at", cursor.longValue(8));
            return row;
        }
        row.put("sender_id", cursor.longValue(6));
        row.put("message_date", cursor.intValue(7));
        row.put("edit_date", cursor.intValue(8));
        row.put("saved_at", cursor.longValue(9));
        row.put("text", cursor.isNull(10) ? JSONObject.NULL : cursor.stringValue(10));
        row.put("entities_json", cursor.stringValue(11));
        row.put("message_type", cursor.stringValue(12));
        row.put("reply_to_message_id", cursor.intValue(13));
        row.put("grouped_id", cursor.longValue(14));
        row.put("raw_format_version", cursor.intValue(15));
        int rawLengthColumn = table == TransferTable.MESSAGES ? 20 : 18;
        int rawLength = cursor.isNull(rawLengthColumn) ? 0 : cursor.intValue(rawLengthColumn);
        if (rawLength > ArchiveTransferLimits.MAX_RAW_PAYLOAD_BYTES) {
            throw new ArchiveTransferException(ArchiveOperation.ErrorCode.LIMIT_EXCEEDED,
                    "raw payload exceeds export limit");
        }
        if (includeRawPayload && !cursor.isNull(16)) {
            row.put("raw_payload_base64", Base64.encodeToString(cursor.byteArrayValue(16), Base64.NO_WRAP));
        } else {
            row.put("raw_payload_base64", JSONObject.NULL);
        }
        row.put("content_hash", cursor.stringValue(17));
        if (table == TransferTable.MESSAGES) {
            row.put("is_deleted", cursor.intValue(18) != 0);
            row.put("deleted_at", cursor.longValue(19));
        }
        return row;
    }

    private boolean mergeMessage(ImportedMessage imported) throws Exception {
        ImportedMessage current = readImportedMessage(imported);
        if (current == null) {
            insertOrReplaceMessage(imported);
            return false;
        }
        boolean deleted = current.deleted || imported.deleted;
        long deletedAt = Math.max(current.deletedAt, imported.deletedAt);
        if (current.contentHash.equals(imported.contentHash)) {
            SQLitePreparedStatement update = database.executeFast("UPDATE archive_messages SET " +
                    "is_deleted=?,deleted_at=?,raw_format_version=CASE WHEN raw_payload IS NULL AND ? IS NOT NULL " +
                    "THEN ? ELSE raw_format_version END,raw_payload=COALESCE(raw_payload,?) WHERE " +
                    "account_environment=? AND account_id=? AND dialog_id=? AND topic_id=? AND message_id=?");
            try {
                update.bindInteger(1, deleted ? 1 : 0);
                update.bindLong(2, deletedAt);
                bindBlob(update, 3, imported.rawPayload);
                update.bindInteger(4, imported.rawFormatVersion);
                bindBlob(update, 5, imported.rawPayload);
                bindImportedKey(update, 6, imported);
                update.step();
            } finally {
                update.dispose();
            }
            return false;
        }
        boolean importedIsNewer = imported.editDate > 0 && imported.editDate > current.editDate;
        if (importedIsNewer) {
            insertRevision(current);
            imported.deleted = deleted;
            imported.deletedAt = deletedAt;
            insertOrReplaceMessage(imported);
            return false;
        } else {
            boolean ambiguous = !(current.editDate > 0
                    && (imported.editDate == 0 || imported.editDate < current.editDate));
            insertRevision(imported);
            if (deleted != current.deleted || deletedAt != current.deletedAt) {
                SQLitePreparedStatement update = database.executeFast("UPDATE archive_messages SET is_deleted=?,deleted_at=? " +
                        "WHERE account_environment=? AND account_id=? AND dialog_id=? AND topic_id=? AND message_id=?");
                try {
                    update.bindInteger(1, deleted ? 1 : 0);
                    update.bindLong(2, deletedAt);
                    bindImportedKey(update, 3, current);
                    update.step();
                } finally {
                    update.dispose();
                }
            }
            return ambiguous;
        }
    }

    private void mergeRevision(ImportedMessage revision) throws Exception {
        ImportedMessage current = readImportedMessage(revision);
        if (current == null || !current.contentHash.equals(revision.contentHash)) insertRevision(revision);
    }

    private void mergeDeletion(JSONObject row) throws Exception {
        SQLitePreparedStatement insert = database.executeFast("INSERT OR IGNORE INTO archive_deletion_events(" +
                "account_environment,account_id,dialog_id,topic_id,message_id,source_event_id,deleted_at,saved_at) " +
                "VALUES(?,?,?,?,?,?,?,?)");
        try {
            insert.bindInteger(1, row.getInt("account_environment"));
            insert.bindLong(2, row.getLong("account_id"));
            insert.bindLong(3, row.getLong("dialog_id"));
            insert.bindLong(4, row.getLong("topic_id"));
            insert.bindInteger(5, row.getInt("message_id"));
            insert.bindString(6, row.getString("source_event_id"));
            insert.bindLong(7, row.getLong("deleted_at"));
            insert.bindLong(8, row.getLong("saved_at"));
            insert.step();
        } finally {
            insert.dispose();
        }
        SQLitePreparedStatement mark = database.executeFast("UPDATE archive_messages SET is_deleted=1," +
                "deleted_at=MAX(deleted_at,?) WHERE account_environment=? AND account_id=? AND dialog_id=? " +
                "AND topic_id=? AND message_id=?");
        try {
            mark.bindLong(1, row.getLong("deleted_at"));
            mark.bindInteger(2, row.getInt("account_environment"));
            mark.bindLong(3, row.getLong("account_id"));
            mark.bindLong(4, row.getLong("dialog_id"));
            mark.bindLong(5, row.getLong("topic_id"));
            mark.bindInteger(6, row.getInt("message_id"));
            mark.step();
        } finally {
            mark.dispose();
        }
    }

    private void mergeMetadata(JSONObject row) throws Exception {
        SQLitePreparedStatement statement = database.executeFast(
                "INSERT OR IGNORE INTO archive_metadata(key,value) VALUES(?,?)");
        try {
            statement.bindString(1, row.getString("key"));
            statement.bindString(2, row.getString("value"));
            statement.step();
        } finally {
            statement.dispose();
        }
    }

    private void mergeMedia(JSONObject row) throws Exception {
        ArchiveMediaDescriptor descriptor = new ArchiveMediaDescriptor(
                row.getInt("account_environment"), row.getLong("account_id"), row.getLong("dialog_id"),
                row.getLong("topic_id"), row.getInt("message_id"), row.getString("media_type"),
                row.getString("mime_type"), row.getString("original_name"), row.getInt("position"),
                row.getString("role"));
        String hash = row.getString("content_hash");
        if (!linkMedia(descriptor, hash, row.getLong("size_bytes"), "media/" + hash,
                row.getLong("created_at"), row.getLong("last_used_at"))) {
            throw new ArchiveTransferException(ArchiveOperation.ErrorCode.LIMIT_EXCEEDED,
                    "media archive size limit exceeded");
        }
    }

    private ImportedMessage readImportedMessage(ImportedMessage key) throws Exception {
        SQLiteCursor cursor = database.queryFinalized("SELECT account_environment,account_id,dialog_id,topic_id," +
                        "message_id,sender_id,message_date,edit_date,saved_at,text,entities_json,message_type," +
                        "reply_to_message_id,grouped_id,raw_format_version,raw_payload,content_hash,is_deleted,deleted_at " +
                        "FROM archive_messages WHERE account_environment=? AND account_id=? AND dialog_id=? AND topic_id=? AND message_id=?",
                key.accountEnvironment, key.accountId, key.dialogId, key.topicId, key.messageId);
        try {
            return cursor.next() ? ImportedMessage.from(cursor) : null;
        } finally {
            cursor.dispose();
        }
    }

    private void insertOrReplaceMessage(ImportedMessage message) throws Exception {
        SQLitePreparedStatement statement = database.executeFast("INSERT OR REPLACE INTO archive_messages(" +
                "account_environment,account_id,dialog_id,topic_id,message_id,sender_id,message_date,edit_date,saved_at," +
                "text,entities_json,message_type,reply_to_message_id,grouped_id,raw_format_version,raw_payload,content_hash," +
                "is_deleted,deleted_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
        try {
            bindImported(statement, message);
            statement.bindInteger(18, message.deleted ? 1 : 0);
            statement.bindLong(19, message.deletedAt);
            statement.step();
        } finally {
            statement.dispose();
        }
    }

    private void insertRevision(ImportedMessage message) throws Exception {
        SQLitePreparedStatement statement = database.executeFast("INSERT OR IGNORE INTO archive_message_revisions(" +
                "account_environment,account_id,dialog_id,topic_id,message_id,sender_id,message_date,edit_date,saved_at," +
                "text,entities_json,message_type,reply_to_message_id,grouped_id,raw_format_version,raw_payload,content_hash) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
        try {
            bindImported(statement, message);
            statement.step();
        } finally {
            statement.dispose();
        }
    }

    private static void bindImported(SQLitePreparedStatement statement, ImportedMessage message) throws Exception {
        statement.bindInteger(1, message.accountEnvironment);
        statement.bindLong(2, message.accountId);
        statement.bindLong(3, message.dialogId);
        statement.bindLong(4, message.topicId);
        statement.bindInteger(5, message.messageId);
        statement.bindLong(6, message.senderId);
        statement.bindInteger(7, message.messageDate);
        statement.bindInteger(8, message.editDate);
        statement.bindLong(9, message.savedAt);
        if (message.text == null) statement.bindNull(10); else statement.bindString(10, message.text);
        statement.bindString(11, message.entitiesJson);
        statement.bindString(12, message.messageType);
        statement.bindInteger(13, message.replyToMessageId);
        statement.bindLong(14, message.groupedId);
        statement.bindInteger(15, message.rawFormatVersion);
        bindBlob(statement, 16, message.rawPayload);
        statement.bindString(17, message.contentHash);
    }

    private static void bindImportedKey(SQLitePreparedStatement statement, int first,
                                        ImportedMessage key) throws Exception {
        statement.bindInteger(first, key.accountEnvironment);
        statement.bindLong(first + 1, key.accountId);
        statement.bindLong(first + 2, key.dialogId);
        statement.bindLong(first + 3, key.topicId);
        statement.bindInteger(first + 4, key.messageId);
    }

    private static void bindBlob(SQLitePreparedStatement statement, int index, byte[] value) throws Exception {
        if (value == null) {
            statement.bindNull(index);
            return;
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(value.length);
        buffer.put(value).flip();
        statement.bindByteBuffer(index, buffer);
    }

    private static final class ImportedMessage {
        int accountEnvironment;
        long accountId;
        long dialogId;
        long topicId;
        int messageId;
        long senderId;
        int messageDate;
        int editDate;
        long savedAt;
        String text;
        String entitiesJson;
        String messageType;
        int replyToMessageId;
        long groupedId;
        int rawFormatVersion;
        byte[] rawPayload;
        String contentHash;
        boolean deleted;
        long deletedAt;

        static ImportedMessage from(JSONObject row, boolean allowRawPayload) throws Exception {
            ImportedMessage result = new ImportedMessage();
            result.accountEnvironment = row.getInt("account_environment");
            result.accountId = row.getLong("account_id");
            result.dialogId = row.getLong("dialog_id");
            result.topicId = row.getLong("topic_id");
            result.messageId = row.getInt("message_id");
            result.senderId = row.getLong("sender_id");
            result.messageDate = row.getInt("message_date");
            result.editDate = row.getInt("edit_date");
            result.savedAt = row.getLong("saved_at");
            result.text = row.isNull("text") ? null : row.getString("text");
            result.entitiesJson = row.getString("entities_json");
            result.messageType = row.getString("message_type");
            result.replyToMessageId = row.getInt("reply_to_message_id");
            result.groupedId = row.getLong("grouped_id");
            result.rawFormatVersion = row.getInt("raw_format_version");
            if (allowRawPayload && !row.isNull("raw_payload_base64")) {
                result.rawPayload = Base64.decode(row.getString("raw_payload_base64"), Base64.DEFAULT);
            }
            result.contentHash = row.getString("content_hash");
            result.deleted = row.optBoolean("is_deleted", false);
            result.deletedAt = row.optLong("deleted_at", 0);
            return result;
        }

        static ImportedMessage from(SQLiteCursor cursor) throws Exception {
            ImportedMessage result = new ImportedMessage();
            result.accountEnvironment = cursor.intValue(0);
            result.accountId = cursor.longValue(1);
            result.dialogId = cursor.longValue(2);
            result.topicId = cursor.longValue(3);
            result.messageId = cursor.intValue(4);
            result.senderId = cursor.longValue(5);
            result.messageDate = cursor.intValue(6);
            result.editDate = cursor.intValue(7);
            result.savedAt = cursor.longValue(8);
            result.text = cursor.isNull(9) ? null : cursor.stringValue(9);
            result.entitiesJson = cursor.stringValue(10);
            result.messageType = cursor.stringValue(11);
            result.replyToMessageId = cursor.intValue(12);
            result.groupedId = cursor.longValue(13);
            result.rawFormatVersion = cursor.intValue(14);
            result.rawPayload = cursor.isNull(15) ? null : cursor.byteArrayValue(15);
            result.contentHash = cursor.stringValue(16);
            result.deleted = cursor.intValue(17) != 0;
            result.deletedAt = cursor.longValue(18);
            return result;
        }
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
            bindSnapshot(statement, snapshot);
            statement.step();
        } finally {
            statement.dispose();
        }
    }

    void saveEdit(ArchiveMessageSnapshot previous, ArchiveMessageSnapshot current) throws Exception {
        database.executeFast("SAVEPOINT archive_edit").stepThis().dispose();
        boolean successful = false;
        try {
            if (previous != null) {
                if (!previous.contentHash.equals(current.contentHash)) saveRevision(previous);
            } else {
                saveCurrentAsRevision(current);
            }
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
            if (!hasDeletionEvent(key)) {
                SQLitePreparedStatement event = database.executeFast("INSERT INTO archive_deletion_events(" +
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
            }
            long stableDeletedAt = deletionEventTime(key);
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

    private boolean hasDeletionEvent(ArchiveMessageSnapshot key) throws Exception {
        SQLiteCursor cursor = database.queryFinalized("SELECT 1 FROM archive_deletion_events WHERE " +
                        "account_environment=? AND account_id=? AND dialog_id=? AND topic_id=? AND message_id=? LIMIT 1",
                key.accountEnvironment, key.accountId, key.dialogId, key.topicId, key.messageId);
        try {
            return cursor.next();
        } finally {
            cursor.dispose();
        }
    }

    private long deletionEventTime(ArchiveMessageSnapshot key) throws Exception {
        SQLiteCursor cursor = database.queryFinalized("SELECT deleted_at FROM archive_deletion_events WHERE " +
                        "account_environment=? AND account_id=? AND dialog_id=? AND topic_id=? AND message_id=? " +
                        "ORDER BY deletion_id LIMIT 1",
                key.accountEnvironment, key.accountId, key.dialogId, key.topicId, key.messageId);
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
            bindSnapshot(statement, snapshot);
            statement.step();
        } finally {
            statement.dispose();
        }
    }

    private void saveCurrentAsRevision(ArchiveMessageSnapshot current) throws Exception {
        SQLitePreparedStatement statement = database.executeFast("INSERT OR IGNORE INTO archive_message_revisions(" +
                "account_environment,account_id,dialog_id,topic_id,message_id,sender_id,message_date,edit_date,saved_at," +
                "text,entities_json,message_type,reply_to_message_id,grouped_id,raw_format_version,raw_payload,content_hash) " +
                "SELECT account_environment,account_id,dialog_id,topic_id,message_id,sender_id,message_date,edit_date,saved_at," +
                "text,entities_json,message_type,reply_to_message_id,grouped_id,raw_format_version,raw_payload,content_hash " +
                "FROM archive_messages WHERE account_environment=? AND account_id=? AND dialog_id=? AND topic_id=? " +
                "AND message_id=? AND content_hash<>?");
        try {
            statement.bindInteger(1, current.accountEnvironment);
            statement.bindLong(2, current.accountId);
            statement.bindLong(3, current.dialogId);
            statement.bindLong(4, current.topicId);
            statement.bindInteger(5, current.messageId);
            statement.bindString(6, current.contentHash);
            statement.step();
        } finally {
            statement.dispose();
        }
    }

    private static void bindSnapshot(SQLitePreparedStatement statement,
                                     ArchiveMessageSnapshot s) throws Exception {
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
        if (raw == null) {
            statement.bindNull(16);
        } else {
            ByteBuffer rawBuffer = ByteBuffer.allocateDirect(raw.length);
            rawBuffer.put(raw);
            rawBuffer.flip();
            statement.bindByteBuffer(16, rawBuffer);
        }
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
        return listDeleted(accountEnvironment, accountId, dialogId, topicId, false);
    }

    ArrayList<ArchiveMessageRecord> listAllDeleted(int accountEnvironment, long accountId) throws Exception {
        return listDeleted(accountEnvironment, accountId, 0, -1, true);
    }

    private ArrayList<ArchiveMessageRecord> listDeleted(int accountEnvironment, long accountId, long dialogId,
                                                         long topicId, boolean allDialogs) throws Exception {
        String dialogClause = allDialogs ? "" : " AND dialog_id=?";
        String topicClause = !allDialogs && topicId >= 0 ? " AND topic_id=?" : "";
        Object[] args = allDialogs
                ? new Object[]{accountEnvironment, accountId}
                : topicId < 0
                ? new Object[]{accountEnvironment, accountId, dialogId}
                : new Object[]{accountEnvironment, accountId, dialogId, topicId};
        SQLiteCursor cursor = database.queryFinalized("SELECT account_environment,account_id,dialog_id,topic_id,message_id," +
                "sender_id,message_date,edit_date,saved_at,text,message_type,is_deleted,deleted_at," +
                "(SELECT am.local_path FROM archive_message_media mm JOIN archive_media am ON am.media_id=mm.media_id " +
                "WHERE mm.account_environment=archive_messages.account_environment AND mm.account_id=archive_messages.account_id " +
                "AND mm.dialog_id=archive_messages.dialog_id AND mm.topic_id=archive_messages.topic_id " +
                "AND mm.message_id=archive_messages.message_id AND am.copy_state=1 ORDER BY mm.position LIMIT 1) " +
                "FROM archive_messages WHERE account_environment=? AND account_id=?" + dialogClause + " AND is_deleted=1" +
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
        return listEdited(accountEnvironment, accountId, dialogId, topicId, false);
    }

    ArrayList<ArchiveMessageRecord> listAllEdited(int accountEnvironment, long accountId) throws Exception {
        return listEdited(accountEnvironment, accountId, 0, -1, true);
    }

    private ArrayList<ArchiveMessageRecord> listEdited(int accountEnvironment, long accountId, long dialogId,
                                                        long topicId, boolean allDialogs) throws Exception {
        String dialogClause = allDialogs ? "" : " AND m.dialog_id=?";
        String topicClause = !allDialogs && topicId >= 0 ? " AND m.topic_id=?" : "";
        Object[] args = allDialogs
                ? new Object[]{accountEnvironment, accountId}
                : topicId < 0
                ? new Object[]{accountEnvironment, accountId, dialogId}
                : new Object[]{accountEnvironment, accountId, dialogId, topicId};
        SQLiteCursor cursor = database.queryFinalized("SELECT m.account_environment,m.account_id,m.dialog_id,m.topic_id,m.message_id," +
                "m.sender_id,m.message_date,m.edit_date,m.saved_at,m.text,m.message_type,m.is_deleted,m.deleted_at," +
                "(SELECT r.text FROM archive_message_revisions r WHERE r.account_environment=m.account_environment " +
                "AND r.account_id=m.account_id AND r.dialog_id=m.dialog_id AND r.topic_id=m.topic_id AND r.message_id=m.message_id " +
                VISIBLE_REVISION +
                "ORDER BY r.saved_at DESC,r.revision_id DESC LIMIT 1)," +
                "(SELECT COUNT(*) FROM archive_message_revisions r WHERE r.account_environment=m.account_environment " +
                "AND r.account_id=m.account_id AND r.dialog_id=m.dialog_id AND r.topic_id=m.topic_id AND r.message_id=m.message_id " +
                VISIBLE_REVISION + ")," +
                "(SELECT am.local_path FROM archive_message_media mm JOIN archive_media am ON am.media_id=mm.media_id " +
                "WHERE mm.account_environment=m.account_environment AND mm.account_id=m.account_id " +
                "AND mm.dialog_id=m.dialog_id AND mm.topic_id=m.topic_id AND mm.message_id=m.message_id " +
                "AND am.copy_state=1 ORDER BY mm.position LIMIT 1),m.raw_payload " +
                "FROM archive_messages m WHERE m.account_environment=? AND m.account_id=?" + dialogClause + topicClause +
                " AND m.edit_date>0" +
                " AND EXISTS(SELECT 1 FROM archive_message_revisions r WHERE r.account_environment=m.account_environment " +
                "AND r.account_id=m.account_id AND r.dialog_id=m.dialog_id AND r.topic_id=m.topic_id AND r.message_id=m.message_id " +
                VISIBLE_REVISION + ") " +
                "ORDER BY m.edit_date DESC,m.saved_at DESC,m.message_id DESC", args);
        try {
            ArrayList<ArchiveMessageRecord> result = new ArrayList<>();
            while (cursor.next()) {
                if (!isVisibleTelegramEdit(cursor, 16)) continue;
                result.add(readRecord(cursor, cursor.stringValue(13), cursor.intValue(14),
                        mediaFileExists(cursor, 15)));
            }
            return result;
        } finally {
            cursor.dispose();
        }
    }

    ArrayList<ArchiveMessageRecord> messageHistory(int accountEnvironment, long accountId, long dialogId,
                                                    long topicId, int messageId) throws Exception {
        ArrayList<ArchiveMessageRecord> result = new ArrayList<>();
        SQLiteCursor revisions = database.queryFinalized("SELECT r.account_environment,r.account_id,r.dialog_id,r.topic_id,r.message_id," +
                        "r.sender_id,r.message_date,r.edit_date,r.saved_at,r.text,r.message_type,0,0,r.raw_format_version,r.raw_payload " +
                        "FROM archive_message_revisions r JOIN archive_messages m ON " +
                        "m.account_environment=r.account_environment AND m.account_id=r.account_id " +
                        "AND m.dialog_id=r.dialog_id AND m.topic_id=r.topic_id AND m.message_id=r.message_id WHERE " +
                        "r.account_environment=? AND r.account_id=? AND r.dialog_id=? AND r.topic_id=? AND r.message_id=? " +
                        VISIBLE_REVISION +
                        "ORDER BY r.saved_at ASC,r.revision_id ASC",
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

    ArrayList<String> deleteLocalMessage(int accountEnvironment, long accountId, long dialogId,
                                         long topicId, int messageId) throws Exception {
        database.executeFast("SAVEPOINT archive_local_remove").stepThis().dispose();
        boolean successful = false;
        ArrayList<String> orphanedPaths = new ArrayList<>();
        try {
            ArrayList<Long> mediaIds = new ArrayList<>();
            SQLiteCursor media = database.queryFinalized("SELECT media_id FROM archive_message_media WHERE " +
                            "account_environment=? AND account_id=? AND dialog_id=? AND topic_id=? AND message_id=?",
                    accountEnvironment, accountId, dialogId, topicId, messageId);
            try {
                while (media.next()) mediaIds.add(media.longValue(0));
            } finally {
                media.dispose();
            }
            deleteByMessageKey("archive_message_media", accountEnvironment, accountId, dialogId, topicId, messageId);
            deleteByMessageKey("archive_deletion_events", accountEnvironment, accountId, dialogId, topicId, messageId);
            deleteByMessageKey("archive_message_revisions", accountEnvironment, accountId, dialogId, topicId, messageId);
            deleteByMessageKey("archive_messages", accountEnvironment, accountId, dialogId, topicId, messageId);
            for (Long mediaId : mediaIds) {
                SQLiteCursor references = database.queryFinalized(
                        "SELECT 1 FROM archive_message_media WHERE media_id=? LIMIT 1", mediaId);
                boolean referenced;
                try {
                    referenced = references.next();
                } finally {
                    references.dispose();
                }
                if (!referenced) {
                    SQLiteCursor path = database.queryFinalized(
                            "SELECT local_path FROM archive_media WHERE media_id=?", mediaId);
                    try {
                        if (path.next()) orphanedPaths.add(path.stringValue(0));
                    } finally {
                        path.dispose();
                    }
                    database.executeFast("DELETE FROM archive_media WHERE media_id=" + mediaId).stepThis().dispose();
                }
            }
            successful = true;
            return orphanedPaths;
        } finally {
            finishSavepoint("archive_local_remove", successful);
        }
    }

    ArrayList<String> clearArchive(int accountEnvironment, long accountId,
                                   boolean allAccounts) throws Exception {
        database.executeFast("SAVEPOINT archive_clear").stepThis().dispose();
        boolean successful = false;
        ArrayList<String> orphanedPaths = new ArrayList<>();
        try {
            if (allAccounts) {
                collectMediaPaths(orphanedPaths, false);
                database.executeFast("DELETE FROM archive_message_media").stepThis().dispose();
                database.executeFast("DELETE FROM archive_deletion_events").stepThis().dispose();
                database.executeFast("DELETE FROM archive_message_revisions").stepThis().dispose();
                database.executeFast("DELETE FROM archive_messages").stepThis().dispose();
                database.executeFast("DELETE FROM archive_media").stepThis().dispose();
            } else {
                deleteAccountRows("archive_message_media", accountEnvironment, accountId);
                deleteAccountRows("archive_deletion_events", accountEnvironment, accountId);
                deleteAccountRows("archive_message_revisions", accountEnvironment, accountId);
                deleteAccountRows("archive_messages", accountEnvironment, accountId);
                collectMediaPaths(orphanedPaths, true);
                database.executeFast("DELETE FROM archive_media WHERE NOT EXISTS(" +
                        "SELECT 1 FROM archive_message_media mm WHERE mm.media_id=archive_media.media_id)")
                        .stepThis().dispose();
            }
            successful = true;
            return orphanedPaths;
        } finally {
            finishSavepoint("archive_clear", successful);
        }
    }

    private void collectMediaPaths(ArrayList<String> paths, boolean onlyUnreferenced) throws Exception {
        SQLiteCursor cursor = database.queryFinalized("SELECT local_path FROM archive_media" +
                (onlyUnreferenced ? " WHERE NOT EXISTS(SELECT 1 FROM archive_message_media mm " +
                        "WHERE mm.media_id=archive_media.media_id)" : ""));
        try {
            while (cursor.next()) {
                if (!cursor.isNull(0)) paths.add(cursor.stringValue(0));
            }
        } finally {
            cursor.dispose();
        }
    }

    private void deleteAccountRows(String table, int accountEnvironment, long accountId) throws Exception {
        SQLitePreparedStatement statement = database.executeFast("DELETE FROM " + table +
                " WHERE account_environment=? AND account_id=?");
        try {
            statement.bindInteger(1, accountEnvironment);
            statement.bindLong(2, accountId);
            statement.step();
        } finally {
            statement.dispose();
        }
    }

    private void deleteByMessageKey(String table, int accountEnvironment, long accountId,
                                    long dialogId, long topicId, int messageId) throws Exception {
        SQLitePreparedStatement statement = database.executeFast("DELETE FROM " + table + " WHERE " +
                "account_environment=? AND account_id=? AND dialog_id=? AND topic_id=? AND message_id=?");
        try {
            statement.bindInteger(1, accountEnvironment);
            statement.bindLong(2, accountId);
            statement.bindLong(3, dialogId);
            statement.bindLong(4, topicId);
            statement.bindInteger(5, messageId);
            statement.step();
        } finally {
            statement.dispose();
        }
    }

    private static ArchiveMessageRecord readRecord(SQLiteCursor cursor, String previousText, int revisionCount) throws Exception {
        return readRecord(cursor, previousText, revisionCount,
                cursor.getColumnCount() > 13 && mediaFileExists(cursor, 13));
    }

    private static boolean mediaFileExists(SQLiteCursor cursor, int column) throws Exception {
        if (cursor.isNull(column)) return false;
        String relative = cursor.stringValue(column);
        return ArchiveMediaStore.resolveRelativePath(relative).isFile();
    }

    private static boolean isVisibleTelegramEdit(SQLiteCursor cursor, int rawColumn) throws Exception {
        if (cursor.isNull(rawColumn)) return true;
        byte[] raw = cursor.byteArrayValue(rawColumn);
        if (raw == null || raw.length < 4) return true;
        SerializedData data = null;
        try {
            data = new SerializedData(raw);
            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
            return message == null || ArchiveMessageMapper.isVisibleEdit(message);
        } catch (Throwable ignore) {
            // Old/imported payloads may use a raw format unknown to this reader. Preserve them
            // rather than silently hiding a potentially genuine edit.
            return true;
        } finally {
            if (data != null) data.cleanup();
        }
    }

    private static ArchiveMessageRecord readRecord(SQLiteCursor cursor, String previousText, int revisionCount,
                                                   boolean mediaSaved) throws Exception {
        return new ArchiveMessageRecord(cursor.intValue(0), cursor.longValue(1), cursor.longValue(2),
                cursor.longValue(3), cursor.intValue(4), cursor.longValue(5), cursor.intValue(6),
                cursor.intValue(7), cursor.longValue(8), cursor.stringValue(9), previousText,
                cursor.stringValue(10), cursor.intValue(11) != 0, cursor.longValue(12), revisionCount,
                0, null, mediaSaved);
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
