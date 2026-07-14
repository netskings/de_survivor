package org.telegram.messenger.archive;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.BuildVars;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Versioned, privacy-minimal description of a .tgarchive container. */
public final class ArchiveManifest {
    public static final String FORMAT_NAME = "telegram-local-archive";
    static final int FORMAT_MINOR_VERSION = 0;

    public final String formatVersion;
    public final String minimumReaderVersion;
    public final int databaseSchemaVersion;
    public final int rawFormatVersion;
    public final String createdAt;
    public final String applicationVersion;
    public final int exportedAccountEnvironment;
    public final long exportedAccountId;
    public final String exportScope;
    public final boolean includesRawPayload;
    public final boolean includesMedia;
    public final String checksumAlgorithm;
    public final long messageCount;
    public final long revisionCount;
    public final long deletionEventCount;
    public final long metadataCount;
    public final long mediaFileCount;
    public final long mediaLinkCount;
    public final long mediaBytes;
    public final List<ArchiveAccountIdentity> accounts;

    ArchiveManifest(ArchiveExportOptions options, ArchiveRepository.ExportCounts counts,
                    List<ArchiveAccountIdentity> accounts) {
        formatVersion = (options.includeMedia ? 2 : 1) + "." + FORMAT_MINOR_VERSION;
        minimumReaderVersion = formatVersion;
        // A media-free 1.0 payload only uses the original schema and remains readable by 1.0 clients.
        databaseSchemaVersion = options.includeMedia ? ArchiveSchema.DATABASE_SCHEMA_VERSION : 1;
        rawFormatVersion = ArchiveSchema.RAW_FORMAT_VERSION;
        createdAt = utcNow();
        applicationVersion = BuildVars.BUILD_VERSION_STRING;
        exportedAccountEnvironment = options.scope == ArchiveExportOptions.Scope.ACCOUNT
                ? options.accountEnvironment : -1;
        exportedAccountId = options.scope == ArchiveExportOptions.Scope.ACCOUNT ? options.accountId : 0;
        exportScope = options.scope == ArchiveExportOptions.Scope.ACCOUNT ? "account" : "all_accounts";
        includesRawPayload = options.includeRawPayload;
        includesMedia = options.includeMedia;
        checksumAlgorithm = "SHA-256";
        messageCount = counts.messages;
        revisionCount = counts.revisions;
        deletionEventCount = counts.deletions;
        metadataCount = counts.metadata;
        mediaFileCount = counts.mediaFiles;
        mediaLinkCount = counts.mediaLinks;
        mediaBytes = counts.mediaBytes;
        this.accounts = Collections.unmodifiableList(new ArrayList<>(accounts));
    }

    private ArchiveManifest(String formatVersion, String minimumReaderVersion,
                            int databaseSchemaVersion, int rawFormatVersion, String createdAt,
                            String applicationVersion, int exportedAccountEnvironment,
                            long exportedAccountId, String exportScope, boolean includesRawPayload,
                            boolean includesMedia, String checksumAlgorithm, long messageCount,
                            long revisionCount, long deletionEventCount, long metadataCount,
                            long mediaFileCount, long mediaLinkCount, long mediaBytes,
                            List<ArchiveAccountIdentity> accounts) {
        this.formatVersion = formatVersion;
        this.minimumReaderVersion = minimumReaderVersion;
        this.databaseSchemaVersion = databaseSchemaVersion;
        this.rawFormatVersion = rawFormatVersion;
        this.createdAt = createdAt;
        this.applicationVersion = applicationVersion;
        this.exportedAccountEnvironment = exportedAccountEnvironment;
        this.exportedAccountId = exportedAccountId;
        this.exportScope = exportScope;
        this.includesRawPayload = includesRawPayload;
        this.includesMedia = includesMedia;
        this.checksumAlgorithm = checksumAlgorithm;
        this.messageCount = messageCount;
        this.revisionCount = revisionCount;
        this.deletionEventCount = deletionEventCount;
        this.metadataCount = metadataCount;
        this.mediaFileCount = mediaFileCount;
        this.mediaLinkCount = mediaLinkCount;
        this.mediaBytes = mediaBytes;
        this.accounts = Collections.unmodifiableList(new ArrayList<>(accounts));
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("format_name", FORMAT_NAME);
        json.put("format_version", formatVersion);
        json.put("minimum_reader_version", minimumReaderVersion);
        json.put("database_schema_version", databaseSchemaVersion);
        json.put("raw_format_version", rawFormatVersion);
        json.put("created_at", createdAt);
        json.put("application_version", applicationVersion);
        json.put("exported_account_environment", exportedAccountEnvironment);
        json.put("exported_account_id", exportedAccountId);
        json.put("export_scope", exportScope);
        json.put("includes_raw_payload", includesRawPayload);
        json.put("checksum_algorithm", checksumAlgorithm);
        json.put("message_count", messageCount);
        json.put("revision_count", revisionCount);
        json.put("deletion_event_count", deletionEventCount);
        json.put("metadata_count", metadataCount);
        if (includesMedia) {
            json.put("includes_media", true);
            json.put("media_file_count", mediaFileCount);
            json.put("media_link_count", mediaLinkCount);
            json.put("media_bytes", mediaBytes);
        }
        JSONArray identities = new JSONArray();
        for (ArchiveAccountIdentity account : accounts) {
            JSONObject identity = new JSONObject();
            identity.put("account_environment", account.accountEnvironment);
            identity.put("account_id", account.accountId);
            identities.put(identity);
        }
        json.put("accounts", identities);
        json.put("required_extensions", new JSONArray());
        return json;
    }

    static ArchiveManifest parse(JSONObject json) throws ArchiveTransferException {
        try {
            assertString(json, "format_name");
            if (!FORMAT_NAME.equals(json.getString("format_name"))) invalid("format name");
            String formatVersion = requireString(json, "format_version");
            String minimumReader = requireString(json, "minimum_reader_version");
            int[] writer = parseVersion(formatVersion);
            int[] minimum = parseVersion(minimumReader);
            if (writer[0] < 1 || writer[0] > ArchiveSchema.EXPORT_FORMAT_VERSION) {
                throw new ArchiveTransferException(ArchiveOperation.ErrorCode.UNSUPPORTED_VERSION,
                        "unsupported archive major version");
            }
            if (minimum[0] > ArchiveSchema.EXPORT_FORMAT_VERSION
                    || (minimum[0] == ArchiveSchema.EXPORT_FORMAT_VERSION
                    && minimum[1] > FORMAT_MINOR_VERSION)) {
                throw new ArchiveTransferException(ArchiveOperation.ErrorCode.UNSUPPORTED_VERSION,
                        "reader version is too old");
            }
            JSONArray requiredExtensions = json.optJSONArray("required_extensions");
            if (requiredExtensions != null && requiredExtensions.length() != 0) {
                throw new ArchiveTransferException(ArchiveOperation.ErrorCode.UNSUPPORTED_VERSION,
                        "unsupported required extension");
            }
            int databaseSchema = requireInt(json, "database_schema_version");
            int rawFormat = requireInt(json, "raw_format_version");
            String createdAt = requireString(json, "created_at");
            parseUtc(createdAt);
            String applicationVersion = requireString(json, "application_version");
            int environment = requireInt(json, "exported_account_environment");
            long accountId = requireLong(json, "exported_account_id");
            String scope = requireString(json, "export_scope");
            if (!"account".equals(scope) && !"all_accounts".equals(scope)) invalid("export scope");
            boolean includesRaw = requireBoolean(json, "includes_raw_payload");
            boolean includesMedia = writer[0] >= 2
                    ? requireBoolean(json, "includes_media")
                    : json.optBoolean("includes_media", false);
            if (writer[0] == 1 && includesMedia) invalid("media is unsupported in format 1.0");
            if (writer[0] == 2 && !includesMedia) invalid("format 2.0 requires media");
            String checksum = requireString(json, "checksum_algorithm");
            if (!"SHA-256".equals(checksum)) invalid("checksum algorithm");
            long messageCount = requireNonNegative(json, "message_count");
            long revisionCount = requireNonNegative(json, "revision_count");
            long deletionCount = requireNonNegative(json, "deletion_event_count");
            long metadataCount = json.has("metadata_count") ? requireNonNegative(json, "metadata_count") : 0;
            long mediaFileCount = writer[0] >= 2 ? requireNonNegative(json, "media_file_count") : 0;
            long mediaLinkCount = writer[0] >= 2 ? requireNonNegative(json, "media_link_count") : 0;
            long mediaBytes = writer[0] >= 2 ? requireNonNegative(json, "media_bytes") : 0;
            if (messageCount > ArchiveTransferLimits.MAX_RECORDS
                    || revisionCount > ArchiveTransferLimits.MAX_RECORDS
                    || deletionCount > ArchiveTransferLimits.MAX_RECORDS
                    || metadataCount > ArchiveTransferLimits.MAX_RECORDS
                    || mediaLinkCount > ArchiveTransferLimits.MAX_RECORDS
                    || messageCount > ArchiveTransferLimits.MAX_RECORDS - revisionCount
                    || messageCount + revisionCount > ArchiveTransferLimits.MAX_RECORDS - deletionCount
                    || messageCount + revisionCount + deletionCount > ArchiveTransferLimits.MAX_RECORDS - metadataCount
                    || messageCount + revisionCount + deletionCount + metadataCount
                    > ArchiveTransferLimits.MAX_RECORDS - mediaLinkCount) {
                throw new ArchiveTransferException(ArchiveOperation.ErrorCode.LIMIT_EXCEEDED,
                        "record limit exceeded");
            }
            if (mediaFileCount > ArchiveTransferLimits.MAX_MEDIA_FILES
                    || mediaLinkCount > ArchiveTransferLimits.MAX_MEDIA_LINKS
                    || mediaBytes > ArchiveTransferLimits.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw new ArchiveTransferException(ArchiveOperation.ErrorCode.LIMIT_EXCEEDED,
                        "media limit exceeded");
            }
            JSONArray identities = json.optJSONArray("accounts");
            if (identities == null) invalid("accounts");
            ArrayList<ArchiveAccountIdentity> accounts = new ArrayList<>();
            for (int i = 0; i < identities.length(); i++) {
                JSONObject identity = identities.optJSONObject(i);
                if (identity == null) invalid("account identity");
                ArchiveAccountIdentity account = new ArchiveAccountIdentity(
                        requireInt(identity, "account_environment"), requireLong(identity, "account_id"));
                if (account.accountId == 0 || accounts.contains(account)) invalid("account identity");
                accounts.add(account);
            }
            if ("account".equals(scope)) {
                if (accountId == 0 || accounts.size() != 1
                        || environment != accounts.get(0).accountEnvironment
                        || accountId != accounts.get(0).accountId) invalid("account scope identity");
            }
            return new ArchiveManifest(formatVersion, minimumReader, databaseSchema, rawFormat,
                    createdAt, applicationVersion, environment, accountId, scope, includesRaw,
                    includesMedia, checksum, messageCount, revisionCount, deletionCount, metadataCount,
                    mediaFileCount, mediaLinkCount, mediaBytes, accounts);
        } catch (ArchiveTransferException e) {
            throw e;
        } catch (Throwable e) {
            throw new ArchiveTransferException(ArchiveOperation.ErrorCode.INVALID_MANIFEST,
                    "invalid manifest", e);
        }
    }

    private static int[] parseVersion(String value) throws ArchiveTransferException {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 2) invalid("version");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            if (major < 0 || minor < 0) invalid("version");
            return new int[]{major, minor};
        } catch (NumberFormatException e) {
            invalid("version");
            return null;
        }
    }

    private static String utcNow() {
        SimpleDateFormat format = utcFormat();
        return format.format(new Date());
    }

    private static void parseUtc(String value) throws Exception {
        if (!value.endsWith("Z") || utcFormat().parse(value) == null) invalid("UTC timestamp");
    }

    private static SimpleDateFormat utcFormat() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format;
    }

    private static void assertString(JSONObject json, String key) throws ArchiveTransferException {
        if (!(json.opt(key) instanceof String)) invalid(key);
    }

    private static String requireString(JSONObject json, String key) throws ArchiveTransferException, JSONException {
        assertString(json, key);
        return json.getString(key);
    }

    private static long requireNonNegative(JSONObject json, String key) throws ArchiveTransferException {
        long value = requireLong(json, key);
        if (value < 0) invalid(key);
        return value;
    }

    private static long requireLong(JSONObject json, String key) throws ArchiveTransferException {
        Object value = json.opt(key);
        if (!(value instanceof Number)) invalid(key);
        return ((Number) value).longValue();
    }

    private static int requireInt(JSONObject json, String key) throws ArchiveTransferException {
        long value = requireLong(json, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) invalid(key);
        return (int) value;
    }

    private static boolean requireBoolean(JSONObject json, String key) throws ArchiveTransferException {
        Object value = json.opt(key);
        if (!(value instanceof Boolean)) invalid(key);
        return (Boolean) value;
    }

    private static void invalid(String field) throws ArchiveTransferException {
        throw new ArchiveTransferException(ArchiveOperation.ErrorCode.INVALID_MANIFEST,
                "invalid manifest field: " + field);
    }
}
