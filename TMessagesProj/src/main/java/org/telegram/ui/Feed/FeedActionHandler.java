package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScrimOptions;
import org.telegram.ui.Components.StickersAlert;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PhotoViewer;

import java.util.ArrayList;

import androidx.recyclerview.widget.RecyclerView;

class FeedActionHandler {

    private final FeedActivity activity;

    FeedActionHandler(FeedActivity activity) {
        this.activity = activity;
    }

    void showMenu(View anchor, FeedController.FeedItem item) {
        ItemOptions options = ItemOptions.makeOptions(activity, anchor);

        options.add(R.drawable.msg_saved, getString(R.string.SaveToBookmarks), () -> {
            activity.shareHelper.forwardToSaved(item);
            BulletinFactory.of(activity)
                    .createSimpleBulletin(R.drawable.msg_saved,
                            getString(R.string.FeedSavedToBookmarks))
                    .show();
        });

        options.add(R.drawable.msg_channel,  getString(R.string.OpenChannel2), () -> {
            activity.saveScroll();
            activity.openChannel(item);
        });

        int account = activity.getAccount();
        boolean isMuted = MessagesController.getInstance(account)
                .isDialogMuted(item.channelId, 0);
        options.add(
                isMuted ? R.drawable.msg_mute : R.drawable.msg_unmute,
                isMuted ? getString(R.string.ChatsUnmute)
                        : getString(R.string.ChatsMute),
                () -> {
                    if (!isMuted) {
                        NotificationsController.getInstance(account)
                                .setDialogNotificationsSettings(item.channelId, 0,
                                        NotificationsController.SETTING_MUTE_FOREVER);
                    } else {
                        NotificationsController.getInstance(account)
                                .setDialogNotificationsSettings(item.channelId, 0,
                                        NotificationsController.SETTING_MUTE_UNMUTE);
                    }
                    BulletinFactory.createMuteBulletin(activity, !isMuted, null).show();
                });

        options.add(R.drawable.msg_markread, getString(R.string.MarkAsRead), () -> {
            activity.feedController.markAsRead(item);
            int pos = activity.adapter.findItemPosition(item);
            if (pos >= 0) activity.adapter.updateItem(pos);
        });

        options.addGap();

        TLRPC.Chat chat = MessagesController.getInstance(activity.getAccount())
                .getChat(-item.channelId);
        String channelName = chat != null ? chat.title : "this channel";

        options.add(R.drawable.msg_block2, getString(R.string.HideFromFeed), () -> {
            long chatId = -item.channelId;
            activity.feedController.hideChannel(chatId);
            activity.adapter.setItems(activity.feedController.getCachedFeed());
            activity.updateEmpty();
            BulletinFactory.of(activity)
                    .createSimpleBulletin(R.drawable.msg_block2,
                            channelName + getString(R.string.HiddenFromFeed))
                    .show();
        });

        options.add(R.drawable.msg_report,
                getString(R.string.ReportChat), true,
                () -> activity.reportHelper.showReportDialog(item));

        options.show();
    }

    void showPostScrim(View cell) {
        if (!(cell instanceof FeedPostCell)) return;
        FeedPostCell postCell = (FeedPostCell) cell;
        FeedController.FeedItem item = postCell.getCurrentItem();
        if (item == null) return;

        ItemOptions options = ItemOptions.makeOptions(activity, cell);
        options.setBlur(true);

        MessageObject primary = item.getPrimaryMessage();
        String text = primary != null && primary.messageOwner != null
                ? primary.messageOwner.message : null;

        if (!TextUtils.isEmpty(text)) {
            options.add(R.drawable.msg_copy,
                    getString(R.string.Copy), () -> {
                        AndroidUtilities.addToClipboard(text);
                        BulletinFactory.of(activity)
                                .createCopyBulletin(
                                        getString(R.string.TextCopied))
                                .show();
                    });

            options.add(R.drawable.msg_select,
                    getString(R.string.Select),
                    () -> showSelectableTextDialog(text));
        }

        options.add(R.drawable.msg_link2,
                getString(R.string.CopyLink), () -> {
                    String link = activity.shareHelper.buildPostLink(item);
                    AndroidUtilities.addToClipboard(link);
                    BulletinFactory.of(activity).createCopyLinkBulletin().show();
                });

        options.addGap();

        options.add(R.drawable.msg_forward,
                getString(R.string.Forward),
                () -> activity.shareHelper.sharePost(item));

        options.add(R.drawable.msg_channel, getString(R.string.OpenChannel2), () -> {
            activity.saveScroll();
            activity.openChannel(item);
        });

        options.show();
    }

    void openComments(FeedController.FeedItem item) {
        MessageObject msg = item.getPrimaryMessage();
        TLRPC.MessageReplies replies = msg.messageOwner.replies;

        if (replies == null || replies.channel_id == 0) {
            activity.openChannel(item);
            return;
        }

        activity.swipeRefreshLayout.setRefreshing(true);

        TLRPC.TL_messages_getDiscussionMessage req = new TLRPC.TL_messages_getDiscussionMessage();
        req.peer = MessagesController.getInstance(activity.getAccount())
                .getInputPeer(item.channelId);
        req.msg_id = msg.getId();

        ConnectionsManager.getInstance(activity.getAccount()).sendRequest(req, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    activity.swipeRefreshLayout.setRefreshing(false);
                    if (response instanceof TLRPC.TL_messages_discussionMessage) {
                        TLRPC.TL_messages_discussionMessage res =
                                (TLRPC.TL_messages_discussionMessage) response;
                        MessagesController controller =
                                MessagesController.getInstance(activity.getAccount());
                        controller.putUsers(res.users, false);
                        controller.putChats(res.chats, false);

                        if (!res.messages.isEmpty()) {
                            TLRPC.Message discussionMsg = res.messages.get(0);
                            long chatId = 0;
                            if (discussionMsg.peer_id != null) {
                                if (discussionMsg.peer_id.channel_id != 0)
                                    chatId = discussionMsg.peer_id.channel_id;
                                else if (discussionMsg.peer_id.chat_id != 0)
                                    chatId = discussionMsg.peer_id.chat_id;
                            }
                            if (chatId != 0) {
                                ArrayList<MessageObject> threadMsgs = new ArrayList<>();
                                for (TLRPC.Message m : res.messages)
                                    threadMsgs.add(new MessageObject(
                                            activity.getAccount(), m, true, true));

                                TLRPC.Chat discussionChat = controller.getChat(chatId);
                                Bundle args = new Bundle();
                                args.putLong("chat_id", chatId);
                                args.putInt("message_id", discussionMsg.id);
                                args.putInt("topic_id", discussionMsg.id);

                                ChatActivity chatActivity = new ChatActivity(args);
                                chatActivity.setThreadMessages(threadMsgs, discussionChat,
                                        discussionMsg.id, res.read_inbox_max_id,
                                        res.read_outbox_max_id, null);
                                activity.presentFragment(chatActivity);
                                return;
                            }
                        }
                    }
                    activity.openChannel(item);
                }));
    }

    void toggleBookmark(FeedController.FeedItem item) {
        int account = activity.getAccount();

        if (item.isBookmarked) {
            item.isBookmarked = false;
            updateBookmarkIcon(item);
            long selfId = UserConfig.getInstance(account).getClientUserId();
            Bulletin b = BulletinFactory.of(activity)
                    .createSimpleBulletin(R.drawable.msg_saved,
                            getString(R.string.RemovedFromBookmarks), getString(R.string.OpenSaved), () -> {
                                Bundle args = new Bundle();
                                args.putLong("user_id", selfId);
                                activity.presentFragment(new ChatActivity(args));
                            });
            b.setDuration(3000);
            activity.showBulletinTop(b);
            return;
        }

        long selfId = UserConfig.getInstance(account).getClientUserId();
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.Chat chat = controller.getChat(-item.channelId);

        TLRPC.TL_messages_forwardMessages req = new TLRPC.TL_messages_forwardMessages();
        req.to_peer = controller.getInputPeer(selfId);
        req.from_peer = controller.getInputPeer(item.channelId);
        req.random_id = new ArrayList<>();
        req.id = new ArrayList<>();
        req.silent = true;
        if (chat != null && chat.noforwards) req.drop_author = true;
        for (MessageObject m : item.messages) {
            req.id.add(m.getId());
            req.random_id.add(Utilities.random.nextLong());
        }

        item.isBookmarked = true;
        updateBookmarkIcon(item);

        Bulletin b = BulletinFactory.of(activity)
                .createSimpleBulletin(R.drawable.msg_saved,
                        getString(R.string.FeedSavedToBookmarks), getString(R.string.View), () -> {
                            Bundle args = new Bundle();
                            args.putLong("user_id", selfId);
                            activity.presentFragment(new ChatActivity(args));
                        });
        b.setDuration(3000);
        activity.showBulletinTop(b);

        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (error != null) {
                        item.isBookmarked = false;
                        updateBookmarkIcon(item);
                        Bulletin err = BulletinFactory.of(activity)
                                .createSimpleBulletin(R.drawable.msg_saved, getString(R.string.FailedToSave));
                        activity.showBulletinTop(err);
                    }
                }));
    }

    private void updateBookmarkIcon(FeedController.FeedItem item) {
        int pos = activity.adapter.findItemPosition(item);
        if (pos < 0) return;
        RecyclerView.ViewHolder vh =
                activity.listView.findViewHolderForAdapterPosition(pos);
        if (vh != null && vh.itemView instanceof FeedPostCell)
            ((FeedPostCell) vh.itemView).updateBookmarkState(item.isBookmarked);
    }

    void handleDoubleTap(FeedController.FeedItem item) {
        int pos = activity.adapter.findItemPosition(item);
        if (pos < 0) return;

        RecyclerView.ViewHolder vh =
                activity.listView.findViewHolderForAdapterPosition(pos);
        if (vh == null || !(vh.itemView instanceof FeedPostCell)) return;

        FeedReactionsView reactionsView =
                ((FeedPostCell) vh.itemView).getReactionsView();
        if (reactionsView != null) {
            boolean sent = reactionsView.triggerDefaultReaction();
            if (!sent) {
                reactionsView.animate().scaleX(0.95f).scaleY(0.95f)
                        .setDuration(100)
                        .withEndAction(() -> reactionsView.animate()
                                .scaleX(1f).scaleY(1f)
                                .setDuration(150).start())
                        .start();
            }
        }
    }

    void openMedia(FeedController.FeedItem item, int index) {
        ArrayList<MessageObject> media = new ArrayList<>();
        for (MessageObject msg : item.messages) {
            TLRPC.MessageMedia m = msg.messageOwner.media;
            if (m == null || m instanceof TLRPC.TL_messageMediaEmpty
                    || m instanceof TLRPC.TL_messageMediaWebPage) continue;
            if (m instanceof TLRPC.TL_messageMediaPhoto) {
                media.add(msg);
            } else if (m instanceof TLRPC.TL_messageMediaDocument && m.document != null) {
                if (MessageObject.isRoundVideoDocument(m.document)) continue;
                for (TLRPC.DocumentAttribute attr : m.document.attributes) {
                    if (attr instanceof TLRPC.TL_documentAttributeVideo
                            || attr instanceof TLRPC.TL_documentAttributeAnimated) {
                        media.add(msg);
                        break;
                    }
                }
            }
        }
        if (media.isEmpty()) return;
        if (index >= media.size()) index = 0;

        PhotoViewer.getInstance().setParentActivity(
                activity.getParentActivity(), activity.getResProvider());
        PhotoViewer.getInstance().openPhoto(media, index, item.channelId, 0, 0,
                new PhotoViewer.EmptyPhotoViewerProvider());
    }

    void onLinkClick(String url) {
        if (url == null) return;
        AlertsCreator.showOpenUrlAlert(activity, url, true, true, activity.getResProvider());
    }

    void showLinkOptions(String url, View cell, ClickableSpan span) {
        if (activity.getParentActivity() == null || url == null || cell == null) return;

        String cleanUrl = url;
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            if (uri.getScheme() != null) {
                cleanUrl = url.replaceFirst(uri.getScheme() + "://", "");
                if (cleanUrl.endsWith("/"))
                    cleanUrl = cleanUrl.substring(0, cleanUrl.length() - 1);
            }
        } catch (Exception ignored) {}

        final String urlFinal = url;
        final String cleanUrlFinal = cleanUrl;

        boolean isInternal = Browser.isInternalUrl(url, null);
        boolean customTabs = org.telegram.messenger.SharedConfig.inappBrowser
                && !isInternal && !url.startsWith("#") && !url.startsWith("$");
        boolean isMail = url.startsWith("mailto:");
        boolean isHashtag = url.startsWith("#") || url.startsWith("$");

        Theme.ResourcesProvider rp = activity.getResProvider();
        final ScrimOptions scrimDialog = new ScrimOptions(activity.getParentActivity(), rp);
        final ItemOptions options = ItemOptions.makeOptions(
                scrimDialog.getContainerView(), rp, scrimDialog.getContainerView());

        if (!isMail) {
            options.add(R.drawable.msg_openin,
                    getString(customTabs && !isHashtag
                            ? R.string.OpenInTelegramBrowser : R.string.Open),
                    () -> Browser.openUrl(activity.getParentActivity(), urlFinal, true));
        }
        if (customTabs && !isHashtag || isMail) {
            options.add(R.drawable.msg_language,
                    getString(R.string.OpenInSystemBrowser),
                    () -> Browser.openUrl(activity.getParentActivity(), urlFinal, false));
        }
        options.add(R.drawable.msg_copy,
                getString(isHashtag ? R.string.CopyHashtag
                        : isMail ? R.string.CopyMail : R.string.CopyLink),
                () -> {
                    AndroidUtilities.addToClipboard(
                            isMail ? urlFinal.substring("mailto:".length()) : urlFinal);
                    BulletinFactory.of(activity).createCopyLinkBulletin().show();
                });
        if (!isHashtag && !isMail) {
            options.add(R.drawable.msg_copy, getString(R.string.CopyWithoutProtocol), () -> {
                AndroidUtilities.addToClipboard(cleanUrlFinal);
                BulletinFactory.of(activity).createCopyLinkBulletin().show();
            });
        }

        scrimDialog.setItemOptions(options);
        scrimDialog.setOnDismissListener(d -> options.dismiss());
        options.setOnDismiss(scrimDialog::dismissFast);

        if (cell instanceof FeedPostCell) {
            TextView tv = ((FeedPostCell) cell).getMessageTextView();

            String formattedUrl = url;
            try {
                android.net.Uri uri = android.net.Uri.parse(url);
                formattedUrl = org.telegram.messenger.browser.Browser.replaceHostname(
                        uri,
                        org.telegram.messenger.browser.Browser.IDN_toUnicode(uri.getHost()),
                        null);
                formattedUrl = java.net.URLDecoder.decode(
                        formattedUrl.replaceAll("\\+", "%2b"), "UTF-8");
            } catch (Exception ignored) {}

            if (formattedUrl.length() > 204) {
                formattedUrl = formattedUrl.substring(0, 204) + "…";
            }

            android.text.SpannableString urlSpannable =
                    new android.text.SpannableString(formattedUrl);
            urlSpannable.setSpan(span, 0, urlSpannable.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            scrimDialog.setScrimForTextView(tv, span, urlSpannable);
        }
        scrimDialog.show();
    }

    void onDateEntityClick(TLRPC.TL_messageEntityFormattedDate entity, View anchor) {
        if (entity == null || activity.getParentActivity() == null) return;

        String fullDate = LocaleController.formatEntityFormattedDate(entity, true);
        Theme.ResourcesProvider rp = activity.getResProvider();

        final ScrimOptions scrimDialog = new ScrimOptions(activity.getParentActivity(), rp);
        final ItemOptions options = ItemOptions.makeOptions(
                scrimDialog.getContainerView(), rp, scrimDialog.getContainerView());

        options.addText(fullDate, 15);
        options.addGap();

        options.add(R.drawable.msg_copy,
                getString(R.string.Copy), () -> {
                    AndroidUtilities.addToClipboard(fullDate);
                    BulletinFactory.of(activity)
                            .createCopyBulletin(getString(R.string.TextCopied))
                            .show();
                });

        options.add(R.drawable.msg_calendar2, getString(R.string.RelativeDateMenuAddToACalendar), () -> {
            try {
                android.content.Intent intent = new android.content.Intent(
                        android.content.Intent.ACTION_INSERT);
                intent.setData(android.provider.CalendarContract.Events.CONTENT_URI);
                intent.putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                        entity.date * 1000L);
                intent.putExtra(android.provider.CalendarContract.Events.TITLE, fullDate);
                activity.getParentActivity().startActivity(intent);
            } catch (Exception e) {
                BulletinFactory.of(activity)
                        .createSimpleBulletin(R.drawable.msg_calendar2,
                                getString(R.string.NoCalendarAppFound))
                        .show();
            }
        });

        scrimDialog.setItemOptions(options);
        scrimDialog.setOnDismissListener(d -> options.dismiss());
        options.setOnDismiss(scrimDialog::dismissFast);

        if (anchor instanceof FeedPostCell) {
            TextView tv = ((FeedPostCell) anchor).getMessageTextView();
            if (tv.getText() instanceof android.text.Spanned) {
                android.text.Spanned spanned = (android.text.Spanned) tv.getText();
                FeedDateSpan[] spans = spanned.getSpans(0,
                        tv.getText().length(), FeedDateSpan.class);
                for (FeedDateSpan ds : spans) {
                    if (ds.entity == entity) {
                        scrimDialog.setScrimForTextView(tv, ds);
                        break;
                    }
                }
            }
        }
        scrimDialog.show();
    }

    void showSelectableTextDialog(String text) {
        if (activity.getParentActivity() == null || TextUtils.isEmpty(text)) return;

        Theme.ResourcesProvider rp = activity.getResProvider();

        FrameLayout container = new FrameLayout(activity.getParentActivity());
        container.setPadding(dp(24), dp(16), dp(24), dp(8));

        TextView tv = new TextView(activity.getParentActivity());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        tv.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        tv.setLineSpacing(dp(2), 1f);
        tv.setTextIsSelectable(true);

        try {
            int handleColor = Theme.getColor(Theme.key_chat_TextSelectionCursor, rp);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.graphics.drawable.Drawable left = tv.getTextSelectHandleLeft();
                if (left != null) {
                    left.setColorFilter(new PorterDuffColorFilter(handleColor, PorterDuff.Mode.SRC_IN));
                    tv.setTextSelectHandleLeft(left);
                }
                android.graphics.drawable.Drawable right = tv.getTextSelectHandleRight();
                if (right != null) {
                    right.setColorFilter(new PorterDuffColorFilter(handleColor, PorterDuff.Mode.SRC_IN));
                    tv.setTextSelectHandleRight(right);
                }
            }
        } catch (Exception ignored) {}

        tv.setHighlightColor(
                Theme.getColor(Theme.key_chat_inTextSelectionHighlight, rp));
        container.addView(tv,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        BottomSheet.Builder builder =
                new BottomSheet.Builder(activity.getParentActivity(), false, rp);
        builder.setCustomView(container);
        activity.showDialog(builder.create());
    }

    void subscribeFromRecommendation(FeedController.FeedItem item) {
        if (item == null || !item.isRecommendation || item.recommendedChat == null) return;

        TLRPC.TL_channels_joinChannel req = new TLRPC.TL_channels_joinChannel();
        req.channel = MessagesController.getInputChannel(item.recommendedChat);

        ConnectionsManager.getInstance(activity.getAccount()).sendRequest(req, (resp, err) ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (err == null) {
                        String name = item.recommendedChat.title;

                        int pos = activity.adapter.findItemPosition(item);

                        item.isRecommendation = false;
                        item.recommendationReason = null;
                        item.recommendedChat = null;

                        activity.feedController.getRecommendationEngine()
                                .onChannelSubscribed(item.recommendedChannelId);

                        if (pos >= 0) {
                            activity.adapter.notifyItemChanged(pos);
                        }

                        BulletinFactory.of(activity)
                                .createSimpleBulletin(R.drawable.msg_channel,
                                        getString(R.string.SubscribedTo) + name)
                                .show();

                    } else {
                        String errText = getString(R.string.FailedToSubscribe);
                        if (err.text != null && err.text.contains("CHANNELS_TOO_MUCH"))
                            errText = getString(R.string.YouHaveJoinedTooManyChannels);
                        BulletinFactory.of(activity)
                                .createSimpleBulletin(R.drawable.msg_channel, errText)
                                .show();
                    }
                }));
    }

    void dismissRecommendedPost(FeedController.FeedItem item) {
        if (item == null || !item.isRecommendation) return;
        activity.feedController.getRecommendationEngine().dismissPost(item);
        activity.refreshDisplayList();
        BulletinFactory.of(activity)
                .createSimpleBulletin(R.drawable.msg_close,
                        getString(R.string.WontRecommendThisChannel))
                .show();
    }

    void openStickerSet(TLRPC.InputStickerSet inputSet) {
        if (inputSet == null || activity.getParentActivity() == null) return;

        StickersAlert alert = new StickersAlert(
                activity.getParentActivity(),
                activity,
                inputSet,
                null,
                null,
                activity.getResProvider(),
                false
        );

        activity.showDialog(alert);
    }

    void showChannelPreview(View anchor, FeedController.FeedItem item) {
        if (activity.getParentActivity() == null || item == null) return;

        long channelId = -item.channelId;
        TLRPC.Chat chat = MessagesController.getInstance(activity.getAccount())
                .getChat(channelId);
        if (chat == null) return;

        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);

        Bundle args = new Bundle();
        args.putLong("chat_id", channelId);

        int flags = ActionBarPopupWindow.ActionBarPopupWindowLayout.FLAG_SHOWN_FROM_BOTTOM;
        ActionBarPopupWindow.ActionBarPopupWindowLayout previewMenu =
                new ActionBarPopupWindow.ActionBarPopupWindowLayout(
                        activity.getParentActivity(),
                        R.drawable.popup_fixed_alert2,
                        activity.getResProvider(),
                        flags);

        ActionBarMenuSubItem openItem = new ActionBarMenuSubItem(
                activity.getParentActivity(), true, false);
        openItem.setTextAndIcon(
                getString(R.string.OpenChannel),
                R.drawable.msg_channel);
        openItem.setMinimumWidth(dp(160));
        openItem.setOnClickListener(v -> {
            activity.finishPreviewAndResetBlur();
            AndroidUtilities.runOnUIThread(() -> {
                activity.saveScroll();
                activity.openChannel(item);
            }, 100);
        });

        previewMenu.addView(openItem);

        TLRPC.Dialog dialog = MessagesController.getInstance(activity.getAccount())
                .dialogs_dict.get(item.channelId);
        if (dialog != null && (dialog.unread_count > 0 || dialog.unread_mark)) {
            ActionBarMenuSubItem readItem = new ActionBarMenuSubItem(
                    activity.getParentActivity(), false, false);
            readItem.setTextAndIcon(
                    getString(R.string.MarkAsRead),
                    R.drawable.msg_markread);
            readItem.setMinimumWidth(dp(160));
            readItem.setOnClickListener(v -> {
                markChannelAsRead(item);
                activity.finishPreviewAndResetBlur();
            });
            previewMenu.addView(readItem);
        }

        boolean isMuted = MessagesController.getInstance(activity.getAccount())
                .isDialogMuted(item.channelId, 0);
        ActionBarMenuSubItem muteItem = new ActionBarMenuSubItem(
                activity.getParentActivity(), false, false);
        muteItem.setTextAndIcon(
                isMuted ? getString(R.string.ChatsUnmute)
                        : getString(R.string.ChatsMute),
                isMuted ? R.drawable.msg_unmute : R.drawable.msg_mute);
        muteItem.setMinimumWidth(dp(160));
        muteItem.setOnClickListener(v -> {
            if (!isMuted) {
                NotificationsController.getInstance(activity.getAccount())
                        .setDialogNotificationsSettings(item.channelId, 0,
                                NotificationsController.SETTING_MUTE_FOREVER);
            } else {
                NotificationsController.getInstance(activity.getAccount())
                        .setDialogNotificationsSettings(item.channelId, 0,
                                NotificationsController.SETTING_MUTE_UNMUTE);
            }
            BulletinFactory.createMuteBulletin(activity, !isMuted, null).show();
            activity.finishPreviewAndResetBlur();
        });
        previewMenu.addView(muteItem);

        String channelName = chat.title;
        ActionBarMenuSubItem hideItem = new ActionBarMenuSubItem(
                activity.getParentActivity(), false, true);
        hideItem.setIconColor(Theme.getColor(Theme.key_text_RedRegular, activity.getResProvider()));
        hideItem.setTextColor(Theme.getColor(Theme.key_text_RedBold, activity.getResProvider()));
        hideItem.setSelectorColor(Theme.multAlpha(
                Theme.getColor(Theme.key_text_RedBold, activity.getResProvider()), .12f));
        hideItem.setTextAndIcon(getString(R.string.HideFromFeed), R.drawable.msg_block2);
        hideItem.setMinimumWidth(dp(160));
        hideItem.setOnClickListener(v -> {
            activity.finishPreviewAndResetBlur();
            AndroidUtilities.runOnUIThread(() -> {
                activity.feedController.hideChannel(item.channelId);
                activity.adapter.setItems(activity.feedController.getCachedFeed());
                activity.updateEmpty();
                BulletinFactory.of(activity)
                        .createSimpleBulletin(R.drawable.msg_block2,
                                channelName + getString(R.string.HiddenFromFeed))
                        .show();
            }, 100);
        });
        previewMenu.addView(hideItem);

        ChatActivity chatActivity = new ChatActivity(args);

        INavigationLayout layout = activity.getParentLayout();
        if (layout == null && activity.getParentActivity() instanceof LaunchActivity) {
            layout = ((LaunchActivity) activity.getParentActivity()).getActionBarLayout();
        }

        if (layout != null) {
            activity.prepareBlurBitmap();
            activity.previewLayout = layout;
            layout.setHighlightActionButtons(true);

            if (AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y) {
                layout.presentFragment(
                        new INavigationLayout.NavigationParams(chatActivity)
                                .setPreview(true));
            } else {
                layout.presentFragment(
                        new INavigationLayout.NavigationParams(chatActivity)
                                .setPreview(true)
                                .setMenuView(previewMenu));
                chatActivity.allowExpandPreviewByClick = true;
            }
        }
    }

    private void markChannelAsRead(FeedController.FeedItem item) {
        int account = activity.getAccount();
        long dialogId = item.channelId;

        TLRPC.Dialog dialog = MessagesController.getInstance(account)
                .dialogs_dict.get(dialogId);
        if (dialog == null) return;

        MessagesController.getInstance(account).markDialogAsReadOnInteraction(
                dialogId, dialog.top_message, dialog.top_message,
                dialog.last_message_date, false, 0, 0, true, 0);

        for (FeedController.FeedItem fi : activity.adapter.getItems()) {
            if (fi.channelId == item.channelId) {
                fi.isRead = true;
            }
        }
        int pos = activity.adapter.findItemPosition(item);
        if (pos >= 0) activity.adapter.notifyItemChanged(pos);

        BulletinFactory.of(activity)
                .createSimpleBulletin(R.drawable.msg_markread, getString(R.string.MarkAsRead))
                .show();
    }

    void onTextLongPress(View cell, CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            showPostScrim(cell);
            return;
        }
        showSelectableTextDialog(text.toString());
    }
}
