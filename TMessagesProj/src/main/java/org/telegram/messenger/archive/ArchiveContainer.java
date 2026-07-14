package org.telegram.messenger.archive;

import android.util.Base64;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.io.BufferedOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Streaming implementation of .tgarchive 1.0 (six entries) and media-aware 2.0. */
final class ArchiveContainer {
    static final String MANIFEST = "manifest.json";
    static final String MESSAGES = "messages.jsonl";
    static final String REVISIONS = "revisions.jsonl";
    static final String DELETIONS = "deletions.jsonl";
    static final String METADATA = "metadata.jsonl";
    static final String MEDIA_INDEX = "media.jsonl";
    static final String MEDIA_PREFIX = "media/";
    static final String CHECKSUMS = "checksums.json";

    private static final List<String> CONTENT_ENTRIES;
    private static final Set<String> ALL_ENTRIES;

    static {
        ArrayList<String> content = new ArrayList<>();
        Collections.addAll(content, MANIFEST, MESSAGES, REVISIONS, DELETIONS, METADATA);
        CONTENT_ENTRIES = Collections.unmodifiableList(content);
        HashSet<String> all = new HashSet<>(content);
        all.add(CHECKSUMS);
        ALL_ENTRIES = Collections.unmodifiableSet(all);
    }

    static final class ValidationResult {
        final ArchiveManifest manifest;

        ValidationResult(ArchiveManifest manifest) {
            this.manifest = manifest;
        }
    }

    private ArchiveContainer() {
    }

    static ArchiveManifest exportTo(File file, ArchiveRepository repository,
                                    ArchiveExportOptions options, ArchiveOperation operation) throws Exception {
        ArchiveRepository.ExportCounts counts = repository.exportCounts(options);
        if (counts.total() > ArchiveTransferLimits.MAX_RECORDS) limit("record limit exceeded");
        if (counts.mediaFiles > ArchiveTransferLimits.MAX_MEDIA_FILES
                || counts.mediaLinks > ArchiveTransferLimits.MAX_MEDIA_LINKS
                || counts.mediaBytes > ArchiveTransferLimits.MAX_TOTAL_UNCOMPRESSED_BYTES) {
            limit("media archive limit exceeded");
        }
        List<ArchiveAccountIdentity> accounts = repository.exportAccounts(options);
        ArchiveManifest manifest = new ArchiveManifest(options, counts, accounts);
        LinkedHashMap<String, String> checksums = new LinkedHashMap<>();
        long[] processed = {0};
        CountingOutputStream counted = null;
        try {
            counted = new CountingOutputStream(new BufferedOutputStream(new FileOutputStream(file), 64 * 1024));
            ZipOutputStream zip = new ZipOutputStream(counted, StandardCharsets.UTF_8);
            try {
                writeBytesEntry(zip, MANIFEST, manifest.toJson().toString().getBytes(StandardCharsets.UTF_8), checksums);
                writeRowsEntry(zip, MESSAGES, ArchiveRepository.TransferTable.MESSAGES, repository,
                        options, operation, processed, counts.total(), counted, checksums);
                writeRowsEntry(zip, REVISIONS, ArchiveRepository.TransferTable.REVISIONS, repository,
                        options, operation, processed, counts.total(), counted, checksums);
                writeRowsEntry(zip, DELETIONS, ArchiveRepository.TransferTable.DELETIONS, repository,
                        options, operation, processed, counts.total(), counted, checksums);
                writeRowsEntry(zip, METADATA, ArchiveRepository.TransferTable.METADATA, repository,
                        options, operation, processed, counts.total(), counted, checksums);
                if (options.includeMedia) {
                    writeRowsEntry(zip, MEDIA_INDEX, ArchiveRepository.TransferTable.MEDIA, repository,
                            options, operation, processed, counts.total(), counted, checksums);
                    for (ArchiveMediaFile media : repository.exportMediaFiles(options)) {
                        operation.throwIfCancelled();
                        writeMediaEntry(zip, media, operation, counted, checksums);
                    }
                }
                JSONObject checksumJson = new JSONObject();
                checksumJson.put("algorithm", "SHA-256");
                JSONObject entries = new JSONObject();
                for (Map.Entry<String, String> checksum : checksums.entrySet()) {
                    entries.put(checksum.getKey(), checksum.getValue());
                }
                checksumJson.put("entries", entries);
                writeBytesEntry(zip, CHECKSUMS, checksumJson.toString().getBytes(StandardCharsets.UTF_8), null);
                operation.throwIfCancelled();
                zip.finish();
            } finally {
                zip.close();
            }
        } finally {
            if (counted != null) counted.close();
        }
        return manifest;
    }

    private static void writeMediaEntry(ZipOutputStream zip, ArchiveMediaFile media,
                                        ArchiveOperation operation, CountingOutputStream counted,
                                        Map<String, String> checksums) throws Exception {
        if (media == null || !isSha256(media.contentHash)) invalidJson("invalid media hash");
        File file = ArchiveMediaStore.resolveRelativePath(media.relativePath);
        if (!file.isFile() || file.length() != media.sizeBytes) {
            throw new ArchiveTransferException(ArchiveOperation.ErrorCode.IO_ERROR,
                    "archived media file is missing");
        }
        String name = MEDIA_PREFIX + media.contentHash;
        MessageDigest digest = sha256();
        putEntry(zip, name);
        long copied = 0;
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file), 64 * 1024)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                operation.throwIfCancelled();
                copied += read;
                if (copied > ArchiveTransferLimits.MAX_MEDIA_FILE_BYTES) limit("media file size limit exceeded");
                digest.update(buffer, 0, read);
                zip.write(buffer, 0, read);
                operation.progress("exporting_media", 0, 0, copied, media.sizeBytes);
            }
        }
        zip.closeEntry();
        if (copied != media.sizeBytes || !media.contentHash.equals(hex(digest.digest()))) {
            checksum("archived media changed during export");
        }
        checksums.put(name, media.contentHash);
    }

    static ValidationResult validate(File file, ArchiveOperation operation) throws Exception {
        if (!file.isFile() || file.length() > ArchiveTransferLimits.MAX_CONTAINER_BYTES) {
            limit("container size limit exceeded");
        }
        try (ZipFile zip = new ZipFile(file, StandardCharsets.UTF_8)) {
            Map<String, ZipEntry> entries = validateCentralDirectory(zip);
            if (!entries.containsKey(MANIFEST) || !entries.containsKey(CHECKSUMS)) {
                throw new ArchiveTransferException(ArchiveOperation.ErrorCode.INVALID_ZIP,
                        "required ZIP entry is missing");
            }
            JSONObject checksums = new JSONObject(new String(readSmallEntry(zip, entries.get(CHECKSUMS),
                    ArchiveTransferLimits.MAX_CHECKSUM_ENTRY_BYTES), StandardCharsets.UTF_8));
            if (!"SHA-256".equals(checksums.optString("algorithm", null))) checksum("checksum algorithm");
            JSONObject expected = checksums.optJSONObject("entries");
            if (expected == null || expected.length() != entries.size() - 1) checksum("checksum list");

            byte[] manifestBytes = readSmallEntry(zip, entries.get(MANIFEST),
                    ArchiveTransferLimits.MAX_SMALL_ENTRY_BYTES);
            verifyChecksum(expected, MANIFEST, digest(manifestBytes));
            ArchiveManifest manifest = ArchiveManifest.parse(new JSONObject(
                    new String(manifestBytes, StandardCharsets.UTF_8)));
            validateLayout(entries, manifest);

            ValidationCounters counters = new ValidationCounters(file.length(), manifest);
            validateJsonl(zip, entries.get(MESSAGES), MESSAGES, ArchiveRepository.TransferTable.MESSAGES,
                    manifest, operation, counters, expected, null, null);
            validateJsonl(zip, entries.get(REVISIONS), REVISIONS, ArchiveRepository.TransferTable.REVISIONS,
                    manifest, operation, counters, expected, null, null);
            validateJsonl(zip, entries.get(DELETIONS), DELETIONS, ArchiveRepository.TransferTable.DELETIONS,
                    manifest, operation, counters, expected, null, null);
            validateJsonl(zip, entries.get(METADATA), METADATA, ArchiveRepository.TransferTable.METADATA,
                    manifest, operation, counters, expected, null, null);
            MediaValidation media = null;
            if (manifest.includesMedia) {
                media = new MediaValidation();
                validateJsonl(zip, entries.get(MEDIA_INDEX), MEDIA_INDEX, ArchiveRepository.TransferTable.MEDIA,
                        manifest, operation, counters, expected, null, media);
                validateMediaEntries(zip, entries, expected, media, manifest, operation);
            }
            if (counters.messages != manifest.messageCount
                    || counters.revisions != manifest.revisionCount
                    || counters.deletions != manifest.deletionEventCount
                    || counters.metadata != manifest.metadataCount
                    || counters.mediaLinks != manifest.mediaLinkCount) {
                invalidJson("manifest counts do not match JSONL records");
            }
            for (ArchiveAccountIdentity identity : counters.accounts) {
                if (!manifest.accounts.contains(identity)) invalidManifest("undeclared account identity");
            }
            if ("all_accounts".equals(manifest.exportScope)
                    && !counters.accounts.containsAll(manifest.accounts)) {
                invalidManifest("manifest account list does not match records");
            }
            return new ValidationResult(manifest);
        } catch (ArchiveTransferException | ArchiveOperation.CancelledException e) {
            throw e;
        } catch (java.util.zip.ZipException e) {
            throw new ArchiveTransferException(ArchiveOperation.ErrorCode.INVALID_ZIP, "invalid ZIP", e);
        }
    }

    static void importInto(File file, ArchiveRepository repository, ArchiveManifest manifest,
                           ArchiveOperation operation) throws Exception {
        boolean allowRaw = manifest.includesRawPayload
                && manifest.rawFormatVersion <= ArchiveSchema.RAW_FORMAT_VERSION;
        long total = manifest.messageCount + manifest.revisionCount
                + manifest.deletionEventCount + manifest.metadataCount + manifest.mediaLinkCount;
        long[] processed = {0};
        long[] ambiguousConflicts = {0};
        ArrayList<String> createdMedia = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file, StandardCharsets.UTF_8)) {
            if (manifest.includesMedia) {
                MediaValidation media = readMediaIndex(zip, manifest, operation);
                for (Map.Entry<String, Long> entry : media.files.entrySet()) {
                    operation.throwIfCancelled();
                    try (InputStream input = zip.getInputStream(zip.getEntry(MEDIA_PREFIX + entry.getKey()))) {
                        if (ArchiveMediaStore.importVerified(input, entry.getKey(), entry.getValue(), operation)) {
                            createdMedia.add(entry.getKey());
                        }
                    }
                }
            }
            repository.importTransaction(target -> {
                importJsonl(zip, zip.getEntry(MESSAGES), ArchiveRepository.TransferTable.MESSAGES,
                        manifest, target, allowRaw, operation, processed, total, ambiguousConflicts);
                importJsonl(zip, zip.getEntry(REVISIONS), ArchiveRepository.TransferTable.REVISIONS,
                        manifest, target, allowRaw, operation, processed, total, ambiguousConflicts);
                importJsonl(zip, zip.getEntry(DELETIONS), ArchiveRepository.TransferTable.DELETIONS,
                        manifest, target, allowRaw, operation, processed, total, ambiguousConflicts);
                importJsonl(zip, zip.getEntry(METADATA), ArchiveRepository.TransferTable.METADATA,
                        manifest, target, allowRaw, operation, processed, total, ambiguousConflicts);
                if (manifest.includesMedia) {
                    importJsonl(zip, zip.getEntry(MEDIA_INDEX), ArchiveRepository.TransferTable.MEDIA,
                            manifest, target, false, operation, processed, total, ambiguousConflicts);
                }
                operation.throwIfCancelled();
            });
        } catch (Exception e) {
            for (String hash : createdMedia) {
                if (!repository.hasMediaHash(hash)) ArchiveMediaStore.deleteImported(hash);
            }
            throw e;
        }
        if (ambiguousConflicts[0] != 0) {
            FileLog.e("Local archive import ambiguous conflicts: " + ambiguousConflicts[0]);
        }
    }

    private static void writeRowsEntry(ZipOutputStream zip, String name,
                                       ArchiveRepository.TransferTable table,
                                       ArchiveRepository repository, ArchiveExportOptions options,
                                       ArchiveOperation operation, long[] processed, long total,
                                       CountingOutputStream counted,
                                       Map<String, String> checksums) throws Exception {
        MessageDigest digest = sha256();
        putEntry(zip, name);
        DigestOutputStream digestStream = new DigestOutputStream(new NonClosingOutputStream(zip), digest);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(digestStream, StandardCharsets.UTF_8), 64 * 1024);
        try {
            repository.exportRows(table, options, row -> {
                operation.throwIfCancelled();
                validateRow(table, row, options.includeRawPayload, ArchiveSchema.RAW_FORMAT_VERSION, null);
                byte[] line = row.toString().getBytes(StandardCharsets.UTF_8);
                if (line.length > ArchiveTransferLimits.MAX_JSONL_LINE_BYTES) limit("JSONL line limit exceeded");
                writer.write(new String(line, StandardCharsets.UTF_8));
                writer.write('\n');
                processed[0]++;
                if ((processed[0] % 250) == 0) {
                    writer.flush();
                    operation.progress("exporting", processed[0], total, counted.count,
                            ArchiveTransferLimits.MAX_CONTAINER_BYTES);
                }
            });
            writer.flush();
        } finally {
            zip.closeEntry();
        }
        checksums.put(name, hex(digest.digest()));
    }

    private static void writeBytesEntry(ZipOutputStream zip, String name, byte[] bytes,
                                        Map<String, String> checksums) throws Exception {
        putEntry(zip, name);
        MessageDigest digest = sha256();
        digest.update(bytes);
        zip.write(bytes);
        zip.closeEntry();
        if (checksums != null) checksums.put(name, hex(digest.digest()));
    }

    private static void putEntry(ZipOutputStream zip, String name) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
    }

    private static Map<String, ZipEntry> validateCentralDirectory(ZipFile zip) throws Exception {
        LinkedHashMap<String, ZipEntry> result = new LinkedHashMap<>();
        long total = 0;
        int count = 0;
        Enumeration<? extends ZipEntry> enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            count++;
            if (count > ArchiveTransferLimits.MAX_ZIP_ENTRIES) limit("ZIP entry count exceeded");
            String name = entry.getName();
            boolean media = name != null && name.startsWith(MEDIA_PREFIX)
                    && isSha256(name.substring(MEDIA_PREFIX.length()));
            boolean known = ALL_ENTRIES.contains(name) || MEDIA_INDEX.equals(name) || media;
            if (!safeEntryName(name) || entry.isDirectory() || !known) {
                throw new ArchiveTransferException(ArchiveOperation.ErrorCode.INVALID_ZIP,
                        "unsafe or unknown ZIP entry");
            }
            if (result.put(name, entry) != null) {
                throw new ArchiveTransferException(ArchiveOperation.ErrorCode.INVALID_ZIP,
                        "duplicate ZIP entry");
            }
            long size = entry.getSize();
            long compressed = entry.getCompressedSize();
            long maximum = media ? ArchiveTransferLimits.MAX_MEDIA_FILE_BYTES
                    : ArchiveTransferLimits.MAX_ENTRY_BYTES;
            if (size < 0 || compressed < 0 || size > maximum) {
                limit("ZIP entry size limit exceeded");
            }
            total += size;
            if (total > ArchiveTransferLimits.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                limit("uncompressed size limit exceeded");
            }
            if (size >= ArchiveTransferLimits.COMPRESSION_RATIO_MIN_BYTES
                    && (compressed == 0 || ((double) size / compressed) > ArchiveTransferLimits.MAX_COMPRESSION_RATIO)) {
                limit("ZIP compression ratio exceeded");
            }
        }
        return result;
    }

    private static void validateLayout(Map<String, ZipEntry> entries, ArchiveManifest manifest)
            throws ArchiveTransferException {
        if (!manifest.includesMedia) {
            if (!entries.keySet().equals(ALL_ENTRIES)) {
                throw new ArchiveTransferException(ArchiveOperation.ErrorCode.INVALID_ZIP,
                        "format 1.0 requires exactly six ZIP entries");
            }
            return;
        }
        HashSet<String> required = new HashSet<>(ALL_ENTRIES);
        required.add(MEDIA_INDEX);
        if (!entries.keySet().containsAll(required)) {
            throw new ArchiveTransferException(ArchiveOperation.ErrorCode.INVALID_ZIP,
                    "required format 2.0 ZIP entry is missing");
        }
        long files = 0;
        for (String name : entries.keySet()) {
            if (required.contains(name)) continue;
            if (!name.startsWith(MEDIA_PREFIX)
                    || !isSha256(name.substring(MEDIA_PREFIX.length()))) {
                throw new ArchiveTransferException(ArchiveOperation.ErrorCode.INVALID_ZIP,
                        "unknown format 2.0 ZIP entry");
            }
            files++;
        }
        if (files != manifest.mediaFileCount || files > ArchiveTransferLimits.MAX_MEDIA_FILES) {
            invalidManifest("media file count does not match ZIP entries");
        }
    }

    private static boolean safeEntryName(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.startsWith("\\")
                || name.contains("\\") || name.contains(":") || name.indexOf('\0') >= 0) return false;
        String[] parts = name.split("/", -1);
        for (String part : parts) if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return false;
        return true;
    }

    private static void validateJsonl(ZipFile zip, ZipEntry entry, String name,
                                      ArchiveRepository.TransferTable table, ArchiveManifest manifest,
                                      ArchiveOperation operation, ValidationCounters counters,
                                      JSONObject expectedChecksums, ArchiveRepository repository,
                                      MediaValidation mediaValidation) throws Exception {
        MessageDigest digest = sha256();
        try (InputStream raw = zip.getInputStream(entry);
             BoundedInputStream bounded = new BoundedInputStream(raw, ArchiveTransferLimits.MAX_ENTRY_BYTES);
             DigestInputStream input = new DigestInputStream(bounded, digest)) {
            readLines(input, line -> {
                operation.throwIfCancelled();
                JSONObject row = parseLine(line);
                validateRow(table, row, manifest.includesRawPayload, manifest.rawFormatVersion, counters.accounts);
                if (mediaValidation != null) mediaValidation.add(row);
                if (repository != null) {
                    repository.importRow(table, row, manifest.includesRawPayload
                            && manifest.rawFormatVersion <= ArchiveSchema.RAW_FORMAT_VERSION);
                }
                counters.add(table, line.length);
                if ((counters.totalRecords % 250) == 0) {
                    operation.progress("validating", counters.totalRecords, counters.expectedRecords,
                            counters.processedBytes, counters.totalBytes);
                }
            });
        } catch (LimitIOException e) {
            limit("ZIP entry size limit exceeded");
        }
        verifyChecksum(expectedChecksums, name, digest.digest());
    }

    private static MediaValidation readMediaIndex(ZipFile zip, ArchiveManifest manifest,
                                                  ArchiveOperation operation) throws Exception {
        MediaValidation media = new MediaValidation();
        try (InputStream input = zip.getInputStream(zip.getEntry(MEDIA_INDEX))) {
            readLines(input, line -> {
                operation.throwIfCancelled();
                JSONObject row = parseLine(line);
                validateRow(ArchiveRepository.TransferTable.MEDIA, row, false,
                        manifest.rawFormatVersion, null);
                media.add(row);
            });
        }
        if (media.links != manifest.mediaLinkCount || media.files.size() != manifest.mediaFileCount
                || media.totalBytes != manifest.mediaBytes) {
            invalidManifest("media index does not match manifest");
        }
        return media;
    }

    private static void validateMediaEntries(ZipFile zip, Map<String, ZipEntry> entries,
                                             JSONObject expected, MediaValidation media,
                                             ArchiveManifest manifest, ArchiveOperation operation) throws Exception {
        if (media.links != manifest.mediaLinkCount || media.files.size() != manifest.mediaFileCount
                || media.totalBytes != manifest.mediaBytes) {
            invalidManifest("media index does not match manifest");
        }
        for (Map.Entry<String, Long> file : media.files.entrySet()) {
            operation.throwIfCancelled();
            String name = MEDIA_PREFIX + file.getKey();
            ZipEntry entry = entries.get(name);
            if (entry == null || entry.getSize() != file.getValue()) {
                invalidJson("media file is missing or has the wrong size");
            }
            MessageDigest digest = sha256();
            long copied = 0;
            try (InputStream raw = zip.getInputStream(entry);
                 BoundedInputStream input = new BoundedInputStream(raw,
                         ArchiveTransferLimits.MAX_MEDIA_FILE_BYTES)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    operation.throwIfCancelled();
                    copied += read;
                    digest.update(buffer, 0, read);
                }
            } catch (LimitIOException e) {
                limit("media file size limit exceeded");
            }
            String actual = hex(digest.digest());
            if (copied != file.getValue() || !constantTimeEquals(file.getKey(), actual)) {
                checksum("media SHA-256 mismatch");
            }
            verifyChecksum(expected, name, hexToBytes(actual));
        }
        for (String name : entries.keySet()) {
            if (name.startsWith(MEDIA_PREFIX)
                    && !media.files.containsKey(name.substring(MEDIA_PREFIX.length()))) {
                invalidJson("unindexed media file");
            }
        }
    }

    private static void importJsonl(ZipFile zip, ZipEntry entry, ArchiveRepository.TransferTable table,
                                    ArchiveManifest manifest, ArchiveRepository repository,
                                    boolean allowRaw, ArchiveOperation operation,
                                    long[] processed, long total, long[] ambiguousConflicts) throws Exception {
        try (InputStream input = zip.getInputStream(entry)) {
            readLines(input, line -> {
                operation.throwIfCancelled();
                JSONObject row = parseLine(line);
                validateRow(table, row, manifest.includesRawPayload, manifest.rawFormatVersion, null);
                if (repository.importRow(table, row, allowRaw)) ambiguousConflicts[0]++;
                processed[0]++;
                if ((processed[0] % 250) == 0) {
                    operation.progress("importing", processed[0], total, 0, 0);
                }
            });
        }
    }

    private static JSONObject parseLine(byte[] line) throws ArchiveTransferException {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(line)).toString();
            if (value.trim().isEmpty()) invalidJson("empty JSONL line");
            return new JSONObject(value);
        } catch (ArchiveTransferException e) {
            throw e;
        } catch (CharacterCodingException e) {
            invalidJson("invalid UTF-8");
            return null;
        } catch (Throwable e) {
            throw new ArchiveTransferException(ArchiveOperation.ErrorCode.INVALID_JSONL,
                    "invalid JSONL", e);
        }
    }

    private static void validateRow(ArchiveRepository.TransferTable table, JSONObject row,
                                    boolean includesRaw, int rawFormatVersion,
                                    Set<ArchiveAccountIdentity> accounts) throws Exception {
        if (table == ArchiveRepository.TransferTable.METADATA) {
            boundedString(row, "key", 1024, false);
            boundedString(row, "value", ArchiveTransferLimits.MAX_TEXT_BYTES, false);
            return;
        }
        int environment = integer(row, "account_environment");
        long accountId = longNumber(row, "account_id");
        if (accountId == 0) invalidJson("zero account id");
        longNumber(row, "dialog_id");
        longNumber(row, "topic_id");
        integer(row, "message_id");
        if (accounts != null) accounts.add(new ArchiveAccountIdentity(environment, accountId));
        if (table == ArchiveRepository.TransferTable.MEDIA) {
            String hash = boundedStringValue(row, "content_hash", 64, false);
            if (!isSha256(hash)) invalidJson("content_hash");
            boundedString(row, "mime_type", 512, false);
            boundedString(row, "original_name", 1024, false);
            long size = nonNegative(row, "size_bytes");
            if (size == 0 || size > ArchiveTransferLimits.MAX_MEDIA_FILE_BYTES) {
                limit("media file size limit exceeded");
            }
            String mediaType = boundedStringValue(row, "media_type", 64, false);
            if (!ArchiveMediaSettings.PHOTO.equals(mediaType)
                    && !ArchiveMediaSettings.VIDEO.equals(mediaType)
                    && !ArchiveMediaSettings.DOCUMENT.equals(mediaType)
                    && !ArchiveMediaSettings.VOICE.equals(mediaType)
                    && !ArchiveMediaSettings.MUSIC.equals(mediaType)
                    && !ArchiveMediaSettings.STICKER.equals(mediaType)
                    && !ArchiveMediaSettings.ANIMATION.equals(mediaType)) {
                invalidJson("unknown media type");
            }
            int position = integer(row, "position");
            if (position < 0) invalidJson("position");
            String role = boundedStringValue(row, "role", 64, false);
            if (!"primary".equals(role)) invalidJson("unknown media role");
            nonNegative(row, "created_at");
            nonNegative(row, "last_used_at");
            return;
        }
        if (table == ArchiveRepository.TransferTable.DELETIONS) {
            boundedString(row, "source_event_id", 4096, false);
            nonNegative(row, "deleted_at");
            nonNegative(row, "saved_at");
            return;
        }
        longNumber(row, "sender_id");
        integer(row, "message_date");
        integer(row, "edit_date");
        nonNegative(row, "saved_at");
        boundedString(row, "text", ArchiveTransferLimits.MAX_TEXT_BYTES, true);
        boundedString(row, "entities_json", ArchiveTransferLimits.MAX_TEXT_BYTES, false);
        boundedString(row, "message_type", 1024, false);
        integer(row, "reply_to_message_id");
        longNumber(row, "grouped_id");
        int rowRawVersion = integer(row, "raw_format_version");
        if (rowRawVersion < 0 || rowRawVersion > rawFormatVersion) invalidJson("raw format version");
        Object raw = row.opt("raw_payload_base64");
        if (raw != JSONObject.NULL && !(raw instanceof String)) invalidJson("raw payload type");
        if (!includesRaw && raw instanceof String) invalidJson("raw payload contradicts manifest");
        if (raw instanceof String) {
            int maxEncoded = ((ArchiveTransferLimits.MAX_RAW_PAYLOAD_BYTES + 2) / 3) * 4;
            if (((String) raw).length() > maxEncoded) limit("raw payload limit exceeded");
            byte[] decoded;
            try {
                decoded = Base64.decode((String) raw, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                invalidJson("invalid raw payload encoding");
                return;
            }
            if (decoded.length > ArchiveTransferLimits.MAX_RAW_PAYLOAD_BYTES) limit("raw payload limit exceeded");
        }
        boundedString(row, "content_hash", 256, false);
        if (table == ArchiveRepository.TransferTable.MESSAGES) {
            if (!(row.opt("is_deleted") instanceof Boolean)) invalidJson("is_deleted type");
            nonNegative(row, "deleted_at");
        }
    }

    private static void readLines(InputStream input, LineConsumer consumer) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream(4096);
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            for (int i = 0; i < read; i++) {
                byte value = buffer[i];
                if (value == '\n') {
                    byte[] bytes = line.toByteArray();
                    if (bytes.length > 0 && bytes[bytes.length - 1] == '\r') {
                        byte[] trimmed = new byte[bytes.length - 1];
                        System.arraycopy(bytes, 0, trimmed, 0, trimmed.length);
                        bytes = trimmed;
                    }
                    consumer.accept(bytes);
                    line.reset();
                } else {
                    if (line.size() >= ArchiveTransferLimits.MAX_JSONL_LINE_BYTES) {
                        limit("JSONL line limit exceeded");
                    }
                    line.write(value);
                }
            }
        }
        if (line.size() != 0) consumer.accept(line.toByteArray());
    }

    private static byte[] readSmallEntry(ZipFile zip, ZipEntry entry, int maximum) throws Exception {
        try (InputStream input = zip.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min((int) entry.getSize(), 16 * 1024))) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximum) limit("small entry limit exceeded");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void verifyChecksum(JSONObject expected, String name, byte[] actual) throws Exception {
        Object value = expected.opt(name);
        if (!(value instanceof String) || !((String) value).matches("[0-9a-f]{64}")
                || !constantTimeEquals((String) value, hex(actual))) checksum("checksum mismatch");
    }

    private static boolean constantTimeEquals(String first, String second) {
        if (first.length() != second.length()) return false;
        int difference = 0;
        for (int i = 0; i < first.length(); i++) difference |= first.charAt(i) ^ second.charAt(i);
        return difference == 0;
    }

    private static byte[] digest(byte[] bytes) throws Exception {
        return sha256().digest(bytes);
    }

    private static MessageDigest sha256() throws Exception {
        return MessageDigest.getInstance("SHA-256");
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        return result.toString();
    }

    private static long nonNegative(JSONObject row, String key) throws ArchiveTransferException {
        long value = longNumber(row, key);
        if (value < 0) invalidJson(key);
        return value;
    }

    private static int integer(JSONObject row, String key) throws ArchiveTransferException {
        long value = longNumber(row, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) invalidJson(key);
        return (int) value;
    }

    private static long longNumber(JSONObject row, String key) throws ArchiveTransferException {
        Object value = row.opt(key);
        if (!(value instanceof Number)) invalidJson(key);
        return ((Number) value).longValue();
    }

    private static void boundedString(JSONObject row, String key, int maximumBytes,
                                      boolean nullable) throws ArchiveTransferException {
        boundedStringValue(row, key, maximumBytes, nullable);
    }

    private static String boundedStringValue(JSONObject row, String key, int maximumBytes,
                                             boolean nullable) throws ArchiveTransferException {
        Object value = row.opt(key);
        if (nullable && value == JSONObject.NULL) return null;
        if (!(value instanceof String)) invalidJson(key);
        if (((String) value).getBytes(StandardCharsets.UTF_8).length > maximumBytes) limit(key + " limit exceeded");
        return (String) value;
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static byte[] hexToBytes(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static void limit(String message) throws ArchiveTransferException {
        throw new ArchiveTransferException(ArchiveOperation.ErrorCode.LIMIT_EXCEEDED, message);
    }

    private static void checksum(String message) throws ArchiveTransferException {
        throw new ArchiveTransferException(ArchiveOperation.ErrorCode.CHECKSUM_MISMATCH, message);
    }

    private static void invalidJson(String message) throws ArchiveTransferException {
        throw new ArchiveTransferException(ArchiveOperation.ErrorCode.INVALID_JSONL, message);
    }

    private static void invalidManifest(String message) throws ArchiveTransferException {
        throw new ArchiveTransferException(ArchiveOperation.ErrorCode.INVALID_MANIFEST, message);
    }

    private interface LineConsumer {
        void accept(byte[] line) throws Exception;
    }

    private static final class ValidationCounters {
        final long totalBytes;
        final long expectedRecords;
        final Set<ArchiveAccountIdentity> accounts = new HashSet<>();
        long messages;
        long revisions;
        long deletions;
        long metadata;
        long mediaLinks;
        long totalRecords;
        long processedBytes;

        ValidationCounters(long totalBytes, ArchiveManifest manifest) {
            this.totalBytes = totalBytes;
            expectedRecords = manifest.messageCount + manifest.revisionCount
                    + manifest.deletionEventCount + manifest.metadataCount + manifest.mediaLinkCount;
        }

        void add(ArchiveRepository.TransferTable table, int bytes) throws ArchiveTransferException {
            if (table == ArchiveRepository.TransferTable.MESSAGES) messages++;
            else if (table == ArchiveRepository.TransferTable.REVISIONS) revisions++;
            else if (table == ArchiveRepository.TransferTable.DELETIONS) deletions++;
            else if (table == ArchiveRepository.TransferTable.METADATA) metadata++;
            else if (table == ArchiveRepository.TransferTable.MEDIA) mediaLinks++;
            totalRecords++;
            processedBytes += bytes;
            if (totalRecords > ArchiveTransferLimits.MAX_RECORDS) limit("record limit exceeded");
            if (processedBytes > ArchiveTransferLimits.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                limit("uncompressed size limit exceeded");
            }
        }
    }

    private static final class MediaValidation {
        final LinkedHashMap<String, Long> files = new LinkedHashMap<>();
        final LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        final HashSet<String> linkKeys = new HashSet<>();
        long links;
        long totalBytes;

        void add(JSONObject row) throws Exception {
            String hash = row.getString("content_hash");
            long size = row.getLong("size_bytes");
            Long previous = files.putIfAbsent(hash, size);
            if (previous != null && previous != size) invalidJson("conflicting media sizes");
            String signature = row.getString("mime_type") + "\n" + row.getString("original_name")
                    + "\n" + row.getString("media_type");
            String previousMetadata = metadata.putIfAbsent(hash, signature);
            if (previousMetadata != null && !previousMetadata.equals(signature)) {
                invalidJson("conflicting media metadata");
            }
            String linkKey = row.getInt("account_environment") + ":" + row.getLong("account_id")
                    + ":" + row.getLong("dialog_id") + ":" + row.getLong("topic_id")
                    + ":" + row.getInt("message_id") + ":" + row.getInt("position")
                    + ":" + row.getString("role");
            if (!linkKeys.add(linkKey)) invalidJson("duplicate media link");
            if (previous == null) {
                totalBytes += size;
                if (files.size() > ArchiveTransferLimits.MAX_MEDIA_FILES
                        || totalBytes > ArchiveTransferLimits.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    limit("media archive limit exceeded");
                }
            }
            links++;
            if (links > ArchiveTransferLimits.MAX_MEDIA_LINKS) limit("media link limit exceeded");
        }
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        long count;

        CountingOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void write(int value) throws IOException {
            out.write(value);
            count++;
            check();
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            out.write(buffer, offset, length);
            count += length;
            check();
        }

        private void check() throws IOException {
            if (count > ArchiveTransferLimits.MAX_CONTAINER_BYTES) throw new IOException("container limit exceeded");
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long maximum;
        private long count;

        BoundedInputStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) add(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) add(read);
            return read;
        }

        private void add(int bytes) throws IOException {
            count += bytes;
            if (count > maximum) throw new LimitIOException();
        }
    }

    private static final class LimitIOException extends IOException {
    }

    private static final class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
