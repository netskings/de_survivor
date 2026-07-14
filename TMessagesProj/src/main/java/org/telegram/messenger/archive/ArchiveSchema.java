package org.telegram.messenger.archive;

/**
 * SQL schema for the independent local message archive.
 * Existing cache4.db anti-recall data is intentionally not migrated here; that migration is a
 * separate phase after this archive has been validated in production.
 */
public final class ArchiveSchema {
    public static final int DATABASE_SCHEMA_VERSION = 2;
    public static final int EXPORT_FORMAT_VERSION = 2;
    public static final int RAW_FORMAT_VERSION = 1;

    private ArchiveSchema() {
    }

    static final String CREATE_MESSAGES = "CREATE TABLE IF NOT EXISTS archive_messages(" +
            "account_environment INTEGER NOT NULL, account_id INTEGER NOT NULL, " +
            "dialog_id INTEGER NOT NULL, topic_id INTEGER NOT NULL, message_id INTEGER NOT NULL, " +
            "sender_id INTEGER NOT NULL, message_date INTEGER NOT NULL, edit_date INTEGER NOT NULL, " +
            "saved_at INTEGER NOT NULL, text TEXT, entities_json TEXT NOT NULL, message_type TEXT NOT NULL, " +
            "reply_to_message_id INTEGER NOT NULL, grouped_id INTEGER NOT NULL, " +
            "raw_format_version INTEGER NOT NULL, raw_payload BLOB, content_hash TEXT NOT NULL, " +
            "is_deleted INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY(account_environment, account_id, dialog_id, topic_id, message_id))";

    static final String CREATE_REVISIONS = "CREATE TABLE IF NOT EXISTS archive_message_revisions(" +
            "revision_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "account_environment INTEGER NOT NULL, account_id INTEGER NOT NULL, " +
            "dialog_id INTEGER NOT NULL, topic_id INTEGER NOT NULL, message_id INTEGER NOT NULL, " +
            "sender_id INTEGER NOT NULL, message_date INTEGER NOT NULL, edit_date INTEGER NOT NULL, " +
            "saved_at INTEGER NOT NULL, text TEXT, entities_json TEXT NOT NULL, message_type TEXT NOT NULL, " +
            "reply_to_message_id INTEGER NOT NULL, grouped_id INTEGER NOT NULL, " +
            "raw_format_version INTEGER NOT NULL, raw_payload BLOB, content_hash TEXT NOT NULL, " +
            "UNIQUE(account_environment, account_id, dialog_id, topic_id, message_id, content_hash))";

    static final String CREATE_DELETIONS = "CREATE TABLE IF NOT EXISTS archive_deletion_events(" +
            "deletion_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "account_environment INTEGER NOT NULL, account_id INTEGER NOT NULL, " +
            "dialog_id INTEGER NOT NULL, topic_id INTEGER NOT NULL, message_id INTEGER NOT NULL, " +
            "source_event_id TEXT NOT NULL, deleted_at INTEGER NOT NULL, saved_at INTEGER NOT NULL, " +
            "UNIQUE(account_environment, account_id, dialog_id, topic_id, message_id, source_event_id))";

    static final String CREATE_METADATA = "CREATE TABLE IF NOT EXISTS archive_metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)";

    static final String CREATE_MEDIA = "CREATE TABLE IF NOT EXISTS archive_media(" +
            "media_id INTEGER PRIMARY KEY AUTOINCREMENT, content_hash TEXT NOT NULL UNIQUE, " +
            "mime_type TEXT NOT NULL, original_name TEXT NOT NULL, size_bytes INTEGER NOT NULL, " +
            "local_path TEXT NOT NULL, media_type TEXT NOT NULL, copy_state INTEGER NOT NULL, " +
            "created_at INTEGER NOT NULL, last_used_at INTEGER NOT NULL)";

    static final String CREATE_MESSAGE_MEDIA = "CREATE TABLE IF NOT EXISTS archive_message_media(" +
            "account_environment INTEGER NOT NULL, account_id INTEGER NOT NULL, dialog_id INTEGER NOT NULL, " +
            "topic_id INTEGER NOT NULL, message_id INTEGER NOT NULL, media_id INTEGER NOT NULL, " +
            "position INTEGER NOT NULL, role TEXT NOT NULL, " +
            "PRIMARY KEY(account_environment,account_id,dialog_id,topic_id,message_id,position,role))";

    static final String[] CREATE_INDEXES = {
            "CREATE INDEX IF NOT EXISTS archive_messages_dialog_date_idx ON archive_messages(account_environment, account_id, dialog_id, message_date DESC)",
            "CREATE INDEX IF NOT EXISTS archive_messages_sender_idx ON archive_messages(account_environment, account_id, sender_id)",
            "CREATE INDEX IF NOT EXISTS archive_messages_deleted_idx ON archive_messages(account_environment, account_id, is_deleted)",
            "CREATE INDEX IF NOT EXISTS archive_messages_saved_idx ON archive_messages(account_environment, account_id, saved_at DESC)",
            "CREATE INDEX IF NOT EXISTS archive_revisions_message_idx ON archive_message_revisions(account_environment, account_id, dialog_id, topic_id, message_id, edit_date)",
            "CREATE INDEX IF NOT EXISTS archive_message_media_message_idx ON archive_message_media(account_environment,account_id,dialog_id,topic_id,message_id)",
            "CREATE INDEX IF NOT EXISTS archive_message_media_media_idx ON archive_message_media(media_id)",
            "CREATE INDEX IF NOT EXISTS archive_media_last_used_idx ON archive_media(last_used_at)"
    };
}
