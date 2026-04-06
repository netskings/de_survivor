package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@SuppressLint("ViewConstructor")
public class FeedPostCell extends LinearLayout {

    private static final int MAX_LINES_COLLAPSED = 8;

    private final BackupImageView avatarView;
    private final TextView channelNameView;
    private final TextView timeView;
    private final View unreadDot;

    private final AnimatedEmojiSpan.TextViewEmojis messageTextView;
    private final TextView readMoreView;

    private final TextView pollTextView;

    private final android.widget.FrameLayout mediaContainer;
    private final BackupImageView mediaImageView1;
    private final LinearLayout mediaRow;
    private final TextView mediaOverlayLabel;
    private final TextView albumLabel;

    private final ImageView viewsIcon;
    private final TextView viewsCountView;
    private final LinearLayout commentsBtn;
    private final TextView commentsCountView;
    private final LinearLayout shareBtn;
    private final TextView sharesCountView;

    private FeedController.FeedItem currentItem;
    private boolean textExpanded = false;
    private boolean needsReadMore = false;
    private CharSequence fullText = null;
    private int collapsedEndOffset = -1;
    private final int currentAccount;
    private final Theme.ResourcesProvider resourceProvider;
    private final AvatarDrawable avatarDrawable;

    public boolean isNeedsReadMore() {
        return needsReadMore;
    }

    public void setNeedsReadMore(boolean needsReadMore) {
        this.needsReadMore = needsReadMore;
    }

    public interface Callback {
        void onHeaderClick(FeedController.FeedItem item);
        void onMediaClick(FeedController.FeedItem item, int mediaIndex);
        void onMenuClick(View anchor, FeedController.FeedItem item);
        void onCommentsClick(FeedController.FeedItem item);
        void onShareClick(FeedController.FeedItem item);
    }

    private Callback callback;
    public void setCallback(Callback callback) { this.callback = callback; }

    public FeedPostCell(Context context, int account, Theme.ResourcesProvider resourceProvider) {
        super(context);
        this.currentAccount = account;
        this.resourceProvider = resourceProvider;
        this.avatarDrawable = new AvatarDrawable();

        setOrientation(VERTICAL);
        setPadding(dp(16), dp(12), dp(16), 0);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));

        int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourceProvider);
        PorterDuffColorFilter grayFilter = new PorterDuffColorFilter(grayColor, PorterDuff.Mode.SRC_IN);

        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout headerClickZone = new LinearLayout(context);
        headerClickZone.setOrientation(HORIZONTAL);
        headerClickZone.setGravity(Gravity.CENTER_VERTICAL);
        headerClickZone.setOnClickListener(v -> {
            if (callback != null && currentItem != null) callback.onHeaderClick(currentItem);
        });
        headerClickZone.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourceProvider), 2));

        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(22));
        headerClickZone.addView(avatarView, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL));

        LinearLayout nameCol = new LinearLayout(context);
        nameCol.setOrientation(VERTICAL);
        nameCol.setPadding(dp(12), 0, 0, 0);

        channelNameView = new TextView(context);
        channelNameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        channelNameView.setTypeface(AndroidUtilities.bold());
        channelNameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        channelNameView.setMaxLines(1);
        channelNameView.setEllipsize(TextUtils.TruncateAt.END);
        nameCol.addView(channelNameView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        timeView = new TextView(context);
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        timeView.setTextColor(grayColor);
        nameCol.addView(timeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        headerClickZone.addView(nameCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        unreadDot = new View(context) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(@NonNull Canvas canvas) {
                p.setColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourceProvider));
                canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, dp(4), p);
            }
        };
        unreadDot.setVisibility(GONE);
        headerClickZone.addView(unreadDot, LayoutHelper.createLinear(10, 10, Gravity.CENTER_VERTICAL, 4, 0, 0, 0));

        headerRow.addView(headerClickZone, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        ImageView menuButton = new ImageView(context);
        menuButton.setScaleType(ImageView.ScaleType.CENTER);
        menuButton.setImageResource(R.drawable.msg_actions);
        menuButton.setColorFilter(grayFilter);
        menuButton.setPadding(dp(8), dp(8), dp(4), dp(8));
        menuButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourceProvider), 1));
        menuButton.setOnClickListener(v -> {
            if (callback != null && currentItem != null) callback.onMenuClick(v, currentItem);
        });
        headerRow.addView(menuButton, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));

        addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        messageTextView = new AnimatedEmojiSpan.TextViewEmojis(context);
        messageTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        messageTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        messageTextView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2, resourceProvider));
        messageTextView.setLineSpacing(dp(2), 1f);
        messageTextView.setMovementMethod(LinkMovementMethod.getInstance());
        messageTextView.setVisibility(GONE);
        addView(messageTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        readMoreView = new TextView(context);
        readMoreView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        readMoreView.setTypeface(AndroidUtilities.bold());
        readMoreView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2, resourceProvider));
        readMoreView.setVisibility(GONE);
        readMoreView.setPadding(0, dp(4), 0, dp(2));
        readMoreView.setOnClickListener(v -> toggleExpanded());
        addView(readMoreView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        pollTextView = new TextView(context);
        pollTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        pollTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        pollTextView.setVisibility(GONE);
        pollTextView.setPadding(0, dp(8), 0, 0);
        addView(pollTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        mediaContainer = new android.widget.FrameLayout(context);
        mediaContainer.setVisibility(GONE);

        mediaImageView1 = new BackupImageView(context);
        mediaImageView1.setRoundRadius(dp(12));
        mediaImageView1.setOnClickListener(v -> {
            if (callback != null && currentItem != null) callback.onMediaClick(currentItem, 0);
        });
        mediaContainer.addView(mediaImageView1, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        mediaOverlayLabel = new TextView(context);
        mediaOverlayLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        mediaOverlayLabel.setTextColor(0xFFFFFFFF);
        mediaOverlayLabel.setBackgroundColor(0x99000000);
        mediaOverlayLabel.setPadding(dp(8), dp(3), dp(8), dp(3));
        mediaOverlayLabel.setVisibility(GONE);
        mediaContainer.addView(mediaOverlayLabel, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 8, 0, 0, 8));

        addView(mediaContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 200, 0, 8, 0, 0));

        mediaRow = new LinearLayout(context);
        mediaRow.setOrientation(HORIZONTAL);
        mediaRow.setVisibility(GONE);
        addView(mediaRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 80, 0, 4, 0, 0));

        albumLabel = new TextView(context);
        albumLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        albumLabel.setTextColor(grayColor);
        albumLabel.setVisibility(GONE);
        addView(albumLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        LinearLayout engRow = new LinearLayout(context);
        engRow.setOrientation(HORIZONTAL);
        engRow.setGravity(Gravity.CENTER_VERTICAL);
        engRow.setPadding(0, dp(10), 0, dp(12));

        viewsIcon = new ImageView(context);
        viewsIcon.setImageResource(R.drawable.msg_views);
        viewsIcon.setColorFilter(grayFilter);
        engRow.addView(viewsIcon, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL));

        viewsCountView = smallText(context, grayColor);
        engRow.addView(viewsCountView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 4, 0, 20, 0));

        commentsBtn = new LinearLayout(context);
        commentsBtn.setOrientation(HORIZONTAL);
        commentsBtn.setGravity(Gravity.CENTER_VERTICAL);
        commentsBtn.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourceProvider), 2));
        commentsBtn.setOnClickListener(v -> {
            if (callback != null && currentItem != null) callback.onCommentsClick(currentItem);
        });
        commentsBtn.setVisibility(GONE);

        ImageView cIcon = new ImageView(context);
        cIcon.setImageResource(R.drawable.msg_discussion);
        cIcon.setColorFilter(grayFilter);
        commentsBtn.addView(cIcon, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL));

        commentsCountView = smallText(context, grayColor);
        commentsBtn.addView(commentsCountView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
        engRow.addView(commentsBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 20, 0));

        shareBtn = new LinearLayout(context);
        shareBtn.setOrientation(HORIZONTAL);
        shareBtn.setGravity(Gravity.CENTER_VERTICAL);
        shareBtn.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourceProvider), 2));
        shareBtn.setOnClickListener(v -> {
            if (callback != null && currentItem != null) callback.onShareClick(currentItem);
        });

        ImageView sharesIcon = new ImageView(context);
        sharesIcon.setImageResource(R.drawable.msg_share);
        sharesIcon.setColorFilter(grayFilter);
        shareBtn.addView(sharesIcon, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL));

        sharesCountView = smallText(context, grayColor);
        sharesCountView.setVisibility(GONE);
        shareBtn.addView(sharesCountView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 4, 0, 0, 0));

        engRow.addView(shareBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 0, 0));

        addView(engRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider, resourceProvider));
        addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
    }

    private TextView smallText(Context ctx, int color) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tv.setTextColor(color);
        return tv;
    }

    public void setPost(FeedController.FeedItem item) {
        currentItem = item;
        textExpanded = false;
        needsReadMore = false;
        fullText = null;
        collapsedEndOffset = -1;
        pollTextView.setVisibility(GONE);
        readMoreView.setVisibility(GONE);

        if (item == null) return;

        MessageObject primary = item.getPrimaryMessage();
        TLRPC.Message raw = primary.messageOwner;

        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-item.channelId);
        if (chat != null) {
            channelNameView.setText(chat.title);
            avatarDrawable.setInfo(chat);
            if (chat.photo != null && chat.photo.photo_small != null)
                avatarView.setImage(ImageLocation.getForChat(chat, ImageLocation.TYPE_SMALL), "44_44", avatarDrawable, chat);
            else avatarView.setImageDrawable(avatarDrawable);
        }

        timeView.setText(LocaleController.formatDateAudio(raw.date, true));
        unreadDot.setVisibility(item.isRead ? GONE : VISIBLE);

        if (raw.media instanceof TLRPC.TL_messageMediaPoll) {
            TLRPC.TL_messageMediaPoll pollMedia = (TLRPC.TL_messageMediaPoll) raw.media;
            StringBuilder sb = new StringBuilder();
            sb.append(pollMedia.poll.quiz ? "📊 Quiz" : "📊 Poll");
            sb.append(": ").append(pollMedia.poll.question.text);
            for (TLRPC.PollAnswer answer : pollMedia.poll.answers) {
                sb.append("\n• ").append(answer.text.text);
            }
            pollTextView.setText(sb.toString());
            pollTextView.setVisibility(VISIBLE);
            mediaContainer.setVisibility(GONE);
            mediaRow.setVisibility(GONE);
            albumLabel.setVisibility(GONE);
            mediaOverlayLabel.setVisibility(GONE);
        } else {
            pollTextView.setVisibility(GONE);
            setupMedia(item);
        }

        CharSequence text = getFormattedText(item);
        if (text != null && text.length() > 0) {
            fullText = text;
            messageTextView.setMaxLines(Integer.MAX_VALUE);
            messageTextView.setText(fullText);
            messageTextView.setVisibility(VISIBLE);
            messageTextView.post(this::measureAndTruncate);
        } else {
            fullText = null;
            messageTextView.setVisibility(GONE);
            readMoreView.setVisibility(GONE);
        }

        if (raw.views > 0) {
            viewsCountView.setText(LocaleController.formatShortNumber(raw.views, null));
            viewsIcon.setVisibility(VISIBLE);
            viewsCountView.setVisibility(VISIBLE);
        } else {
            viewsIcon.setVisibility(GONE);
            viewsCountView.setVisibility(GONE);
        }

        if (raw.replies != null && raw.replies.replies > 0) {
            commentsCountView.setText(LocaleController.formatShortNumber(raw.replies.replies, null));
            commentsBtn.setVisibility(VISIBLE);
        } else {
            commentsBtn.setVisibility(GONE);
        }

        if (raw.forwards > 0) {
            sharesCountView.setText(LocaleController.formatShortNumber(raw.forwards, null));
            sharesCountView.setVisibility(VISIBLE);
        } else {
            sharesCountView.setVisibility(GONE);
        }
        shareBtn.setVisibility(VISIBLE);
    }

    private void measureAndTruncate() {
        Layout layout = messageTextView.getLayout();
        if (layout == null) {
            messageTextView.post(this::measureAndTruncate);
            return;
        }

        if (layout.getLineCount() > MAX_LINES_COLLAPSED) {
            needsReadMore = true;
            collapsedEndOffset = layout.getLineEnd(MAX_LINES_COLLAPSED - 1);
            if (!textExpanded) {
                setCollapsedText();
            }
            readMoreView.setVisibility(VISIBLE);
            readMoreView.setText(LocaleController.getString("FeedReadMore", R.string.FeedReadMore));
        } else {
            needsReadMore = false;
            collapsedEndOffset = -1;
            readMoreView.setVisibility(GONE);
        }
    }

    private void setCollapsedText() {
        if (fullText == null || collapsedEndOffset <= 0) return;
        int end = Math.min(collapsedEndOffset, fullText.length());
        SpannableStringBuilder truncated = new SpannableStringBuilder(fullText, 0, end);
        while (truncated.length() > 0 && Character.isWhitespace(truncated.charAt(truncated.length() - 1))) {
            truncated.delete(truncated.length() - 1, truncated.length());
        }
        truncated.append("…");
        messageTextView.setText(truncated);
    }

    private void toggleExpanded() {
        textExpanded = !textExpanded;
        if (textExpanded) {
            messageTextView.setText(fullText);
            readMoreView.setText(LocaleController.getString("FeedShowLess", R.string.FeedShowLess));
        } else {
            setCollapsedText();
            readMoreView.setText(LocaleController.getString("FeedReadMore", R.string.FeedReadMore));
        }
        requestLayout();
    }

    private CharSequence getFormattedText(FeedController.FeedItem item) {
        MessageObject primary = item.getPrimaryMessage();
        CharSequence text = null;

        if (item.isAlbum()) {
            for (MessageObject msg : item.messages) {
                CharSequence mt = msg.messageText;
                if (mt != null && mt.length() > 0 && !isPlaceholderText(mt.toString().trim())) {
                    text = mt;
                    break;
                }
            }
            if (text == null || text.length() == 0) {
                for (MessageObject msg : item.messages) {
                    if (msg.caption != null && msg.caption.length() > 0) {
                        text = msg.caption;
                        break;
                    }
                }
            }
        }

        if (text == null || text.length() == 0) {
            CharSequence mt = primary.messageText;
            if (mt != null && mt.length() > 0 && !isPlaceholderText(mt.toString().trim())) {
                text = mt;
            }
        }

        if (text == null || text.length() == 0) {
            text = primary.caption;
        }

        if (text == null || text.length() == 0) return null;
        if (isPlaceholderText(text.toString().trim())) return null;

        if (!(text instanceof Spannable)) {
            text = new SpannableStringBuilder(text);
        }

        text = Emoji.replaceEmoji(text, messageTextView.getPaint().getFontMetricsInt(), false);

        return text;
    }

    private boolean isPlaceholderText(String text) {
        if (text == null || text.isEmpty()) return true;

        switch (text) {
            case "Photo":
            case "Video":
            case "GIF":
            case "Document":
            case "Sticker":
            case "Audio":
            case "Voice message":
            case "Video message":
            case "Contact":
            case "Location":
            case "Live location":
            case "Poll":
            case "Quiz":
                return true;
        }

        try {
            if (text.equals(LocaleController.getString(R.string.AttachPhoto))
                    || text.equals(LocaleController.getString(R.string.AttachVideo))
                    || text.equals(LocaleController.getString(R.string.AttachGif))
                    || text.equals(LocaleController.getString(R.string.AttachDocument))
                    || text.equals(LocaleController.getString(R.string.AttachSticker))
                    || text.equals(LocaleController.getString(R.string.AttachAudio))
                    || text.equals(LocaleController.getString(R.string.AttachRound))
                    || text.equals(LocaleController.getString(R.string.AttachContact))
                    || text.equals(LocaleController.getString(R.string.AttachLocation))
                    || text.equals(LocaleController.getString(R.string.AttachLiveLocation))
                    || text.equals(LocaleController.getString(R.string.Poll))
            ) {
                return true;
            }
        } catch (Exception e) { /* ignore missing resources */ }

        return false;
    }

    @SuppressLint("SetTextI18n")
    private void setupMedia(FeedController.FeedItem item) {
        List<MessageObject> mediaMessages = new ArrayList<>();
        for (MessageObject msg : item.messages) {
            TLRPC.MessageMedia media = msg.messageOwner.media;
            if (media == null) continue;
            if (media instanceof TLRPC.TL_messageMediaEmpty) continue;
            if (media instanceof TLRPC.TL_messageMediaWebPage) continue;
            if (media instanceof TLRPC.TL_messageMediaPoll) continue;

            if (media instanceof TLRPC.TL_messageMediaPhoto) {
                mediaMessages.add(msg);
            } else if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
                boolean isVisualMedia = false;
                for (TLRPC.DocumentAttribute attr : media.document.attributes) {
                    if (attr instanceof TLRPC.TL_documentAttributeVideo) isVisualMedia = true;
                    if (attr instanceof TLRPC.TL_documentAttributeAnimated) isVisualMedia = true;
                }
                if (isVisualMedia) mediaMessages.add(msg);
            }
        }

        if (mediaMessages.isEmpty()) {
            mediaContainer.setVisibility(GONE);
            mediaOverlayLabel.setVisibility(GONE);
            mediaRow.setVisibility(GONE);
            albumLabel.setVisibility(GONE);
            return;
        }

        int h = setupSingleMedia(mediaMessages.get(0), mediaImageView1, mediaOverlayLabel);
        mediaContainer.setVisibility(VISIBLE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mediaContainer.getLayoutParams();
        lp.height = h;
        mediaContainer.setLayoutParams(lp);

        if (mediaMessages.size() >= 2) {
            mediaRow.removeAllViews();
            int show = Math.min(mediaMessages.size() - 1, 3);
            for (int i = 0; i < show; i++) {
                final int idx = i + 1;
                BackupImageView thumb = new BackupImageView(getContext());
                thumb.setRoundRadius(dp(8));
                thumb.setOnClickListener(v -> {
                    if (callback != null && currentItem != null) callback.onMediaClick(currentItem, idx);
                });
                setupMediaThumb(mediaMessages.get(idx), thumb);
                mediaRow.addView(thumb, LayoutHelper.createLinear(80, 80, 0, 0, 4, 0));
            }
            if (mediaMessages.size() > 4) {
                TextView more = new TextView(getContext());
                more.setText("+" + (mediaMessages.size() - 4));
                more.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourceProvider));
                more.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                more.setGravity(Gravity.CENTER);
                mediaRow.addView(more, LayoutHelper.createLinear(48, 80, Gravity.CENTER_VERTICAL));
            }
            mediaRow.setVisibility(VISIBLE);

            int photos = 0, videos = 0;
            for (MessageObject m : mediaMessages) {
                if (m.isVideo() || m.isRoundVideo()) videos++;
                else photos++;
            }
            StringBuilder sb = new StringBuilder("Album • ");
            if (photos > 0) sb.append(photos).append(photos == 1 ? " photo" : " photos");
            if (photos > 0 && videos > 0) sb.append(", ");
            if (videos > 0) sb.append(videos).append(videos == 1 ? " video" : " videos");
            albumLabel.setText(sb.toString());
            albumLabel.setVisibility(VISIBLE);
        } else {
            mediaRow.setVisibility(GONE);
            albumLabel.setVisibility(GONE);
        }
    }

    @SuppressLint("SetTextI18n")
    private int setupSingleMedia(MessageObject msg, BackupImageView iv, TextView overlay) {
        TLRPC.Message raw = msg.messageOwner;
        int height = dp(200);

        if (raw.media instanceof TLRPC.TL_messageMediaPhoto && raw.media.photo != null) {
            TLRPC.PhotoSize best = bestSize(raw.media.photo.sizes);
            if (best != null) {
                if (best.w > 0 && best.h > 0) {
                    int w = AndroidUtilities.displaySize.x - dp(32);
                    height = Math.max(dp(150), Math.min(dp(400), (int) (w * ((float) best.h / best.w))));
                }
                iv.setImage(ImageLocation.getForPhoto(best, raw.media.photo), height + "_" + height, (ImageLocation) null, null, 0, raw.media.photo);
            }
            overlay.setVisibility(GONE);
        } else if (raw.media instanceof TLRPC.TL_messageMediaDocument && raw.media.document != null) {
            TLRPC.Document doc = raw.media.document;
            boolean isGif = false, isVideo = false;
            double duration = 0;
            for (TLRPC.DocumentAttribute attr : doc.attributes) {
                if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                    isVideo = true;
                    duration = ((TLRPC.TL_documentAttributeVideo) attr).duration;
                    if (attr.w > 0 && attr.h > 0) {
                        int w = AndroidUtilities.displaySize.x - dp(32);
                        height = Math.max(dp(150), Math.min(dp(400), (int) (w * ((float) attr.h / attr.w))));
                    }
                }
                if (attr instanceof TLRPC.TL_documentAttributeAnimated) isGif = true;
            }
            if (doc.thumbs != null && !doc.thumbs.isEmpty()) {
                TLRPC.PhotoSize thumb = bestSize(doc.thumbs);
                if (thumb != null)
                    iv.setImage(ImageLocation.getForDocument(thumb, doc), height + "_" + height, (ImageLocation) null, null, 0, doc);
            }
            if (isGif) {
                overlay.setText("GIF");
                overlay.setVisibility(VISIBLE);
            } else if (isVideo) {
                int d = (int) duration;
                overlay.setText(String.format(Locale.US, "▶ %d:%02d", d / 60, d % 60));
                overlay.setVisibility(VISIBLE);
            } else {
                overlay.setVisibility(GONE);
            }
        }
        return height;
    }

    private void setupMediaThumb(MessageObject msg, BackupImageView v) {
        TLRPC.Message raw = msg.messageOwner;
        if (raw.media instanceof TLRPC.TL_messageMediaPhoto && raw.media.photo != null) {
            TLRPC.PhotoSize best = bestSize(raw.media.photo.sizes);
            if (best != null)
                v.setImage(ImageLocation.getForPhoto(best, raw.media.photo), "80_80", (ImageLocation) null, null, 0, raw.media.photo);
        } else if (raw.media instanceof TLRPC.TL_messageMediaDocument && raw.media.document != null) {
            TLRPC.Document doc = raw.media.document;
            if (doc.thumbs != null && !doc.thumbs.isEmpty()) {
                TLRPC.PhotoSize thumb = bestSize(doc.thumbs);
                if (thumb != null)
                    v.setImage(ImageLocation.getForDocument(thumb, doc), "80_80", (ImageLocation) null, null, 0, doc);
            }
        }
    }

    private TLRPC.PhotoSize bestSize(List<TLRPC.PhotoSize> sizes) {
        if (sizes == null) return null;
        TLRPC.PhotoSize best = null;
        int bestA = 0;
        for (TLRPC.PhotoSize s : sizes) {
            if (s instanceof TLRPC.TL_photoSizeEmpty) continue;
            int a = s.w * s.h;
            if (a > bestA) {
                bestA = a;
                best = s;
            }
        }
        return best;
    }

    public FeedController.FeedItem getCurrentItem() {
        return currentItem;
    }
}