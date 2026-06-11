package org.telegram.ui.Custom;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;

public class DeletedMessagesActivity extends BaseFragment {

    private static final int SEARCH_BUTTON = 1;

    private final long dialogId;
    private final ArrayList<DeletedMessageItem> allItems = new ArrayList<>();
    private final ArrayList<DeletedMessageItem> visibleItems = new ArrayList<>();

    private RecyclerListView listView;
    private ListAdapter adapter;
    private TextView emptyView;
    private String searchQuery = "";
    private boolean loading = true;

    public DeletedMessagesActivity(long dialogId) {
        this.dialogId = dialogId;
    }

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) {
            return false;
        }
        loadDeletedMessages();
        return true;
    }

    @Override
    public View createView(Context context) {
        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.ViewDeletedMessages));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ActionBarMenuItem searchItem = actionBar.createMenu().addItem(SEARCH_BUTTON, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchCollapse() {
                searchQuery = "";
                applySearch();
            }

            @Override
            public void onTextChanged(EditText editText) {
                searchQuery = editText.getText().toString();
                applySearch();
            }
        });
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search));

        listView = new RecyclerListView(context);
        DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setDelayAnimations(false);
        itemAnimator.setSupportsChangeAnimations(false);
        listView.setItemAnimator(itemAnimator);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < visibleItems.size()) {
                showDeletedMessage(visibleItems.get(position));
            }
        });
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

    private void loadDeletedMessages() {
        loading = true;
        updateEmptyView();
        MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<DeletedMessageItem> result = new ArrayList<>();
            HashSet<Integer> seenMessageIds = new HashSet<>();

            loadMessagesFromTable(result, seenMessageIds, "messages_v2", false);
            loadMessagesFromTable(result, seenMessageIds, "messages_topics", false);
            loadMessagesFromTable(result, seenMessageIds, "deleted_messages_v2", true);

            Collections.sort(result, (a, b) -> {
                int firstDate = a.messageObject != null && a.messageObject.messageOwner != null ? a.messageObject.messageOwner.date : a.savedAt;
                int secondDate = b.messageObject != null && b.messageObject.messageOwner != null ? b.messageObject.messageOwner.date : b.savedAt;
                return Integer.compare(secondDate, firstDate);
            });

            AndroidUtilities.runOnUIThread(() -> {
                allItems.clear();
                allItems.addAll(result);
                loading = false;
                applySearch();
            });
        });
    }

    private void loadMessagesFromTable(ArrayList<DeletedMessageItem> result, HashSet<Integer> seenMessageIds, String tableName, boolean archivedTable) {
        SQLiteCursor cursor = null;
        try {
            String query;
            if (archivedTable) {
                query = String.format(Locale.US, "SELECT mid, data, date FROM %s WHERE dialog_id = %d ORDER BY date DESC", tableName, dialogId);
            } else {
                query = String.format(Locale.US, "SELECT mid, data, date FROM %s WHERE uid = %d AND is_recalled = 1 ORDER BY date DESC", tableName, dialogId);
            }
            cursor = MessagesStorage.getInstance(currentAccount).getDatabase().queryFinalized(query);
            while (cursor.next()) {
                int mid = cursor.intValue(0);
                if (seenMessageIds.contains(mid)) {
                    continue;
                }
                NativeByteBuffer data = cursor.byteBufferValue(1);
                if (data == null) {
                    continue;
                }
                try {
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message != null) {
                        message.readAttachPath(data, UserConfig.getInstance(currentAccount).clientUserId);
                        message.id = mid;
                        message.is_recalled = true;
                        if (message.dialog_id == 0) {
                            message.dialog_id = dialogId;
                        }
                        if (message.peer_id == null) {
                            message.peer_id = MessagesController.getInstance(currentAccount).getPeer(dialogId);
                        }
                        int savedAt = cursor.intValue(2);
                        if (message.date == 0) {
                            message.date = savedAt;
                        }
                        result.add(new DeletedMessageItem(new MessageObject(currentAccount, message, false, false), savedAt));
                        seenMessageIds.add(mid);
                    }
                } finally {
                    data.reuse();
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
    }

    private void applySearch() {
        visibleItems.clear();
        String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.US);
        for (int i = 0; i < allItems.size(); i++) {
            DeletedMessageItem item = allItems.get(i);
            if (TextUtils.isEmpty(query) || getSearchText(item).contains(query)) {
                visibleItems.add(item);
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmptyView();
    }

    private String getSearchText(DeletedMessageItem item) {
        String author = getAuthorName(item.messageObject);
        String text = getMessageText(item.messageObject);
        return (author + "\n" + text).toLowerCase(Locale.US);
    }

    private void updateEmptyView() {
        if (emptyView == null) {
            return;
        }
        if (loading) {
            emptyView.setText(LocaleController.getString(R.string.Loading));
            emptyView.setVisibility(View.VISIBLE);
        } else if (visibleItems.isEmpty()) {
            emptyView.setText(TextUtils.isEmpty(searchQuery) ? LocaleController.getString(R.string.ViewDeletedMessagesEmpty) : LocaleController.getString(R.string.ViewDeletedMessagesSearchEmpty));
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private void showDeletedMessage(DeletedMessageItem item) {
        if (getParentActivity() == null) {
            return;
        }
        String text = getMessageText(item.messageObject);
        String savedAt = LocaleController.formatString(R.string.ViewDeletedSavedAt, LocaleController.formatDateTime(item.savedAt, true));
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getAuthorName(item.messageObject));
        builder.setMessage(text + "\n\n" + savedAt);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        builder.setNegativeButton(LocaleController.getString(R.string.Copy), (dialog, which) -> AndroidUtilities.addToClipboard(text));
        showDialog(builder.create());
    }

    private String getAuthorName(MessageObject messageObject) {
        if (messageObject == null) {
            return LocaleController.getString(R.string.ViewDeletedUnknownSender);
        }
        if (messageObject.isOutOwner() || messageObject.getFromChatId() == UserConfig.getInstance(currentAccount).getClientUserId()) {
            return LocaleController.getString(R.string.ViewDeletedYou);
        }
        if (messageObject.messageOwner != null && !TextUtils.isEmpty(messageObject.messageOwner.post_author)) {
            return messageObject.messageOwner.post_author;
        }
        long fromId = messageObject.getFromChatId();
        if (fromId != 0) {
            Object sender = MessagesController.getInstance(currentAccount).getUserOrChat(fromId);
            if (sender instanceof TLRPC.User) {
                TLRPC.User user = (TLRPC.User) sender;
                String name = ContactsController.formatName(user.first_name, user.last_name);
                return TextUtils.isEmpty(name) ? LocaleController.getString(R.string.ViewDeletedUnknownSender) : name;
            } else if (sender instanceof TLRPC.Chat) {
                String title = ((TLRPC.Chat) sender).title;
                return TextUtils.isEmpty(title) ? LocaleController.getString(R.string.ViewDeletedUnknownSender) : title;
            }
        }
        return LocaleController.getString(R.string.ViewDeletedUnknownSender);
    }

    private String getMessageText(MessageObject messageObject) {
        if (messageObject == null) {
            return "";
        }
        if (!TextUtils.isEmpty(messageObject.caption)) {
            return messageObject.caption.toString();
        }
        if (!TextUtils.isEmpty(messageObject.messageText)) {
            return messageObject.messageText.toString();
        }
        if (messageObject.isPhoto()) {
            return LocaleController.getString(R.string.AttachPhoto);
        }
        if (messageObject.isVideo()) {
            return LocaleController.getString(R.string.AttachVideo);
        }
        if (messageObject.getDocument() != null) {
            return LocaleController.getString(R.string.AttachDocument);
        }
        return "";
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return visibleItems.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            DeletedMessageCell cell = new DeletedMessageCell(context);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((DeletedMessageCell) holder.itemView).setItem(visibleItems.get(position), position + 1 < visibleItems.size());
        }
    }

    private class DeletedMessageCell extends LinearLayout {
        private final TextView titleView;
        private final TextView messageView;
        private final TextView metaView;
        private final View divider;

        DeletedMessageCell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(9), AndroidUtilities.dp(16), 0);

            titleView = new TextView(context);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 20));

            messageView = new TextView(context);
            messageView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            messageView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            messageView.setMaxLines(5);
            messageView.setEllipsize(TextUtils.TruncateAt.END);
            messageView.setLineSpacing(AndroidUtilities.dp(1), 1.0f);
            addView(messageView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

            metaView = new TextView(context);
            metaView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            metaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            metaView.setSingleLine(true);
            metaView.setEllipsize(TextUtils.TruncateAt.END);
            addView(metaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 18, 0, 6, 0, 8));

            divider = new View(context);
            divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
            addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
        }

        void setItem(DeletedMessageItem item, boolean drawDivider) {
            titleView.setText(getAuthorName(item.messageObject));
            messageView.setText(getMessageText(item.messageObject));
            int date = item.messageObject != null && item.messageObject.messageOwner != null ? item.messageObject.messageOwner.date : item.savedAt;
            metaView.setText(LocaleController.formatDateTime(date, true));
            divider.setVisibility(drawDivider ? View.VISIBLE : View.GONE);
        }
    }

    private static class DeletedMessageItem {
        private final MessageObject messageObject;
        private final int savedAt;

        private DeletedMessageItem(MessageObject messageObject, int savedAt) {
            this.messageObject = messageObject;
            this.savedAt = savedAt;
        }
    }
}
