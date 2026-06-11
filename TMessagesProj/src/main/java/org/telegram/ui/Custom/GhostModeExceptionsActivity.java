package org.telegram.ui.Custom;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;
import java.util.Collections;

public class GhostModeExceptionsActivity extends BaseFragment {

    private static final int VIEW_TYPE_ADD_EXCEPTION = 0;
    private static final int VIEW_TYPE_CHAT = 1;
    private static final int VIEW_TYPE_DIVIDER = 2;
    private static final int VIEW_TYPE_DELETE_ALL = 3;
    private static final int VIEW_TYPE_INFO = 4;

    private RecyclerListView recyclerListView;
    private ListAdapter adapter;
    private final ArrayList<Long> exceptionDialogIds = new ArrayList<>();

    private int rowCount;
    private int addExceptionRow;
    private int exceptionsStartRow;
    private int exceptionsEndRow;
    private int dividerRow;
    private int deleteAllRow;
    private int infoRow;

    @Override
    public boolean onFragmentCreate() {
        updateRows(false);
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;

        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setTitle(LocaleController.getString(R.string.CustomSettingsGhostModeExceptions));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        recyclerListView = new RecyclerListView(context);
        DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setDelayAnimations(false);
        itemAnimator.setSupportsChangeAnimations(false);
        recyclerListView.setItemAnimator(itemAnimator);
        recyclerListView.setLayoutManager(new LinearLayoutManager(context));
        recyclerListView.setAdapter(adapter = new ListAdapter(context));
        recyclerListView.setOnItemClickListener((view, position) -> {
            if (position == addExceptionRow) {
                openAddException();
            } else if (position >= exceptionsStartRow && position < exceptionsEndRow) {
                showRemoveExceptionAlert(exceptionDialogIds.get(position - exceptionsStartRow));
            } else if (position == deleteAllRow) {
                showClearExceptionsAlert();
            }
        });

        frameLayout.addView(recyclerListView);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        updateRows(false);
        return fragmentView;
    }

    private void updateRows(boolean notify) {
        exceptionDialogIds.clear();
        exceptionDialogIds.addAll(CustomSettings.getGhostModeExceptionDialogIds());
        Collections.sort(exceptionDialogIds);

        rowCount = 0;
        addExceptionRow = rowCount++;
        exceptionsStartRow = rowCount;
        rowCount += exceptionDialogIds.size();
        exceptionsEndRow = rowCount;
        if (exceptionDialogIds.isEmpty()) {
            dividerRow = -1;
            deleteAllRow = -1;
        } else {
            dividerRow = rowCount++;
            deleteAllRow = rowCount++;
        }
        infoRow = rowCount++;

        if (notify && adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void openAddException() {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("checkCanWrite", false);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_DEFAULT);
        args.putBoolean("allowGlobalSearch", false);

        DialogsActivity activity = new DialogsActivity(args);
        activity.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            activity.finishFragment();
            for (int i = 0; i < dids.size(); i++) {
                CustomSettings.setGhostModeDisabledForDialog(dids.get(i).dialogId, true);
            }
            updateRows(true);
            return true;
        });
        presentFragment(activity);
    }

    private void showRemoveExceptionAlert(long dialogId) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.CustomSettingsRemoveGhostModeExceptionTitle));
        builder.setMessage(LocaleController.getString(R.string.CustomSettingsRemoveGhostModeExceptionInfo));
        builder.setPositiveButton(LocaleController.getString(R.string.CustomSettingsRemoveGhostModeException), (dialog, which) -> {
            CustomSettings.setGhostModeDisabledForDialog(dialogId, false);
            updateRows(true);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog alertDialog = builder.create();
        showDialog(alertDialog);
        alertDialog.redPositive();
    }

    private void showClearExceptionsAlert() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.CustomSettingsClearGhostModeExceptionsTitle));
        builder.setMessage(LocaleController.getString(R.string.CustomSettingsClearGhostModeExceptionsInfo));
        builder.setPositiveButton(LocaleController.getString(R.string.CustomSettingsClearGhostModeExceptions), (dialog, which) -> {
            CustomSettings.clearGhostModeExceptions();
            updateRows(true);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog alertDialog = builder.create();
        showDialog(alertDialog);
        alertDialog.redPositive();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int viewType = holder.getItemViewType();
            return viewType == VIEW_TYPE_ADD_EXCEPTION || viewType == VIEW_TYPE_CHAT || viewType == VIEW_TYPE_DELETE_ALL;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == addExceptionRow) {
                return VIEW_TYPE_ADD_EXCEPTION;
            }
            if (position >= exceptionsStartRow && position < exceptionsEndRow) {
                return VIEW_TYPE_CHAT;
            }
            if (position == dividerRow) {
                return VIEW_TYPE_DIVIDER;
            }
            if (position == deleteAllRow) {
                return VIEW_TYPE_DELETE_ALL;
            }
            if (position == infoRow) {
                return VIEW_TYPE_INFO;
            }
            return VIEW_TYPE_INFO;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_ADD_EXCEPTION) {
                TextCell textCell = new TextCell(context);
                textCell.setTextAndIcon(LocaleController.getString(R.string.CustomSettingsAddGhostModeException), R.drawable.msg_contact_add, true);
                textCell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueButton);
                textCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                view = textCell;
            } else if (viewType == VIEW_TYPE_CHAT) {
                view = new UserCell(context, 4, 0, false, false);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == VIEW_TYPE_DELETE_ALL) {
                TextCell textCell = new TextCell(context);
                textCell.setText(LocaleController.getString(R.string.CustomSettingsClearGhostModeExceptions), false);
                textCell.setColors(-1, Theme.key_text_RedRegular);
                textCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                view = textCell;
            } else if (viewType == VIEW_TYPE_DIVIDER) {
                view = new ShadowSectionCell(context);
            } else {
                TextInfoPrivacyCell cell = new TextInfoPrivacyCell(context);
                cell.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                view = cell;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int viewType = holder.getItemViewType();
            if (viewType == VIEW_TYPE_CHAT) {
                UserCell cell = (UserCell) holder.itemView;
                long dialogId = exceptionDialogIds.get(position - exceptionsStartRow);
                TLObject object = getMessagesController().getUserOrChat(dialogId);
                String title = null;
                if (object instanceof TLRPC.User) {
                    TLRPC.User user = (TLRPC.User) object;
                    title = user.self ? LocaleController.getString(R.string.SavedMessages) : ContactsController.formatName(user.first_name, user.last_name);
                } else if (object instanceof TLRPC.Chat) {
                    title = ((TLRPC.Chat) object).title;
                }
                boolean divider = position + 1 < exceptionsEndRow;
                cell.setSelfAsSavedMessages(true);
                cell.setData(object, title, LocaleController.getString(R.string.CustomSettingsGhostModeDisabledHere), 0, divider);
            } else if (viewType == VIEW_TYPE_INFO) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setText(LocaleController.getString(R.string.CustomSettingsGhostModeExceptionsInfo));
            }
        }
    }
}
