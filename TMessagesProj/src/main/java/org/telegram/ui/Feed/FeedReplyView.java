package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.Feed.FeedMediaHelper.smallestThumb;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

@SuppressLint("ViewConstructor")
public class FeedReplyView extends LinearLayout {

    public interface OnReplyClickListener {
        void onReplyClick(long channelId, int messageId);
    }

    private final int currentAccount;
    private final TextView nameView;
    private final TextView textView;
    private final BackupImageView imageView;

    private long replyChannelId;
    private int replyMessageId;
    private OnReplyClickListener listener;

    public FeedReplyView(Context context, int account, Theme.ResourcesProvider resourceProvider) {
        super(context);
        this.currentAccount = account;

        int accentColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2, resourceProvider);
        int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourceProvider);

        setOrientation(HORIZONTAL);
        setPadding(0, dp(8), 0, 0);
        setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, resourceProvider), 2));
        setOnClickListener(v -> {
            if (listener != null && replyChannelId != 0 && replyMessageId != 0)
                listener.onReplyClick(replyChannelId, replyMessageId);
        });

        View border = new View(context);
        border.setBackgroundColor(accentColor);
        addView(border, LayoutHelper.createLinear(3, LayoutHelper.MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        content.setPadding(dp(8), dp(2), dp(4), dp(2));

        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(accentColor);
        nameView.setMaxLines(1);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(nameView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        textView.setTextColor(grayColor);
        textView.setMaxLines(2);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(textView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addView(content, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        imageView = new BackupImageView(context);
        imageView.setRoundRadius(dp(4));
        imageView.setVisibility(GONE);
        addView(imageView, LayoutHelper.createLinear(
                36, 36, Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
    }

    public void setOnReplyClickListener(OnReplyClickListener l) { listener = l; }

    public int getReplyMessageId() { return replyMessageId; }

    @SuppressLint("SetTextI18n")
    public void setData(TLRPC.Message raw, MessagesController controller) {
        imageView.setVisibility(GONE);
        replyChannelId = 0;
        replyMessageId = 0;

        if (raw.reply_to == null || raw.reply_to.reply_to_msg_id == 0) {
            setVisibility(GONE);
            return;
        }

        replyMessageId = raw.reply_to.reply_to_msg_id;
        if (raw.reply_to.reply_to_peer_id != null && raw.reply_to.reply_to_peer_id.channel_id != 0) {
            replyChannelId = raw.reply_to.reply_to_peer_id.channel_id;
        } else if (raw.peer_id != null && raw.peer_id.channel_id != 0) {
            replyChannelId = raw.peer_id.channel_id;
        }

        String name = resolveReplyName(raw, controller);
        nameView.setText(name != null ? name : getString(R.string.Message));

        String replyText = resolveReplyText(raw);
        if (replyText == null || replyText.isEmpty()) {
            replyText = "...";
            loadReplyMessage(raw, controller);
        }

        boolean isQuote = false;
        try { isQuote = raw.reply_to.quote; } catch (Exception ignored) {}
        if (isQuote && raw.reply_to.quote_text != null && !raw.reply_to.quote_text.isEmpty()) {
            textView.setText("💬 " + replyText);
        } else {
            textView.setText(replyText);
        }

        setVisibility(VISIBLE);
    }

    public void clear() {
        replyChannelId = 0;
        replyMessageId = 0;
        imageView.setVisibility(GONE);
        setVisibility(GONE);
    }

    private String resolveReplyName(TLRPC.Message raw, MessagesController ctrl) {
        String name = null;
        if (raw.reply_to.reply_to_peer_id != null)
            name = FeedUtils.getPeerName(raw.reply_to.reply_to_peer_id, ctrl);
        try {
            TLRPC.MessageFwdHeader rf = raw.reply_to.reply_from;
            if (rf != null) {
                if (name == null && rf.from_id != null)
                    name = FeedUtils.getPeerName(rf.from_id, ctrl);
                if (name == null && rf.from_name != null)
                    name = rf.from_name;
            }
        } catch (Exception ignored) {}
        if (name == null && replyChannelId != 0) {
            TLRPC.Chat c = ctrl.getChat(replyChannelId);
            if (c != null) name = c.title;
        }
        return name;
    }

    private String resolveReplyText(TLRPC.Message raw) {
        String text = null;
        try { text = raw.reply_to.quote_text; } catch (Exception ignored) {}
        if (text == null || text.isEmpty()) {
            TLRPC.Message replyMsg = raw.replyMessage;
            if (replyMsg != null) {
                if (replyMsg.message != null && !replyMsg.message.isEmpty())
                    text = replyMsg.message;
                if (replyMsg.media != null
                        && !(replyMsg.media instanceof TLRPC.TL_messageMediaEmpty)) {
                    setupReplyImage(replyMsg);
                    if (text == null || text.isEmpty())
                        text = FeedUtils.getMediaTypeLabel(replyMsg.media);
                }
            }
        }
        return text;
    }

    private void setupReplyImage(TLRPC.Message replyMsg) {
        if (replyMsg.media instanceof TLRPC.TL_messageMediaPhoto
                && replyMsg.media.photo != null) {
            TLRPC.PhotoSize thumb = smallestThumb(replyMsg.media.photo.sizes);
            if (thumb != null) {
                imageView.setImage(
                        ImageLocation.getForPhoto(thumb, replyMsg.media.photo),
                        "36_36", null, null, 0, replyMsg.media.photo);
                imageView.setVisibility(VISIBLE);
            }
        } else if (replyMsg.media instanceof TLRPC.TL_messageMediaDocument
                && replyMsg.media.document != null) {
            TLRPC.Document doc = replyMsg.media.document;
            if (doc.thumbs != null && !doc.thumbs.isEmpty()) {
                TLRPC.PhotoSize thumb = smallestThumb(doc.thumbs);
                if (thumb != null) {
                    imageView.setImage(
                            ImageLocation.getForDocument(thumb, doc),
                            "36_36", null, null, 0, doc);
                    imageView.setVisibility(VISIBLE);
                }
            }
        }
    }

    private void loadReplyMessage(TLRPC.Message raw, MessagesController ctrl) {
        if (raw.peer_id == null || raw.peer_id.channel_id == 0) return;
        TLRPC.Chat chat = ctrl.getChat(raw.peer_id.channel_id);
        if (chat == null) return;

        TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
        req.channel = MessagesController.getInputChannel(chat);
        req.id = new ArrayList<>();
        req.id.add(raw.reply_to.reply_to_msg_id);

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (resp, err) -> {
            if (resp instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) resp;
                if (!msgs.messages.isEmpty()) {
                    TLRPC.Message reply = msgs.messages.get(0);
                    org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                        String t = reply.message;
                        if (t == null || t.isEmpty()) {
                            if (reply.media != null)
                                t = FeedUtils.getMediaTypeLabel(reply.media);
                        }
                        if (t != null && !t.isEmpty()) textView.setText(t);
                        if (reply.media != null
                                && !(reply.media instanceof TLRPC.TL_messageMediaEmpty))
                            setupReplyImage(reply);
                    });
                }
            }
        });
    }
}
