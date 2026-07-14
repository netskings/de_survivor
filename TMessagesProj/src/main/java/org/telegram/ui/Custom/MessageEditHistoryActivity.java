package org.telegram.ui.Custom;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.archive.ArchiveMessageRecord;
import org.telegram.messenger.archive.ArchiveSchema;
import org.telegram.messenger.archive.ArchiveService;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Displays every archived revision as a regular Telegram message bubble.
 * The interaction model is adapted from AyuGram's AyuMessageHistory screen.
 */
public class MessageEditHistoryActivity extends BaseFragment implements ArchiveService.EditListener {
    private static final int DELETE_LOCAL = 1;
    private final long dialogId;
    private final long topicId;
    private final int messageId;
    private final ArrayList<ArchiveMessageRecord> versions = new ArrayList<>();
    private RecyclerListView listView;
    private HistoryAdapter adapter;
    private TextView emptyView;
    private boolean loading = true;
    private int loadGeneration;

    public MessageEditHistoryActivity(long dialogId, long topicId, int messageId) {
        this.dialogId = dialogId;
        this.topicId = topicId;
        this.messageId = messageId;
    }

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) return false;
        ArchiveService.getInstance().addEditListener(this);
        loadHistory();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        ArchiveService.getInstance().removeEditListener(this);
        super.onFragmentDestroy();
    }

    private void loadHistory() {
        final int generation = ++loadGeneration;
        loading = true;
        updateEmptyView();
        ArchiveService.getInstance().loadMessageHistory(currentAccount, dialogId, topicId, messageId, result -> {
            if (generation != loadGeneration) return;
            versions.clear();
            versions.addAll(result);
            loading = false;
            updateActionBar();
            if (adapter != null) adapter.notifyDataSetChanged();
            updateEmptyView();
        });
    }

    @Override
    public void onMessageEdited(int accountEnvironment, long accountId, long editedDialogId,
                                long editedTopicId, int editedMessageId) {
        if (accountEnvironment != (ConnectionsManager.getInstance(currentAccount).isTestBackend() ? 1 : 0)
                || accountId != UserConfig.getInstance(currentAccount).getClientUserId()
                || editedDialogId != dialogId || editedTopicId != topicId || editedMessageId != messageId) {
            return;
        }
        loadHistory();
    }

    @Override
    public View createView(Context context) {
        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.MessageEditHistory));
        actionBar.setSubtitle(String.valueOf(messageId));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else if (id == DELETE_LOCAL) confirmDeleteLocal();
            }
        });
        actionBar.createMenu().addItem(DELETE_LOCAL, R.drawable.msg_delete);
        updateActionBar();

        listView = new RecyclerListView(context);
        listView.setItemAnimator(null);
        listView.setLayoutAnimation(null);
        listView.setVerticalScrollBarEnabled(true);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        });
        listView.setAdapter(adapter = new HistoryAdapter(context));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        updateEmptyView();
        return fragmentView;
    }

    private void confirmDeleteLocal() {
        if (getParentActivity() == null) return;
        org.telegram.ui.ActionBar.AlertDialog.Builder builder =
                new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.ArchiveDeleteLocal));
        builder.setMessage(LocaleController.getString(R.string.ArchiveDeleteLocalConfirm));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) ->
                ArchiveService.getInstance().deleteLocalMessage(currentAccount, dialogId, topicId, messageId,
                        success -> {
                            if (getParentActivity() != null) {
                                Toast.makeText(getParentActivity(), success ? R.string.ArchiveDeletedLocal
                                        : R.string.ArchiveOperationFailed, Toast.LENGTH_SHORT).show();
                            }
                            if (success) finishFragment();
                        }));
        showDialog(builder.create());
    }

    private void updateActionBar() {
        if (actionBar == null) return;
        TLObject peer = getMessagesController().getUserOrChat(dialogId);
        if (peer instanceof TLRPC.User) {
            actionBar.setTitle(UserObject.getFirstName((TLRPC.User) peer));
        } else if (peer instanceof TLRPC.Chat && !TextUtils.isEmpty(((TLRPC.Chat) peer).title)) {
            actionBar.setTitle(((TLRPC.Chat) peer).title);
        } else {
            actionBar.setTitle(LocaleController.getString(R.string.MessageEditHistory));
        }
        actionBar.setSubtitle(String.valueOf(messageId));
    }

    private void updateEmptyView() {
        if (emptyView == null) return;
        if (loading) {
            emptyView.setText(LocaleController.getString(R.string.Loading));
            emptyView.setVisibility(View.VISIBLE);
        } else if (versions.isEmpty()) {
            emptyView.setText(LocaleController.getString(R.string.MessageEditHistoryEmpty));
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private MessageObject createMessageObject(ArchiveMessageRecord record) {
        TLRPC.Message message = deserialize(record);
        if (message == null) {
            TLRPC.TL_message fallback = new TLRPC.TL_message();
            fallback.id = record.messageId;
            fallback.dialog_id = record.dialogId;
            fallback.peer_id = getMessagesController().getPeer(record.dialogId);
            if (record.senderId != 0) fallback.from_id = getMessagesController().getPeer(record.senderId);
            fallback.message = record.text;
            fallback.media = new TLRPC.TL_messageMediaEmpty();
            message = fallback;
        }
        message.dialog_id = record.dialogId;
        message.date = versionDate(record);
        message.edit_hide = true;
        return new MessageObject(currentAccount, message, false, true);
    }

    private TLRPC.Message deserialize(ArchiveMessageRecord record) {
        if (record.rawFormatVersion != ArchiveSchema.RAW_FORMAT_VERSION) return null;
        byte[] payload = record.copyRawPayload();
        if (payload == null || payload.length == 0) return null;
        SerializedData data = new SerializedData(payload);
        try {
            return TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
        } catch (Throwable ignore) {
            return null;
        } finally {
            data.cleanup();
        }
    }

    private int versionDate(ArchiveMessageRecord record) {
        long date = record.savedAt != 0 ? record.savedAt
                : record.editDate != 0 ? record.editDate : record.messageDate;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, date));
    }

    private class HistoryAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        HistoryAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return versions.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            HistoryMessageCell cell = new HistoryMessageCell(context);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ArchiveMessageRecord record = versions.get(position);
            HistoryMessageCell cell = (HistoryMessageCell) holder.itemView;
            cell.setArchiveRecord(record);
            cell.setMessageObject(createMessageObject(record), null, false, false, position == 0);
        }
    }

    private class HistoryMessageCell extends ChatMessageCell {
        private ArchiveMessageRecord record;

        HistoryMessageCell(Context context) {
            super(context, MessageEditHistoryActivity.this.currentAccount);
            setFullyDraw(true);
            isChat = false;
            setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
            });
            setOnClickListener(view -> {
                MessageObject message = getMessageObject();
                if (message != null && MessageEditHistoryActivity.this.getParentActivity() != null
                        && (message.isPhoto() || message.getDocument() != null)) {
                    AndroidUtilities.openForView(message, MessageEditHistoryActivity.this.getParentActivity(),
                            MessageEditHistoryActivity.this.getResourceProvider(), false);
                } else {
                    copyText();
                }
            });
            setOnLongClickListener(view -> {
                copyText();
                return true;
            });
        }

        void setArchiveRecord(ArchiveMessageRecord record) {
            this.record = record;
        }

        private void copyText() {
            if (record == null || TextUtils.isEmpty(record.text)) return;
            AndroidUtilities.addToClipboard(record.text);
            BulletinFactory.of(MessageEditHistoryActivity.this)
                    .createCopyBulletin(LocaleController.getString(R.string.MessageCopied))
                    .show();
        }
    }
}
