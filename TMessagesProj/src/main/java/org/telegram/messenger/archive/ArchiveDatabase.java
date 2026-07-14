package org.telegram.messenger.archive;

import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;

import java.io.File;

/** Owns archive.db and refuses unknown newer schemas without deleting user data. */
final class ArchiveDatabase {
    static final class UnsupportedSchemaException extends Exception {
        UnsupportedSchemaException(int version) {
            super("Unsupported local archive schema version " + version);
        }
    }

    private final SQLiteDatabase database;

    ArchiveDatabase(File file) throws Exception {
        File parent = file.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            throw new SQLiteException("Unable to create local archive directory");
        }
        database = new SQLiteDatabase(file.getAbsolutePath());
        try {
            int version = database.executeInt("PRAGMA user_version");
            if (version > ArchiveSchema.DATABASE_SCHEMA_VERSION) {
                throw new UnsupportedSchemaException(version);
            }
            if (version == 0) {
                createVersionOne();
                version = 1;
            }
            if (version == 1) {
                migrateToVersionTwo();
            }
        } catch (Exception e) {
            database.close();
            throw e;
        }
    }

    SQLiteDatabase sqlite() {
        return database;
    }

    void close() {
        database.close();
    }

    private void createVersionOne() throws Exception {
        database.executeFast("SAVEPOINT archive_schema_v1").stepThis().dispose();
        boolean successful = false;
        try {
            database.executeFast(ArchiveSchema.CREATE_MESSAGES).stepThis().dispose();
            database.executeFast(ArchiveSchema.CREATE_REVISIONS).stepThis().dispose();
            database.executeFast(ArchiveSchema.CREATE_DELETIONS).stepThis().dispose();
            database.executeFast(ArchiveSchema.CREATE_METADATA).stepThis().dispose();
            database.executeFast(ArchiveSchema.CREATE_INDEXES[0]).stepThis().dispose();
            database.executeFast(ArchiveSchema.CREATE_INDEXES[1]).stepThis().dispose();
            database.executeFast(ArchiveSchema.CREATE_INDEXES[2]).stepThis().dispose();
            database.executeFast(ArchiveSchema.CREATE_INDEXES[3]).stepThis().dispose();
            database.executeFast(ArchiveSchema.CREATE_INDEXES[4]).stepThis().dispose();
            database.executeFast("INSERT OR REPLACE INTO archive_metadata(key, value) VALUES('database_schema_version', '1')").stepThis().dispose();
            database.executeFast("PRAGMA user_version = 1").stepThis().dispose();
            successful = true;
        } finally {
            if (!successful) {
                database.executeFast("ROLLBACK TO archive_schema_v1").stepThis().dispose();
            }
            database.executeFast("RELEASE archive_schema_v1").stepThis().dispose();
        }
    }

    private void migrateToVersionTwo() throws Exception {
        database.executeFast("SAVEPOINT archive_schema_v2").stepThis().dispose();
        boolean successful = false;
        try {
            database.executeFast(ArchiveSchema.CREATE_MEDIA).stepThis().dispose();
            database.executeFast(ArchiveSchema.CREATE_MESSAGE_MEDIA).stepThis().dispose();
            database.executeFast(ArchiveSchema.CREATE_INDEXES[5]).stepThis().dispose();
            database.executeFast(ArchiveSchema.CREATE_INDEXES[6]).stepThis().dispose();
            database.executeFast(ArchiveSchema.CREATE_INDEXES[7]).stepThis().dispose();
            database.executeFast("INSERT OR REPLACE INTO archive_metadata(key, value) VALUES('database_schema_version', '2')").stepThis().dispose();
            database.executeFast("PRAGMA user_version = 2").stepThis().dispose();
            successful = true;
        } finally {
            if (!successful) database.executeFast("ROLLBACK TO archive_schema_v2").stepThis().dispose();
            database.executeFast("RELEASE archive_schema_v2").stepThis().dispose();
        }
    }
}
