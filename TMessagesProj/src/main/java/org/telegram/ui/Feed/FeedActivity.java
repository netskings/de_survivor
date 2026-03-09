package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.Components.ShareAlert;

import java.util.ArrayList;

public class FeedActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {

    private RecyclerListView listView;
    private FeedAdapter adapter;
    private LinearLayoutManager layoutManager;
    private FeedController feedController;
    private SwipeRefreshLayout swipeRefreshLayout;
    private TextView emptyView;

    private boolean hasMainTabs;

    private static Parcelable savedScrollState;
    private static boolean hasScrollState = false;

    private Runnable markReadRunnable;

    @Override
    public boolean onFragmentCreate() {
        feedController = FeedController.getInstance(currentAccount);
        hasMainTabs = arguments != null && arguments.getBoolean("hasMainTabs", false);
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle(LocaleController.getString("FeedTitle", R.string.FeedTitle));
        actionBar.setBackButtonDrawable(null);
        actionBar.setCastShadows(false);
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));
        actionBar.setTitleColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        actionBar.setAddToContainer(true);

        FrameLayout rootView = new FrameLayout(context);
        rootView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));

        int topPad = ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight;
        int bottomPad = hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT + DialogsActivity.MAIN_TABS_MARGIN + 16) : 0;

        adapter = new FeedAdapter(context, currentAccount, resourceProvider);
        adapter.setCellCallback(new FeedPostCell.Callback() {
            @Override public void onHeaderClick(FeedController.FeedItem item) {
                saveScroll(); openChannel(item);
            }
            @Override public void onMediaClick(FeedController.FeedItem item, int idx) {
                openMedia(item, idx);
            }
            @Override public void onMenuClick(View anchor, FeedController.FeedItem item) {
                showMenu(anchor, item);
            }
            @Override public void onCommentsClick(FeedController.FeedItem item) {
                saveScroll();
                MessageObject msg = item.getPrimaryMessage();
                TLRPC.MessageReplies replies = msg.messageOwner.replies;
                if (replies != null && replies.channel_id != 0) {
                    Bundle args = new Bundle();
                    args.putLong("chat_id", replies.channel_id);
                    if (replies.max_id != 0) args.putInt("message_id", replies.max_id);
                    presentFragment(new ChatActivity(args));
                } else openChannel(item);
            }
            @Override public void onShareClick(FeedController.FeedItem item) {
                sharePost(item);
            }
            @Override public void onForwardClick(long channelId, int messageId) {
                saveScroll();
                Bundle args = new Bundle();
                args.putLong("chat_id", channelId);
                if (messageId > 0) args.putInt("message_id", messageId);
                presentFragment(new ChatActivity(args));
            }
            @Override public void onReplyClick(long channelId, int messageId) {
                saveScroll();
                Bundle args = new Bundle();
                args.putLong("chat_id", channelId);
                if (messageId > 0) args.putInt("message_id", messageId);
                presentFragment(new ChatActivity(args));
            }
        });

        layoutManager = new LinearLayoutManager(context);
        listView = new RecyclerListView(context);
        listView.setLayoutManager(layoutManager);
        listView.setAdapter(adapter);
        listView.setClipToPadding(false);
        listView.setVerticalScrollBarEnabled(true);
        listView.setPadding(0, topPad, 0, bottomPad);

        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                scheduleMarkAsRead();
            }
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) {
                    cancelScheduledMark(); markVisibleAsRead();
                }
            }
        });

        swipeRefreshLayout = new SwipeRefreshLayout(context);
        swipeRefreshLayout.setProgressViewOffset(false, topPad, topPad + dp(64));
        swipeRefreshLayout.setColorSchemeColors(Theme.getColor(Theme.key_featuredStickers_addButton, resourceProvider));
        swipeRefreshLayout.setOnRefreshListener(() -> loadFeed(true));
        swipeRefreshLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        rootView.addView(swipeRefreshLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString("FeedEmpty", R.string.FeedEmpty));
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider));
        emptyView.setTextSize(16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        emptyView.setPadding(dp(40), 0, dp(40), 0);
        rootView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = rootView;
        return fragmentView;
    }

    private void showMenu(View anchor, FeedController.FeedItem item) {
        ItemOptions options = ItemOptions.makeOptions(this, anchor);

        options.add(R.drawable.msg_saved, LocaleController.getString("FeedSavedToBookmarks", R.string.FeedSavedToBookmarks), () -> {
            forwardToSaved(item);
            BulletinFactory.of(FeedActivity.this)
                    .createSimpleBulletin(R.drawable.msg_saved, LocaleController.getString("FeedSavedToBookmarks", R.string.FeedSavedToBookmarks))
                    .show();
        });

        options.add(R.drawable.msg_channel, "Open channel", () -> {
            saveScroll(); openChannel(item);
        });

        options.add(R.drawable.msg_markread, "Mark as read", () -> {
            feedController.markAsRead(item);
            int pos = adapter.findItemPosition(item);
            if (pos >= 0) adapter.updateItem(pos);
        });

        options.show();
    }

    private void sharePost(FeedController.FeedItem item) {
        if (getParentActivity() == null) return;

        MessageObject msg = item.getPrimaryMessage();
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-item.channelId);

        String link;
        if (chat != null && !TextUtils.isEmpty(chat.username)) {
            link = "https://t.me/" + chat.username + "/" + msg.getId();
        } else {
            link = "https://t.me/c/" + (-item.channelId) + "/" + msg.getId();
        }

        ShareAlert shareAlert = new ShareAlert(getParentActivity(), null, link, false, link, false);
        showDialog(shareAlert);
    }

    private void forwardToSaved(FeedController.FeedItem item) {
        try {
            long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
            TLRPC.TL_messages_forwardMessages req = new TLRPC.TL_messages_forwardMessages();
            req.to_peer = MessagesController.getInstance(currentAccount).getInputPeer(selfId);
            req.from_peer = MessagesController.getInstance(currentAccount).getInputPeer(item.channelId);
            req.random_id = new ArrayList<>();
            req.id = new ArrayList<>();
            req.silent = true;
            for (MessageObject m : item.messages) {
                req.id.add(m.getId());
                req.random_id.add(Utilities.random.nextLong());
            }
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (r, e) -> {});
        } catch (Exception e) { /* ignore */ }
    }

    private void openMedia(FeedController.FeedItem item, int index) {
        ArrayList<MessageObject> media = new ArrayList<>();
        for (MessageObject msg : item.messages) {
            TLRPC.MessageMedia m = msg.messageOwner.media;
            if (m == null || m instanceof TLRPC.TL_messageMediaEmpty || m instanceof TLRPC.TL_messageMediaWebPage) continue;
            if (m instanceof TLRPC.TL_messageMediaPhoto) media.add(msg);
            else if (m instanceof TLRPC.TL_messageMediaDocument && m.document != null) {
                for (TLRPC.DocumentAttribute attr : m.document.attributes) {
                    if (attr instanceof TLRPC.TL_documentAttributeVideo || attr instanceof TLRPC.TL_documentAttributeAnimated) {
                        media.add(msg); break;
                    }
                }
            }
        }
        if (media.isEmpty()) return;
        if (index >= media.size()) index = 0;

        PhotoViewer.getInstance().setParentActivity(getParentActivity(), resourceProvider);
        PhotoViewer.getInstance().openPhoto(
                media,
                index,
                item.channelId,
                0,
                0,
                new PhotoViewer.EmptyPhotoViewerProvider()
        );
    }

    private void scheduleMarkAsRead() {
        if (markReadRunnable != null) return;
        markReadRunnable = () -> { markVisibleAsRead(); markReadRunnable = null; };
        AndroidUtilities.runOnUIThread(markReadRunnable, 1500);
    }

    private void cancelScheduledMark() {
        if (markReadRunnable != null) { AndroidUtilities.cancelRunOnUIThread(markReadRunnable); markReadRunnable = null; }
    }

    private void markVisibleAsRead() {
        if (layoutManager == null || adapter == null) return;
        int first = layoutManager.findFirstVisibleItemPosition();
        int last = layoutManager.findLastVisibleItemPosition();
        for (int i = first; i <= last; i++) {
            if (i < 0 || i >= adapter.getItems().size()) continue;
            FeedController.FeedItem item = adapter.getItems().get(i);
            if (item != null && !item.isRead) feedController.markAsRead(item);
        }
    }

    @Override public void onResume() {
        super.onResume();
        if (hasScrollState && feedController.hasCachedFeed()) {
            adapter.setItems(feedController.getCachedFeed());
            if (savedScrollState != null && layoutManager != null)
                layoutManager.onRestoreInstanceState(savedScrollState);
            updateEmpty();
        } else if (feedController.hasCachedFeed()) {
            adapter.setItems(feedController.getCachedFeed());
            updateEmpty();
        } else {
            loadFeedStreaming();
        }
    }

    @Override public void onPause() {
        super.onPause();
        cancelScheduledMark(); markVisibleAsRead();
        if (layoutManager != null) {
            savedScrollState = layoutManager.onSaveInstanceState();
            hasScrollState = true;
        }
    }

    private void loadFeedStreaming() {
        swipeRefreshLayout.setRefreshing(true);
        emptyView.setVisibility(View.GONE);

        feedController.loadFeedStreaming(true,
                (batch, isLast) -> {
                    adapter.addItems(batch);
                    if (isLast) {
                        swipeRefreshLayout.setRefreshing(false);
                        updateEmpty();
                    }
                },
                (items, hasMore) -> {
                    adapter.setItems(items);
                    swipeRefreshLayout.setRefreshing(false);
                    updateEmpty();
                }
        );
    }

    private void loadFeed(boolean force) {
        if (force) {
            adapter.setItems(new ArrayList<>());
            loadFeedStreaming();
        } else {
            swipeRefreshLayout.setRefreshing(true);
            feedController.loadFeed(false, (items, hasMore) -> {
                swipeRefreshLayout.setRefreshing(false);
                adapter.setItems(items);
                updateEmpty();
            });
        }
    }

    private void updateEmpty() {
        emptyView.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void openChannel(FeedController.FeedItem item) {
        Bundle args = new Bundle();
        args.putLong("chat_id", -item.channelId);
        args.putInt("message_id", item.getMessageId());
        presentFragment(new ChatActivity(args));
    }

    private void saveScroll() {
        if (layoutManager != null) { savedScrollState = layoutManager.onSaveInstanceState(); hasScrollState = true; }
    }

    @Override public boolean canParentTabsSlide(MotionEvent ev, boolean forward) { return true; }
    @Override public void onParentScrollToTop() {
        if (listView != null) listView.smoothScrollToPosition(0);
        swipeRefreshLayout.setRefreshing(true);
        loadFeed(true);
    }
}