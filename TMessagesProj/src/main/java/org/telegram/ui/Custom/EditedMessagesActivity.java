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

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.archive.ArchiveMessageRecord;
import org.telegram.messenger.archive.ArchiveService;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Locale;

public class EditedMessagesActivity extends BaseFragment {

    private static final int SEARCH_BUTTON = 1;

    private final long dialogId;
    private final long topicId;
    private final ArrayList<ArchiveMessageRecord> allItems = new ArrayList<>();
    private final ArrayList<ArchiveMessageRecord> visibleItems = new ArrayList<>();
    private ChatActivity chatActivity;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private TextView emptyView;
    private String searchQuery = "";
    private boolean loading = true;

    public EditedMessagesActivity(long dialogId) {
        this(dialogId, -1);
    }

    public EditedMessagesActivity(long dialogId, long topicId) {
        this.dialogId = dialogId;
        this.topicId = topicId;
    }

    public EditedMessagesActivity setChatActivity(ChatActivity chatActivity) {
        this.chatActivity = chatActivity;
        return this;
    }

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) {
            return false;
        }
        loadEditedMessages();
        return true;
    }

    @Override
    public View createView(Context context) {
        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.ViewEditedMessages));
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
                openMessage(visibleItems.get(position));
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position >= 0 && position < visibleItems.size()) {
                ArchiveMessageRecord item = visibleItems.get(position);
                presentFragment(new MessageEditHistoryActivity(item.dialogId, item.topicId, item.messageId));
                return true;
            }
            return false;
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

    private void loadEditedMessages() {
        loading = true;
        updateEmptyView();
        ArchiveService.getInstance().loadEditedMessages(currentAccount, dialogId, topicId, result -> {
            allItems.clear();
            allItems.addAll(result);
            loading = false;
            applySearch();
        });
    }

    private void applySearch() {
        visibleItems.clear();
        String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.US);
        for (int i = 0; i < allItems.size(); i++) {
            ArchiveMessageRecord item = allItems.get(i);
            if (TextUtils.isEmpty(query) || getSearchText(item).contains(query)) {
                visibleItems.add(item);
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmptyView();
    }

    private String getSearchText(ArchiveMessageRecord item) {
        String author = getAuthorName(item);
        String oldText = item.previousText;
        String newText = getMessageText(item);
        return (author + "\n" + oldText + "\n" + newText).toLowerCase(Locale.US);
    }

    private void updateEmptyView() {
        if (emptyView == null) {
            return;
        }
        if (loading) {
            emptyView.setText(LocaleController.getString(R.string.Loading));
            emptyView.setVisibility(View.VISIBLE);
        } else if (visibleItems.isEmpty()) {
            emptyView.setText(TextUtils.isEmpty(searchQuery) ? LocaleController.getString(R.string.ViewEditedMessagesEmpty) : LocaleController.getString(R.string.ViewEditedMessagesSearchEmpty));
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private void openMessage(ArchiveMessageRecord item) {
        ChatActivity target = chatActivity;
        if (target == null) {
            presentFragment(ChatActivity.of(item.dialogId, item.messageId), true);
            return;
        }
        finishFragment();
        AndroidUtilities.runOnUIThread(() -> target.scrollToMessageId(item.messageId, 0, true, 0, true, 0), 180);
    }

    private String getAuthorName(ArchiveMessageRecord item) {
        if (item.senderId == UserConfig.getInstance(currentAccount).getClientUserId()) {
            return LocaleController.getString(R.string.ViewDeletedYou);
        }
        long fromId = item.senderId;
        if (fromId != 0) {
            Object sender = MessagesController.getInstance(currentAccount).getUserOrChat(fromId);
            if (sender instanceof TLRPC.User) {
                TLRPC.User user = (TLRPC.User) sender;
                String name = ContactsController.formatName(user.first_name, user.last_name);
                return TextUtils.isEmpty(name) ? LocaleController.getString(R.string.ViewEditedUnknownSender) : name;
            } else if (sender instanceof TLRPC.Chat) {
                String title = ((TLRPC.Chat) sender).title;
                return TextUtils.isEmpty(title) ? LocaleController.getString(R.string.ViewEditedUnknownSender) : title;
            }
        }
        return LocaleController.getString(R.string.ViewEditedUnknownSender);
    }

    private String getMessageText(ArchiveMessageRecord item) {
        if (!TextUtils.isEmpty(item.text)) return item.text;
        if (item.messageType.contains("Photo")) {
            return LocaleController.getString(R.string.AttachPhoto);
        }
        if (item.messageType.contains("Video")) {
            return LocaleController.getString(R.string.AttachVideo);
        }
        if (item.messageType.contains("Document")) {
            return LocaleController.getString(R.string.AttachDocument);
        }
        return LocaleController.getString(R.string.ViewEditedUnsupportedMessage);
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
            EditedMessageCell cell = new EditedMessageCell(context);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((EditedMessageCell) holder.itemView).setItem(visibleItems.get(position), position + 1 < visibleItems.size());
        }
    }

    private class EditedMessageCell extends LinearLayout {
        private final TextView titleView;
        private final TextView messageView;
        private final TextView metaView;
        private final View divider;

        EditedMessageCell(Context context) {
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

        void setItem(ArchiveMessageRecord item, boolean drawDivider) {
            titleView.setText(getAuthorName(item));
            String text = LocaleController.getString(R.string.ViewEditedBefore) + " " + item.previousText
                    + "\n" + LocaleController.getString(R.string.ViewEditedAfter) + " " + getMessageText(item);
            messageView.setText(text);
            metaView.setText(LocaleController.formatString(R.string.ViewEditedRevisions,
                    item.revisionCount, LocaleController.formatDateTime(item.editDate, true)));
            divider.setVisibility(drawDivider ? View.VISIBLE : View.GONE);
        }
    }
}
