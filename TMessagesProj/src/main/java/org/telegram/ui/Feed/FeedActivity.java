package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.Stars.StarsReactionsSheet;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FeedActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {

    private final Runnable onNewPostRunnable = this::onNewPostsReceived;

    private static final int MENU_SETTINGS = 1;

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

    private Runnable pendingPaidSend;
    private FeedController.FeedItem pendingPaidItem;
    private int pendingPaidAmount;
    private long pendingPaidRandomId;
    private Bulletin currentStarBulletin;

    private boolean isLoadingMore = false;
    private View loadingFooter;

    private void onNewPostsReceived() {
        if (adapter == null || !feedController.hasCachedFeed()) return;

        List<FeedController.FeedItem> items = feedController.getCachedFeed();
        int oldCount = adapter.getItemCount();
        int newCount = items.size();

        if (newCount <= oldCount) return;

        adapter.setItemsSilent(items);
        adapter.notifyItemRangeInserted(oldCount, newCount - oldCount);
        updateEmpty();
    }

    @Override
    public boolean onFragmentCreate() {
        feedController = FeedController.getInstance(currentAccount);
        hasMainTabs = arguments != null && arguments.getBoolean("hasMainTabs", false);

        feedController.startObserving();
        feedController.addNewPostListener(onNewPostRunnable);

        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        feedController.removeNewPostListener(onNewPostRunnable);
        feedController.stopObserving();
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle(LocaleController.getString("FeedTitle", R.string.FeedTitle));
        actionBar.setBackButtonDrawable(null);
        actionBar.setCastShadows(false);
        actionBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));
        actionBar.setTitleColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        actionBar.setAddToContainer(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == MENU_SETTINGS) {
                    saveScroll();
                    presentFragment(new FeedSettingsActivity());
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_SETTINGS, R.drawable.msg_settings);

        FrameLayout rootView = new FrameLayout(context);
        rootView.setClipChildren(false);
        rootView.setClipToPadding(false);
        rootView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));

        int topPad = ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight;
        int bottomPad = hasMainTabs
                ? dp(DialogsActivity.MAIN_TABS_HEIGHT + DialogsActivity.MAIN_TABS_MARGIN + 128)
                : dp(128);

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
                openComments(item);
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
            @Override
            public void onInlineButtonClick(FeedController.FeedItem item, TLRPC.KeyboardButton button) {
                saveScroll();
                openChannel(item);
            }
            @Override
            public void onReactionToggle(FeedController.FeedItem item, TLRPC.Reaction reaction) {
                sendReaction(item, reaction);
            }

            @Override
            public void onPaidReactionTap(FeedController.FeedItem item) {
                handlePaidReaction(item, 1);
            }

            @Override
            public void onPaidReactionLongPress(FeedController.FeedItem item) {
                showStarAmountPicker(item);
            }
            @Override
            public void onDoubleTap(FeedController.FeedItem item) {
                handleDoubleTap(item);
            }
        });

        layoutManager = new LinearLayoutManager(context);
        listView = new RecyclerListView(context);
        listView.setLayoutManager(layoutManager);
        listView.setAdapter(adapter);
        listView.setClipToPadding(false);

        loadingFooter = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec,
                        MeasureSpec.makeMeasureSpec(dp(60), MeasureSpec.EXACTLY));
            }
        };
        org.telegram.ui.Components.RadialProgressView progressFooter =
                new org.telegram.ui.Components.RadialProgressView(context);
        progressFooter.setSize(dp(28));
        progressFooter.setProgressColor(Theme.getColor(
                Theme.key_featuredStickers_addButton, resourceProvider));
        ((FrameLayout) loadingFooter).addView(progressFooter,
                LayoutHelper.createFrame(40, 40, Gravity.CENTER));
        loadingFooter.setVisibility(View.GONE);

        int footerBottom = hasMainTabs
                ? dp(DialogsActivity.MAIN_TABS_HEIGHT + DialogsActivity.MAIN_TABS_MARGIN + 24)
                : dp(24);
        rootView.addView(loadingFooter, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, 60,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                0, 0, 0, footerBottom / AndroidUtilities.density));
        org.telegram.ui.Components.RadialProgressView progressView =
                new org.telegram.ui.Components.RadialProgressView(context);
        progressView.setSize(dp(28));
        progressView.setProgressColor(Theme.getColor(
                Theme.key_featuredStickers_addButton, resourceProvider));
        ((FrameLayout) loadingFooter).addView(progressView,
                LayoutHelper.createFrame(40, 40, Gravity.CENTER));
        loadingFooter.setVisibility(View.GONE);

        listView.setVerticalScrollBarEnabled(true);
        listView.setPadding(0, topPad, 0, bottomPad);
        listView.setClipChildren(false);

        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                scheduleMarkAsRead();
                checkLoadMore();
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) {
                    cancelScheduledMark();
                    markVisibleAsRead();
                    checkLoadMore();
                }
            }
        });

        swipeRefreshLayout = new SwipeRefreshLayout(context);
        swipeRefreshLayout.setClipChildren(false);
        swipeRefreshLayout.setClipToPadding(false);
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

    private void handleDoubleTap(FeedController.FeedItem item) {
        int pos = adapter.findItemPosition(item);
        if (pos < 0) return;

        RecyclerView.ViewHolder vh = listView.findViewHolderForAdapterPosition(pos);
        if (vh == null || !(vh.itemView instanceof FeedPostCell)) return;

        FeedPostCell cell = (FeedPostCell) vh.itemView;
        FeedReactionsView reactionsView = cell.getReactionsView();

        if (reactionsView != null) {
            boolean sent = reactionsView.triggerDefaultReaction();
            if (!sent) {
                reactionsView.animate()
                        .scaleX(0.95f).scaleY(0.95f)
                        .setDuration(100)
                        .withEndAction(() ->
                                reactionsView.animate()
                                        .scaleX(1f).scaleY(1f)
                                        .setDuration(150)
                                        .start()
                        ).start();
            }
        }
    }

    private void showMenu(View anchor, FeedController.FeedItem item) {
        ItemOptions options = ItemOptions.makeOptions(this, anchor);

        options.add(R.drawable.msg_saved, "Save to bookmarks", () -> {
            forwardToSaved(item);
            BulletinFactory.of(FeedActivity.this)
                    .createSimpleBulletin(R.drawable.msg_saved,
                            LocaleController.getString("FeedSavedToBookmarks", R.string.FeedSavedToBookmarks))
                    .show();
        });

        options.add(R.drawable.msg_channel, "Open channel", () -> {
            saveScroll();
            openChannel(item);
        });

        options.add(R.drawable.msg_markread, "Mark as read", () -> {
            feedController.markAsRead(item);
            int pos = adapter.findItemPosition(item);
            if (pos >= 0) adapter.updateItem(pos);
        });

        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-item.channelId);
        String channelName = chat != null ? chat.title : "this channel";
        options.add(R.drawable.msg_block2, "Hide from feed", true, () -> {
            long chatId = -item.channelId;
            feedController.hideChannel(chatId);
            adapter.setItems(feedController.getCachedFeed());
            updateEmpty();
            BulletinFactory.of(FeedActivity.this)
                    .createSimpleBulletin(R.drawable.msg_block2,
                            channelName + " hidden from feed")
                    .show();
        });

        options.show();
    }

    private void openComments(FeedController.FeedItem item) {
        MessageObject msg = item.getPrimaryMessage();
        TLRPC.MessageReplies replies = msg.messageOwner.replies;

        if (replies == null || replies.channel_id == 0) {
            openChannel(item);
            return;
        }

        swipeRefreshLayout.setRefreshing(true);

        TLRPC.TL_messages_getDiscussionMessage req = new TLRPC.TL_messages_getDiscussionMessage();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(item.channelId);
        req.msg_id = msg.getId();

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            AndroidUtilities.runOnUIThread(() -> {
                swipeRefreshLayout.setRefreshing(false);

                if (response instanceof TLRPC.TL_messages_discussionMessage) {
                    TLRPC.TL_messages_discussionMessage res = (TLRPC.TL_messages_discussionMessage) response;

                    MessagesController controller = MessagesController.getInstance(currentAccount);
                    controller.putUsers(res.users, false);
                    controller.putChats(res.chats, false);

                    if (!res.messages.isEmpty()) {
                        TLRPC.Message discussionMsg = res.messages.get(0);

                        long chatId = 0;
                        if (discussionMsg.peer_id != null) {
                            if (discussionMsg.peer_id.channel_id != 0) {
                                chatId = discussionMsg.peer_id.channel_id;
                            } else if (discussionMsg.peer_id.chat_id != 0) {
                                chatId = discussionMsg.peer_id.chat_id;
                            }
                        }

                        if (chatId != 0) {
                            ArrayList<MessageObject> threadMessages = new ArrayList<>();
                            for (TLRPC.Message m : res.messages) {
                                threadMessages.add(new MessageObject(currentAccount, m, true, true));
                            }

                            TLRPC.Chat discussionChat = controller.getChat(chatId);

                            Bundle args = new Bundle();
                            args.putLong("chat_id", chatId);
                            args.putInt("message_id", discussionMsg.id);
                            args.putInt("topic_id", discussionMsg.id);

                            ChatActivity chatActivity = new ChatActivity(args);

                            chatActivity.setThreadMessages(
                                    threadMessages,
                                    discussionChat,
                                    discussionMsg.id,
                                    res.read_inbox_max_id,
                                    res.read_outbox_max_id,
                                    null
                            );

                            presentFragment(chatActivity);
                            return;
                        }
                    }
                }

                openChannel(item);
            });
        });
    }

    private void sharePost(FeedController.FeedItem item) {
        if (getParentActivity() == null) return;

        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-item.channelId);
        boolean noForwards = chat != null && chat.noforwards;

        if (!noForwards) {
            ArrayList<MessageObject> msgs = new ArrayList<>(item.messages);
            ShareAlert alert = new ShareAlert(getParentActivity(), msgs, null, false, null, false);
            showDialog(alert);
        } else {
            forwardAsCopy(item);
        }
    }

    private void forwardAsCopy(FeedController.FeedItem item) {
        String link = buildPostLink(item);

        new ShareAlert(getParentActivity(), null, link, false, null, false) {
            @Override
            public void dismissInternal() {
                super.dismissInternal();
            }
        };

        showCopyDestinationPicker(item, link);
    }

    private void showCopyDestinationPicker(FeedController.FeedItem item, String link) {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", 3);

        DialogsActivity dialogsActivity = new DialogsActivity(args);

        dialogsActivity.setDelegate((fragment, dids, message, param, param2, scheduleDate, sendMode, topicsFragment) -> {
            if (dids != null) {
                for (MessagesStorage.TopicKey topicKey : dids) {
                    long did = topicKey.dialogId;
                    if (did != 0) {
                        forwardDropAuthor(item, did, link);
                    }
                }
            }
            fragment.finishFragment();
            BulletinFactory.of(FeedActivity.this)
                    .createSimpleBulletin(R.drawable.msg_forward,
                            LocaleController.getString("FeedSavedToBookmarks", R.string.FeedSavedToBookmarks))
                    .show();
            return true;
        });

        presentFragment(dialogsActivity);
    }

    private void sendReaction(FeedController.FeedItem item, TLRPC.Reaction reaction) {
        if (reaction instanceof TLRPC.TL_reactionPaid) return;

        MessageObject msg = item.getPrimaryMessage();
        MessagesController controller = MessagesController.getInstance(currentAccount);

        boolean nowChosen = false;
        if (msg.messageOwner.reactions != null && msg.messageOwner.reactions.results != null) {
            for (TLRPC.ReactionCount rc : msg.messageOwner.reactions.results) {
                if (FeedReactionsView.reactionsEqual(rc.reaction, reaction) && (rc.flags & 1) != 0) {
                    nowChosen = true;
                    break;
                }
            }
        }

        TLRPC.TL_messages_sendReaction req = new TLRPC.TL_messages_sendReaction();
        req.peer = controller.getInputPeer(item.channelId);
        req.msg_id = msg.getId();
        req.flags |= 1;
        req.reaction = new ArrayList<>();
        if (nowChosen) {
            req.reaction.add(reaction);
            req.flags |= 4;
        }

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            if (error != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    int pos = adapter.findItemPosition(item);
                    if (pos >= 0) adapter.notifyItemChanged(pos);
                });
            }
        });
    }

    private void handlePaidReaction(FeedController.FeedItem item, int amount) {
        if (pendingPaidSend != null && pendingPaidItem != null && pendingPaidItem != item) {
            AndroidUtilities.cancelRunOnUIThread(pendingPaidSend);
            doSendPaidReaction(pendingPaidItem, pendingPaidAmount, pendingPaidRandomId);
            pendingPaidSend = null;
        }

        if (pendingPaidSend != null && pendingPaidItem == item) {
            AndroidUtilities.cancelRunOnUIThread(pendingPaidSend);
            pendingPaidAmount += amount;
        } else {
            pendingPaidItem = item;
            pendingPaidAmount = amount;
            long currentTime = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
            pendingPaidRandomId = (Utilities.random.nextLong() & 0xFFFFFFFFL) | (currentTime << 32);
        }

        updatePaidReactionUI(item, amount);

        showPaidUndoBulletin(item, pendingPaidAmount);

        final long randomId = pendingPaidRandomId;
        pendingPaidSend = () -> {
            doSendPaidReaction(pendingPaidItem, pendingPaidAmount, randomId);
            pendingPaidSend = null;
            pendingPaidItem = null;
            pendingPaidAmount = 0;
        };
        AndroidUtilities.runOnUIThread(pendingPaidSend, 5000);
    }

    private void updatePaidReactionUI(FeedController.FeedItem item, int amount) {
        int pos = adapter.findItemPosition(item);
        if (pos < 0) return;
        RecyclerView.ViewHolder vh = listView.findViewHolderForAdapterPosition(pos);
        if (vh != null && vh.itemView instanceof FeedPostCell) {
            FeedReactionsView rv = ((FeedPostCell) vh.itemView).getReactionsView();
            rv.optimisticallyAddPaid(amount);
        }
    }

    private void showPaidUndoBulletin(FeedController.FeedItem item, int totalAmount) {
        if (currentStarBulletin != null) {
            currentStarBulletin.hide();
        }

        String title = "You sent ⭐ " + totalAmount + " anonymously";
        String subtitle = "You reacted with " + totalAmount + " star" + (totalAmount != 1 ? "s" : "");

        Bulletin.TwoLineLottieLayout layout = new Bulletin.TwoLineLottieLayout(
                getParentActivity(), resourceProvider);
        layout.setAnimation(R.raw.stars_topup, 36, 36);
        layout.titleTextView.setText(title);
        layout.subtitleTextView.setText(subtitle);

        Bulletin.UndoButton undoButton = new Bulletin.UndoButton(
                getParentActivity(), true, false, resourceProvider);
        undoButton.setText("Undo");
        undoButton.setUndoAction(() -> undoPaidReaction(item, pendingPaidAmount));
        layout.setButton(undoButton);

        currentStarBulletin = Bulletin.make(this, layout, 5000);
        currentStarBulletin.show(true);

        layout.post(() -> {
            View parentView = (View) layout.getParent();
            if (parentView != null && parentView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) parentView.getLayoutParams();
                lp.topMargin = ActionBar.getCurrentActionBarHeight()
                        + AndroidUtilities.statusBarHeight + dp(8);
                parentView.setLayoutParams(lp);
            }
        });
    }


    private void undoPaidReaction(FeedController.FeedItem item, int amount) {
        if (pendingPaidSend != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingPaidSend);
            pendingPaidSend = null;
        }

        int pos = adapter.findItemPosition(item);
        if (pos >= 0) {
            RecyclerView.ViewHolder vh = listView.findViewHolderForAdapterPosition(pos);
            if (vh != null && vh.itemView instanceof FeedPostCell) {
                ((FeedPostCell) vh.itemView).getReactionsView()
                        .optimisticallyRemovePaid(amount);
            }
        }

        pendingPaidItem = null;
        pendingPaidAmount = 0;
    }

    private void doSendPaidReaction(FeedController.FeedItem item, int amount, long randomId) {
        if (item == null || amount <= 0) return;

        MessageObject msg = item.getPrimaryMessage();
        MessagesController controller = MessagesController.getInstance(currentAccount);

        TLRPC.TL_messages_sendPaidReaction req = new TLRPC.TL_messages_sendPaidReaction();
        req.peer = controller.getInputPeer(item.channelId);
        req.msg_id = msg.getId();
        req.count = amount;
        req.random_id = randomId;

        req.flags = 0;

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            AndroidUtilities.runOnUIThread(() -> {
                if (error != null) {
                    int pos = adapter.findItemPosition(item);
                    if (pos >= 0) {
                        RecyclerView.ViewHolder vh = listView.findViewHolderForAdapterPosition(pos);
                        if (vh != null && vh.itemView instanceof FeedPostCell) {
                            ((FeedPostCell) vh.itemView).getReactionsView()
                                    .optimisticallyRemovePaid(amount);
                        }
                    }

                    String errText;
                    if (error.text != null && error.text.contains("BALANCE")) {
                        errText = "Not enough Stars";
                    } else {
                        errText = "Error: " + error.text;
                    }
                    BulletinFactory.of(FeedActivity.this)
                            .createSimpleBulletin(R.drawable.star_small_inner, errText)
                            .show();
                } else if (response instanceof TLRPC.Updates) {
                    controller.processUpdates((TLRPC.Updates) response, false);
                }
            });
        });
    }


    @SuppressLint("SetTextI18n")
    private void showStarAmountPicker(FeedController.FeedItem item) {
        if (getParentActivity() == null) return;

        MessageObject msg = item.getPrimaryMessage();

        ArrayList<TLRPC.MessageReactor> reactors = null;
        if (msg.messageOwner.reactions != null
                && msg.messageOwner.reactions.recent_reactions != null
                && !msg.messageOwner.reactions.recent_reactions.isEmpty()) {
            reactors = new ArrayList<>();
            for (TLRPC.MessagePeerReaction mpr : msg.messageOwner.reactions.recent_reactions) {
                if (mpr.reaction instanceof TLRPC.TL_reactionPaid) {
                    TLRPC.TL_messageReactor reactor = new TLRPC.TL_messageReactor();
                    reactor.peer_id = mpr.peer_id;
                    reactor.count = 1;
                    reactors.add(reactor);
                }
            }
        }

        StarsReactionsSheet sheet = getStarsReactionsSheet(item, msg, reactors);

        sheet.show();
    }

    @NonNull
    private StarsReactionsSheet getStarsReactionsSheet(FeedController.FeedItem item, MessageObject msg, ArrayList<TLRPC.MessageReactor> reactors) {
        StarsReactionsSheet sheet = new StarsReactionsSheet(
                getParentActivity(),
                currentAccount,
                item.channelId,
                null,
                msg,
                reactors,
                true,
                false,
                UserObject.ANONYMOUS,
                resourceProvider
        );

        sheet.setOnSend((peer, stars) -> {
            AndroidUtilities.runOnUIThread(() -> {
                handlePaidReaction(item, (int) (long) stars);
            }, 300);
            return Integer.MIN_VALUE;
        });
        return sheet;
    }

    private void forwardDropAuthor(FeedController.FeedItem item, long targetDialogId, String link) {
        MessagesController controller = MessagesController.getInstance(currentAccount);

        TLRPC.TL_messages_forwardMessages req = new TLRPC.TL_messages_forwardMessages();
        req.to_peer = controller.getInputPeer(targetDialogId);
        req.from_peer = controller.getInputPeer(item.channelId);
        req.drop_author = true;
        req.silent = false;
        req.random_id = new ArrayList<>();
        req.id = new ArrayList<>();
        for (MessageObject m : item.messages) {
            req.id.add(m.getId());
            req.random_id.add(Utilities.random.nextLong());
        }

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            if (error != null) {
                AndroidUtilities.runOnUIThread(() -> sendManualCopy(item, targetDialogId, link));
            } else {
                AndroidUtilities.runOnUIThread(() -> sendFormattedMessage(targetDialogId, link, null));
            }
        });
    }

    private void sendManualCopy(FeedController.FeedItem item, long targetDialogId, String link) {
        new Thread(() -> {
            try {
                String messageText = "";
                ArrayList<TLRPC.MessageEntity> entities = null;
                for (MessageObject msg : item.messages) {
                    String text = msg.messageOwner.message;
                    if (text != null && !text.trim().isEmpty() && !isCopyPlaceholder(text.trim())) {
                        messageText = text;
                        entities = msg.messageOwner.entities;
                        break;
                    }
                }

                String caption = messageText.isEmpty() ? link : messageText + "\n\n" + link;

                ArrayList<MessageObject> mediaMessages = new ArrayList<>();
                for (MessageObject msg : item.messages) {
                    TLRPC.MessageMedia media = msg.messageOwner.media;
                    if (media == null || media instanceof TLRPC.TL_messageMediaEmpty) continue;
                    if (media instanceof TLRPC.TL_messageMediaWebPage) continue;
                    if (media instanceof TLRPC.TL_messageMediaPhoto || media instanceof TLRPC.TL_messageMediaDocument) {
                        File f = getMediaFile(msg);
                        if (f != null && f.exists()) {
                            mediaMessages.add(msg);
                        }
                    }
                }

               if (mediaMessages.isEmpty()) {
                    sendFormattedMessage(targetDialogId, caption, entities);
                    return;
                }

                for (int i = 0; i < mediaMessages.size(); i++) {
                    MessageObject msg = mediaMessages.get(i);
                    TLRPC.Message raw = msg.messageOwner;
                    File file = getMediaFile(msg);
                    if (file == null || !file.exists()) continue;

                    String mediaCaption = (i == 0) ? caption : "";
                    ArrayList<TLRPC.MessageEntity> mediaEntities = (i == 0) ? entities : null;

                    TLRPC.InputFile uploaded = uploadFile(file);
                    if (uploaded == null) continue;

                    TLRPC.TL_messages_sendMedia req = new TLRPC.TL_messages_sendMedia();
                    req.peer = MessagesController.getInstance(currentAccount).getInputPeer(targetDialogId);
                    req.random_id = Utilities.random.nextLong();
                    req.message = mediaCaption;

                    if (mediaEntities != null && !mediaEntities.isEmpty()) {
                        req.entities = new ArrayList<>(mediaEntities);
                        req.flags |= 8;
                    }

                    if (raw.media instanceof TLRPC.TL_messageMediaPhoto) {
                        TLRPC.TL_inputMediaUploadedPhoto photo = new TLRPC.TL_inputMediaUploadedPhoto();
                        photo.file = uploaded;
                        req.media = photo;
                    } else if (raw.media instanceof TLRPC.TL_messageMediaDocument && raw.media.document != null) {
                        TLRPC.TL_inputMediaUploadedDocument doc = new TLRPC.TL_inputMediaUploadedDocument();
                        doc.file = uploaded;
                        doc.mime_type = raw.media.document.mime_type != null
                                ? raw.media.document.mime_type : "application/octet-stream";
                        doc.attributes = new ArrayList<>(raw.media.document.attributes);
                        req.media = doc;
                    } else {
                        continue;
                    }

                    sendRequestSync(req);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private boolean isCopyPlaceholder(String text) {
        switch (text) {
            case "Photo": case "Video": case "GIF": case "Document":
            case "Sticker": case "Audio": case "Voice message":
            case "Video message": case "Contact": case "Location":
                return true;
        }
        return false;
    }

    private void sendRequestSync(TLObject request) {
        final Object lock = new Object();
        final boolean[] done = {false};
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (r, e) -> {
            synchronized (lock) { done[0] = true; lock.notifyAll(); }
        });
        synchronized (lock) {
            try { while (!done[0]) lock.wait(30000); } catch (InterruptedException e) {}
        }
    }

    private void sendFormattedMessage(long targetDialogId, String text, ArrayList<TLRPC.MessageEntity> entities) {
        TLRPC.TL_messages_sendMessage req = new TLRPC.TL_messages_sendMessage();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(targetDialogId);
        req.message = text;
        req.random_id = Utilities.random.nextLong();
        req.no_webpage = true;
        if (entities != null && !entities.isEmpty()) {
            req.entities = new ArrayList<>(entities);
            req.flags |= 8;
        }
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (r, e) -> {});
    }

    private TLRPC.InputFile uploadFile(File file) {
        try {
            long fileSize = file.length();
            boolean isBigFile = fileSize > 10 * 1024 * 1024; // > 10 MB
            long fileId = Utilities.random.nextLong();
            int partSize;
            int totalParts;

            if (fileSize < 1024 * 1024) {
                partSize = 64 * 1024;
            } else if (fileSize < 10 * 1024 * 1024) {
                partSize = 128 * 1024;
            } else {
                partSize = 512 * 1024;
            }

            totalParts = (int) Math.ceil((double) fileSize / partSize);

            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            byte[] buffer = new byte[partSize];
            int bytesRead;
            int partNum = 0;

            while ((bytesRead = fis.read(buffer)) != -1) {
                byte[] partData;
                if (bytesRead < partSize) {
                    partData = new byte[bytesRead];
                    System.arraycopy(buffer, 0, partData, 0, bytesRead);
                } else {
                    partData = buffer;
                }

                boolean success;
                if (isBigFile) {
                    TLRPC.TL_upload_saveBigFilePart req = new TLRPC.TL_upload_saveBigFilePart();
                    req.file_id = fileId;
                    req.file_part = partNum;
                    req.file_total_parts = totalParts;
                    req.bytes = new org.telegram.tgnet.NativeByteBuffer(partData.length);
                    req.bytes.writeBytes(partData);
                    req.bytes.position(0);

                    final boolean[] done = {false};
                    final boolean[] result = {false};
                    ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
                        result[0] = error == null;
                        synchronized (done) { done[0] = true; done.notifyAll(); }
                    });
                    synchronized (done) { while (!done[0]) done.wait(5000); }
                    success = result[0];
                } else {
                    TLRPC.TL_upload_saveFilePart req = new TLRPC.TL_upload_saveFilePart();
                    req.file_id = fileId;
                    req.file_part = partNum;
                    req.bytes = new org.telegram.tgnet.NativeByteBuffer(partData.length);
                    req.bytes.writeBytes(partData);
                    req.bytes.position(0);

                    final boolean[] done = {false};
                    final boolean[] result = {false};
                    ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
                        result[0] = error == null;
                        synchronized (done) { done[0] = true; done.notifyAll(); }
                    });
                    synchronized (done) { while (!done[0]) done.wait(5000); }
                    success = result[0];
                }

                if (!success) {
                    fis.close();
                    return null;
                }
                partNum++;
            }
            fis.close();

            if (isBigFile) {
                TLRPC.TL_inputFileBig inputFile = new TLRPC.TL_inputFileBig();
                inputFile.id = fileId;
                inputFile.parts = totalParts;
                inputFile.name = file.getName();
                return inputFile;
            } else {
                TLRPC.TL_inputFile inputFile = new TLRPC.TL_inputFile();
                inputFile.id = fileId;
                inputFile.parts = totalParts;
                inputFile.name = file.getName();
                inputFile.md5_checksum = "";
                return inputFile;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private File getMediaFile(MessageObject msg) {
        TLRPC.Message raw = msg.messageOwner;

        if (raw.media instanceof TLRPC.TL_messageMediaPhoto && raw.media.photo != null) {
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(
                    raw.media.photo.sizes, AndroidUtilities.getPhotoSize());
            if (size != null) {
                File f = FileLoader.getInstance(currentAccount).getPathToAttach(size, true);
                if (f != null && f.exists()) return f;
                f = FileLoader.getInstance(currentAccount).getPathToAttach(size, false);
                if (f != null && f.exists()) return f;
            }
        } else if (raw.media instanceof TLRPC.TL_messageMediaDocument && raw.media.document != null) {
            File f = FileLoader.getInstance(currentAccount).getPathToAttach(raw.media.document, true);
            if (f != null && f.exists()) return f;
            f = FileLoader.getInstance(currentAccount).getPathToAttach(raw.media.document, false);
            if (f != null && f.exists()) return f;
        }

        File f = FileLoader.getInstance(currentAccount).getPathToMessage(raw);
        if (f != null && f.exists()) return f;

        return null;
    }

    private String buildPostLink(FeedController.FeedItem item) {
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-item.channelId);
        MessageObject msg = item.getPrimaryMessage();
        if (chat != null && !TextUtils.isEmpty(chat.username)) {
            return "https://t.me/" + chat.username + "/" + msg.getId();
        }
        return "https://t.me/c/" + (-item.channelId) + "/" + msg.getId();
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
                if (MessageObject.isRoundVideoDocument(m.document)) continue;
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
        if (markReadRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(markReadRunnable);
            markReadRunnable = null;
        }
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

    @Override
    public void onResume() {
        super.onResume();
        if (feedController.hasCachedFeed()) {
            adapter.setItems(feedController.getCachedFeed());
            if (hasScrollState && savedScrollState != null && layoutManager != null) {
                layoutManager.onRestoreInstanceState(savedScrollState);
            }
            updateEmpty();
        } else {
            loadFeed(false);
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        if (pendingPaidSend != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingPaidSend);
            doSendPaidReaction(pendingPaidItem, pendingPaidAmount, pendingPaidRandomId);
            pendingPaidSend = null;
            pendingPaidItem = null;
            pendingPaidAmount = 0;
        }

        cancelScheduledMark();
        markVisibleAsRead();
        if (layoutManager != null) {
            savedScrollState = layoutManager.onSaveInstanceState();
            hasScrollState = true;
        }
    }

    private void loadFeed(boolean force) {
        swipeRefreshLayout.setRefreshing(true);
        emptyView.setVisibility(View.GONE);

        if (force) {
            feedController.resetLoadMore();
        }

        feedController.loadFeed(force, (items, hasMore) -> {
            adapter.setItems(items);
            swipeRefreshLayout.setRefreshing(false);
            updateEmpty();
        });
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
        if (layoutManager != null) {
            savedScrollState = layoutManager.onSaveInstanceState();
            hasScrollState = true;
        }
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) { return true; }

    @Override
    public void onParentScrollToTop() {
        if (listView != null) listView.smoothScrollToPosition(0);
        loadFeed(true);
    }

    private void checkLoadMore() {
        if (isLoadingMore || !feedController.hasMore()) return;
        if (layoutManager == null || adapter == null) return;

        int lastVisible = layoutManager.findLastVisibleItemPosition();
        int total = adapter.getItemCount();

        if (lastVisible >= total - 3 && total > 0) {
            loadMorePosts();
        }
    }

    private void loadMorePosts() {
        if (isLoadingMore || !feedController.hasMore()) return;
        isLoadingMore = true;

        if (loadingFooter != null) {
            loadingFooter.setVisibility(View.VISIBLE);
        }

        feedController.loadMore((items, hasMore) -> {
            isLoadingMore = false;
            if (loadingFooter != null) {
                loadingFooter.setVisibility(View.GONE);
            }
            adapter.setItems(items);
            updateEmpty();
        });
    }
}