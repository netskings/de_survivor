package org.telegram.ui.Custom;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ViewOnceSaver {

    private static final Set<String> savedMessages = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static void saveViewOnceMediaSafely(File sourceFile, MessageObject msgObj) {
        if (sourceFile == null || !sourceFile.exists() || msgObj == null) return;

        String saveKey = getSaveKey(msgObj);
        if (!savedMessages.add(saveKey)) {
            return;
        }

        Utilities.globalQueue.postRunnable(() -> saveViewOnceMedia(sourceFile, msgObj, saveKey));
    }

    private static void saveViewOnceMedia(File sourceFile, MessageObject msgObj, String saveKey) {
        try {
            if (!sourceFile.exists()) {
                savedMessages.remove(saveKey);
                return;
            }
            String extension = getExtension(msgObj);
            String fileName = "viewonce_" + msgObj.currentAccount + "_" + msgObj.getDialogId() + "_" + msgObj.getId() + "." + extension;
            String mimeType = getMimeType(msgObj, extension);
            Uri treeUri = CustomSettings.saveTemporaryMediaTreeUri();
            if (treeUri != null) {
                saveToDocumentTree(sourceFile, fileName, mimeType, treeUri);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(sourceFile, fileName, mimeType);
            } else {
                saveToDownloads(sourceFile, fileName);
            }
        } catch (Exception e) {
            savedMessages.remove(saveKey);
            FileLog.e(e);
        }
    }

    private static void saveToDocumentTree(File sourceFile, String fileName, String mimeType, Uri treeUri) throws IOException {
        ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
        Uri treeDocumentUri;
        try {
            treeDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        } catch (Exception e) {
            throw new IOException("Invalid save folder " + treeUri, e);
        }

        Uri uri = DocumentsContract.createDocument(resolver, treeDocumentUri, mimeType, fileName);
        if (uri == null) {
            throw new IOException("Unable to create document for " + fileName);
        }

        try {
            try (OutputStream outputStream = resolver.openOutputStream(uri, "w")) {
                if (outputStream == null) {
                    throw new IOException("Unable to open document output stream for " + fileName);
                }
                copyFile(sourceFile, outputStream);
            }
        } catch (Exception e) {
            try {
                DocumentsContract.deleteDocument(resolver, uri);
            } catch (Exception ignore) {
            }
            throw e;
        }
    }

    private static void saveToMediaStore(File sourceFile, String fileName, String mimeType) throws IOException {
        ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + CustomSettings.saveTemporaryMediaRelativePath());
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Unable to create MediaStore item for " + fileName);
        }

        try {
            try (OutputStream outputStream = resolver.openOutputStream(uri)) {
                if (outputStream == null) {
                    throw new IOException("Unable to open MediaStore output stream for " + fileName);
                }
                copyFile(sourceFile, outputStream);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        } catch (Exception e) {
            resolver.delete(uri, null, null);
            throw e;
        }
    }

    private static void saveToDownloads(File sourceFile, String fileName) throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), CustomSettings.saveTemporaryMediaRelativePath());
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create " + dir.getAbsolutePath());
        }
        File destFile = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(destFile)) {
            copyFile(sourceFile, fos);
        }
        MediaScannerConnection.scanFile(ApplicationLoader.applicationContext,
                new String[]{destFile.getAbsolutePath()}, null, null);
    }

    private static void copyFile(File source, OutputStream outputStream) throws IOException {
        try (FileInputStream fis = new FileInputStream(source)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.flush();
        }
    }

    private static String getSaveKey(MessageObject msgObj) {
        if (msgObj == null) {
            return "unknown";
        }
        return msgObj.currentAccount + "_" + msgObj.getDialogId() + "_" + msgObj.getId();
    }

    private static String getExtension(MessageObject msgObj) {
        String fallback = msgObj != null && msgObj.isVideo() ? "mp4" : "jpg";
        if (msgObj == null || !msgObj.isVideo()) {
            return fallback;
        }
        String fileName = msgObj.getFileName();
        int idx = fileName != null ? fileName.lastIndexOf('.') : -1;
        if (idx < 0 || idx == fileName.length() - 1) {
            return fallback;
        }
        String extension = fileName.substring(idx + 1).toLowerCase(Locale.US);
        if (!extension.matches("[a-z0-9]{1,8}")) {
            return fallback;
        }
        return extension;
    }

    private static String getMimeType(MessageObject msgObj, String extension) {
        if (msgObj == null || msgObj.isPhoto()) {
            return "image/jpeg";
        }
        if ("webm".equals(extension)) {
            return "video/webm";
        }
        if ("mov".equals(extension)) {
            return "video/quicktime";
        }
        return "video/mp4";
    }
}
