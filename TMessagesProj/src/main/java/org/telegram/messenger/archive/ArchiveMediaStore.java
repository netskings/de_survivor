package org.telegram.messenger.archive;

import android.system.Os;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;

/** Owns immutable content-addressed media files; network downloads are explicit opt-in only. */
public final class ArchiveMediaStore {
    private static volatile ArchiveMediaStore instance;

    public static ArchiveMediaStore getInstance() {
        ArchiveMediaStore local = instance;
        if (local == null) {
            synchronized (ArchiveMediaStore.class) {
                local = instance;
                if (local == null) instance = local = new ArchiveMediaStore();
            }
        }
        return local;
    }

    private final DispatchQueue mediaQueue = new DispatchQueue("archiveMediaQueue");
    private final HashMap<String, ArrayList<MessageObject>> resolving = new HashMap<>();
    private volatile long lastRetentionCheck;

    private ArchiveMediaStore() {
        mediaQueue.postRunnable(() -> {
            try {
                File[] files = mediaRoot().listFiles();
                if (files == null) return;
                for (File file : files) {
                    String name = file.getName();
                    if (name.startsWith(".") && name.endsWith(".partial") && !file.delete()) {
                        safeLog("stale_partial_cleanup", null);
                    }
                }
            } catch (Throwable e) {
                safeLog("startup_cleanup", e);
            }
        });
    }

    public void captureDownloaded(int accountSlot, MessageObject messageObject, File sourceFile) {
        capture(accountSlot, messageObject, sourceFile, true);
    }

    /** Captures an outgoing/local attachment which did not pass through FileLoader download. */
    public void captureAlreadyAvailable(int accountSlot, TLRPC.Message message) {
        if (message == null) return;
        try {
            MessageObject object = new MessageObject(accountSlot, message, false, false);
            File source = !TextUtils.isEmpty(message.attachPath) ? new File(message.attachPath) : null;
            if (source == null || !source.isFile()) {
                source = FileLoader.getInstance(accountSlot).getPathToMessage(message, false);
            }
            capture(accountSlot, object, source, false);
        } catch (Throwable e) {
            safeLog("available", e);
        }
    }

    private void capture(int accountSlot, MessageObject messageObject, File sourceFile,
                         boolean requirePrimary) {
        if (!ArchiveSettings.isEnabled() || !ArchiveMediaSettings.isEnabled()) return;
        Capture capture;
        try {
            capture = freeze(accountSlot, messageObject, sourceFile, requirePrimary);
        } catch (Throwable e) {
            safeLog("policy", e);
            return;
        }
        if (capture == null) return;
        maybeRunRetention();
        mediaQueue.postRunnable(() -> copyAndLink(capture));
    }

    private void maybeRunRetention() {
        int days = ArchiveMediaSettings.retentionDays();
        if (days <= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastRetentionCheck < 24L * 60 * 60 * 1000) return;
        lastRetentionCheck = now;
        ArchiveService.getInstance().cleanupExpiredMedia(
                now / 1000L - days * 24L * 60 * 60);
    }

    public void applyRetentionNow() {
        lastRetentionCheck = 0;
        maybeRunRetention();
    }

    /** Best-effort deletion fallback. It only uses a file which is already present locally. */
    public void captureAvailableAtDeletion(int accountSlot, TLRPC.Message message) {
        if (!ArchiveSettings.isEnabled() || !ArchiveMediaSettings.isEnabled() || message == null) return;
        try {
            MessageObject object = new MessageObject(accountSlot, message, false, false);
            File file = !TextUtils.isEmpty(message.attachPath) ? new File(message.attachPath) : null;
            if (file == null || !file.isFile()) {
                file = FileLoader.getInstance(accountSlot).getPathToMessage(message, false);
            }
            capture(accountSlot, object, file, false);
        } catch (Throwable e) {
            safeLog("delete_fallback", e);
        }
    }

    public void captureAvailableAtDeletion(int accountSlot, ArchiveMessageSnapshot snapshot) {
        if (snapshot == null) return;
        byte[] raw = snapshot.copyRawPayload();
        if (raw == null || raw.length < 4) return;
        SerializedData data = null;
        try {
            data = new SerializedData(raw);
            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
            if (message != null) {
                if (message.dialog_id == 0) message.dialog_id = snapshot.dialogId;
                captureAvailableAtDeletion(accountSlot, message);
            }
        } catch (Throwable e) {
            safeLog("delete_snapshot", e);
        } finally {
            if (data != null) data.cleanup();
        }
    }

    public void maybeDownloadIfEnabled(int accountSlot, TLRPC.Message message) {
        if (!ArchiveMediaSettings.autoDownloadMissing() || message == null) return;
        try {
            MessageObject object = new MessageObject(accountSlot, message, false, false);
            if (!basicEligible(accountSlot, object)) return;
            String type = classify(object);
            if (type == null || !ArchiveMediaSettings.isTypeEnabled(type)) return;
            TLRPC.Document document = object.getDocument();
            if (document != null) {
                FileLoader.getInstance(accountSlot).loadFile(document, object, FileLoader.PRIORITY_LOW, 0);
            } else {
                TLRPC.MessageMedia media = MessageObject.getMedia(message);
                if (media != null && media.photo != null) {
                    TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(
                            media.photo.sizes, AndroidUtilities.getPhotoSize(true), false, null, true);
                    if (size != null) {
                        FileLoader.getInstance(accountSlot).loadFile(
                                ImageLocation.getForPhoto(size, media.photo), object, null,
                                FileLoader.PRIORITY_LOW, 0);
                    }
                }
            }
        } catch (Throwable e) {
            safeLog("auto_download", e);
        }
    }

    public void resolveFallback(MessageObject messageObject) {
        if (!ArchiveSettings.isEnabled()
                || messageObject == null || messageObject.messageOwner == null
                || messageObject.mediaExists || messageObject.attachPathExists) return;
        TLRPC.Message message = messageObject.messageOwner;
        if (message.id <= 0 || DialogObject.isEncryptedDialog(messageObject.getDialogId())
                || message.ttl_period != 0 || MessageObject.isSecretMedia(message)) return;
        String key = messageObject.currentAccount + ":" + messageObject.getDialogId() + ":"
                + messageObject.getTopicId() + ":" + messageObject.getId();
        synchronized (resolving) {
            ArrayList<MessageObject> waiters = resolving.get(key);
            if (waiters != null) {
                if (!waiters.contains(messageObject)) waiters.add(messageObject);
                return;
            }
            waiters = new ArrayList<>();
            waiters.add(messageObject);
            resolving.put(key, waiters);
        }
        ArchiveService.getInstance().loadMediaPath(messageObject.currentAccount, messageObject.getDialogId(),
                messageObject.getTopicId(), messageObject.getId(), relative -> {
                    ArrayList<MessageObject> waiters;
                    synchronized (resolving) {
                        waiters = resolving.remove(key);
                    }
                    if (waiters == null) return;
                    for (MessageObject waiter : waiters) applyResolvedFallback(waiter, relative);
                    NotificationCenter.getInstance(messageObject.currentAccount)
                            .postNotificationName(NotificationCenter.updateInterfaces, 0);
                });
    }

    private static void applyResolvedFallback(MessageObject messageObject, String relative) {
        messageObject.archiveMediaLookupCompleted = true;
        if (messageObject.mediaExists || messageObject.attachPathExists) {
            messageObject.archiveMediaMissing = false;
            return;
        }
        File standard = FileLoader.getInstance(messageObject.currentAccount)
                .getPathToMessage(messageObject.messageOwner, false);
        if (standard.isFile()) {
            messageObject.archiveMediaMissing = false;
            messageObject.mediaExists = true;
            return;
        }
        File archived = TextUtils.isEmpty(relative) ? null : resolveRelativePath(relative);
        if (archived != null && archived.isFile()) {
            messageObject.messageOwner.attachPath = archived.getAbsolutePath();
            messageObject.attachPathExists = true;
            messageObject.archiveMediaMissing = false;
        } else {
            messageObject.archiveMediaMissing = true;
        }
    }

    void deleteRelativePaths(ArrayList<String> paths) {
        if (paths == null || paths.isEmpty()) return;
        ArrayList<String> stable = new ArrayList<>(paths);
        mediaQueue.postRunnable(() -> {
            for (String relative : stable) {
                try {
                    File file = resolveRelativePath(relative);
                    if (isInsideMediaRoot(file) && file.exists() && !file.delete()) {
                        FileLog.e("Local archive orphan media cleanup failed");
                    }
                } catch (Throwable e) {
                    safeLog("cleanup", e);
                }
            }
        });
    }

    static File mediaRoot() {
        return new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "local_archive/media");
    }

    static File resolveRelativePath(String relative) {
        if (relative == null || !relative.matches("media/[0-9a-f]{64}")) {
            return new File(mediaRoot(), ".invalid");
        }
        return new File(mediaRoot(), relative.substring("media/".length()));
    }

    public static boolean isArchiveFilePath(String path) {
        return !TextUtils.isEmpty(path) && isInsideMediaRoot(new File(path));
    }

    /** Imports one already validated media entry without ever exposing a partial file. */
    static boolean importVerified(InputStream source, String contentHash, long expectedSize,
                                  ArchiveOperation operation) throws Exception {
        if (!contentHash.matches("[0-9a-f]{64}") || expectedSize < 0
                || expectedSize > ArchiveTransferLimits.MAX_MEDIA_FILE_BYTES) {
            throw new ArchiveTransferException(ArchiveOperation.ErrorCode.INVALID_JSONL,
                    "invalid media index");
        }
        File root = mediaRoot();
        if (!root.exists() && !root.mkdirs()) {
            throw new ArchiveTransferException(ArchiveOperation.ErrorCode.IO_ERROR,
                    "cannot create archive media directory");
        }
        File target = new File(root, contentHash);
        if (target.isFile()) {
            if (target.length() != expectedSize || !contentHash.equals(hashFile(target, operation))) {
                throw new ArchiveTransferException(ArchiveOperation.ErrorCode.CHECKSUM_MISMATCH,
                        "existing archived media does not match its hash");
            }
            return false;
        }
        File temporary = new File(root, "." + UUID.randomUUID() + ".import.partial");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied = 0;
            try (FileOutputStream fileOutput = new FileOutputStream(temporary);
                 BufferedOutputStream output = new BufferedOutputStream(fileOutput, 64 * 1024)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = source.read(buffer)) != -1) {
                    operation.throwIfCancelled();
                    copied += read;
                    if (copied > expectedSize || copied > ArchiveTransferLimits.MAX_MEDIA_FILE_BYTES) {
                        throw new ArchiveTransferException(ArchiveOperation.ErrorCode.LIMIT_EXCEEDED,
                                "media file size limit exceeded");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                output.flush();
                fileOutput.getFD().sync();
            }
            if (copied != expectedSize || !contentHash.equals(hex(digest.digest()))) {
                throw new ArchiveTransferException(ArchiveOperation.ErrorCode.CHECKSUM_MISMATCH,
                        "media hash mismatch");
            }
            if (target.exists()) {
                if (target.length() != expectedSize || !contentHash.equals(hashFile(target, operation))) {
                    throw new ArchiveTransferException(ArchiveOperation.ErrorCode.CHECKSUM_MISMATCH,
                            "archived media collision");
                }
                return false;
            }
            Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
            temporary = null;
            return true;
        } finally {
            if (temporary != null && temporary.exists() && !temporary.delete()) {
                safeLog("import_partial_cleanup", null);
            }
        }
    }

    static void deleteImported(String contentHash) {
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) return;
        File file = new File(mediaRoot(), contentHash);
        if (isInsideMediaRoot(file) && file.exists() && !file.delete()) {
            safeLog("import_rollback", null);
        }
    }

    private static String hashFile(File file, ArchiveOperation operation) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file), 64 * 1024)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                operation.throwIfCancelled();
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private void copyAndLink(Capture capture) {
        File temporary = null;
        boolean createdTarget = false;
        try {
            if (!capture.source.isFile()) return;
            long size = capture.source.length();
            if (size <= 0 || size > ArchiveMediaSettings.maxFileBytes()) return;
            File root = mediaRoot();
            if (!root.exists() && !root.mkdirs()) return;
            if (isInsideMediaRoot(capture.source)) return;
            temporary = new File(root, "." + UUID.randomUUID() + ".partial");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long copied = 0;
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(capture.source), 64 * 1024);
                 FileOutputStream fileOutput = new FileOutputStream(temporary);
                 BufferedOutputStream output = new BufferedOutputStream(fileOutput, 64 * 1024)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    copied += read;
                    if (copied > ArchiveMediaSettings.maxFileBytes()) return;
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                output.flush();
                fileOutput.getFD().sync();
            }
            if (copied != size) return;
            String hash = hex(digest.digest());
            File target = new File(root, hash);
            if (target.exists()) {
                if (target.length() != size) return;
                if (!temporary.delete()) safeLog("dedup_cleanup", null);
                temporary = null;
            } else {
                Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
                temporary = null;
                createdTarget = true;
            }
            boolean finalCreatedTarget = createdTarget;
            String relative = "media/" + hash;
            ArchiveService.getInstance().linkMedia(capture.descriptor, hash, size, relative, accepted -> {
                if (!accepted && finalCreatedTarget) {
                    deleteRelativePaths(single(relative));
                } else if (accepted) {
                    NotificationCenter.getInstance(capture.accountSlot)
                            .postNotificationName(NotificationCenter.updateInterfaces, 0);
                }
            });
        } catch (Throwable e) {
            safeLog("copy", e);
        } finally {
            if (temporary != null && temporary.exists() && !temporary.delete()) safeLog("partial_cleanup", null);
        }
    }

    private static Capture freeze(int accountSlot, MessageObject object, File source, boolean requirePrimary) {
        if (object == null || object.messageOwner == null || source == null || !source.isFile()) return null;
        TLRPC.Message message = object.messageOwner;
        long accountId = UserConfig.getInstance(accountSlot).getClientUserId();
        long dialogId = object.getDialogId();
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (accountId == 0 || !basicEligible(accountSlot, object)) return null;

        String type = classify(object);
        if (type == null) return null;
        if (!ArchiveMediaSettings.isTypeEnabled(type)) return null;

        String expected = null;
        String mime = "application/octet-stream";
        String originalName = source.getName();
        TLRPC.Document document = object.getDocument();
        if (document != null) {
            expected = FileLoader.getAttachFileName(document);
            if (!TextUtils.isEmpty(document.mime_type)) mime = document.mime_type;
            String documentName = FileLoader.getDocumentFileName(document);
            if (!TextUtils.isEmpty(documentName)) originalName = documentName;
        } else if (media.photo != null && media.photo.sizes != null) {
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(
                    media.photo.sizes, AndroidUtilities.getPhotoSize(true), false, null, true);
            if (size != null) expected = FileLoader.getAttachFileName(size);
            mime = "image/jpeg";
        }
        if (requirePrimary && !TextUtils.isEmpty(expected) && !expected.equals(source.getName())) return null;
        originalName = safeName(originalName);
        int environment = ConnectionsManager.getInstance(accountSlot).isTestBackend() ? 1 : 0;
        ArchiveMediaDescriptor descriptor = new ArchiveMediaDescriptor(environment, accountId, dialogId,
                object.getTopicId(), message.id, type, mime, originalName, 0, "primary");
        return new Capture(accountSlot, descriptor, source.getAbsoluteFile());
    }

    private static boolean basicEligible(int accountSlot, MessageObject object) {
        if (object == null || object.messageOwner == null) return false;
        TLRPC.Message message = object.messageOwner;
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        long dialogId = object.getDialogId();
        return ArchiveSettings.isEnabled() && ArchiveMediaSettings.isEnabled()
                && UserConfig.getInstance(accountSlot).getClientUserId() != 0
                && message.id > 0 && dialogId != 0 && !DialogObject.isEncryptedDialog(dialogId)
                && !(message instanceof TLRPC.TL_message_secret) && message.ttl_period == 0
                && media != null && media.ttl_seconds == 0 && !MessageObject.isSecretMedia(message)
                && !MessageObject.isQuickReply(message);
    }

    private static String classify(MessageObject object) {
        if (object.isPhoto()) return ArchiveMediaSettings.PHOTO;
        if (object.isVoice()) return ArchiveMediaSettings.VOICE;
        if (object.isMusic()) return ArchiveMediaSettings.MUSIC;
        if (object.isAnyKindOfSticker()) return ArchiveMediaSettings.STICKER;
        if (object.isGif()) return ArchiveMediaSettings.ANIMATION;
        if (object.isVideo() || object.isRoundVideo()) return ArchiveMediaSettings.VIDEO;
        if (object.getDocument() != null) return ArchiveMediaSettings.DOCUMENT;
        return null;
    }

    private static boolean isInsideMediaRoot(File file) {
        try {
            String root = mediaRoot().getCanonicalPath() + File.separator;
            return file.getCanonicalPath().startsWith(root);
        } catch (Throwable e) {
            return false;
        }
    }

    private static String safeName(String value) {
        String name = new File(value == null ? "" : value).getName().replace('\0', '_');
        if (name.length() > 255) name = name.substring(name.length() - 255);
        return name;
    }

    private static ArrayList<String> single(String value) {
        ArrayList<String> result = new ArrayList<>(1);
        result.add(value);
        return result;
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte b : value) result.append(String.format(Locale.US, "%02x", b & 0xff));
        return result.toString();
    }

    private static void safeLog(String operation, Throwable error) {
        FileLog.e("Local archive media " + operation + " failed" +
                (error == null ? "" : ": " + error.getClass().getSimpleName()));
    }

    private static final class Capture {
        final int accountSlot;
        final ArchiveMediaDescriptor descriptor;
        final File source;

        Capture(int accountSlot, ArchiveMediaDescriptor descriptor, File source) {
            this.accountSlot = accountSlot;
            this.descriptor = descriptor;
            this.source = source;
        }
    }
}
