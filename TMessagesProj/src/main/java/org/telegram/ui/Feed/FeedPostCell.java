package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;

import java.util.HashMap;

@SuppressLint("ViewConstructor")
public class FeedPostCell extends LinearLayout {

    static final int MAX_LINES_COLLAPSED = 8;

    private BackupImageView avatarView;
    private TextView channelNameView;
    private TextView timeView;
    private View unreadDot;
    private final AvatarDrawable avatarDrawable;

    final FeedReplyView replyView;
    final FeedForwardView forwardView;
    final FeedPollView pollView;
    final FeedDocumentView documentView;
    final FeedVoiceView voiceView;
    final FeedInlineButtonsView buttonsView;
    final FeedReactionsView reactionsView;

    boolean messageHasSpoilers;
    Runnable spoilerRevealer;
    final FeedMessageTextView messageTextView;
    private final TextView readMoreView;
    final android.graphics.Path spoilerClipPath = new android.graphics.Path();

    final android.widget.FrameLayout mediaContainer;
    final FeedRoundVideoView roundVideoView;
    final BackupImageView mediaImageView1;
    final LinearLayout mediaRow;
    final TextView mediaOverlayLabel;
    final TextView albumLabel;
    final FeedMediaShimmer mediaShimmer;
    final FeedStickerView stickerView;

    private ImageView viewsIcon;
    private TextView viewsCountView;
    LinearLayout commentsBtn;
    private TextView commentsCountView;
    LinearLayout shareBtn;
    private TextView sharesCountView;
    private ImageView bookmarkIcon;

    final TextView summarizeBtn;
    final LinearLayout summaryCard;
    final TextView summaryTextView;

    final TextView translateBtn;
    final LinearLayout translationCard;
    final TextView translationHeaderView;
    final TextView translationTextView;

    private final LinearLayout recommendationHeader;
    private final TextView recommendationReasonView;
    private TextView subscribeBtn;

    FeedController.FeedItem currentItem;
    boolean textExpanded;
    CharSequence fullText;
    int collapsedEndOffset = -1;
    final int currentAccount;
    final Theme.ResourcesProvider resourceProvider;
    private ViewTreeObserver.OnPreDrawListener pendingTruncateListener;
    final java.util.HashSet<Integer> expandedQuoteOffsets = new java.util.HashSet<>();
    private final FeedTextFormatter textFormatter;

    final LinkSpanDrawable.LinkCollector linkCollector;
    private LinkSpanDrawable<ClickableSpan> pressedLink;
    private final Paint dimPaint = new Paint();
    private boolean isDimmed;
    private final PorterDuffColorFilter grayFilter;

    private final GestureDetector doubleTapDetector;
    private Runnable longPressRunnable;
    private boolean longPressTriggered;
    private float longPressDownX, longPressDownY;

    private boolean downAllowsDoubleTap;
    private int touchSlop;
    private int longPressTimeout;

    final FeedPostSummaryHelper summaryHelper;
    final FeedPostTranslationHelper translationHelper;

    Callback callback;

    public void setPressedLink(LinkSpanDrawable<ClickableSpan> link) {
        this.pressedLink = link;
    }

    public LinkSpanDrawable<ClickableSpan> getPressedLink() {
        return pressedLink;
    }

    public FeedReactionsView getReactionsView() {
        return reactionsView;
    }

    public FeedController.FeedItem getCurrentItem() {
        return currentItem;
    }

    public TextView getMessageTextView() {
        return messageTextView;
    }

    public interface Callback {
        void onHeaderClick(FeedController.FeedItem item);
        void onMediaClick(FeedController.FeedItem item, int mediaIndex);
        void onMenuClick(View anchor, FeedController.FeedItem item);
        void onCommentsClick(FeedController.FeedItem item);
        void onShareClick(FeedController.FeedItem item);
        void onForwardClick(long channelId, int messageId);
        void onReplyClick(long channelId, int messageId);
        void onInlineButtonClick(FeedController.FeedItem item, TLRPC.KeyboardButton button);
        void onReactionToggle(FeedController.FeedItem item, TLRPC.Reaction reaction);
        void onPaidReactionTap(FeedController.FeedItem item);
        void onPaidReactionLongPress(FeedController.FeedItem item);
        void onDoubleTap(FeedController.FeedItem item);
        void onBookmarkClick(FeedController.FeedItem item);
        void onLinkClick(String url);
        void onLinkLongPress(String url, View cell, ClickableSpan span);
        void onPostLongPress(View cell);
        void onTextLongPress(View cell, FeedController.FeedItem item, CharSequence text);
        void onSubscribeClick(FeedController.FeedItem item);
        void onDismissRecommendation(FeedController.FeedItem item);
        void onDateEntityClick(TLRPC.TL_messageEntityFormattedDate entity, View anchor);
        void onStickerClick(FeedStickerView stickerView, TLRPC.InputStickerSet stickerSet);
        void onAvatarLongPress(View anchor, FeedController.FeedItem item);
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
        reactionsView.setCallback(new FeedReactionsView.ReactionCallback() {
            @Override
            public void onReactionToggle(FeedController.FeedItem item, TLRPC.Reaction reaction) {
                if (cb != null) cb.onReactionToggle(item, reaction);
            }
            @Override
            public void onPaidReactionTap(FeedController.FeedItem item) {
                if (cb != null) cb.onPaidReactionTap(item);
            }
            @Override
            public void onPaidReactionLongPress(FeedController.FeedItem item) {
                if (cb != null) cb.onPaidReactionLongPress(item);
            }
        });
    }

    @SuppressLint("SetTextI18n")
    public FeedPostCell(Context context, int account, Theme.ResourcesProvider resourceProvider) {
        super(context);
        this.currentAccount = account;
        this.resourceProvider = resourceProvider;
        this.avatarDrawable = new AvatarDrawable();

        setOrientation(VERTICAL);
        setPadding(dp(16), dp(12), dp(16), 0);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));
        setClipChildren(false);
        setClipToPadding(false);
        setClickable(true);

        android.view.ViewConfiguration vc = android.view.ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout();

        int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourceProvider);
        int accentColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2, resourceProvider);
        grayFilter = new PorterDuffColorFilter(grayColor, PorterDuff.Mode.SRC_IN);

        recommendationHeader = new LinearLayout(context);
        recommendationHeader.setOrientation(HORIZONTAL);
        recommendationHeader.setGravity(Gravity.CENTER_VERTICAL);
        recommendationHeader.setPadding(0, 0, 0, dp(8));
        recommendationHeader.setVisibility(GONE);

        TextView recIcon = new TextView(context);
        recIcon.setText("✨");
        recIcon.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        recommendationHeader.addView(recIcon,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

        recommendationReasonView = new TextView(context);
        recommendationReasonView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        recommendationReasonView.setTextColor(accentColor);
        recommendationReasonView.setTypeface(AndroidUtilities.bold());
        recommendationReasonView.setMaxLines(1);
        recommendationReasonView.setEllipsize(TextUtils.TruncateAt.END);
        recommendationHeader.addView(recommendationReasonView,
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        TextView dismissBtn = new TextView(context);
        dismissBtn.setText("✕");
        dismissBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        dismissBtn.setTextColor(grayColor);
        dismissBtn.setPadding(dp(12), dp(4), dp(4), dp(4));
        dismissBtn.setOnClickListener(v -> {
            if (callback != null && currentItem != null) callback.onDismissRecommendation(currentItem);
        });
        recommendationHeader.addView(dismissBtn,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL));

        addView(recommendationHeader,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout headerRow = buildHeaderRow(context, grayColor, accentColor);
        addView(headerRow,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        replyView = new FeedReplyView(context, currentAccount, resourceProvider);
        replyView.setVisibility(GONE);
        replyView.setOnReplyClickListener((chId, msgId) -> {
            if (callback != null) callback.onReplyClick(chId, msgId);
        });
        addView(replyView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        forwardView = new FeedForwardView(context, resourceProvider);
        forwardView.setVisibility(GONE);
        forwardView.setOnForwardClickListener((chId, msgId) -> {
            if (callback != null) callback.onForwardClick(chId, msgId);
        });
        addView(forwardView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        translateBtn = new TextView(context);
        translateBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        translateBtn.setTextColor(accentColor);
        translateBtn.setPadding(0, dp(6), 0, dp(2));
        translateBtn.setVisibility(GONE);
        addView(translateBtn,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        translationCard = new LinearLayout(context);
        translationCard.setOrientation(VERTICAL);
        GradientDrawable translationBg = new GradientDrawable();
        translationBg.setColor(ColorUtils.setAlphaComponent(accentColor, 0x14));
        translationBg.setCornerRadius(dp(10));
        translationCard.setBackground(translationBg);
        translationCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        translationCard.setVisibility(GONE);

        translationHeaderView = new TextView(context);
        translationHeaderView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        translationHeaderView.setTextColor(grayColor);
        translationHeaderView.setTypeface(AndroidUtilities.bold());
        translationCard.addView(translationHeaderView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        translationTextView = new TextView(context);
        translationTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        translationTextView.setTextColor(
                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        translationTextView.setLineSpacing(dp(2), 1f);
        translationTextView.setLinkTextColor(accentColor);
        translationTextView.setPadding(0, dp(4), 0, 0);
        translationCard.addView(translationTextView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addView(translationCard,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                        0, 4, 0, 4));

        messageTextView = new FeedMessageTextView(context, this);
        linkCollector = new LinkSpanDrawable.LinkCollector(messageTextView);
        dimPaint.setColor(0x44000000);

        messageTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        messageTextView.setTextColor(
                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        messageTextView.setLinkTextColor(accentColor);
        messageTextView.setLineSpacing(dp(2), 1f);   // ★ множитель вместо абсолютного dp
        messageTextView.setIncludeFontPadding(false); // ★ убирает лишний padding сверху/снизу
        messageTextView.setTextIsSelectable(false);   // ★ конфликтует с AnimatedEmojiSpan
        messageTextView.setCursorVisible(false);
        messageTextView.setVisibility(GONE);
        addView(messageTextView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                        0, 8, 0, 0));

        readMoreView = new TextView(context);
        readMoreView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        readMoreView.setTypeface(AndroidUtilities.bold());
        readMoreView.setTextColor(accentColor);
        readMoreView.setVisibility(GONE);
        readMoreView.setPadding(0, dp(4), 0, dp(2));
        readMoreView.setOnClickListener(v -> toggleExpanded());
        addView(readMoreView,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        summarizeBtn = new TextView(context);
        summarizeBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        summarizeBtn.setTextColor(accentColor);
        summarizeBtn.setTypeface(AndroidUtilities.bold());
        summarizeBtn.setPadding(0, dp(6), 0, dp(2));
        summarizeBtn.setVisibility(GONE);
        addView(summarizeBtn,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        summaryCard = new LinearLayout(context);
        summaryCard.setOrientation(VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(ColorUtils.setAlphaComponent(accentColor, 0x1A));
        cardBg.setCornerRadius(dp(10));
        summaryCard.setBackground(cardBg);
        summaryCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        summaryCard.setVisibility(GONE);

        TextView summaryTitleView = new TextView(context);
        summaryTitleView.setText("✨ AI Summary");
        summaryTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        summaryTitleView.setTextColor(accentColor);
        summaryTitleView.setTypeface(AndroidUtilities.bold());
        summaryCard.addView(summaryTitleView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        summaryTextView = new TextView(context);
        summaryTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        summaryTextView.setTextColor(
                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        summaryTextView.setLineSpacing(dp(2), 1f);
        summaryTextView.setPadding(0, dp(4), 0, 0);
        summaryCard.addView(summaryTextView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addView(summaryCard,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                        0, 6, 0, 0));

        pollView = new FeedPollView(context, currentAccount, resourceProvider);
        pollView.setVisibility(GONE);
        addView(pollView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        documentView = new FeedDocumentView(context, currentAccount, resourceProvider);
        documentView.setVisibility(GONE);
        addView(documentView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        voiceView = new FeedVoiceView(context, currentAccount, resourceProvider);
        voiceView.setVisibility(GONE);
        addView(voiceView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        mediaContainer = new android.widget.FrameLayout(context);
        mediaContainer.setVisibility(GONE);

        mediaImageView1 = new BackupImageView(context);
        mediaImageView1.setRoundRadius(dp(12));
        mediaImageView1.setOnClickListener(v -> {
            if (callback != null && currentItem != null) callback.onMediaClick(currentItem, 0);
        });
        mediaContainer.addView(mediaImageView1,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        mediaShimmer = new FeedMediaShimmer(context);
        mediaContainer.addView(mediaShimmer,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        mediaOverlayLabel = new TextView(context);
        mediaOverlayLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        mediaOverlayLabel.setTextColor(0xFFFFFFFF);
        mediaOverlayLabel.setBackgroundColor(0x99000000);
        mediaOverlayLabel.setPadding(dp(8), dp(3), dp(8), dp(3));
        mediaOverlayLabel.setVisibility(GONE);
        mediaContainer.addView(mediaOverlayLabel, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.LEFT, 8, 0, 0, 8));

        addView(mediaContainer,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 200, 0, 8, 0, 0));

        roundVideoView = new FeedRoundVideoView(context, currentAccount);
        roundVideoView.setVisibility(GONE);
        addView(roundVideoView,
                LayoutHelper.createLinear(240, 240, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));
        roundVideoView.setSizeChangeListener(newSizePx -> {
            LinearLayout.LayoutParams rvLp =
                    (LinearLayout.LayoutParams) roundVideoView.getLayoutParams();
            if (rvLp != null) {
                rvLp.width = newSizePx;
                rvLp.height = newSizePx;
                roundVideoView.setLayoutParams(rvLp);
            }
        });

        stickerView = new FeedStickerView(context);
        stickerView.setVisibility(GONE);
        stickerView.setOnClickListener(v -> {
            if (callback != null && currentItem != null) {
                TLRPC.InputStickerSet inputSet = stickerView.getInputStickerSet();
                if (inputSet != null) {
                    callback.onStickerClick(stickerView, inputSet);
                }
            }
        });
        addView(stickerView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        mediaRow = new LinearLayout(context);
        mediaRow.setOrientation(HORIZONTAL);
        mediaRow.setVisibility(GONE);
        addView(mediaRow,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 80, 0, 4, 0, 0));

        albumLabel = new TextView(context);
        albumLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        albumLabel.setTextColor(grayColor);
        albumLabel.setVisibility(GONE);
        addView(albumLabel,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                        0, 4, 0, 0));

        buttonsView = new FeedInlineButtonsView(context, resourceProvider);
        buttonsView.setVisibility(GONE);
        buttonsView.setOnButtonClickListener((item, button) -> {
            if (callback != null) callback.onInlineButtonClick(item, button);
        });
        addView(buttonsView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                        0, 6, 0, 0));

        reactionsView = new FeedReactionsView(context, currentAccount, resourceProvider);
        reactionsView.setVisibility(GONE);
        addView(reactionsView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                        0, 4, 0, 0));

        LinearLayout engRow = buildEngagementRow(context, grayColor);
        addView(engRow,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        textFormatter = new FeedTextFormatter(resourceProvider, expandedQuoteOffsets);
        textFormatter.setRebuildCallback(this::rebuildMessageText);

        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider, resourceProvider));
        addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        doubleTapDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(@NonNull MotionEvent e) {
                        if (callback != null && currentItem != null) callback.onDoubleTap(currentItem);
                        return true;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                        return false;
                    }
                });
        doubleTapDetector.setIsLongpressEnabled(false);

        summaryHelper = new FeedPostSummaryHelper(this);
        translationHelper = new FeedPostTranslationHelper(this);

        summarizeBtn.setOnClickListener(v -> summaryHelper.onSummarizeClick());
        translateBtn.setOnClickListener(v -> translationHelper.onTranslateClick());
    }

    private LinearLayout buildHeaderRow(Context context, int grayColor, int accentColor) {
        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout headerClickZone = new LinearLayout(context);
        headerClickZone.setOrientation(HORIZONTAL);
        headerClickZone.setGravity(Gravity.CENTER_VERTICAL);
        headerClickZone.setOnClickListener(v -> {
            if (callback != null && currentItem != null) callback.onHeaderClick(currentItem);
        });
        headerClickZone.setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, resourceProvider), 2));

        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(dp(22));
        avatarView.setOnClickListener(v -> {
            if (callback != null && currentItem != null) callback.onHeaderClick(currentItem);
        });

        View.OnLongClickListener previewLongClick = v -> {
            if (callback != null && currentItem != null) {
                callback.onAvatarLongPress(avatarView, currentItem);
                return true;
            }
            return false;
        };

        headerClickZone.setOnLongClickListener(previewLongClick);
        avatarView.setOnLongClickListener(previewLongClick);

        headerClickZone.addView(avatarView,
                LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL));

        LinearLayout nameCol = new LinearLayout(context);
        nameCol.setOrientation(VERTICAL);
        nameCol.setPadding(dp(12), 0, 0, 0);

        channelNameView = new TextView(context);
        channelNameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        channelNameView.setTypeface(AndroidUtilities.bold());
        channelNameView.setTextColor(
                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        channelNameView.setMaxLines(1);
        channelNameView.setEllipsize(TextUtils.TruncateAt.END);
        nameCol.addView(channelNameView,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        timeView = new TextView(context);
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        timeView.setTextColor(grayColor);
        nameCol.addView(timeView,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                        0, 1, 0, 0));

        headerClickZone.addView(nameCol,
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        unreadDot = new View(context) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                p.setColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourceProvider));
                canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, dp(4), p);
            }
        };
        unreadDot.setVisibility(GONE);
        headerClickZone.addView(unreadDot,
                LayoutHelper.createLinear(10, 10, Gravity.CENTER_VERTICAL, 4, 0, 0, 0));

        headerRow.addView(headerClickZone,
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        subscribeBtn = new TextView(context);
        subscribeBtn.setText("Subscribe");
        subscribeBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subscribeBtn.setTextColor(0xFFFFFFFF);
        subscribeBtn.setTypeface(AndroidUtilities.bold());
        subscribeBtn.setGravity(Gravity.CENTER);
        subscribeBtn.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable subBg = new GradientDrawable();
        subBg.setColor(accentColor);
        subBg.setCornerRadius(dp(16));
        subscribeBtn.setBackground(subBg);
        subscribeBtn.setVisibility(GONE);
        subscribeBtn.setOnClickListener(v -> {
            if (callback != null && currentItem != null) callback.onSubscribeClick(currentItem);
        });
        headerRow.addView(subscribeBtn,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30,
                        Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        ImageView menuButton = new ImageView(context);
        menuButton.setScaleType(ImageView.ScaleType.CENTER);
        menuButton.setImageResource(R.drawable.msg_actions);
        menuButton.setColorFilter(grayFilter);
        menuButton.setPadding(dp(8), dp(8), dp(4), dp(8));
        menuButton.setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, resourceProvider), 1));
        menuButton.setOnClickListener(v -> {
            if (callback != null && currentItem != null) callback.onMenuClick(v, currentItem);
        });
        headerRow.addView(menuButton,
                LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));

        return headerRow;
    }

    private LinearLayout buildEngagementRow(Context context, int grayColor) {
        LinearLayout engRow = new LinearLayout(context);
        engRow.setOrientation(HORIZONTAL);
        engRow.setGravity(Gravity.CENTER_VERTICAL);
        engRow.setPadding(0, dp(10), 0, dp(12));

        commentsBtn = new LinearLayout(context);
        commentsBtn.setOrientation(HORIZONTAL);
        commentsBtn.setGravity(Gravity.CENTER_VERTICAL);
        commentsBtn.setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, resourceProvider), 2));
        commentsBtn.setOnClickListener(v -> {
            if (callback != null && currentItem != null) callback.onCommentsClick(currentItem);
        });
        commentsBtn.setVisibility(GONE);

        ImageView cIcon = new ImageView(context);
        cIcon.setImageResource(R.drawable.msg_discussion);
        cIcon.setColorFilter(grayFilter);
        commentsBtn.addView(cIcon,
                LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL));

        commentsCountView = smallText(context, grayColor);
        commentsBtn.addView(commentsCountView,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
        engRow.addView(commentsBtn,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL, 0, 0, 20, 0));

        shareBtn = new LinearLayout(context);
        shareBtn.setOrientation(HORIZONTAL);
        shareBtn.setGravity(Gravity.CENTER_VERTICAL);
        shareBtn.setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, resourceProvider), 2));
        shareBtn.setOnClickListener(v -> {
            if (callback != null && currentItem != null) callback.onShareClick(currentItem);
        });

        ImageView sharesIcon = new ImageView(context);
        sharesIcon.setImageResource(R.drawable.msg_share);
        sharesIcon.setColorFilter(grayFilter);
        shareBtn.addView(sharesIcon,
                LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL));

        sharesCountView = smallText(context, grayColor);
        sharesCountView.setVisibility(GONE);
        shareBtn.addView(sharesCountView,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
        engRow.addView(shareBtn,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL, 0, 0, 16, 0));

        LinearLayout bookmarkBtn = new LinearLayout(context);
        bookmarkBtn.setOrientation(HORIZONTAL);
        bookmarkBtn.setGravity(Gravity.CENTER_VERTICAL);
        bookmarkBtn.setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, resourceProvider), 2));
        bookmarkBtn.setOnClickListener(v -> {
            if (callback != null && currentItem != null) callback.onBookmarkClick(currentItem);
        });

        bookmarkIcon = new ImageView(context);
        bookmarkIcon.setImageResource(R.drawable.msg_saved);
        bookmarkIcon.setColorFilter(grayFilter);
        bookmarkBtn.addView(bookmarkIcon,
                LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL));
        engRow.addView(bookmarkBtn,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL, 0, 0, 20, 0));

        View spacer = new View(context);
        engRow.addView(spacer, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        viewsIcon = new ImageView(context);
        viewsIcon.setImageResource(R.drawable.msg_views);
        viewsIcon.setColorFilter(grayFilter);
        engRow.addView(viewsIcon,
                LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL));

        viewsCountView = smallText(context, grayColor);
        engRow.addView(viewsCountView,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL, 4, 0, 0, 0));

        return engRow;
    }

    public void setPost(FeedController.FeedItem item) {
        cancelPendingTruncate();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.animate().cancel();
            child.setTranslationY(0);
            child.setAlpha(1f);
        }
        currentItem = item;
        fullText = null;
        collapsedEndOffset = -1;

        mediaShimmer.hide(false);
        mediaImageView1.setImageDrawable(null);
        mediaImageView1.getImageReceiver().clearImage();
        mediaImageView1.getImageReceiver().setDelegate(null);
        mediaContainer.setVisibility(GONE);
        mediaRow.setVisibility(GONE);
        albumLabel.setVisibility(GONE);
        mediaOverlayLabel.setVisibility(GONE);
        roundVideoView.setVisibility(GONE);

        replyView.clear();
        forwardView.clear();
        pollView.setVisibility(GONE);
        documentView.clear();
        voiceView.clear();
        buttonsView.clear();
        readMoreView.setVisibility(GONE);
        stickerView.clear();

        summaryHelper.reset();
        translationHelper.reset();

        recommendationHeader.setVisibility(GONE);
        subscribeBtn.setVisibility(GONE);

        if (item == null) return;

        textExpanded = item.textExpanded;
        expandedQuoteOffsets.clear();
        expandedQuoteOffsets.addAll(item.expandedQuoteOffsets);

        MessageObject primary = item.getPrimaryMessage();
        if (primary == null) {
            setVisibility(GONE);
            return;
        }
        TLRPC.Message raw = primary.messageOwner;
        if (raw == null) {
            setVisibility(GONE);
            return;
        }
        MessagesController controller = MessagesController.getInstance(currentAccount);

        bindHeader(item, raw, controller);
        replyView.setData(raw, controller);
        forwardView.setData(raw, controller);

        if (raw.media instanceof TLRPC.TL_messageMediaPoll) {
            TLRPC.TL_messageMediaPoll pollMedia = (TLRPC.TL_messageMediaPoll) raw.media;
            pollView.setPoll(pollMedia, raw);
            pollView.setVisibility(VISIBLE);
            mediaContainer.setVisibility(GONE);
            mediaRow.setVisibility(GONE);
            albumLabel.setVisibility(GONE);
            mediaOverlayLabel.setVisibility(GONE);
        } else {
            pollView.setVisibility(GONE);
            bindMedia(item);
        }

        MessageObject stickerMsg = FeedMediaHelper.findStickerMessage(item);
        if (stickerMsg != null) {
            stickerView.setSticker(stickerMsg);
        } else {
            stickerView.clear();
        }

        documentView.setData(item);
        voiceView.setData(item);
        buttonsView.setData(raw, item);
        reactionsView.setData(item);

        bindMessageText(item);
        bindEngagement(raw, item);
        summaryHelper.bind(item);
        translationHelper.bind(item);
        bindRecommendation(item);
    }

    private void bindMedia(FeedController.FeedItem item) {
        mediaShimmer.start();
        final FeedController.FeedItem capturedItem = item;

        FeedMediaHelper.setupMedia(item, getContext(), mediaContainer, mediaImageView1,
                mediaOverlayLabel, mediaRow, albumLabel,
                (feedItem, index) -> {
                    if (callback != null) callback.onMediaClick(feedItem, index);
                },
                resourceProvider, roundVideoView,
                (fromCache) -> {
                    if (currentItem != capturedItem) return;
                    mediaShimmer.hide(!fromCache);
                });

        if (mediaContainer.getVisibility() == GONE) {
            mediaShimmer.hide(false);
        } else {
            mediaImageView1.postDelayed(() -> {
                if (currentItem != capturedItem) return;
                if (mediaShimmer.isStarted()) mediaShimmer.hide(true);
            }, 15_000);
        }
    }

    private void bindHeader(FeedController.FeedItem item, TLRPC.Message raw,
                            MessagesController controller) {
        TLRPC.Chat chat = controller.getChat(-item.channelId);
        if (chat != null) {
            channelNameView.setText(chat.title);
            avatarDrawable.setInfo(chat);
            if (chat.photo != null && chat.photo.photo_small != null) {
                avatarView.setImage(
                        ImageLocation.getForChat(chat, ImageLocation.TYPE_SMALL),
                        "44_44", avatarDrawable, chat);
            } else {
                avatarView.setImageDrawable(avatarDrawable);
            }
        }

        String timeStr = LocaleController.formatDateAudio(raw.date, true);
        if (FeedUtils.isReallyEdited(raw)) timeStr += " · edited";
        timeView.setText(timeStr);
        unreadDot.setVisibility(item.isRead ? GONE : VISIBLE);
    }

    void bindMessageText(FeedController.FeedItem item) {
        CharSequence text = textFormatter.format(item,
                messageTextView.getPaint().getFontMetricsInt());

        if (text != null && text.length() > 0) {
            fullText = text;

            int rightPad = computeTextRightPad(text);
            messageTextView.setPadding(0, 0, rightPad, 0);
            messageTextView.setMaxLines(Integer.MAX_VALUE);
            messageTextView.setText(fullText);
            messageTextView.setVisibility(VISIBLE);
            messageTextView.post(messageTextView::invalidate);

            scheduleMeasureAndTruncate();
        } else {
            fullText = null;
            messageTextView.setPadding(0, 0, 0, 0);
            messageTextView.setVisibility(GONE);
            readMoreView.setVisibility(GONE);
        }
    }

    private int computeTextRightPad(CharSequence text) {
        if (!(text instanceof Spanned)) return 0;
        Spanned sp = (Spanned) text;
        int pad = 0;
        if (sp.getSpans(0, text.length(), FeedQuoteSpan.class).length > 0)
            pad = Math.max(pad, FeedQuoteSpan.ICON_ZONE);
        if (sp.getSpans(0, text.length(), FeedCodeSpan.Block.class).length > 0)
            pad = Math.max(pad, FeedCodeSpan.COPY_ZONE);
        return pad;
    }

    private void scheduleMeasureAndTruncate() {
        cancelPendingTruncate();
        pendingTruncateListener = () -> {
            if (messageTextView.getWidth() <= 0) return true;
            if (messageTextView.getLayout() == null) return true;
            cancelPendingTruncate();
            performMeasureAndTruncate();
            return true;
        };
        messageTextView.getViewTreeObserver().addOnPreDrawListener(pendingTruncateListener);
    }

    void cancelPendingTruncate() {
        if (pendingTruncateListener != null) {
            try {
                messageTextView.getViewTreeObserver()
                        .removeOnPreDrawListener(pendingTruncateListener);
            } catch (Exception ignored) {}
            pendingTruncateListener = null;
        }
    }

    private void performMeasureAndTruncate() {
        android.text.Layout layout = messageTextView.getLayout();
        if (layout == null) return;

        updateQuoteWidths();

        if (layout.getLineCount() > MAX_LINES_COLLAPSED) {
            collapsedEndOffset = layout.getLineEnd(MAX_LINES_COLLAPSED - 1);
            if (!textExpanded) {
                setCollapsedText();
                scheduleQuoteWidthUpdate();
                readMoreView.setVisibility(VISIBLE);
                readMoreView.setText(LocaleController.getString("FeedReadMore", R.string.FeedReadMore));
            } else {
                readMoreView.setVisibility(VISIBLE);
                readMoreView.setText(LocaleController.getString("FeedShowLess", R.string.FeedShowLess));
            }
        } else {
            collapsedEndOffset = -1;
            readMoreView.setVisibility(GONE);
        }
    }

    private void setCollapsedText() {
        if (fullText == null || collapsedEndOffset <= 0) return;
        int end = Math.min(collapsedEndOffset, fullText.length());
        SpannableStringBuilder truncated = new SpannableStringBuilder(fullText, 0, end);
        while (truncated.length() > 0
                && Character.isWhitespace(truncated.charAt(truncated.length() - 1)))
            truncated.delete(truncated.length() - 1, truncated.length());
        truncated.append("…");
        messageTextView.setText(truncated);
    }

    private void toggleExpanded() {
        textExpanded = !textExpanded;
        if (currentItem != null) currentItem.textExpanded = textExpanded;

        if (textExpanded) {
            messageTextView.setText(fullText);
            readMoreView.setText(LocaleController.getString("FeedShowLess", R.string.FeedShowLess));
        } else {
            setCollapsedText();
            readMoreView.setText(LocaleController.getString("FeedReadMore", R.string.FeedReadMore));

            scrollToTopOfPost();
        }
        scheduleQuoteWidthUpdate();
        requestLayout();
    }

    void rebuildMessageText() {
        rebuildMessageText(true);
    }

    void rebuildMessageText(boolean animate) {
        if (currentItem == null) return;
        currentItem.expandedQuoteOffsets.clear();
        currentItem.expandedQuoteOffsets.addAll(expandedQuoteOffsets);

        CharSequence text = textFormatter.format(currentItem,
                messageTextView.getPaint().getFontMetricsInt());
        if (text == null || text.length() == 0) {
            applyNewMessageText();
            return;
        }

        boolean canAnimate = animate
                && messageTextView.getVisibility() == VISIBLE
                && messageTextView.getWidth() > 0
                && messageTextView.getLayout() != null;

        if (!canAnimate) {
            applyNewMessageText();
            return;
        }

        final HashMap<View, Integer> oldPositions = new HashMap<>();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                oldPositions.put(child, child.getTop());
            }
        }

        fullText = text;
        int rightPad = computeTextRightPad(text);
        messageTextView.setPadding(0, 0, rightPad, 0);
        messageTextView.setMaxLines(Integer.MAX_VALUE);
        messageTextView.setText(fullText);
        messageTextView.setVisibility(VISIBLE);

        cancelPendingTruncate();

        pendingTruncateListener = new ViewTreeObserver.OnPreDrawListener() {
            boolean truncationApplied = false;
            int safety = 0;

            @Override
            public boolean onPreDraw() {
                safety++;
                if (safety > 5) {
                    cleanup();
                    return true;
                }

                android.text.Layout layout = messageTextView.getLayout();
                if (layout == null || messageTextView.getWidth() <= 0) return true;

                if (!truncationApplied) {
                    truncationApplied = true;
                    updateQuoteWidths();

                    if (layout.getLineCount() > MAX_LINES_COLLAPSED) {
                        collapsedEndOffset = layout.getLineEnd(MAX_LINES_COLLAPSED - 1);
                        if (!textExpanded) {
                            setCollapsedText();
                            readMoreView.setVisibility(VISIBLE);
                            readMoreView.setText(
                                    LocaleController.getString("FeedReadMore", R.string.FeedReadMore));
                            return false;
                        } else {
                            readMoreView.setVisibility(VISIBLE);
                            readMoreView.setText(
                                    LocaleController.getString("FeedShowLess", R.string.FeedShowLess));
                        }
                    } else {
                        collapsedEndOffset = -1;
                        readMoreView.setVisibility(GONE);
                    }
                }

                cleanup();
                scheduleQuoteWidthUpdate();
                runQuoteAnimation(oldPositions);
                return true;
            }

            private void cleanup() {
                try {
                    messageTextView.getViewTreeObserver().removeOnPreDrawListener(this);
                } catch (Exception ignored) {}
                pendingTruncateListener = null;
            }
        };
        messageTextView.getViewTreeObserver().addOnPreDrawListener(pendingTruncateListener);
    }

    private void applyNewMessageText() {
        CharSequence text = textFormatter.format(currentItem,
                messageTextView.getPaint().getFontMetricsInt());
        if (text == null || text.length() == 0) return;

        fullText = text;
        int rightPad = computeTextRightPad(text);
        messageTextView.setPadding(0, 0, rightPad, 0);
        messageTextView.setMaxLines(Integer.MAX_VALUE);
        messageTextView.setText(fullText);
        messageTextView.setVisibility(VISIBLE);

        collapsedEndOffset = -1;
        readMoreView.setVisibility(GONE);
        scheduleMeasureAndTruncate();
    }

    void updateQuoteWidths() {
        android.text.Layout layout = messageTextView.getLayout();
        if (layout == null) return;
        CharSequence text = messageTextView.getText();
        if (!(text instanceof Spanned)) return;

        Spanned sp = (Spanned) text;
        FeedQuoteSpan[] quotes = sp.getSpans(0, text.length(), FeedQuoteSpan.class);
        if (quotes == null || quotes.length == 0) return;

        boolean changed = false;
        for (FeedQuoteSpan q : quotes) {
            int start = sp.getSpanStart(q);
            int end = sp.getSpanEnd(q);
            if (start < 0 || end <= start) continue;

            int startLine = layout.getLineForOffset(start);
            int endLine = layout.getLineForOffset(Math.max(start, end - 1));

            float maxW = 0;
            for (int line = startLine; line <= endLine; line++)
                maxW = Math.max(maxW, layout.getLineWidth(line));

            float boxW = Math.max(maxW + FeedQuoteSpan.ICON_ZONE, dp(60));
            if (q.boxWidth != boxW) { q.boxWidth = boxW; changed = true; }
        }
        if (changed) messageTextView.invalidate();
    }

    private void scheduleQuoteWidthUpdate() {
        messageTextView.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        messageTextView.getViewTreeObserver().removeOnPreDrawListener(this);
                        updateQuoteWidths();
                        return true;
                    }
                });
    }

    private void bindEngagement(TLRPC.Message raw, FeedController.FeedItem item) {
        if (raw.views > 0) {
            viewsCountView.setText(LocaleController.formatShortNumber(raw.views, null));
            viewsIcon.setVisibility(VISIBLE);
            viewsCountView.setVisibility(VISIBLE);
        } else {
            viewsIcon.setVisibility(GONE);
            viewsCountView.setVisibility(GONE);
        }

        if (raw.replies != null) {
            if (raw.replies.replies > 0) {
                commentsCountView.setText(
                        LocaleController.formatShortNumber(raw.replies.replies, null));
                commentsCountView.setVisibility(VISIBLE);
            } else {
                commentsCountView.setText("");
                commentsCountView.setVisibility(GONE);
            }
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

        updateBookmarkState(item != null && item.isBookmarked);
    }

    public void updateBookmarkState(boolean bookmarked) {
        bookmarkIcon.setImageResource(R.drawable.msg_saved);
        bookmarkIcon.setColorFilter(bookmarked
                ? new PorterDuffColorFilter(
                Theme.getColor(Theme.key_featuredStickers_addButton, resourceProvider),
                PorterDuff.Mode.SRC_IN)
                : grayFilter);
    }

    private void bindRecommendation(FeedController.FeedItem item) {
        if (item.isRecommendation) {
            String reason = item.recommendationReason;
            if (reason == null || reason.isEmpty()) reason = "Recommended for you";
            recommendationReasonView.setText(reason);
            recommendationHeader.setVisibility(VISIBLE);
            subscribeBtn.setVisibility(VISIBLE);
        } else {
            recommendationHeader.setVisibility(GONE);
            subscribeBtn.setVisibility(GONE);
        }
    }

    void setDimmed(boolean dimmed) {
        if (isDimmed == dimmed) return;
        isDimmed = dimmed;
        invalidate();
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (isDimmed) canvas.drawRect(0, 0, getWidth(), getHeight(), dimPaint);
        super.dispatchDraw(canvas);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                longPressTriggered = false;
                longPressDownX = ev.getX();
                longPressDownY = ev.getY();

                boolean onPlainText = isTouchOnPlainText(ev);
                boolean onInteractive = isTouchOnInteractiveChild(ev);

                downAllowsDoubleTap = onPlainText || !onInteractive;

                cancelPendingLongPress();

                if (onPlainText) {
                    scheduleTextLongPress();
                } else if (!onInteractive) {
                    schedulePostLongPress();
                }

                if (downAllowsDoubleTap) {
                    doubleTapDetector.onTouchEvent(ev);
                }
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                float dx = Math.abs(ev.getX() - longPressDownX);
                float dy = Math.abs(ev.getY() - longPressDownY);

                if (dx > touchSlop || dy > touchSlop) {
                    cancelPendingLongPress();
                }

                if (downAllowsDoubleTap) {
                    doubleTapDetector.onTouchEvent(ev);
                }
                break;
            }

            case MotionEvent.ACTION_UP: {
                cancelPendingLongPress();

                if (longPressTriggered) {
                    longPressTriggered = false;
                    return true;
                }

                if (downAllowsDoubleTap && doubleTapDetector.onTouchEvent(ev)) {
                    return true;
                }
                break;
            }

            case MotionEvent.ACTION_CANCEL: {
                cancelPendingLongPress();
                longPressTriggered = false;
                downAllowsDoubleTap = false;
                break;
            }
        }

        return super.dispatchTouchEvent(ev);
    }

    private void cancelPendingLongPress() {
        if (longPressRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(longPressRunnable);
            longPressRunnable = null;
        }
    }

    boolean isTouchOnMessageText(MotionEvent ev) {
        if (messageTextView == null || messageTextView.getVisibility() != VISIBLE) return false;
        return ev.getX() >= messageTextView.getLeft()
                && ev.getX() <= messageTextView.getRight()
                && ev.getY() >= messageTextView.getTop()
                && ev.getY() <= messageTextView.getBottom();
    }

    private boolean isTouchOnInteractiveChild(MotionEvent ev) {
        return hasInteractiveTarget(this, ev.getX(), ev.getY());
    }

    private boolean hasInteractiveTarget(View view, float x, float y) {
        if (view == null || view.getVisibility() != VISIBLE) return false;
        if (x < 0 || y < 0 || x > view.getWidth() || y > view.getHeight()) return false;

        if (view == messageTextView) {
            return messageTextView.hasInteractiveElementAt(x, y);
        }

        if (view != this && (view.isClickable() || view.isLongClickable())) {
            return true;
        }

        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                View child = vg.getChildAt(i);
                float childX = x - child.getLeft();
                float childY = y - child.getTop();
                if (hasInteractiveTarget(child, childX, childY)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (messageTextView != null && messageTextView.getVisibility() == VISIBLE
                && fullText != null && fullText.length() > 0)
            messageTextView.invalidate();

        if (mediaImageView1 != null && mediaContainer.getVisibility() == VISIBLE) {
            mediaImageView1.invalidate();
            ImageReceiver ir = mediaImageView1.getImageReceiver();
            boolean loaded = ir.hasNotThumb() || ir.getBitmap() != null
                    || ir.getStaticThumb() != null;
            if (loaded) {
                mediaShimmer.hide(false);
            } else {
                ir.setDelegate((imageReceiver, set, thumb, memCache) -> {
                    if (set && !thumb) mediaShimmer.hide(!memCache);
                });
                if (!mediaShimmer.isStarted()) mediaShimmer.start();
            }
        }

        if (mediaRow != null && mediaRow.getVisibility() == VISIBLE) {
            for (int i = 0; i < mediaRow.getChildCount(); i++) {
                View child = mediaRow.getChildAt(i);
                if (child instanceof BackupImageView) child.invalidate();
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelPendingTruncate();
        roundVideoView.release();
        if (mediaShimmer != null) mediaShimmer.hide(false);
        if (mediaImageView1 != null) mediaImageView1.getImageReceiver().setDelegate(null);
    }

    private TextView smallText(Context ctx, int color) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tv.setTextColor(color);
        return tv;
    }

    private void runQuoteAnimation(HashMap<View, Integer> oldPositions) {
        int msgIndex = indexOfChild(messageTextView);

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            if (i <= msgIndex) continue;

            Integer oldTop = oldPositions.get(child);
            if (oldTop == null) {
                child.setAlpha(0f);
                child.animate()
                        .alpha(1f)
                        .setDuration(200)
                        .start();
                continue;
            }

            int delta = child.getTop() - oldTop;
            if (delta != 0) {
                child.setTranslationY(-delta);
                child.animate()
                        .translationY(0)
                        .setDuration(250)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                        .start();
            }
        }
    }

    private void scrollToTopOfPost() {
        android.view.ViewParent parent = getParent();
        while (parent != null && !(parent instanceof RecyclerView)) {
            parent = parent.getParent();
        }
        if (parent == null) return;

        RecyclerView rv = (RecyclerView) parent;

        post(() -> {
            int top = getTop();
            int rvTop = rv.getPaddingTop();

            if (top < rvTop) {
                int scrollBy = top - rvTop;
                rv.smoothScrollBy(0, scrollBy);
            }
        });
    }

    public CharSequence getSelectableText() {
        if (fullText != null) {
            return fullText;
        }
        return messageTextView != null ? messageTextView.getText() : "";
    }

    private boolean isTouchOnPlainText(MotionEvent ev) {
        if (messageTextView == null || messageTextView.getVisibility() != VISIBLE) return false;

        float localX = ev.getX() - messageTextView.getLeft();
        float localY = ev.getY() - messageTextView.getTop();

        return messageTextView.hasPlainTextAt(localX, localY);
    }

    private void schedulePostLongPress() {
        longPressRunnable = () -> {
            longPressTriggered = true;
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            if (callback != null && currentItem != null) {
                callback.onPostLongPress(this);
            }
        };
        AndroidUtilities.runOnUIThread(longPressRunnable, longPressTimeout);
    }

    private void scheduleTextLongPress() {
        longPressRunnable = () -> {
            longPressTriggered = true;
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            if (callback != null && currentItem != null) {
                callback.onTextLongPress(this, currentItem, getSelectableText());
            }
        };
        AndroidUtilities.runOnUIThread(longPressRunnable, longPressTimeout);
    }
}