package org.telegram.ui.Custom;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.archive.ArchiveAccountIdentity;
import org.telegram.messenger.archive.ArchiveExportOptions;
import org.telegram.messenger.archive.ArchiveManifest;
import org.telegram.messenger.archive.ArchiveMediaSettings;
import org.telegram.messenger.archive.ArchiveMediaStore;
import org.telegram.messenger.archive.ArchiveOperation;
import org.telegram.messenger.archive.ArchiveOperationListener;
import org.telegram.messenger.archive.ArchiveProgress;
import org.telegram.messenger.archive.ArchiveSettings;
import org.telegram.messenger.archive.ArchiveService;
import org.telegram.messenger.archive.ArchiveTransferManager;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** User-facing local archive center. SAF is only a file transport around the typed archive API. */
public class LocalArchiveActivity extends UniversalFragment {
    private static final int ITEM_ENABLED = 1;
    private static final int ITEM_DELETED = 2;
    private static final int ITEM_EDITED = 3;
    private static final int ITEM_EXPORT = 4;
    private static final int ITEM_IMPORT = 5;
    private static final int ITEM_CLEAR = 6;
    private static final int ITEM_MEDIA_ENABLED = 10;
    private static final int ITEM_MEDIA_PHOTO = 11;
    private static final int ITEM_MEDIA_VIDEO = 12;
    private static final int ITEM_MEDIA_DOCUMENT = 13;
    private static final int ITEM_MEDIA_VOICE = 14;
    private static final int ITEM_MEDIA_MUSIC = 15;
    private static final int ITEM_MEDIA_STICKER = 16;
    private static final int ITEM_MEDIA_ANIMATION = 17;
    private static final int ITEM_MEDIA_AUTO_DOWNLOAD = 18;
    private static final int ITEM_MEDIA_MAX_FILE = 19;
    private static final int ITEM_MEDIA_MAX_TOTAL = 20;
    private static final int ITEM_MEDIA_RETENTION = 21;
    private static final int REQUEST_EXPORT = 8401;
    private static final int REQUEST_IMPORT = 8402;
    private static final long MAX_CONTAINER_BYTES = 1024L * 1024 * 1024;

    private ArchiveExportOptions pendingExportOptions;
    private ArchiveOperation currentOperation;
    private AlertDialog progressDialog;
    private File workingFile;
    private Uri exportUri;
    private volatile boolean copyCancelled;
    private int workGeneration;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.LocalMessageArchive);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.ArchiveStorageHeader)));
        items.add(UItem.asCheck(ITEM_ENABLED, LocaleController.getString(R.string.LocalMessageArchive))
                .setChecked(ArchiveSettings.isEnabled()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.LocalMessageArchiveInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ArchiveBrowseHeader)));
        items.add(UItem.asButton(ITEM_DELETED, R.drawable.msg_delete,
                LocaleController.getString(R.string.ViewDeletedMessages)));
        items.add(UItem.asButton(ITEM_EDITED, R.drawable.msg_edit,
                LocaleController.getString(R.string.ViewEditedMessages)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ArchiveBrowseInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ArchiveMediaHeader)));
        items.add(UItem.asCheck(ITEM_MEDIA_ENABLED, LocaleController.getString(R.string.ArchiveMediaEnabled))
                .setChecked(ArchiveMediaSettings.isEnabled()));
        addMediaType(items, ITEM_MEDIA_PHOTO, R.string.ArchiveMediaPhotos, ArchiveMediaSettings.PHOTO);
        addMediaType(items, ITEM_MEDIA_VIDEO, R.string.ArchiveMediaVideos, ArchiveMediaSettings.VIDEO);
        addMediaType(items, ITEM_MEDIA_DOCUMENT, R.string.ArchiveMediaDocuments, ArchiveMediaSettings.DOCUMENT);
        addMediaType(items, ITEM_MEDIA_VOICE, R.string.ArchiveMediaVoice, ArchiveMediaSettings.VOICE);
        addMediaType(items, ITEM_MEDIA_MUSIC, R.string.ArchiveMediaMusic, ArchiveMediaSettings.MUSIC);
        addMediaType(items, ITEM_MEDIA_STICKER, R.string.ArchiveMediaStickers, ArchiveMediaSettings.STICKER);
        addMediaType(items, ITEM_MEDIA_ANIMATION, R.string.ArchiveMediaAnimations, ArchiveMediaSettings.ANIMATION);
        items.add(UItem.asCheck(ITEM_MEDIA_AUTO_DOWNLOAD, LocaleController.getString(R.string.ArchiveMediaAutoDownload))
                .setChecked(ArchiveMediaSettings.autoDownloadMissing()));
        items.add(UItem.asButton(ITEM_MEDIA_MAX_FILE, LocaleController.getString(R.string.ArchiveMediaMaxFile),
                AndroidUtilities.formatFileSize(ArchiveMediaSettings.maxFileBytes())));
        items.add(UItem.asButton(ITEM_MEDIA_MAX_TOTAL, LocaleController.getString(R.string.ArchiveMediaMaxTotal),
                AndroidUtilities.formatFileSize(ArchiveMediaSettings.maxTotalBytes())));
        int retention = ArchiveMediaSettings.retentionDays();
        items.add(UItem.asButton(ITEM_MEDIA_RETENTION, LocaleController.getString(R.string.ArchiveMediaRetention),
                retention == 0 ? LocaleController.getString(R.string.ArchiveMediaUnlimited)
                        : LocaleController.formatPluralString("Days", retention)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ArchiveMediaInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ArchiveTransferHeader)));
        items.add(UItem.asButton(ITEM_EXPORT, R.drawable.msg_share,
                LocaleController.getString(R.string.ArchiveExport)));
        items.add(UItem.asButton(ITEM_IMPORT, R.drawable.msg_download,
                LocaleController.getString(R.string.ArchiveImport)));
        items.add(UItem.asButton(ITEM_CLEAR, R.drawable.msg_delete,
                LocaleController.getString(R.string.ArchiveClear)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ArchiveTransferInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ITEM_ENABLED) {
            boolean enabled = !ArchiveSettings.isEnabled();
            ArchiveSettings.setEnabled(enabled);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(enabled);
            return;
        }
        if (item.id == ITEM_MEDIA_ENABLED) {
            boolean enabled = !ArchiveMediaSettings.isEnabled();
            ArchiveMediaSettings.setEnabled(enabled);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(enabled);
            return;
        }
        String mediaType = mediaType(item.id);
        if (mediaType != null) {
            boolean enabled = !ArchiveMediaSettings.isTypeEnabled(mediaType);
            ArchiveMediaSettings.setTypeEnabled(mediaType, enabled);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(enabled);
            return;
        }
        if (item.id == ITEM_MEDIA_AUTO_DOWNLOAD) {
            boolean enabled = !ArchiveMediaSettings.autoDownloadMissing();
            ArchiveMediaSettings.setAutoDownloadMissing(enabled);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(enabled);
            return;
        }
        if (item.id == ITEM_MEDIA_MAX_FILE) {
            chooseMediaLimit(true);
            return;
        } else if (item.id == ITEM_MEDIA_MAX_TOTAL) {
            chooseMediaLimit(false);
            return;
        } else if (item.id == ITEM_MEDIA_RETENTION) {
            chooseRetention();
            return;
        }
        if (item.id == ITEM_CLEAR) {
            chooseClearScope();
            return;
        }
        if (!ArchiveSettings.isEnabled()) {
            Toast.makeText(getContext(), R.string.LocalMessageArchiveDisabledInfo, Toast.LENGTH_LONG).show();
            return;
        }
        if (item.id == ITEM_DELETED) {
            presentFragment(new DeletedMessagesActivity());
        } else if (item.id == ITEM_EDITED) {
            presentFragment(new EditedMessagesActivity());
        } else if (item.id == ITEM_EXPORT) {
            chooseExportScope();
        } else if (item.id == ITEM_IMPORT) {
            openImportPicker();
        }
    }

    private void chooseClearScope() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.ArchiveClear));
        builder.setItems(new CharSequence[]{
                LocaleController.getString(R.string.ArchiveClearCurrentAccount),
                LocaleController.getString(R.string.ArchiveClearAllAccounts)
        }, (dialog, which) -> confirmClearArchive(which == 1));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void confirmClearArchive(boolean allAccounts) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.ArchiveClear));
        builder.setMessage(LocaleController.getString(allAccounts
                ? R.string.ArchiveClearAllConfirm : R.string.ArchiveClearCurrentConfirm));
        builder.setPositiveButton(LocaleController.getString(R.string.ArchiveClearConfirmButton),
                (dialog, which) -> ArchiveService.getInstance().clearArchive(
                        currentAccount, allAccounts, success -> {
                            Activity parent = getParentActivity();
                            if (parent != null) Toast.makeText(parent, success
                                    ? R.string.ArchiveClearDone : R.string.ArchiveOperationFailed,
                                    Toast.LENGTH_SHORT).show();
                        }));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void addMediaType(ArrayList<UItem> items, int id, int title, String type) {
        items.add(UItem.asCheck(id, LocaleController.getString(title))
                .setChecked(ArchiveMediaSettings.isTypeEnabled(type)));
    }

    private String mediaType(int id) {
        if (id == ITEM_MEDIA_PHOTO) return ArchiveMediaSettings.PHOTO;
        if (id == ITEM_MEDIA_VIDEO) return ArchiveMediaSettings.VIDEO;
        if (id == ITEM_MEDIA_DOCUMENT) return ArchiveMediaSettings.DOCUMENT;
        if (id == ITEM_MEDIA_VOICE) return ArchiveMediaSettings.VOICE;
        if (id == ITEM_MEDIA_MUSIC) return ArchiveMediaSettings.MUSIC;
        if (id == ITEM_MEDIA_STICKER) return ArchiveMediaSettings.STICKER;
        if (id == ITEM_MEDIA_ANIMATION) return ArchiveMediaSettings.ANIMATION;
        return null;
    }

    private void chooseMediaLimit(boolean singleFile) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        long mb = 1024L * 1024;
        long[] values = singleFile
                ? new long[]{25 * mb, 50 * mb, 100 * mb, 250 * mb, 500 * mb}
                : new long[]{256 * mb, 512 * mb, 1024 * mb};
        CharSequence[] labels = new CharSequence[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = AndroidUtilities.formatFileSize(values[i]);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(singleFile
                ? R.string.ArchiveMediaMaxFile : R.string.ArchiveMediaMaxTotal));
        builder.setItems(labels, (dialog, which) -> {
            if (singleFile) ArchiveMediaSettings.setMaxFileBytes(values[which]);
            else ArchiveMediaSettings.setMaxTotalBytes(values[which]);
            if (listView != null) listView.adapter.update(true);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void chooseRetention() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        int[] values = {0, 30, 90, 365};
        CharSequence[] labels = {
                LocaleController.getString(R.string.ArchiveMediaUnlimited),
                LocaleController.formatPluralString("Days", 30),
                LocaleController.formatPluralString("Days", 90),
                LocaleController.formatPluralString("Days", 365)
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.ArchiveMediaRetention));
        builder.setItems(labels, (dialog, which) -> {
            ArchiveMediaSettings.setRetentionDays(values[which]);
            ArchiveMediaStore.getInstance().applyRetentionNow();
            if (listView != null) listView.adapter.update(true);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void chooseExportScope() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.ArchiveExport));
        builder.setItems(new CharSequence[]{
                LocaleController.getString(R.string.ArchiveExportCurrentAccount),
                LocaleController.getString(R.string.ArchiveExportAllAccounts)
        }, (dialog, which) -> {
            final int environment;
            final long accountId;
            if (which == 0) {
                accountId = UserConfig.getInstance(currentAccount).getClientUserId();
                environment = ConnectionsManager.getInstance(currentAccount).isTestBackend() ? 1 : 0;
                if (accountId == 0) {
                    Toast.makeText(activity, R.string.ArchiveOperationFailed, Toast.LENGTH_SHORT).show();
                    return;
                }
            } else {
                accountId = 0;
                environment = -1;
            }
            chooseExportMedia(which == 0, environment, accountId);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void chooseExportMedia(boolean currentOnly, int environment, long accountId) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.ArchiveExportMediaTitle));
        builder.setItems(new CharSequence[]{
                LocaleController.getString(R.string.ArchiveExportWithoutMedia),
                LocaleController.getString(R.string.ArchiveExportWithMedia)
        }, (dialog, which) -> {
            boolean includeMedia = which == 1;
            pendingExportOptions = currentOnly
                    ? ArchiveExportOptions.account(environment, accountId, true, includeMedia)
                    : ArchiveExportOptions.allAccounts(true, includeMedia);
            openExportPicker();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void openExportPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE, new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)
                    .format(new Date()) + ".tgarchive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_EXPORT);
        } catch (Throwable e) {
            FileLog.e(e);
            Toast.makeText(getContext(), R.string.ArchiveFilePickerFailed, Toast.LENGTH_LONG).show();
        }
    }

    private void openImportPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // Many document providers expose an unknown .tgarchive extension as octet-stream.
            // Validation, not the provider MIME guess, decides whether the selected file is valid.
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/zip", "application/octet-stream"});
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_IMPORT);
        } catch (Throwable e) {
            FileLog.e(e);
            Toast.makeText(getContext(), R.string.ArchiveFilePickerFailed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_EXPORT && pendingExportOptions != null) {
            beginExport(data.getData(), pendingExportOptions);
            pendingExportOptions = null;
        } else if (requestCode == REQUEST_IMPORT) {
            beginImportCopy(data.getData());
        }
    }

    private void beginExport(Uri destination, ArchiveExportOptions options) {
        Context context = getContext();
        if (context == null) return;
        cleanupWorkingFile();
        workingFile = createWorkingFile(context, "export", ".tgarchive");
        exportUri = destination;
        copyCancelled = false;
        int generation = ++workGeneration;
        showProgress(LocaleController.getString(R.string.ArchiveExportPreparing));
        currentOperation = ArchiveTransferManager.getInstance().exportArchive(options, workingFile,
                new ArchiveOperationListener() {
                    private boolean copying;

                    @Override
                    public void onProgress(ArchiveOperation operation, ArchiveProgress progress) {
                        updateProgress(progress);
                    }

                    @Override
                    public void onStateChanged(ArchiveOperation operation) {
                        if (generation != workGeneration) return;
                        if (operation.getState() == ArchiveOperation.State.COMPLETED && !copying) {
                            copying = true;
                            currentOperation = null;
                            updateProgressMessage(LocaleController.getString(R.string.ArchiveExportWriting));
                            Utilities.globalQueue.postRunnable(() -> finishExportToSaf(context.getApplicationContext(),
                                    destination, generation));
                        } else if (operation.getState() == ArchiveOperation.State.FAILED
                                || operation.getState() == ArchiveOperation.State.CANCELLED) {
                            finishOperation(false, operation.getErrorCode(), true, generation);
                        }
                    }
                });
    }

    private void finishExportToSaf(Context context, Uri destination, int generation) {
        boolean success = false;
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(destination, "rwt");
             BufferedInputStream input = new BufferedInputStream(new FileInputStream(workingFile), 64 * 1024);
             FileOutputStream fileOutput = descriptor == null ? null : new FileOutputStream(descriptor.getFileDescriptor());
             BufferedOutputStream output = fileOutput == null ? null : new BufferedOutputStream(fileOutput, 64 * 1024)) {
            if (descriptor == null || output == null) throw new IllegalStateException("SAF destination unavailable");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (copyCancelled || generation != workGeneration) throw new InterruptedException();
                output.write(buffer, 0, read);
            }
            output.flush();
            fileOutput.getFD().sync();
            success = true;
        } catch (Throwable e) {
            FileLog.e("Local archive SAF export failed: " + e.getClass().getSimpleName());
        }
        if (!success) {
            try {
                context.getContentResolver().delete(destination, null, null);
            } catch (Throwable ignore) {
            }
        }
        boolean delivered = success;
        AndroidUtilities.runOnUIThread(() -> finishOperation(delivered,
                delivered ? ArchiveOperation.ErrorCode.NONE : ArchiveOperation.ErrorCode.IO_ERROR,
                false, generation));
    }

    private void beginImportCopy(Uri source) {
        Context context = getContext();
        if (context == null) return;
        cleanupWorkingFile();
        workingFile = createWorkingFile(context, "import", ".pending");
        copyCancelled = false;
        int generation = ++workGeneration;
        showProgress(LocaleController.getString(R.string.ArchiveImportCopying));
        Context applicationContext = context.getApplicationContext();
        Utilities.globalQueue.postRunnable(() -> {
            boolean success = copyUriToPrivate(applicationContext, source, workingFile, generation);
            AndroidUtilities.runOnUIThread(() -> {
                if (generation != workGeneration) return;
                if (success) startImportValidation(generation);
                else finishOperation(false, ArchiveOperation.ErrorCode.IO_ERROR, false, generation);
            });
        });
    }

    private boolean copyUriToPrivate(Context context, Uri source, File destination, int generation) {
        long copied = 0;
        try (InputStream raw = context.getContentResolver().openInputStream(source);
             BufferedInputStream input = raw == null ? null : new BufferedInputStream(raw, 64 * 1024);
             FileOutputStream fileOutput = new FileOutputStream(destination);
             BufferedOutputStream output = new BufferedOutputStream(fileOutput, 64 * 1024)) {
            if (input == null) throw new IllegalStateException("SAF source unavailable");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (copyCancelled || generation != workGeneration) throw new InterruptedException();
                copied += read;
                if (copied > MAX_CONTAINER_BYTES) throw new IllegalArgumentException("archive is too large");
                output.write(buffer, 0, read);
            }
            output.flush();
            fileOutput.getFD().sync();
            return true;
        } catch (Throwable e) {
            FileLog.e("Local archive SAF import copy failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private void startImportValidation(int generation) {
        updateProgressMessage(LocaleController.getString(R.string.ArchiveImportValidating));
        currentOperation = ArchiveTransferManager.getInstance().importArchive(workingFile,
                new ArchiveOperationListener() {
                    private boolean validated;

                    @Override
                    public void onProgress(ArchiveOperation operation, ArchiveProgress progress) {
                        updateProgress(progress);
                    }

                    @Override
                    public void onArchiveValidated(ArchiveOperation operation, ArchiveManifest manifest) {
                        if (generation != workGeneration || validated) return;
                        validated = true;
                        operation.cancel();
                        currentOperation = null;
                        dismissProgress();
                        showImportConfirmation(manifest, generation);
                    }

                    @Override
                    public void onStateChanged(ArchiveOperation operation) {
                        if (generation != workGeneration || validated) return;
                        if (operation.getState() == ArchiveOperation.State.FAILED) {
                            finishOperation(false, operation.getErrorCode(), false, generation);
                        }
                    }
                });
    }

    private void showImportConfirmation(ArchiveManifest manifest, int generation) {
        Activity activity = getParentActivity();
        if (activity == null || generation != workGeneration) {
            cancelWork();
            return;
        }
        StringBuilder accounts = new StringBuilder();
        for (ArchiveAccountIdentity account : manifest.accounts) {
            if (accounts.length() > 0) accounts.append(", ");
            accounts.append(account.accountEnvironment == 1 ? "test:" : "prod:").append(account.accountId);
        }
        String message = LocaleController.formatString(R.string.ArchiveImportConfirm,
                manifest.messageCount, manifest.revisionCount, manifest.deletionEventCount,
                accounts.length() == 0 ? "—" : accounts.toString());
        if (manifest.includesMedia) {
            message += "\n\n" + LocaleController.formatString(R.string.ArchiveImportMediaSummary,
                    manifest.mediaFileCount, manifest.mediaLinkCount,
                    AndroidUtilities.formatFileSize(manifest.mediaBytes));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.ArchiveImport));
        builder.setMessage(message);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (dialog, which) -> cancelWork());
        builder.setPositiveButton(LocaleController.getString(R.string.ArchiveImportConfirmButton),
                (dialog, which) -> startConfirmedImport(generation));
        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(ignored -> cancelWork());
        showDialog(dialog);
    }

    private void startConfirmedImport(int generation) {
        if (generation != workGeneration || workingFile == null) return;
        showProgress(LocaleController.getString(R.string.ArchiveImportApplying));
        currentOperation = ArchiveTransferManager.getInstance().importArchive(workingFile,
                new ArchiveOperationListener() {
                    @Override
                    public void onProgress(ArchiveOperation operation, ArchiveProgress progress) {
                        updateProgress(progress);
                    }

                    @Override
                    public void onStateChanged(ArchiveOperation operation) {
                        if (generation != workGeneration) return;
                        if (operation.getState() == ArchiveOperation.State.COMPLETED) {
                            finishOperation(true, ArchiveOperation.ErrorCode.NONE, false, generation);
                        } else if (operation.getState() == ArchiveOperation.State.FAILED
                                || operation.getState() == ArchiveOperation.State.CANCELLED) {
                            finishOperation(false, operation.getErrorCode(), false, generation);
                        }
                    }
                });
    }

    private void showProgress(String message) {
        dismissProgress();
        Activity activity = getParentActivity();
        if (activity == null) return;
        progressDialog = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.setMessage(message);
        progressDialog.setCanCancel(true);
        progressDialog.setButton(DialogInterface.BUTTON_NEGATIVE, LocaleController.getString(R.string.Cancel),
                (dialog, which) -> cancelWork());
        progressDialog.setOnCancelListener(dialog -> cancelWork());
        progressDialog.showDelayed(150);
    }

    private void updateProgress(ArchiveProgress progress) {
        if (progress == null) return;
        String message = LocaleController.getString(R.string.ArchiveWorking);
        if (progress.totalRecords > 0) {
            message += " " + progress.processedRecords + " / " + progress.totalRecords;
        } else if (progress.totalBytes > 0) {
            message += " " + (progress.processedBytes * 100 / Math.max(1, progress.totalBytes)) + "%";
        }
        updateProgressMessage(message);
    }

    private void updateProgressMessage(String message) {
        if (progressDialog != null) progressDialog.setMessage(message);
    }

    private void finishOperation(boolean success, ArchiveOperation.ErrorCode errorCode,
                                 boolean deleteSafDestination, int generation) {
        if (generation != workGeneration) return;
        currentOperation = null;
        dismissProgress();
        Context context = getContext();
        if (!success && deleteSafDestination && context != null && exportUri != null) {
            try {
                context.getContentResolver().delete(exportUri, null, null);
            } catch (Throwable ignore) {
            }
        }
        cleanupWorkingFile();
        exportUri = null;
        if (context != null) {
            if (success) {
                Toast.makeText(context, R.string.ArchiveOperationCompleted, Toast.LENGTH_LONG).show();
            } else if (errorCode != ArchiveOperation.ErrorCode.CANCELLED) {
                Toast.makeText(context, LocaleController.formatString(R.string.ArchiveOperationFailedCode,
                        errorCode == null ? "IO_ERROR" : errorCode.name()), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void cancelWork() {
        copyCancelled = true;
        workGeneration++;
        ArchiveOperation operation = currentOperation;
        currentOperation = null;
        if (operation != null) operation.cancel();
        dismissProgress();
        cleanupWorkingFile();
        exportUri = null;
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Throwable ignore) {
            }
            progressDialog = null;
        }
    }

    private File createWorkingFile(Context context, String prefix, String suffix) {
        File directory = new File(context.getCacheDir(), "local_archive_transfer");
        if (!directory.exists()) directory.mkdirs();
        return new File(directory, prefix + "-" + UUID.randomUUID() + suffix);
    }

    private void cleanupWorkingFile() {
        File file = workingFile;
        workingFile = null;
        if (file != null && file.exists() && !file.delete()) {
            FileLog.e("Local archive UI temporary file cleanup failed");
        }
    }

    @Override
    public void onFragmentDestroy() {
        cancelWork();
        super.onFragmentDestroy();
    }
}
