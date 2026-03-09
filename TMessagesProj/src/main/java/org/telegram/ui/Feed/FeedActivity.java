package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

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
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.PhotoViewer;

import java.io.File;
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
                    cancelScheduledMark();
                    markVisibleAsRead();
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
}