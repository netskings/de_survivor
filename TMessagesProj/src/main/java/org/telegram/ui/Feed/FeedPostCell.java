package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
    import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.style.URLSpan;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LanguageDetector;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.TranslateController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkPath;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.TranslateAlert2;
import org.telegram.ui.Components.spoilers.SpoilerEffect;

@SuppressLint("ViewConstructor")
public class FeedPostCell extends LinearLayout {

    private static final int MAX_LINES_COLLAPSED = 8;

    private final BackupImageView avatarView;
    private final TextView channelNameView;
    private final TextView timeView;
    private final View unreadDot;
    private final AvatarDrawable avatarDrawable;

    private final FeedReplyView replyView;
    private final FeedForwardView forwardView;
    private final FeedPollView pollView;
    private final FeedDocumentView documentView;
    private final FeedVoiceView voiceView;
    private final FeedInlineButtonsView buttonsView;
    private final FeedReactionsView reactionsView;

    private boolean messageHasSpoilers = false;
    private Runnable spoilerRevealer = null;
    private final AnimatedEmojiSpan.TextViewEmojis messageTextView;
    private final TextView readMoreView;
    private final android.graphics.Path spoilerClipPath = new android.graphics.Path();

    private final android.widget.FrameLayout mediaContainer;
    private final FeedRoundVideoView roundVideoView;
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
    private final ImageView bookmarkIcon;

    private final PorterDuffColorFilter grayFilter;

    private final TextView summarizeBtn;
    private final LinearLayout summaryCard;
    private final TextView summaryTextView;
    private boolean summaryLoading = false;

    private FeedController.FeedItem currentItem;
    private boolean textExpanded = false;
    private CharSequence fullText = null;
    private int collapsedEndOffset = -1;
    private final int currentAccount;
    private final Theme.ResourcesProvider resourceProvider;
    private ViewTreeObserver.OnPreDrawListener pendingTruncateListener;
    private final java.util.HashSet<Integer> expandedQuoteOffsets = new java.util.HashSet<>();
    private final FeedTextFormatter textFormatter;

    private final LinkSpanDrawable.LinkCollector linkCollector;
    private LinkSpanDrawable<ClickableSpan> pressedLink;
    private final Paint dimPaint = new Paint();

    private final GestureDetector doubleTapDetector;

    private final TextView translateBtn;
    private final LinearLayout translationCard;
    private final TextView translationHeaderView;
    private final TextView translationTextView;
    private boolean translationLoading = false;

    private Runnable longPressRunnable;
    private boolean longPressTriggered;
    private float longPressDownX, longPressDownY;
    private boolean longPressStarted;
    private static final long LONG_PRESS_TIMEOUT = 500;
    private static final float TOUCH_SLOP = dp(10);

    private final LinearLayout recommendationHeader;
    private final TextView recommendationReasonView;
    private final TextView subscribeBtn;

    public void setPressedLink(LinkSpanDrawable<ClickableSpan> pressedLink) {
        this.pressedLink = pressedLink;
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
        void onSubscribeClick(FeedController.FeedItem item);
        void onDismissRecommendation(FeedController.FeedItem item);
    }

    private Callback callback;

    public void setCallback(Callback callback) {
        this.callback = callback;
        reactionsView.setCallback(new FeedReactionsView.ReactionCallback() {
            @Override
            public void onReactionToggle(FeedController.FeedItem item, TLRPC.Reaction reaction) {
                if (callback != null) callback.onReactionToggle(item, reaction);
            }
            @Override
            public void onPaidReactionTap(FeedController.FeedItem item) {
                if (callback != null) callback.onPaidReactionTap(item);
            }
            @Override
            public void onPaidReactionLongPress(FeedController.FeedItem item) {
                if (callback != null) callback.onPaidReactionLongPress(item);
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
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f,
                        Gravity.CENTER_VERTICAL));

        TextView recommendationDismissBtn = new TextView(context);
        recommendationDismissBtn.setText("✕");
        recommendationDismissBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        recommendationDismissBtn.setTextColor(grayColor);
        recommendationDismissBtn.setPadding(dp(12), dp(4), dp(4), dp(4));
        recommendationDismissBtn.setOnClickListener(v -> {
            if (callback != null && currentItem != null)
                callback.onDismissRecommendation(currentItem);
        });
        recommendationHeader.addView(recommendationDismissBtn,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL));

        addView(recommendationHeader,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

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
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

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
        android.graphics.drawable.GradientDrawable subBg = new android.graphics.drawable.GradientDrawable();
        subBg.setColor(accentColor);
        subBg.setCornerRadius(dp(16));
        subscribeBtn.setBackground(subBg);
        subscribeBtn.setVisibility(GONE);
        subscribeBtn.setOnClickListener(v -> {
            if (callback != null && currentItem != null)
                callback.onSubscribeClick(currentItem);
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

        addView(headerRow,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        replyView = new FeedReplyView(context, currentAccount, resourceProvider);
        replyView.setVisibility(GONE);
        replyView.setOnReplyClickListener((channelId, messageId) -> {
            if (callback != null) callback.onReplyClick(channelId, messageId);
        });
        addView(replyView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        forwardView = new FeedForwardView(context, resourceProvider);
        forwardView.setVisibility(GONE);
        forwardView.setOnForwardClickListener((channelId, messageId) -> {
            if (callback != null) callback.onForwardClick(channelId, messageId);
        });
        addView(forwardView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        translateBtn = new TextView(context);
        translateBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        translateBtn.setTextColor(accentColor);
        translateBtn.setPadding(0, dp(6), 0, dp(2));
        translateBtn.setVisibility(GONE);
        translateBtn.setOnClickListener(v -> onTranslateClick());
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


        messageTextView = new AnimatedEmojiSpan.TextViewEmojis(context) {

            private final Paint extBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint extIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint extArrowBg = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint extArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF extRect = new RectF();
            private final android.graphics.Path extPath = new android.graphics.Path();

            private final java.util.List<SpoilerEffect> spoilerEffects = new java.util.ArrayList<>();
            private final java.util.Stack<SpoilerEffect> spoilerPool = new java.util.Stack<>();
            private boolean spoilersRevealed = false;

            {
                extArrowPaint.setStyle(Paint.Style.STROKE);
                extArrowPaint.setStrokeWidth(dp(1.6f));
                extArrowPaint.setStrokeCap(Paint.Cap.ROUND);
                extArrowPaint.setStrokeJoin(Paint.Join.ROUND);
                extIconPaint.setStyle(Paint.Style.FILL);
            }

            @Override
            public void setText(CharSequence text, BufferType type) {
                super.setText(text, type);
                if (spoilerEffects != null) {
                    spoilersRevealed = false;
                    invalidateSpoilers();
                }
            }

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                invalidateSpoilers();
            }

            private void invalidateSpoilers() {
                if (spoilerEffects == null) return;
                spoilerEffects.clear();
                if (spoilersRevealed) return;
                Layout layout = getLayout();
                if (layout != null && getText() instanceof Spanned) {
                    SpoilerEffect.addSpoilers(this, spoilerPool, spoilerEffects);
                }
                messageHasSpoilers = !spoilerEffects.isEmpty();
                spoilerRevealer = this::revealSpoilers;
            }

            public void revealSpoilers() {
                spoilersRevealed = true;
                spoilerEffects.clear();
                invalidate();
            }

            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                boolean hasSpoilers = !spoilerEffects.isEmpty() && !spoilersRevealed;

                if (hasSpoilers) {
                    canvas.save();
                    spoilerClipPath.rewind();
                    int pl = getCompoundPaddingLeft();
                    int pt = getExtendedPaddingTop();
                    for (SpoilerEffect eff : spoilerEffects) {
                        android.graphics.Rect b = eff.getBounds();
                        spoilerClipPath.addRect(
                                pl + b.left, pt + b.top,
                                pl + b.right, pt + b.bottom,
                                android.graphics.Path.Direction.CW);
                    }
                    canvas.clipPath(spoilerClipPath, android.graphics.Region.Op.DIFFERENCE);
                }

                super.onDraw(canvas);

                if (hasSpoilers) {
                    canvas.restore();
                }

                drawQuoteDecorations(canvas);

                drawSpoilerEffects(canvas);
            }

            private void drawSpoilerEffects(Canvas canvas) {
                if (spoilerEffects.isEmpty() || spoilersRevealed) return;

                int pl = getCompoundPaddingLeft();
                int pt = getExtendedPaddingTop();

                canvas.save();
                canvas.translate(pl, pt);
                for (SpoilerEffect eff : spoilerEffects) {
                    eff.setColor(getCurrentTextColor());
                    eff.draw(canvas);
                }
                canvas.restore();

                invalidate();
            }

            private void drawQuoteDecorations(Canvas canvas) {
                Layout layout = getLayout();
                if (layout == null) return;
                CharSequence text = getText();
                if (!(text instanceof Spanned)) return;

                int pr = getPaddingRight();
                Spanned sp = (Spanned) text;
                FeedQuoteSpan[] quotes = sp.getSpans(0, text.length(), FeedQuoteSpan.class);
                if (quotes == null || quotes.length == 0) return;

                int layoutW = layout.getWidth();
                int viewW = getWidth();
                int pl = getCompoundPaddingLeft();
                int pt = getExtendedPaddingTop();

                for (FeedQuoteSpan q : quotes) {
                    int start = sp.getSpanStart(q);
                    int end = sp.getSpanEnd(q);
                    if (start < 0 || end <= start) continue;

                    int firstLine = layout.getLineForOffset(start);
                    int lastLine = layout.getLineForOffset(Math.max(start, end - 1));

                    float bgTop = pt + layout.getLineTop(firstLine) + q.topPad;
                    float bgBottom = pt + layout.getLineBottom(lastLine) - q.bottomPad;
                    int cr = q.cornerRadius;

                    float bgRight;
                    if (q.boxWidth > 0 && q.boxWidth <= layoutW) {
                        bgRight = pl + q.boxWidth;
                    } else {
                        bgRight = Math.min(pl + (q.boxWidth > 0 ? q.boxWidth : layoutW), viewW);
                    }

                    if (pr > 0 && q.boxWidth > layoutW) {
                        float extLeft = pl + layoutW;
                        float extRight = bgRight;

                        extBgPaint.setColor(q.bgPaint.getColor());

                        extPath.reset();
                        extRect.set(extLeft, bgTop, extRight, bgBottom);
                        float tr = cr;
                        float br = cr;
                        extPath.addRoundRect(extRect, new float[]{
                                0, 0, tr, tr, br, br, 0, 0
                        }, android.graphics.Path.Direction.CW);
                        canvas.drawPath(extPath, extBgPaint);
                    }

                    int iconColor = q.stripePaint.getColor();
                    extIconPaint.setColor(iconColor);
                    extIconPaint.setAlpha(140);

                    float iconX = bgRight - dp(10);
                    float iconY = bgTop + dp(6);

                    float qw = dp(3.5f), qh = dp(5.5f), gap = dp(2), r = dp(1);
                    for (int i = 0; i < 2; i++) {
                        float cx = iconX - i * (qw + gap);
                        extRect.set(cx - qw, iconY, cx, iconY + qh);
                        canvas.drawRoundRect(extRect, r, r, extIconPaint);
                    }

                    if (q.collapsible) {
                        float lastTop = pt + layout.getLineTop(lastLine);
                        float lastBottom = pt + layout.getLineBottom(lastLine) - q.bottomPad;

                        float btnRadius = dp(9);
                        float btnCX = bgRight - dp(6) - btnRadius;
                        float btnCY = (lastTop + lastBottom) / 2f;

                        extArrowBg.setColor(iconColor);
                        extArrowBg.setAlpha(38);
                        canvas.drawCircle(btnCX, btnCY, btnRadius, extArrowBg);

                        extArrowPaint.setColor(iconColor);
                        float chevW = dp(3.5f), chevH = dp(2f);
                        extPath.reset();
                        if (!q.expanded) {
                            extPath.moveTo(btnCX - chevW, btnCY - chevH);
                            extPath.lineTo(btnCX, btnCY + chevH);
                            extPath.lineTo(btnCX + chevW, btnCY - chevH);
                        } else {
                            extPath.moveTo(btnCX - chevW, btnCY + chevH);
                            extPath.lineTo(btnCX, btnCY - chevH);
                            extPath.lineTo(btnCX + chevW, btnCY + chevH);
                        }
                        canvas.drawPath(extPath, extArrowPaint);
                    }
                }
            }
            private ClickableSpan touchedSpan;
            private Runnable spanLongPressRunnable;
            private boolean spanLongPressed;
            private float spanDownX, spanDownY;

            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                int action = event.getAction();
                Layout layout = getLayout();

                if (layout != null && getText() instanceof Spanned) {
                    Spanned spanned = (Spanned) getText();
                    int x = (int) event.getX() - getTotalPaddingLeft() + getScrollX();
                    int y = (int) event.getY() - getTotalPaddingTop() + getScrollY();
                    int line = layout.getLineForVertical(y);
                    int off = layout.getOffsetForHorizontal(line, x);
                    float lineLeft = layout.getLineLeft(line);
                    float lineWidth = layout.getLineWidth(line);
                    boolean inText = x >= lineLeft && x <= lineLeft + lineWidth;

                    if (action == MotionEvent.ACTION_DOWN) {
                        spanLongPressed = false;
                        spanDownX = event.getX();
                        spanDownY = event.getY();

                        if (messageHasSpoilers && spoilerRevealer != null) {
                            touchedSpan = null;
                            return true;
                        }

                        if (inText) {
                            ClickableSpan[] spans = spanned.getSpans(off, off, ClickableSpan.class);
                            if (spans.length > 0) {
                                touchedSpan = spans[0];

                                LinkSpanDrawable<ClickableSpan> link = new LinkSpanDrawable<>(
                                        touchedSpan, resourceProvider, event.getX(), event.getY());
                                pressedLink = link;
                                linkCollector.addLink(link);

                                int start = spanned.getSpanStart(touchedSpan);
                                int end = spanned.getSpanEnd(touchedSpan);
                                LinkPath path = link.obtainNewPath();
                                path.setCurrentLayout(layout, start, getPaddingTop());
                                layout.getSelectionPath(start, end, path);

                                setDimmed(true);

                                if (spanLongPressRunnable != null) {
                                    AndroidUtilities.cancelRunOnUIThread(spanLongPressRunnable);
                                }
                                spanLongPressRunnable = () -> {
                                    spanLongPressed = true;
                                    linkCollector.clear();
                                    pressedLink = null;
                                    setDimmed(false);
                                    if (touchedSpan instanceof URLSpan) {
                                        String url = ((URLSpan) touchedSpan).getURL();
                                        if (callback != null)
                                            callback.onLinkLongPress(url, FeedPostCell.this, touchedSpan);
                                    }
                                    touchedSpan = null;
                                };
                                AndroidUtilities.runOnUIThread(spanLongPressRunnable,
                                        ViewConfiguration.getLongPressTimeout());

                                return true;
                            }
                        }

                        touchedSpan = null;
                        return super.onTouchEvent(event);

                    } else if (action == MotionEvent.ACTION_MOVE) {
                        if (touchedSpan != null) {
                            if (Math.abs(event.getX() - spanDownX) > dp(8) ||
                                    Math.abs(event.getY() - spanDownY) > dp(8)) {
                                cancelSpanTouch();
                            }
                            return true;
                        }
                        return super.onTouchEvent(event);

                    } else if (action == MotionEvent.ACTION_UP) {
                        if (spanLongPressRunnable != null) {
                            AndroidUtilities.cancelRunOnUIThread(spanLongPressRunnable);
                            spanLongPressRunnable = null;
                        }
                        setDimmed(false);
                        linkCollector.clear();
                        pressedLink = null;

                        if (messageHasSpoilers && spoilerRevealer != null) {
                            spoilerRevealer.run();
                            messageHasSpoilers = false;
                            touchedSpan = null;
                            return true;
                        }

                        if (spanLongPressed) {
                            spanLongPressed = false;
                            touchedSpan = null;
                            return true;
                        }

                        if (touchedSpan != null) {
                            ClickableSpan span = touchedSpan;
                            touchedSpan = null;

                            if (span instanceof FeedQuoteSpan.Clickable) {
                                span.onClick(this);
                                return true;
                            }
                            if (span instanceof URLSpan) {
                                String url = ((URLSpan) span).getURL();
                                if (callback != null) {
                                    callback.onLinkClick(url);
                                    return true;
                                }
                            }
                            span.onClick(this);
                            return true;
                        }

                        return super.onTouchEvent(event);

                    } else if (action == MotionEvent.ACTION_CANCEL) {
                        cancelSpanTouch();
                        return super.onTouchEvent(event);
                    }
                }

                return super.onTouchEvent(event);
            }

            private void cancelSpanTouch() {
                if (spanLongPressRunnable != null) {
                    AndroidUtilities.cancelRunOnUIThread(spanLongPressRunnable);
                    spanLongPressRunnable = null;
                }
                setDimmed(false);
                linkCollector.clear();
                pressedLink = null;
                touchedSpan = null;
                spanLongPressed = false;
            }
        };

        linkCollector = new LinkSpanDrawable.LinkCollector(messageTextView);
        dimPaint.setColor(0x44000000);

        messageTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        messageTextView.setTextColor(
                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        messageTextView.setLinkTextColor(accentColor);
        messageTextView.setLineSpacing(dp(2), 1f);
        messageTextView.setTextIsSelectable(true);
        messageTextView.setCursorVisible(false);
        messageTextView.setVisibility(GONE);
        addView(messageTextView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

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
        summarizeBtn.setOnClickListener(v -> onSummarizeClick());
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
            android.widget.LinearLayout.LayoutParams rvLp =
                    (android.widget.LinearLayout.LayoutParams) roundVideoView.getLayoutParams();
            if (rvLp != null) {
                rvLp.width  = newSizePx;
                rvLp.height = newSizePx;
                roundVideoView.setLayoutParams(rvLp);
            }
        });

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
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        buttonsView = new FeedInlineButtonsView(context, resourceProvider);
        buttonsView.setVisibility(GONE);
        buttonsView.setOnButtonClickListener((item, button) -> {
            if (callback != null) callback.onInlineButtonClick(item, button);
        });
        addView(buttonsView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        reactionsView = new FeedReactionsView(context, currentAccount, resourceProvider);
        reactionsView.setVisibility(GONE);
        addView(reactionsView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        setClipChildren(false);
        setClipToPadding(false);

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
        commentsBtn.addView(commentsCountView, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
        engRow.addView(commentsBtn, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
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
        shareBtn.addView(sharesCountView, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
        engRow.addView(shareBtn, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
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

        engRow.addView(bookmarkBtn, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
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
        engRow.addView(viewsCountView, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL, 4, 0, 0, 0));

        addView(engRow,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        textFormatter = new FeedTextFormatter(resourceProvider, expandedQuoteOffsets);
        textFormatter.setRebuildCallback(this::rebuildMessageText);

        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider, resourceProvider));
        addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        doubleTapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (callback != null && currentItem != null) {
                    callback.onDoubleTap(currentItem);
                }
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                return false;
            }
        });
        doubleTapDetector.setIsLongpressEnabled(false);
    }

    public void setPost(FeedController.FeedItem item) {
        cancelPendingTruncate();
        currentItem = item;
        fullText = null;
        collapsedEndOffset = -1;

        replyView.clear();
        forwardView.clear();
        pollView.setVisibility(GONE);
        documentView.clear();
        voiceView.clear();
        buttonsView.clear();
        readMoreView.setVisibility(GONE);

        summaryLoading = false;
        summarizeBtn.setVisibility(GONE);
        summaryCard.setVisibility(GONE);

        translationLoading = false;
        translateBtn.setVisibility(GONE);
        translationCard.setVisibility(GONE);

        recommendationHeader.setVisibility(GONE);
        subscribeBtn.setVisibility(GONE);

        if (item == null) return;

        textExpanded = item.textExpanded;
        expandedQuoteOffsets.clear();
        expandedQuoteOffsets.addAll(item.expandedQuoteOffsets);

        MessageObject primary = item.getPrimaryMessage();
        TLRPC.Message raw = primary.messageOwner;
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
            FeedMediaHelper.setupMedia(
                    item, getContext(), mediaContainer, mediaImageView1,
                    mediaOverlayLabel, mediaRow, albumLabel,
                    (feedItem, index) -> {
                        if (callback != null) callback.onMediaClick(feedItem, index);
                    },
                    resourceProvider,
                    roundVideoView
            );
        }

        documentView.setData(item);
        voiceView.setData(item);
        buttonsView.setData(raw, item);
        reactionsView.setData(item);

        bindMessageText(item);
        bindEngagement(raw, item);
        bindSummary(item);
        bindTranslation(item);
        bindRecommendation(item);
    }

    public FeedController.FeedItem getCurrentItem() {
        return currentItem;
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
        if (FeedUtils.isReallyEdited(raw)) {
            timeStr += " · edited";
        }
        timeView.setText(timeStr);
        unreadDot.setVisibility(item.isRead ? GONE : VISIBLE);
    }

    public FeedReactionsView getReactionsView() {
        return reactionsView;
    }

    private void bindMessageText(FeedController.FeedItem item) {
        CharSequence text = textFormatter.format(item,
                messageTextView.getPaint().getFontMetricsInt());

        if (text != null && text.length() > 0) {
            fullText = text;

            boolean hasQuotes = false;
            if (text instanceof Spanned) {
                FeedQuoteSpan[] qs = ((Spanned) text).getSpans(
                        0, text.length(), FeedQuoteSpan.class);
                hasQuotes = qs != null && qs.length > 0;
            }
            messageTextView.setPadding(0, 0,
                    hasQuotes ? FeedQuoteSpan.ICON_ZONE : 0, 0);

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

    private void scheduleMeasureAndTruncate() {
        cancelPendingTruncate();
        pendingTruncateListener = () -> {
            if (messageTextView.getWidth() <= 0) return true;
            Layout layout = messageTextView.getLayout();
            if (layout == null) return true;
            cancelPendingTruncate();
            performMeasureAndTruncate();
            return true;
        };
        messageTextView.getViewTreeObserver().addOnPreDrawListener(pendingTruncateListener);
    }

    private void cancelPendingTruncate() {
        if (pendingTruncateListener != null) {
            try {
                messageTextView.getViewTreeObserver()
                        .removeOnPreDrawListener(pendingTruncateListener);
            } catch (Exception ignored) {
            }
            pendingTruncateListener = null;
        }
    }

    private void performMeasureAndTruncate() {
        Layout layout = messageTextView.getLayout();
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
                && Character.isWhitespace(truncated.charAt(truncated.length() - 1))) {
            truncated.delete(truncated.length() - 1, truncated.length());
        }
        truncated.append("…");
        messageTextView.setText(truncated);
    }

    private void toggleExpanded() {
        textExpanded = !textExpanded;

        if (currentItem != null) {
            currentItem.textExpanded = textExpanded;
        }

        if (textExpanded) {
            messageTextView.setText(fullText);
            readMoreView.setText(LocaleController.getString("FeedShowLess", R.string.FeedShowLess));
        } else {
            setCollapsedText();
            readMoreView.setText(LocaleController.getString("FeedReadMore", R.string.FeedReadMore));
        }
        scheduleQuoteWidthUpdate();
        requestLayout();
    }

    private void rebuildMessageText() {
        if (currentItem == null) return;

        currentItem.expandedQuoteOffsets.clear();
        currentItem.expandedQuoteOffsets.addAll(expandedQuoteOffsets);

        CharSequence text = textFormatter.format(currentItem,
                messageTextView.getPaint().getFontMetricsInt());
        if (text == null || text.length() == 0) return;

        fullText = text;

        boolean hasQuotes = false;
        if (text instanceof Spanned) {
            FeedQuoteSpan[] qs = ((Spanned) text).getSpans(
                    0, text.length(), FeedQuoteSpan.class);
            hasQuotes = qs != null && qs.length > 0;
        }
        messageTextView.setPadding(0, 0,
                hasQuotes ? FeedQuoteSpan.ICON_ZONE : 0, 0);

        messageTextView.setMaxLines(Integer.MAX_VALUE);
        messageTextView.setText(fullText);
        messageTextView.setVisibility(VISIBLE);

        collapsedEndOffset = -1;
        readMoreView.setVisibility(GONE);
        scheduleMeasureAndTruncate();
        requestLayout();
    }

    private void updateQuoteWidths() {
        Layout layout = messageTextView.getLayout();
        if (layout == null) return;

        CharSequence text = messageTextView.getText();
        if (!(text instanceof Spanned)) return;

        Spanned spanned = (Spanned) text;
        FeedQuoteSpan[] quotes = spanned.getSpans(0, text.length(), FeedQuoteSpan.class);
        if (quotes == null || quotes.length == 0) return;

        boolean changed = false;
        for (FeedQuoteSpan q : quotes) {
            int start = spanned.getSpanStart(q);
            int end = spanned.getSpanEnd(q);
            if (start < 0 || end <= start) continue;

            int startLine = layout.getLineForOffset(start);
            int endLine = layout.getLineForOffset(Math.max(start, end - 1));

            float maxLineWidth = 0;
            for (int line = startLine; line <= endLine; line++) {
                maxLineWidth = Math.max(maxLineWidth, layout.getLineWidth(line));
            }

            float boxW = maxLineWidth + FeedQuoteSpan.ICON_ZONE;
            boxW = Math.max(boxW, dp(60));

            if (q.boxWidth != boxW) {
                q.boxWidth = boxW;
                changed = true;
            }
        }

        if (changed) {
            messageTextView.invalidate();
        }
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
        if (item != null && item.isBookmarked) {
            bookmarkIcon.setImageResource(R.drawable.msg_saved);
            bookmarkIcon.setColorFilter(new PorterDuffColorFilter(
                    Theme.getColor(Theme.key_featuredStickers_addButton, resourceProvider),
                    PorterDuff.Mode.SRC_IN));
        } else {
            bookmarkIcon.setImageResource(R.drawable.msg_saved);
            bookmarkIcon.setColorFilter(grayFilter);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelPendingTruncate();
        roundVideoView.release();
    }

    private TextView smallText(Context ctx, int color) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tv.setTextColor(color);
        return tv;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getAction();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (hasTextSelection() && !isTouchOnMessageText(ev)) {
                    clearTextSelection();
                    return true;
                }

                longPressTriggered = false;
                longPressStarted = false;
                longPressDownX = ev.getX();
                longPressDownY = ev.getY();

                cancelPendingLongPress();
                break;

            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(ev.getX() - longPressDownX);
                float dy = Math.abs(ev.getY() - longPressDownY);

                if (dx > TOUCH_SLOP || dy > TOUCH_SLOP) {
                    cancelPendingLongPress();
                    longPressStarted = false;
                } else if (!longPressStarted && !longPressTriggered
                        && !isTouchOnMessageText(ev)) {
                    longPressStarted = true;
                    longPressRunnable = () -> {
                        longPressTriggered = true;
                        if (callback != null && currentItem != null) {
                            callback.onPostLongPress(FeedPostCell.this);
                        }
                    };
                    AndroidUtilities.runOnUIThread(longPressRunnable, LONG_PRESS_TIMEOUT);
                }
                break;

            case MotionEvent.ACTION_UP:
                cancelPendingLongPress();
                if (longPressTriggered) {
                    longPressTriggered = false;
                    return true;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                cancelPendingLongPress();
                longPressTriggered = false;
                break;
        }

        doubleTapDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    private void cancelPendingLongPress() {
        if (longPressRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(longPressRunnable);
            longPressRunnable = null;
        }
    }

    public void updateBookmarkState(boolean bookmarked) {
        if (bookmarked) {
            bookmarkIcon.setImageResource(R.drawable.msg_saved);
            bookmarkIcon.setColorFilter(new PorterDuffColorFilter(
                    Theme.getColor(Theme.key_featuredStickers_addButton, resourceProvider),
                    PorterDuff.Mode.SRC_IN));
        } else {
            bookmarkIcon.setImageResource(R.drawable.msg_saved);
            bookmarkIcon.setColorFilter(grayFilter);
        }
    }

    private boolean isDimmed = false;

    private void setDimmed(boolean dimmed) {
        if (isDimmed == dimmed) return;
        isDimmed = dimmed;
        invalidate();
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (isDimmed) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), dimPaint);
        }
        super.dispatchDraw(canvas);
    }

    public TextView getMessageTextView() {
        return messageTextView;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (messageTextView != null && messageTextView.getVisibility() == VISIBLE
                && fullText != null && fullText.length() > 0) {
            messageTextView.invalidate();
        }
    }

    private boolean canSummarize(MessageObject msg) {
        if (msg == null || msg.messageOwner == null) return false;
        String text = msg.messageOwner.message;
        if (TextUtils.isEmpty(text) || text.length() <= 100) return false;
        return msg.messageOwner.summary_from_language != null;
    }

    @SuppressLint("SetTextI18n")
    private void bindSummary(FeedController.FeedItem item) {
        summaryLoading = false;
        MessageObject primary = item.getPrimaryMessage();

        if (!canSummarize(primary)) {
            summarizeBtn.setVisibility(GONE);
            summaryCard.setVisibility(GONE);
            return;
        }

        TLRPC.Message raw = primary.messageOwner;

        if (raw.summarizedOpen && raw.summaryText != null) {
            CharSequence display = Emoji.replaceEmoji(raw.summaryText.text,
                    summaryTextView.getPaint().getFontMetricsInt(), false);
            summaryTextView.setText(display);
            summaryCard.setVisibility(VISIBLE);
            summarizeBtn.setText("Hide summary");
            summarizeBtn.setAlpha(1f);
            summarizeBtn.setEnabled(true);
            summarizeBtn.setVisibility(VISIBLE);

        } else if (raw.summaryText != null) {
            summaryCard.setVisibility(GONE);
            summarizeBtn.setText("✨ Show summary");
            summarizeBtn.setAlpha(1f);
            summarizeBtn.setEnabled(true);
            summarizeBtn.setVisibility(VISIBLE);

        } else {
            summaryCard.setVisibility(GONE);
            summarizeBtn.setText("✨ Summarize");
            summarizeBtn.setAlpha(1f);
            summarizeBtn.setEnabled(true);
            summarizeBtn.setVisibility(VISIBLE);
        }
    }

    @SuppressLint("SetTextI18n")
    private void onSummarizeClick() {
        if (currentItem == null) return;
        MessageObject primary = currentItem.getPrimaryMessage();
        if (primary == null || primary.messageOwner == null) return;

        TLRPC.Message raw = primary.messageOwner;

        if (raw.summarizedOpen && raw.summaryText != null) {
            raw.summarizedOpen = false;
            saveSummaryState(primary);
            updateSummaryUI();
            return;
        }

        if (raw.summaryText != null) {
            raw.summarizedOpen = true;
            saveSummaryState(primary);
            updateSummaryUI();
            return;
        }

        if (summaryLoading) return;
        requestSummary(primary);
    }

    private void requestSummary(MessageObject message) {
        summaryLoading = true;
        updateSummaryUI();

        TLRPC.TL_messages_summarizeText req = new TLRPC.TL_messages_summarizeText();
        req.peer = MessagesController.getInstance(currentAccount)
                .getInputPeer(message.getDialogId());
        req.id = message.getId();

        final long dialogId = message.getDialogId();
        final int  msgId    = message.getId();

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            AndroidUtilities.runOnUIThread(() -> {
                summaryLoading = false;

                if (response instanceof TLRPC.TL_textWithEntities) {
                    message.messageOwner.summaryText = (TLRPC.TL_textWithEntities) response;
                    message.messageOwner.summarizedOpen = true;
                    MessagesStorage.getInstance(currentAccount)
                            .updateMessageCustomParams(dialogId, message.messageOwner);
                } else {
                    message.messageOwner.summarizedOpen = false;
                    if (error != null && "SUMMARY_FLOOD_PREMIUM".equalsIgnoreCase(error.text)) {
                        android.widget.Toast.makeText(getContext(),
                                "Summary limit reached. Upgrade to Premium for unlimited summaries.",
                                android.widget.Toast.LENGTH_LONG).show();
                    } else if (error != null) {
                        android.widget.Toast.makeText(getContext(),
                                "Failed to summarize",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                }

                if (currentItem != null) {
                    MessageObject cur = currentItem.getPrimaryMessage();
                    if (cur != null && cur.getId() == msgId
                            && cur.getDialogId() == dialogId) {
                        updateSummaryUI();
                    }
                }
            });
        });
    }

    @SuppressLint("SetTextI18n")
    private void updateSummaryUI() {
        if (currentItem == null) return;
        MessageObject primary = currentItem.getPrimaryMessage();
        if (primary == null || primary.messageOwner == null) return;

        TLRPC.Message raw = primary.messageOwner;

        if (summaryLoading) {
            summarizeBtn.setText("Summarizing…");
            summarizeBtn.setAlpha(0.5f);
            summarizeBtn.setEnabled(false);
            summarizeBtn.setVisibility(VISIBLE);
            summaryCard.setVisibility(GONE);

        } else if (raw.summarizedOpen && raw.summaryText != null) {
            CharSequence display = Emoji.replaceEmoji(raw.summaryText.text,
                    summaryTextView.getPaint().getFontMetricsInt(), false);
            summaryTextView.setText(display);
            summaryCard.setVisibility(VISIBLE);
            summarizeBtn.setText("Hide summary");
            summarizeBtn.setAlpha(1f);
            summarizeBtn.setEnabled(true);
            summarizeBtn.setVisibility(VISIBLE);

        } else if (raw.summaryText != null) {
            summaryCard.setVisibility(GONE);
            summarizeBtn.setText("✨ Show summary");
            summarizeBtn.setAlpha(1f);
            summarizeBtn.setEnabled(true);
            summarizeBtn.setVisibility(VISIBLE);

        } else {
            summaryCard.setVisibility(GONE);
            summarizeBtn.setText("✨ Summarize");
            summarizeBtn.setAlpha(1f);
            summarizeBtn.setEnabled(true);
            summarizeBtn.setVisibility(VISIBLE);
        }

        requestLayout();
    }

    private void saveSummaryState(MessageObject message) {
        if (message == null) return;
        MessagesStorage.getInstance(currentAccount)
                .updateMessageCustomParams(message.getDialogId(), message.messageOwner);
    }


    private void bindTranslation(FeedController.FeedItem item) {
        translationLoading = false;
        MessageObject primary = item.getPrimaryMessage();

        if (primary == null || primary.messageOwner == null) {
            translateBtn.setVisibility(GONE);
            translationCard.setVisibility(GONE);
            return;
        }

        String text = primary.messageOwner.message;
        if (TextUtils.isEmpty(text) || text.length() < 30) {
            translateBtn.setVisibility(GONE);
            translationCard.setVisibility(GONE);
            return;
        }

        TLRPC.Message raw = primary.messageOwner;

        if (raw.translatedText != null && !TextUtils.isEmpty(raw.translatedToLanguage)) {
            if (item.translationShown) {
                showTranslatedUI(raw);
            } else {
                showTranslateButton();
            }
            return;
        }

        if (raw.originalLanguage != null
                && !TranslateController.UNKNOWN_LANGUAGE.equals(raw.originalLanguage)) {
            if (isUserLanguage(raw.originalLanguage)) {
                translateBtn.setVisibility(GONE);
                translationCard.setVisibility(GONE);
            } else {
                showTranslateButton();
            }
            return;
        }

        if (LanguageDetector.hasSupport()) {
            translateBtn.setVisibility(GONE);
            translationCard.setVisibility(GONE);
            detectLanguage(primary, item);
        } else {
            showTranslateButton();
        }
    }

    private void detectLanguage(MessageObject message, FeedController.FeedItem item) {
        if (message == null || message.messageOwner == null
                || TextUtils.isEmpty(message.messageOwner.message)) return;

        final long dialogId = message.getDialogId();
        final int msgId = message.getId();

        LanguageDetector.detectLanguage(
                message.messageOwner.message,
                detectedLang -> AndroidUtilities.runOnUIThread(() -> {
                    if (detectedLang == null) {
                        message.messageOwner.originalLanguage = TranslateController.UNKNOWN_LANGUAGE;
                    } else {
                        message.messageOwner.originalLanguage = detectedLang;
                    }
                    MessagesStorage.getInstance(currentAccount)
                            .updateMessageCustomParams(dialogId, message.messageOwner);

                    if (currentItem == item) {
                        if (!isUserLanguage(detectedLang)) {
                            showTranslateButton();
                        }
                    }
                }),
                err -> AndroidUtilities.runOnUIThread(() -> {
                    message.messageOwner.originalLanguage = TranslateController.UNKNOWN_LANGUAGE;
                    if (currentItem == item) {
                        showTranslateButton();
                    }
                })
        );
    }

    @SuppressLint("SetTextI18n")
    private void showTranslateButton() {
        translateBtn.setText("Translate post");
        translateBtn.setAlpha(1f);
        translateBtn.setEnabled(true);
        translateBtn.setVisibility(VISIBLE);
        translationCard.setVisibility(GONE);
    }

    @SuppressLint("SetTextI18n")
    private void showTranslatedUI(TLRPC.Message raw) {
        String fromLangName = null;
        if (raw.originalLanguage != null
                && !TranslateController.UNKNOWN_LANGUAGE.equals(raw.originalLanguage)) {
            fromLangName = TranslateAlert2.capitalFirst(
                    TranslateAlert2.languageName(raw.originalLanguage));
        }
        if (fromLangName != null) {
            translationHeaderView.setText("Translated from " + fromLangName);
        } else {
            translationHeaderView.setText("Translated");
        }

        CharSequence display;
        if (raw.translatedText.entities != null && !raw.translatedText.entities.isEmpty()) {
            SpannableStringBuilder ssb = SpannableStringBuilder.valueOf(raw.translatedText.text);
            MessageObject.addEntitiesToText(ssb, raw.translatedText.entities,
                    false, true, false, false);
            display = Emoji.replaceEmoji(ssb,
                    translationTextView.getPaint().getFontMetricsInt(), false);
        } else {
            display = Emoji.replaceEmoji(raw.translatedText.text,
                    translationTextView.getPaint().getFontMetricsInt(), false);
        }
        translationTextView.setText(display);
        translationCard.setVisibility(VISIBLE);

        translateBtn.setText("Show original");
        translateBtn.setAlpha(1f);
        translateBtn.setEnabled(true);
        translateBtn.setVisibility(VISIBLE);
    }

    private void onTranslateClick() {
        if (currentItem == null) return;
        MessageObject primary = currentItem.getPrimaryMessage();
        if (primary == null || primary.messageOwner == null) return;

        TLRPC.Message raw = primary.messageOwner;

        if (raw.translatedText != null && currentItem.translationShown) {
            currentItem.translationShown = false;
            showTranslateButton();
            return;
        }

        if (raw.translatedText != null && !TextUtils.isEmpty(raw.translatedToLanguage)) {
            currentItem.translationShown = true;
            showTranslatedUI(raw);
            return;
        }

        if (translationLoading) return;
        requestTranslation(primary);
    }

    @SuppressLint("SetTextI18n")
    private void requestTranslation(MessageObject message) {
        translationLoading = true;
        translateBtn.setText("Translating…");
        translateBtn.setAlpha(0.5f);
        translateBtn.setEnabled(false);

        String toLang = TranslateAlert2.getToLanguage();
        if (toLang != null) toLang = toLang.split("_")[0];
        if ("nb".equals(toLang)) toLang = "no";

        final String targetLang = toLang;
        final long dialogId = message.getDialogId();
        final int msgId = message.getId();
        final FeedController.FeedItem item = currentItem;

        TLRPC.TL_messages_translateText req = new TLRPC.TL_messages_translateText();
        req.flags |= 1;
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        req.id.add(msgId);
        req.to_lang = targetLang;

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (err != null && "TRANSLATIONS_DISABLED_ALT".equalsIgnoreCase(err.text)) {
                        requestTranslationFallback(message, targetLang, item);
                        return;
                    }

                    translationLoading = false;

                    if (res instanceof TLRPC.TL_messages_translateResult) {
                        TLRPC.TL_messages_translateResult result =
                                (TLRPC.TL_messages_translateResult) res;
                        if (!result.result.isEmpty() && result.result.get(0) != null) {
                            TLRPC.TL_textWithEntities source = new TLRPC.TL_textWithEntities();
                            source.text = message.messageOwner.message;
                            source.entities = message.messageOwner.entities != null
                                    ? message.messageOwner.entities : new java.util.ArrayList<>();

                            TLRPC.TL_textWithEntities translated =
                                    TranslateAlert2.preprocess(source, result.result.get(0));

                            applyTranslation(message, translated, targetLang, item);
                            return;
                        }
                    }

                    handleTranslationError(err, item);
                })
        );
    }

    private void requestTranslationFallback(MessageObject message, String targetLang,
                                            FeedController.FeedItem item) {
        String text = message.messageOwner.message;
        String fromLang = message.messageOwner.originalLanguage;

        TranslateAlert2.alternativeTranslate(text, fromLang, targetLang, (result, rateLimit) ->
                AndroidUtilities.runOnUIThread(() -> {
                    translationLoading = false;

                    if (result != null) {
                        TLRPC.TL_textWithEntities translated = new TLRPC.TL_textWithEntities();
                        translated.text = result;
                        translated.entities = new java.util.ArrayList<>();
                        applyTranslation(message, translated, targetLang, item);
                    } else {
                        handleTranslationError(null, item);
                    }
                })
        );
    }

    private void applyTranslation(MessageObject message, TLRPC.TL_textWithEntities translated,
                                  String targetLang, FeedController.FeedItem item) {
        message.messageOwner.translatedText = translated;
        message.messageOwner.translatedToLanguage = targetLang;

        MessagesStorage.getInstance(currentAccount)
                .updateMessageCustomParams(message.getDialogId(), message.messageOwner);

        item.translationShown = true;

        if (currentItem == item) {
            showTranslatedUI(message.messageOwner);
        }
    }

    @SuppressLint("SetTextI18n")
    private void handleTranslationError(TLRPC.TL_error err, FeedController.FeedItem item) {
        if (currentItem == item) {
            showTranslateButton();
        }
        String errorMsg = "Translation failed";
        if (err != null && "TO_LANG_INVALID".equals(err.text)) {
            errorMsg = "Invalid target language";
        } else if (err != null && "QUOTA_EXCEEDED".equals(err.text)) {
            errorMsg = "Translation quota exceeded, try later";
        }
        android.widget.Toast.makeText(getContext(), errorMsg,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private boolean isUserLanguage(String lang) {
        if (lang == null || TranslateController.UNKNOWN_LANGUAGE.equals(lang)) return false;
        String appLang = LocaleController.getInstance().getCurrentLocaleInfo().pluralLangCode;
        if (appLang != null) appLang = appLang.split("_")[0];
        String sysLang = java.util.Locale.getDefault().getLanguage();
        return TextUtils.equals(lang, appLang) || TextUtils.equals(lang, sysLang);
    }

    private boolean isTouchOnMessageText(MotionEvent ev) {
        if (messageTextView == null || messageTextView.getVisibility() != VISIBLE) return false;
        float x = ev.getX();
        float y = ev.getY();
        return x >= messageTextView.getLeft() && x <= messageTextView.getRight()
                && y >= messageTextView.getTop() && y <= messageTextView.getBottom();
    }

    public boolean hasTextSelection() {
        if (messageTextView == null || messageTextView.getVisibility() != VISIBLE) return false;
        return messageTextView.hasSelection();
    }

    public void clearTextSelection() {
        if (messageTextView == null) return;
        messageTextView.clearFocus();
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
}