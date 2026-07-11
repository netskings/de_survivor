package org.telegram.ui.Custom;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Feed.FeedSettingsActivity;

public class CustomSettingsActivity extends BaseFragment {

    private static final int REQUEST_SAVE_TEMPORARY_MEDIA_FOLDER = 4242;
    private static final String ARG_SCREEN = "screen";
    private static final int SCREEN_MAIN = 0;
    private static final int SCREEN_GENERAL = 1;
    private static final int SCREEN_PRIVACY = 2;
    private static final int SCREEN_HISTORY = 3;
    private static final int SCREEN_MEDIA = 4;
    private static final int SCREEN_APPEARANCE = 5;
    private static final int SCREEN_TRANSLATION = 6;

    private int generalSettingsRow;
    private int privacySettingsRow;
    private int historySettingsRow;
    private int mediaSettingsRow;
    private int appearanceSettingsRow;
    private int translationSettingsRow;

    private int rowCount;
    private ListAdapter listAdapter;
    private int adsHeaderRow;
    private int hideAdsRow;
    private int hideAdsInfoRow;
    private int proxyHeaderRow;
    private int hideProxySponsorRow;
    private int hideProxySponsorInfoRow;

    private int feedHeaderRow;
    private int feedSettingsRow;
    private int feedInfoRow;

    private int ghostHeaderRow;
    private int hideOnlineStatusRow;
    private int hideOnlineStatusInfoRow;
    private int goOfflineAutomaticallyRow;
    private int goOfflineAutomaticallyInfoRow;
    private int scheduleMessagesInGhostModeRow;
    private int scheduleMessagesInGhostModeInfoRow;
    private int hideTypingStatusRow;
    private int hideTypingStatusInfoRow;
    private int hideReadStatusRow;
    private int hideReadStatusInfoRow;
    private int hideBlockedUsersMessagesRow;
    private int localMessageFiltersRow;
    private int hideStoryViewsRow;
    private int hideStoryViewsInfoRow;
    private int alertBeforeOpeningStoryRow;
    private int alertBeforeOpeningStoryInfoRow;
    private int readOnInteractRow;
    private int readOnInteractInfoRow;
    private int ghostModeExceptionsRow;
    private int ghostModeExceptionsInfoRow;
    private int keepLastSeenUpdatedInGhostModeRow;
    private int keepLastSeenUpdatedInGhostModeInfoRow;

    private int restrictionsHeaderRow;
    private int antiRecallRow;
    private int antiRecallInfoRow;
    private int messageLabelsRow;
    private int messageLabelsInfoRow;
    private int chatTranslationProviderRow;
    private int chatTranslationProviderInfoRow;
    private int keepMessageEditHistoryRow;
    private int keepMessageEditHistoryInfoRow;
    private int saveTemporaryMediaRow;
    private int saveTemporaryMediaInfoRow;
    private int saveTemporaryMediaPathRow;
    private int saveTemporaryMediaPathInfoRow;
    private int keepTemporaryMediaInChatRow;
    private int keepTemporaryMediaInChatInfoRow;
    private int keepKickedChatsCacheRow;
    private int keepKickedChatsCacheInfoRow;
    private int bypassContentProtectionRow;
    private int bypassContentProtectionInfoRow;

    public CustomSettingsActivity() {
    }

    private CustomSettingsActivity(int screen) {
        super(createArguments(screen));
    }

    private static Bundle createArguments(int screen) {
        Bundle args = new Bundle();
        args.putInt(ARG_SCREEN, screen);
        return args;
    }

    private int getScreen() {
        return getArguments() == null ? SCREEN_MAIN : getArguments().getInt(ARG_SCREEN, SCREEN_MAIN);
    }

    private void resetRows() {
        generalSettingsRow = privacySettingsRow = historySettingsRow = mediaSettingsRow = appearanceSettingsRow = translationSettingsRow = -1;
        adsHeaderRow = hideAdsRow = hideAdsInfoRow = proxyHeaderRow = hideProxySponsorRow = hideProxySponsorInfoRow = -1;
        feedHeaderRow = feedSettingsRow = feedInfoRow = -1;
        ghostHeaderRow = hideOnlineStatusRow = hideOnlineStatusInfoRow = goOfflineAutomaticallyRow = goOfflineAutomaticallyInfoRow = -1;
        scheduleMessagesInGhostModeRow = scheduleMessagesInGhostModeInfoRow = hideTypingStatusRow = hideTypingStatusInfoRow = -1;
        hideReadStatusRow = hideReadStatusInfoRow = hideBlockedUsersMessagesRow = localMessageFiltersRow = hideStoryViewsRow = hideStoryViewsInfoRow = -1;
        alertBeforeOpeningStoryRow = alertBeforeOpeningStoryInfoRow = readOnInteractRow = readOnInteractInfoRow = -1;
        ghostModeExceptionsRow = ghostModeExceptionsInfoRow = keepLastSeenUpdatedInGhostModeRow = keepLastSeenUpdatedInGhostModeInfoRow = -1;
        restrictionsHeaderRow = antiRecallRow = antiRecallInfoRow = messageLabelsRow = messageLabelsInfoRow = -1;
        chatTranslationProviderRow = chatTranslationProviderInfoRow = keepMessageEditHistoryRow = keepMessageEditHistoryInfoRow = -1;
        saveTemporaryMediaRow = saveTemporaryMediaInfoRow = saveTemporaryMediaPathRow = saveTemporaryMediaPathInfoRow = -1;
        keepTemporaryMediaInChatRow = keepTemporaryMediaInChatInfoRow = keepKickedChatsCacheRow = keepKickedChatsCacheInfoRow = -1;
        bypassContentProtectionRow = bypassContentProtectionInfoRow = -1;
    }

    @Override
    public boolean onFragmentCreate() {
        rowCount = 0;
        resetRows();
        switch (getScreen()) {
            case SCREEN_GENERAL:
                hideAdsRow = rowCount++;
                hideProxySponsorRow = rowCount++;
                feedSettingsRow = rowCount++;
                break;
            case SCREEN_PRIVACY:
                hideOnlineStatusRow = rowCount++;
                goOfflineAutomaticallyRow = rowCount++;
                scheduleMessagesInGhostModeRow = rowCount++;
                keepLastSeenUpdatedInGhostModeRow = rowCount++;
                hideTypingStatusRow = rowCount++;
                hideReadStatusRow = rowCount++;
                hideBlockedUsersMessagesRow = rowCount++;
                localMessageFiltersRow = rowCount++;
                hideStoryViewsRow = rowCount++;
                alertBeforeOpeningStoryRow = rowCount++;
                readOnInteractRow = rowCount++;
                ghostModeExceptionsRow = rowCount++;
                bypassContentProtectionRow = rowCount++;
                break;
            case SCREEN_HISTORY:
                antiRecallRow = rowCount++;
                keepMessageEditHistoryRow = rowCount++;
                keepKickedChatsCacheRow = rowCount++;
                break;
            case SCREEN_MEDIA:
                saveTemporaryMediaRow = rowCount++;
                saveTemporaryMediaPathRow = rowCount++;
                keepTemporaryMediaInChatRow = rowCount++;
                break;
            case SCREEN_APPEARANCE:
                messageLabelsRow = rowCount++;
                break;
            case SCREEN_TRANSLATION:
                chatTranslationProviderRow = rowCount++;
                break;
            default:
                generalSettingsRow = rowCount++;
                privacySettingsRow = rowCount++;
                historySettingsRow = rowCount++;
                mediaSettingsRow = rowCount++;
                appearanceSettingsRow = rowCount++;
                translationSettingsRow = rowCount++;
                break;
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != REQUEST_SAVE_TEMPORARY_MEDIA_FOLDER) {
            return;
        }
        Activity activity = getParentActivity();
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || activity == null) {
            return;
        }
        Uri uri = data.getData();
        int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (takeFlags == 0) {
            takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        }
        try {
            activity.getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (Exception e) {
            FileLog.e(e);
        }
        CustomSettings.setSaveTemporaryMediaTreeUri(uri);
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        Toast.makeText(activity, LocaleController.getString(R.string.CustomSettingsSaveTemporaryMediaFolderSelected), Toast.LENGTH_SHORT).show();
    }

    @Override
    public View createView(Context context) {

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(getScreenTitle());
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        RecyclerListView listView = getRecyclerListView(context);

        root.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = root;
        return fragmentView;
    }

    private String getScreenTitle() {
        switch (getScreen()) {
            case SCREEN_GENERAL:
                return getString(R.string.CustomSettingsGeneral);
            case SCREEN_PRIVACY:
                return getString(R.string.CustomSettingsPrivacy);
            case SCREEN_HISTORY:
                return getString(R.string.CustomSettingsMessagesHistory);
            case SCREEN_MEDIA:
                return getString(R.string.CustomSettingsMediaStorage);
            case SCREEN_APPEARANCE:
                return getString(R.string.CustomSettingsAppearance);
            case SCREEN_TRANSLATION:
                return getString(R.string.CustomSettingsTranslation);
            default:
                return getString(R.string.CustomSettingsTitle);
        }
    }

    private void promptRestart() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(getString(R.string.CustomSettingsRestartPromptTitle));
        builder.setMessage(getString(R.string.CustomSettingsRestartPromptMessage));
        builder.setPositiveButton(getString(R.string.CustomSettingsRestartNow), (dialog, which) -> {
            try {
                final PackageManager pm = activity.getPackageManager();
                final Intent intent = pm.getLaunchIntentForPackage(activity.getPackageName());
                activity.finishAffinity();
                if (intent != null) {
                    activity.startActivity(intent);
                }
            } catch (Exception ignore) {}
            System.exit(0);
        });
        builder.setNegativeButton(getString(R.string.CustomSettingsRestartLater), null);
        showDialog(builder.create());
    }

    private void showSaveTemporaryMediaPathDialog() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.CustomSettingsSaveTemporaryMediaPathTitle));
        builder.setMessage(LocaleController.formatString(R.string.CustomSettingsSaveTemporaryMediaPathMessage, CustomSettings.saveTemporaryMediaDisplayPath()));
        builder.setItems(new CharSequence[]{
                LocaleController.getString(R.string.CustomSettingsSaveTemporaryMediaChooseFolder),
                LocaleController.getString(R.string.CustomSettingsSaveTemporaryMediaManualPath),
                LocaleController.getString(R.string.CustomSettingsSaveTemporaryMediaPathReset)
        }, (dialog, which) -> {
            if (which == 0) {
                openSaveTemporaryMediaFolderPicker();
            } else if (which == 1) {
                showSaveTemporaryMediaManualPathDialog();
            } else if (which == 2) {
                CustomSettings.resetSaveTemporaryMediaRelativePath();
                if (listAdapter != null) {
                    listAdapter.notifyDataSetChanged();
                }
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void openSaveTemporaryMediaFolderPicker() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
            startActivityForResult(intent, REQUEST_SAVE_TEMPORARY_MEDIA_FOLDER);
        } catch (Exception e) {
            FileLog.e(e);
            Toast.makeText(activity, LocaleController.getString(R.string.CustomSettingsSaveTemporaryMediaFolderPickerError), Toast.LENGTH_SHORT).show();
        }
    }

    private void showSaveTemporaryMediaManualPathDialog() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }

        LinearLayout linearLayout = new LinearLayout(activity);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        TextView message = new TextView(activity);
        message.setText(LocaleController.getString(R.string.CustomSettingsSaveTemporaryMediaManualPathMessage));
        message.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        message.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        message.setGravity(Gravity.LEFT);
        linearLayout.addView(message, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 12));

        EditTextBoldCursor editText = new EditTextBoldCursor(activity);
        editText.setText("Downloads/" + CustomSettings.saveTemporaryMediaRelativePath());
        editText.setSelectAllOnFocus(true);
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintColor(Theme.getColor(Theme.key_groupcreate_hintText));
        editText.setHintText(LocaleController.getString(R.string.CustomSettingsSaveTemporaryMediaPathHint));
        editText.setBackgroundDrawable(null);
        editText.setLineColors(
                Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                Theme.getColor(Theme.key_text_RedRegular));
        editText.setPadding(0, 0, 0, 0);
        linearLayout.addView(editText, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 48, 24, 0, 24, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.CustomSettingsSaveTemporaryMediaPathTitle));
        builder.setView(linearLayout);
        builder.setPositiveButton(LocaleController.getString(R.string.CustomSettingsSaveTemporaryMediaPathApply), (dialog, which) -> {
            CustomSettings.setSaveTemporaryMediaRelativePath(editText.getText().toString());
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        });
        builder.setNeutralButton(LocaleController.getString(R.string.CustomSettingsSaveTemporaryMediaPathReset), (dialog, which) -> {
            CustomSettings.resetSaveTemporaryMediaRelativePath();
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog alertDialog = builder.create();
        showDialog(alertDialog);
        editText.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(editText), 200);
    }

    private void showMessageLabelOptions(boolean edited) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(getString(edited ? R.string.CustomSettingsEditedMessageLabel : R.string.CustomSettingsDeletedMessageLabel));
        builder.setItems(new CharSequence[]{
                getString(R.string.CustomSettingsMessageLabelTextAndColor),
                getString(R.string.CustomSettingsMessageLabelIcon),
                getString(R.string.CustomSettingsMessageLabelReset)
        }, (dialog, which) -> {
            if (which == 0) {
                showMessageLabelTextAndColorDialog(edited);
            } else if (which == 1) {
                showMessageLabelIconDialog(edited);
            } else {
                if (edited) {
                    CustomSettings.resetEditedMessageLabel();
                } else {
                    CustomSettings.resetDeletedMessageLabel();
                }
                if (listAdapter != null) {
                    listAdapter.notifyDataSetChanged();
                }
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showMessageLabelTextAndColorDialog(boolean edited) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);

        EditTextBoldCursor textField = createDialogInput(activity, getString(R.string.CustomSettingsMessageLabelTextHint));
        textField.setText(edited ? CustomSettings.editedMessageLabel(getString(R.string.EditedMessage)) : CustomSettings.deletedMessageLabel());
        layout.addView(textField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 24, 12, 24, 0));

        EditTextBoldCursor colorField = createDialogInput(activity, getString(R.string.CustomSettingsMessageLabelColorHint));
        int color = edited ? CustomSettings.editedMessageLabelColor() : CustomSettings.deletedMessageLabelColor();
        colorField.setText(color == 0 ? "" : String.format("#%08X", color));
        layout.addView(colorField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 24, 0, 24, 12));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(getString(edited ? R.string.CustomSettingsEditedMessageLabel : R.string.CustomSettingsDeletedMessageLabel));
        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.CustomSettingsMessageLabelApply), null);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignore -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            int parsedColor = 0;
            String colorText = colorField.getText().toString().trim();
            if (!TextUtils.isEmpty(colorText)) {
                try {
                    parsedColor = Color.parseColor(colorText);
                } catch (IllegalArgumentException e) {
                    colorField.setError(getString(R.string.CustomSettingsMessageLabelColorError));
                    return;
                }
            }
            if (edited) {
                CustomSettings.setEditedMessageLabel(textField.getText().toString().trim());
                CustomSettings.setEditedMessageLabelColor(parsedColor);
            } else {
                CustomSettings.setDeletedMessageLabel(textField.getText().toString().trim());
                CustomSettings.setDeletedMessageLabelColor(parsedColor);
            }
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
            dialog.dismiss();
        }));
        showDialog(dialog);
        textField.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(textField), 200);
    }

    private EditTextBoldCursor createDialogInput(Activity activity, String hint) {
        EditTextBoldCursor editText = new EditTextBoldCursor(activity);
        editText.setSingleLine(true);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintColor(Theme.getColor(Theme.key_groupcreate_hintText));
        editText.setHintText(hint);
        editText.setBackgroundDrawable(null);
        editText.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        editText.setPadding(0, 0, 0, 0);
        return editText;
    }

    private void showMessageLabelIconDialog(boolean edited) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(getString(R.string.CustomSettingsMessageLabelIcon));
        builder.setItems(new CharSequence[]{
                getString(R.string.CustomSettingsMessageLabelIconDefault),
                getString(R.string.CustomSettingsMessageLabelIconNone),
                getString(R.string.CustomSettingsMessageLabelIconEdit),
                getString(R.string.CustomSettingsMessageLabelIconDelete),
                getString(R.string.CustomSettingsMessageLabelIconPin),
                getString(R.string.CustomSettingsMessageLabelIconCheck)
        }, (dialog, which) -> {
            if (edited) {
                CustomSettings.setEditedMessageLabelIcon(which);
            } else {
                CustomSettings.setDeletedMessageLabelIcon(which);
            }
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showChatTranslationProviderDialog() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(getString(R.string.CustomSettingsChatTranslationProvider));
        builder.setItems(new CharSequence[]{
                getString(R.string.CustomSettingsChatTranslationProviderAuto),
                getString(R.string.CustomSettingsChatTranslationProviderTelegram),
                getString(R.string.CustomSettingsChatTranslationProviderGoogle)
        }, (dialog, which) -> {
            CustomSettings.setChatTranslationProvider(which);
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
            dialog.dismiss();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private String getChatTranslationProviderName() {
        int provider = CustomSettings.chatTranslationProvider();
        if (provider == CustomSettings.CHAT_TRANSLATION_PROVIDER_TELEGRAM) {
            return getString(R.string.CustomSettingsChatTranslationProviderTelegram);
        } else if (provider == CustomSettings.CHAT_TRANSLATION_PROVIDER_GOOGLE) {
            return getString(R.string.CustomSettingsChatTranslationProviderGoogle);
        }
        return getString(R.string.CustomSettingsChatTranslationProviderAuto);
    }

    @NonNull
    private RecyclerListView getRecyclerListView(Context context) {
        listAdapter = new ListAdapter(context);

        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(listAdapter);
        listView.setVerticalScrollBarEnabled(false);

        listView.setOnItemClickListener((view, position) -> {
            if (position == generalSettingsRow) {
                presentFragment(new CustomSettingsActivity(SCREEN_GENERAL));
            } else if (position == privacySettingsRow) {
                presentFragment(new CustomSettingsActivity(SCREEN_PRIVACY));
            } else if (position == historySettingsRow) {
                presentFragment(new CustomSettingsActivity(SCREEN_HISTORY));
            } else if (position == mediaSettingsRow) {
                presentFragment(new CustomSettingsActivity(SCREEN_MEDIA));
            } else if (position == appearanceSettingsRow) {
                presentFragment(new CustomSettingsActivity(SCREEN_APPEARANCE));
            } else if (position == translationSettingsRow) {
                presentFragment(new CustomSettingsActivity(SCREEN_TRANSLATION));
            } else if (position == hideAdsRow) {
                boolean val = !CustomSettings.hideAds();
                CustomSettings.setHideAds(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == hideProxySponsorRow) {
                boolean val = !CustomSettings.hideProxySponsor();
                CustomSettings.setHideProxySponsor(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == feedSettingsRow) {
                presentFragment(new FeedSettingsActivity());
            } else if (position == hideOnlineStatusRow) {
                boolean val = !CustomSettings.hideOnlineStatus();
                CustomSettings.setHideOnlineStatus(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == goOfflineAutomaticallyRow) {
                boolean val = !CustomSettings.goOfflineAutomatically();
                CustomSettings.setGoOfflineAutomatically(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == scheduleMessagesInGhostModeRow) {
                boolean val = !CustomSettings.scheduleMessagesInGhostMode();
                CustomSettings.setScheduleMessagesInGhostMode(val);
                if (listAdapter != null) {
                    listAdapter.notifyDataSetChanged();
                }
            } else if (position == hideTypingStatusRow) {
                boolean val = !CustomSettings.hideTypingStatus();
                CustomSettings.setHideTypingStatus(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == hideReadStatusRow) {
                boolean val = !CustomSettings.hideReadStatus();
                CustomSettings.setHideReadStatus(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == hideBlockedUsersMessagesRow) {
                boolean val = !CustomSettings.hideBlockedUsersMessages();
                CustomSettings.setHideBlockedUsersMessages(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == localMessageFiltersRow) {
                presentFragment(new LocalMessageFiltersActivity());
            } else if (position == hideStoryViewsRow) {
                boolean val = !CustomSettings.hideStoryViews();
                CustomSettings.setHideStoryViews(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == alertBeforeOpeningStoryRow) {
                boolean val = !CustomSettings.alertBeforeOpeningStory();
                CustomSettings.setAlertBeforeOpeningStory(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == readOnInteractRow) {
                boolean val = !CustomSettings.readOnInteract();
                CustomSettings.setReadOnInteract(val);
                if (listAdapter != null) {
                    listAdapter.notifyDataSetChanged();
                }
            } else if (position == keepLastSeenUpdatedInGhostModeRow) {
                boolean val = !CustomSettings.keepLastSeenUpdatedInGhostMode();
                CustomSettings.setKeepLastSeenUpdatedInGhostMode(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == ghostModeExceptionsRow) {
                presentFragment(new GhostModeExceptionsActivity());
            } else if (position == antiRecallRow) {
                boolean val = !CustomSettings.antiRecall();
                CustomSettings.setAntiRecall(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == messageLabelsRow) {
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle(getString(R.string.CustomSettingsMessageLabels));
                builder.setItems(new CharSequence[]{getString(R.string.CustomSettingsEditedMessageLabel), getString(R.string.CustomSettingsDeletedMessageLabel)}, (dialog, which) -> showMessageLabelOptions(which == 0));
                showDialog(builder.create());
            } else if (position == chatTranslationProviderRow) {
                showChatTranslationProviderDialog();
            } else if (position == keepMessageEditHistoryRow) {
                boolean val = !CustomSettings.keepMessageEditHistory();
                CustomSettings.setKeepMessageEditHistory(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == saveTemporaryMediaRow) {
                boolean val = !CustomSettings.saveTemporaryMedia();
                CustomSettings.setSaveTemporaryMedia(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == saveTemporaryMediaPathRow) {
                showSaveTemporaryMediaPathDialog();
            } else if (position == keepTemporaryMediaInChatRow) {
                boolean val = !CustomSettings.keepTemporaryMediaInChat();
                CustomSettings.setKeepTemporaryMediaInChat(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == keepKickedChatsCacheRow) {
                boolean val = !CustomSettings.keepKickedChatsCache();
                CustomSettings.setKeepKickedChatsCache(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
            } else if (position == bypassContentProtectionRow) {
                boolean val = !CustomSettings.bypassContentProtection();
                CustomSettings.setBypassContentProtection(val);
                if (view instanceof TextCheckCell)
                    ((TextCheckCell) view).setChecked(val);
                promptRestart();
            }
        });
        return listView;
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK  = 1;
    private static final int TYPE_INFO   = 2;
    private static final int TYPE_TEXT_CELL = 3;

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context ctx;

        ListAdapter(Context ctx) { this.ctx = ctx; }

        @Override
        public int getItemCount() { return rowCount; }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_CHECK || holder.getItemViewType() == TYPE_TEXT_CELL;
        }

        @Override
        public int getItemViewType(int pos) {
            if (pos == generalSettingsRow || pos == privacySettingsRow || pos == historySettingsRow ||
                    pos == mediaSettingsRow || pos == appearanceSettingsRow || pos == translationSettingsRow) return TYPE_TEXT_CELL;
            if (pos == adsHeaderRow || pos == proxyHeaderRow)         return TYPE_HEADER;
            if (pos == hideAdsRow || pos == hideProxySponsorRow)      return TYPE_CHECK;
            if (pos == hideAdsInfoRow || pos == hideProxySponsorInfoRow) return TYPE_INFO;
            if (pos == feedHeaderRow)    return TYPE_HEADER;
            if (pos == feedSettingsRow)  return TYPE_TEXT_CELL;
            if (pos == feedInfoRow)      return TYPE_INFO;
            if (pos == ghostHeaderRow) return TYPE_HEADER;
            if (pos == hideOnlineStatusRow) return TYPE_CHECK;
            if (pos == hideOnlineStatusInfoRow) return TYPE_INFO;
            if (pos == goOfflineAutomaticallyRow) return TYPE_CHECK;
            if (pos == goOfflineAutomaticallyInfoRow) return TYPE_INFO;
            if (pos == scheduleMessagesInGhostModeRow) return TYPE_CHECK;
            if (pos == scheduleMessagesInGhostModeInfoRow) return TYPE_INFO;
            if (pos == keepLastSeenUpdatedInGhostModeRow) return TYPE_CHECK;
            if (pos == keepLastSeenUpdatedInGhostModeInfoRow) return TYPE_INFO;
            if (pos == hideTypingStatusRow) return TYPE_CHECK;
            if (pos == hideTypingStatusInfoRow) return TYPE_INFO;
            if (pos == hideReadStatusRow) return TYPE_CHECK;
            if (pos == hideBlockedUsersMessagesRow) return TYPE_CHECK;
            if (pos == localMessageFiltersRow) return TYPE_TEXT_CELL;
            if (pos == hideReadStatusInfoRow) return TYPE_INFO;
            if (pos == hideStoryViewsRow) return TYPE_CHECK;
            if (pos == hideStoryViewsInfoRow) return TYPE_INFO;
            if (pos == alertBeforeOpeningStoryRow) return TYPE_CHECK;
            if (pos == alertBeforeOpeningStoryInfoRow) return TYPE_INFO;
            if (pos == readOnInteractRow) return TYPE_CHECK;
            if (pos == readOnInteractInfoRow) return TYPE_INFO;
            if (pos == ghostModeExceptionsRow) return TYPE_TEXT_CELL;
            if (pos == ghostModeExceptionsInfoRow) return TYPE_INFO;
            if (pos == restrictionsHeaderRow) return TYPE_HEADER;
            if (pos == antiRecallRow) return TYPE_CHECK;
            if (pos == antiRecallInfoRow) return TYPE_INFO;
            if (pos == messageLabelsRow) return TYPE_TEXT_CELL;
            if (pos == messageLabelsInfoRow) return TYPE_INFO;
            if (pos == chatTranslationProviderRow) return TYPE_TEXT_CELL;
            if (pos == chatTranslationProviderInfoRow) return TYPE_INFO;
            if (pos == keepMessageEditHistoryRow) return TYPE_CHECK;
            if (pos == keepMessageEditHistoryInfoRow) return TYPE_INFO;
            if (pos == saveTemporaryMediaRow) return TYPE_CHECK;
            if (pos == saveTemporaryMediaInfoRow) return TYPE_INFO;
            if (pos == saveTemporaryMediaPathRow) return TYPE_TEXT_CELL;
            if (pos == saveTemporaryMediaPathInfoRow) return TYPE_INFO;
            if (pos == keepTemporaryMediaInChatRow) return TYPE_CHECK;
            if (pos == keepTemporaryMediaInChatInfoRow) return TYPE_INFO;
            if (pos == keepKickedChatsCacheRow) return TYPE_CHECK;
            if (pos == keepKickedChatsCacheInfoRow) return TYPE_INFO;
            if (pos == bypassContentProtectionRow) return TYPE_CHECK;
            if (pos == bypassContentProtectionInfoRow) return TYPE_INFO;
            return TYPE_HEADER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_HEADER:
                    view = new HeaderCell(ctx);
                    view.setBackgroundColor(
                            Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_CHECK:
                    view = new TextCheckCell(ctx);
                    view.setBackgroundColor(
                            Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_INFO:
                    view = new TextInfoPrivacyCell(ctx);
                    view.setBackground(Theme.getThemedDrawableByKey(ctx,
                            R.drawable.greydivider,
                            Theme.key_windowBackgroundGrayShadow));
                    break;
                case TYPE_TEXT_CELL:
                    view = new TextCell(ctx);
                    view.setBackgroundColor(
                            Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                default:
                    view = new TextInfoPrivacyCell(ctx);
                    view.setBackground(Theme.getThemedDrawableByKey(ctx,
                            R.drawable.greydivider,
                            Theme.key_windowBackgroundGrayShadow));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (pos == adsHeaderRow)   cell.setText(getString(R.string.CustomSettingsAdvertisingHeader));
                    if (pos == proxyHeaderRow)  cell.setText(getString(R.string.Proxy));
                    if (pos == feedHeaderRow)   cell.setText(getString(R.string.CustomSettingsFeedHeader));
                    if (pos == ghostHeaderRow) cell.setText(getString(R.string.CustomSettingsGhostHeader));
                    if (pos == restrictionsHeaderRow) cell.setText(getString(R.string.CustomSettingsRestrictionsHeader));
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    boolean divider = pos + 1 < rowCount;
                    if (pos == hideAdsRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsHideSponsoredMessages),
                                CustomSettings.hideAds(), divider);
                    }
                    if (pos == hideProxySponsorRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsHideProxySponsor),
                                CustomSettings.hideProxySponsor(), divider);
                    }
                    if (pos == hideOnlineStatusRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsHideOnlineStatus),
                                CustomSettings.hideOnlineStatus(), divider);
                    }
                    if (pos == goOfflineAutomaticallyRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsGoOfflineAutomatically),
                                CustomSettings.goOfflineAutomatically(), divider);
                    }
                    if (pos == scheduleMessagesInGhostModeRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsScheduleMessagesInGhostMode),
                                CustomSettings.scheduleMessagesInGhostMode(), divider);
                    }
                    if (pos == hideTypingStatusRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsHideTypingStatus),
                                CustomSettings.hideTypingStatus(), divider);
                    }
                    if (pos == hideReadStatusRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsHideReadStatus),
                                CustomSettings.hideReadStatus(), divider);
                    }
                    if (pos == hideBlockedUsersMessagesRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsHideBlockedUsersMessages),
                                CustomSettings.hideBlockedUsersMessages(), divider);
                    }
                    if (pos == hideStoryViewsRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsHideStoryViews),
                                CustomSettings.hideStoryViews(), divider);
                    }
                    if (pos == alertBeforeOpeningStoryRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsAlertBeforeOpeningStory),
                                CustomSettings.alertBeforeOpeningStory(), divider);
                    }
                    if (pos == readOnInteractRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsReadOnInteract),
                                CustomSettings.readOnInteract(), divider);
                    }
                    if (pos == keepLastSeenUpdatedInGhostModeRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsKeepLastSeenUpdatedInGhostMode),
                                CustomSettings.keepLastSeenUpdatedInGhostMode(), divider);
                    }
                    if (pos == antiRecallRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsAntiRecall),
                                CustomSettings.antiRecall(), divider);
                    }
                    if (pos == keepMessageEditHistoryRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsKeepMessageEditHistory),
                                CustomSettings.keepMessageEditHistory(), divider);
                    }
                    if (pos == saveTemporaryMediaRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsSaveTemporaryMedia),
                                CustomSettings.saveTemporaryMedia(), divider);
                    }
                    if (pos == keepTemporaryMediaInChatRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsKeepTemporaryMediaInChat),
                                CustomSettings.keepTemporaryMediaInChat(), divider);
                    }
                    if (pos == keepKickedChatsCacheRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsKeepKickedChatsCache),
                                CustomSettings.keepKickedChatsCache(), divider);
                    }
                    if (pos == bypassContentProtectionRow) {
                        cell.setTextAndCheck(getString(R.string.CustomSettingsBypassContentProtection),
                                CustomSettings.bypassContentProtection(), divider);
                    }
                    break;
                }
                case TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (pos == hideAdsInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsHideAdsInfo));
                    }
                    if (pos == hideProxySponsorInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsHideProxySponsorInfo));
                    }
                    if (pos == feedInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsFeedInfo));
                    }
                    if (pos == hideOnlineStatusInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsHideOnlineStatusInfo));
                    }
                    if (pos == goOfflineAutomaticallyInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsGoOfflineAutomaticallyInfo));
                    }
                    if (pos == scheduleMessagesInGhostModeInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsScheduleMessagesInGhostModeInfo));
                    }
                    if (pos == hideTypingStatusInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsHideTypingStatusInfo));
                    }
                    if (pos == hideReadStatusInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsHideReadStatusInfo));
                    }
                    if (pos == hideStoryViewsInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsHideStoryViewsInfo));
                    }
                    if (pos == alertBeforeOpeningStoryInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsAlertBeforeOpeningStoryInfo));
                    }
                    if (pos == readOnInteractInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsReadOnInteractInfo));
                    }
                    if (pos == ghostModeExceptionsInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsGhostModeExceptionsInfo));
                    }
                    if (pos == keepLastSeenUpdatedInGhostModeInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsKeepLastSeenUpdatedInGhostModeInfo));
                    }
                    if (pos == antiRecallInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsAntiRecallInfo));
                    }
                    if (pos == messageLabelsInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsMessageLabelsInfo));
                    }
                    if (pos == chatTranslationProviderInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsChatTranslationProviderInfo));
                    }
                    if (pos == keepMessageEditHistoryInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsKeepMessageEditHistoryInfo));
                    }
                    if (pos == saveTemporaryMediaInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsSaveTemporaryMediaInfo));
                    }
                    if (pos == saveTemporaryMediaPathInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsSaveTemporaryMediaPathInfo));
                    }
                    if (pos == keepTemporaryMediaInChatInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsKeepTemporaryMediaInChatInfo));
                    }
                    if (pos == keepKickedChatsCacheInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsKeepKickedChatsCacheInfo));
                    }
                    if (pos == bypassContentProtectionInfoRow) {
                        cell.setText(getString(R.string.CustomSettingsBypassContentProtectionInfo));
                    }
                    break;
                }
                case TYPE_TEXT_CELL: {
                    TextCell cell = (TextCell) holder.itemView;
                    boolean divider = pos + 1 < rowCount;
                    if (pos == generalSettingsRow) {
                        cell.setTextAndIcon(getString(R.string.CustomSettingsGeneral), R.drawable.msg_settings, true);
                    }
                    if (pos == privacySettingsRow) {
                        cell.setTextAndIcon(getString(R.string.CustomSettingsPrivacy), R.drawable.msg_secret, true);
                    }
                    if (pos == historySettingsRow) {
                        cell.setTextAndIcon(getString(R.string.CustomSettingsMessagesHistory), R.drawable.msg_customize, true);
                    }
                    if (pos == mediaSettingsRow) {
                        cell.setTextAndIcon(getString(R.string.CustomSettingsMediaStorage), R.drawable.msg_media, true);
                    }
                    if (pos == appearanceSettingsRow) {
                        cell.setTextAndIcon(getString(R.string.CustomSettingsAppearance), R.drawable.msg_customize, true);
                    }
                    if (pos == translationSettingsRow) {
                        cell.setTextAndIcon(getString(R.string.CustomSettingsTranslation), R.drawable.msg_translate, false);
                    }
                    if (pos == feedSettingsRow) {
                        cell.setTextAndIcon(getString(R.string.CustomSettingsFeedSettings), R.drawable.msg_channel, divider);
                    }
                    if (pos == ghostModeExceptionsRow) {
                        int count = CustomSettings.ghostModeExceptionsCount();
                        cell.setTextAndValue(getString(R.string.CustomSettingsGhostModeExceptions),
                                count == 0 ? "" : LocaleController.formatPluralString("Chats", count), divider);
                    }
                    if (pos == localMessageFiltersRow) {
                        cell.setTextAndValue(getString(R.string.LocalMessageFiltersTitle),
                                getString(R.string.LocalMessageFiltersValue), divider);
                    }
                    if (pos == messageLabelsRow) {
                        cell.setTextAndValue(getString(R.string.CustomSettingsMessageLabels), getString(R.string.CustomSettingsMessageLabelsValue), divider);
                    }
                    if (pos == chatTranslationProviderRow) {
                        cell.setTextAndValue(getString(R.string.CustomSettingsChatTranslationProvider), getChatTranslationProviderName(), divider);
                    }
                    if (pos == saveTemporaryMediaPathRow) {
                        cell.setTextAndValue(getString(R.string.CustomSettingsSaveTemporaryMediaPath),
                                CustomSettings.saveTemporaryMediaDisplayPath(), divider);
                    }
                    break;
                }
            }
        }
    }
}
