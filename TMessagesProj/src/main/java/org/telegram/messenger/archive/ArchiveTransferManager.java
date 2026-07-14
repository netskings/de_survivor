package org.telegram.messenger.archive;

import android.system.ErrnoException;
import android.system.Os;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.UUID;

/** Public, typed boundary for background .tgarchive export and import. */
public final class ArchiveTransferManager {
    private static volatile ArchiveTransferManager instance;

    public static ArchiveTransferManager getInstance() {
        ArchiveTransferManager local = instance;
        if (local == null) {
            synchronized (ArchiveTransferManager.class) {
                local = instance;
                if (local == null) instance = local = new ArchiveTransferManager();
            }
        }
        return local;
    }

    private final DispatchQueue transferQueue = new DispatchQueue("archiveTransferQueue");

    private ArchiveTransferManager() {
    }

    public ArchiveOperation exportArchive(ArchiveExportOptions options, File destination,
                                          ArchiveOperationListener listener) {
        ArchiveOperation operation = new ArchiveOperation(listener);
        if (!ArchiveSettings.isEnabled() || options == null || destination == null) {
            operation.failed(ArchiveOperation.ErrorCode.INVALID_ARGUMENT);
            return operation;
        }
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent == null || destination.isDirectory()) {
            operation.failed(ArchiveOperation.ErrorCode.INVALID_ARGUMENT);
            return operation;
        }
        operation.running();
        ArchiveService.getInstance().postArchiveRunnable(() -> runExport(
                operation, options, destination.getAbsoluteFile(), parent));
        return operation;
    }

    public ArchiveOperation importArchive(File source, ArchiveOperationListener listener) {
        ArchiveOperation operation = new ArchiveOperation(listener);
        if (!ArchiveSettings.isEnabled() || source == null || !source.isFile()) {
            operation.failed(ArchiveOperation.ErrorCode.INVALID_ARGUMENT);
            return operation;
        }
        operation.running();
        transferQueue.postRunnable(() -> prepareImport(operation, source.getAbsoluteFile()));
        return operation;
    }

    private void runExport(ArchiveOperation operation, ArchiveExportOptions options,
                           File destination, File parent) {
        File temporary = null;
        try {
            operation.throwIfCancelled();
            if (!parent.exists() && !parent.mkdirs()) {
                throw new ArchiveTransferException(ArchiveOperation.ErrorCode.IO_ERROR,
                        "unable to create export directory");
            }
            temporary = new File(parent, "." + destination.getName() + "."
                    + UUID.randomUUID() + ".partial");
            ArchiveManifest manifest = ArchiveContainer.exportTo(temporary,
                    ArchiveService.getInstance().repositoryForTransfer(), options, operation);
            operation.throwIfCancelled();
            sync(temporary);
            atomicReplace(temporary, destination);
            temporary = null;
            operation.validated(manifest);
            operation.completed();
        } catch (ArchiveOperation.CancelledException e) {
            operation.cancelled();
        } catch (ArchiveTransferException e) {
            safeLog("export", e.code);
            operation.failed(e.code);
        } catch (Throwable e) {
            safeLog("export", ArchiveOperation.ErrorCode.IO_ERROR);
            operation.failed(ArchiveOperation.ErrorCode.IO_ERROR);
        } finally {
            if (temporary != null && temporary.exists() && !temporary.delete()) {
                safeLog("export_cleanup", ArchiveOperation.ErrorCode.IO_ERROR);
            }
        }
    }

    private void prepareImport(ArchiveOperation operation, File source) {
        File temporary = null;
        try {
            operation.throwIfCancelled();
            if (source.length() > ArchiveTransferLimits.MAX_CONTAINER_BYTES) {
                throw new ArchiveTransferException(ArchiveOperation.ErrorCode.LIMIT_EXCEEDED,
                        "container size limit exceeded");
            }
            File directory = new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(),
                    "local_archive/import_tmp");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new ArchiveTransferException(ArchiveOperation.ErrorCode.IO_ERROR,
                        "unable to create private import directory");
            }
            temporary = new File(directory, UUID.randomUUID() + ".pending");
            copyBounded(source, temporary, operation);
            operation.throwIfCancelled();
            ArchiveContainer.ValidationResult validation = ArchiveContainer.validate(temporary, operation);
            File readyFile = temporary;
            temporary = null;
            operation.validatedThen(validation.manifest,
                    () -> continueImport(operation, readyFile, validation.manifest));
        } catch (ArchiveOperation.CancelledException e) {
            operation.cancelled();
        } catch (ArchiveTransferException e) {
            safeLog("import_validation", e.code);
            operation.failed(e.code);
        } catch (Throwable e) {
            safeLog("import_validation", ArchiveOperation.ErrorCode.INVALID_ZIP);
            operation.failed(ArchiveOperation.ErrorCode.INVALID_ZIP);
        } finally {
            if (temporary != null && temporary.exists() && !temporary.delete()) {
                safeLog("import_cleanup", ArchiveOperation.ErrorCode.IO_ERROR);
            }
        }
    }

    private void continueImport(ArchiveOperation operation, File privateFile,
                                ArchiveManifest manifest) {
        if (operation.isCancellationRequested()) {
            transferQueue.postRunnable(() -> {
                deletePrivateImport(privateFile);
                operation.cancelled();
            });
            return;
        }
        ArchiveService.getInstance().postArchiveRunnable(() -> {
            try {
                operation.throwIfCancelled();
                ArchiveContainer.importInto(privateFile,
                        ArchiveService.getInstance().repositoryForTransfer(), manifest, operation);
                operation.completed();
            } catch (ArchiveOperation.CancelledException e) {
                operation.cancelled();
            } catch (ArchiveTransferException e) {
                safeLog("import", e.code);
                operation.failed(e.code);
            } catch (Throwable e) {
                safeLog("import", ArchiveOperation.ErrorCode.DATABASE_ERROR);
                operation.failed(ArchiveOperation.ErrorCode.DATABASE_ERROR);
            } finally {
                deletePrivateImport(privateFile);
            }
        });
    }

    private static void copyBounded(File source, File destination,
                                    ArchiveOperation operation) throws Exception {
        long total = source.length();
        long copied = 0;
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(source), 64 * 1024);
             BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination), 64 * 1024)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                operation.throwIfCancelled();
                copied += read;
                if (copied > ArchiveTransferLimits.MAX_CONTAINER_BYTES) {
                    throw new ArchiveTransferException(ArchiveOperation.ErrorCode.LIMIT_EXCEEDED,
                            "container size limit exceeded");
                }
                output.write(buffer, 0, read);
                operation.progress("copying", 0, 0, copied, total);
            }
        }
    }

    private static void sync(File file) throws Exception {
        try (RandomAccessFile randomAccess = new RandomAccessFile(file, "rw")) {
            randomAccess.getFD().sync();
        }
    }

    private static void atomicReplace(File source, File destination) throws ArchiveTransferException {
        try {
            Os.rename(source.getAbsolutePath(), destination.getAbsolutePath());
        } catch (ErrnoException e) {
            throw new ArchiveTransferException(ArchiveOperation.ErrorCode.IO_ERROR,
                    "atomic export rename failed", e);
        }
    }

    private static void deletePrivateImport(File file) {
        if (file.exists() && !file.delete()) safeLog("import_cleanup", ArchiveOperation.ErrorCode.IO_ERROR);
    }

    private static void safeLog(String operation, ArchiveOperation.ErrorCode code) {
        FileLog.e("Local archive " + operation + " failed: " + code.name());
    }
}
