package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.ui.Feed.FeedMediaHelper.smallestThumb;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.RectF;
import android.text.Spanned;
import android.text.style.ClickableSpan;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.spoilers.SpoilerEffect;

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

    private final LinearLayout replyContainer;
    private final TextView replyNameView;
    private final TextView replyTextView;
    private final BackupImageView replyImageView;

    private final LinearLayout forwardContainer;
    private final TextView forwardNameView;

    private boolean messageHasSpoilers = false;
    private Runnable spoilerRevealer = null;

    private final AnimatedEmojiSpan.TextViewEmojis messageTextView;
    private final TextView readMoreView;

    private final FeedPollView pollView;

    private final LinearLayout documentContainer;
    private final TextView documentNameView;
    private final TextView documentSizeView;

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
    private CharSequence fullText = null;
    private int collapsedEndOffset = -1;
    private final int currentAccount;
    private final Theme.ResourcesProvider resourceProvider;
    private final AvatarDrawable avatarDrawable;

    private long fwdChannelId = 0;
    private int fwdMessageId = 0;
    private long replyChannelId = 0;
    private int replyMessageId = 0;

    private ViewTreeObserver.OnPreDrawListener pendingTruncateListener;

    private final java.util.HashSet<Integer> expandedQuoteOffsets = new java.util.HashSet<>();
    private final FeedTextFormatter textFormatter;

    private final android.graphics.Path spoilerClipPath = new android.graphics.Path();

    public interface Callback {
        void onHeaderClick(FeedController.FeedItem item);
        void onMediaClick(FeedController.FeedItem item, int mediaIndex);
        void onMenuClick(View anchor, FeedController.FeedItem item);
        void onCommentsClick(FeedController.FeedItem item);
        void onShareClick(FeedController.FeedItem item);
        void onForwardClick(long channelId, int messageId);
        void onReplyClick(long channelId, int messageId);
    }

    private Callback callback;
    public void setCallback(Callback callback) { this.callback = callback; }

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
        int greenColor = Theme.getColor(Theme.key_avatar_nameInMessageGreen, resourceProvider);
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

        replyContainer = new LinearLayout(context);
        replyContainer.setOrientation(HORIZONTAL);
        replyContainer.setVisibility(GONE);
        replyContainer.setPadding(0, dp(8), 0, 0);
        replyContainer.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourceProvider), 2));
        replyContainer.setOnClickListener(v -> {
            if (callback != null && replyChannelId != 0 && replyMessageId != 0) {
                callback.onReplyClick(replyChannelId, replyMessageId);
            }
        });

        View replyBorder = new View(context);
        replyBorder.setBackgroundColor(accentColor);
        replyContainer.addView(replyBorder, LayoutHelper.createLinear(3, LayoutHelper.MATCH_PARENT, 0, 0, 0, 0));

        LinearLayout replyContent = new LinearLayout(context);
        replyContent.setOrientation(VERTICAL);
        replyContent.setPadding(dp(8), dp(2), dp(4), dp(2));

        replyNameView = new TextView(context);
        replyNameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        replyNameView.setTypeface(AndroidUtilities.bold());
        replyNameView.setTextColor(accentColor);
        replyNameView.setMaxLines(1);
        replyNameView.setEllipsize(TextUtils.TruncateAt.END);
        replyContent.addView(replyNameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        replyTextView = new TextView(context);
        replyTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        replyTextView.setTextColor(grayColor);
        replyTextView.setMaxLines(2);
        replyTextView.setEllipsize(TextUtils.TruncateAt.END);
        replyContent.addView(replyTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        replyContainer.addView(replyContent, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        replyImageView = new BackupImageView(context);
        replyImageView.setRoundRadius(dp(4));
        replyImageView.setVisibility(GONE);
        replyContainer.addView(replyImageView, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL, 4, 0, 0, 0));

        addView(replyContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        forwardContainer = new LinearLayout(context);
        forwardContainer.setOrientation(HORIZONTAL);
        forwardContainer.setVisibility(GONE);
        forwardContainer.setPadding(0, dp(8), 0, 0);
        forwardContainer.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourceProvider), 2));
        forwardContainer.setOnClickListener(v -> {
            if (callback != null && fwdChannelId != 0) {
                callback.onForwardClick(fwdChannelId, fwdMessageId);
            }
        });

        View forwardBorder = new View(context);
        forwardBorder.setBackgroundColor(greenColor);
        forwardContainer.addView(forwardBorder, LayoutHelper.createLinear(3, LayoutHelper.MATCH_PARENT, 0, 0, 0, 0));

        LinearLayout forwardContent = new LinearLayout(context);
        forwardContent.setOrientation(VERTICAL);
        forwardContent.setPadding(dp(8), dp(2), dp(4), dp(2));

        TextView forwardLabel = new TextView(context);
        forwardLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        forwardLabel.setTextColor(greenColor);
        forwardLabel.setText("Forwarded from");
        forwardContent.addView(forwardLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        forwardNameView = new TextView(context);
        forwardNameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        forwardNameView.setTypeface(AndroidUtilities.bold());
        forwardNameView.setTextColor(greenColor);
        forwardNameView.setMaxLines(1);
        forwardNameView.setEllipsize(TextUtils.TruncateAt.END);
        forwardContent.addView(forwardNameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        forwardContainer.addView(forwardContent, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        addView(forwardContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        messageTextView = new AnimatedEmojiSpan.TextViewEmojis(context) {

            private final Paint extBgPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint extIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint extArrowBg   = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint extArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF extRect      = new RectF();
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
                spoilersRevealed = false;
                invalidateSpoilers();
            }

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                invalidateSpoilers();
            }

            private void invalidateSpoilers() {
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
                                android.graphics.Path.Direction.CW
                        );
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
                int viewW   = getWidth();
                int pl = getCompoundPaddingLeft();
                int pt = getExtendedPaddingTop();

                for (FeedQuoteSpan q : quotes) {
                    int start = sp.getSpanStart(q);
                    int end   = sp.getSpanEnd(q);
                    if (start < 0 || end <= start) continue;

                    int firstLine = layout.getLineForOffset(start);
                    int lastLine  = layout.getLineForOffset(Math.max(start, end - 1));

                    float bgTop    = pt + layout.getLineTop(firstLine) + q.topPad;
                    float bgBottom = pt + layout.getLineBottom(lastLine) - q.bottomPad;
                    int cr = q.cornerRadius;

                    float bgRight;
                    if (q.boxWidth > 0 && q.boxWidth <= layoutW) {
                        bgRight = pl + q.boxWidth;
                    } else {
                        bgRight = Math.min(pl + (q.boxWidth > 0 ? q.boxWidth : layoutW), viewW);
                    }

                    if (pr > 0 && q.boxWidth > layoutW) {
                        float extLeft  = pl + layoutW;
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
                        float lastTop    = pt + layout.getLineTop(lastLine);
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
        };
        messageTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        messageTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        messageTextView.setLinkTextColor(accentColor);
        messageTextView.setLineSpacing(dp(2), 1f);
        messageTextView.setMovementMethod(new LinkMovementMethod() {
            @Override
            public boolean onTouchEvent(TextView widget, android.text.Spannable buffer,
                                        android.view.MotionEvent event) {
                if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                    int x = (int) event.getX() - widget.getTotalPaddingLeft() + widget.getScrollX();
                    int y = (int) event.getY() - widget.getTotalPaddingTop() + widget.getScrollY();
                    Layout l = widget.getLayout();
                    if (l != null) {
                        int line = l.getLineForVertical(y);
                        int off  = l.getOffsetForHorizontal(line, x);
                        ClickableSpan[] spans = buffer.getSpans(off, off, ClickableSpan.class);
                        if (spans.length > 0) {
                            for (ClickableSpan span : spans) {
                                if (!(span instanceof FeedQuoteSpan.Clickable)) {
                                    span.onClick(widget);
                                    return true;
                                }
                            }
                            spans[0].onClick(widget);
                            return true;
                        }
                    }

                    if (messageHasSpoilers && spoilerRevealer != null) {
                        spoilerRevealer.run();
                        messageHasSpoilers = false;
                        return true;
                    }
                }
                return super.onTouchEvent(widget, buffer, event);
            }
        });
        messageTextView.setVisibility(GONE);
        addView(messageTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        readMoreView = new TextView(context);
        readMoreView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        readMoreView.setTypeface(AndroidUtilities.bold());
        readMoreView.setTextColor(accentColor);
        readMoreView.setVisibility(GONE);
        readMoreView.setPadding(0, dp(4), 0, dp(2));
        readMoreView.setOnClickListener(v -> toggleExpanded());
        addView(readMoreView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        pollView = new FeedPollView(context, currentAccount, resourceProvider);
        pollView.setVisibility(GONE);
        addView(pollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        documentContainer = new LinearLayout(context);
        documentContainer.setOrientation(HORIZONTAL);
        documentContainer.setGravity(Gravity.CENTER_VERTICAL);
        documentContainer.setVisibility(GONE);
        documentContainer.setPadding(0, dp(8), 0, dp(4));

        ImageView documentIcon = new ImageView(context);
        documentIcon.setImageResource(R.drawable.msg_round_file_s);
        documentIcon.setColorFilter(new PorterDuffColorFilter(accentColor, PorterDuff.Mode.SRC_IN));
        documentContainer.addView(documentIcon, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));

        LinearLayout docTextCol = new LinearLayout(context);
        docTextCol.setOrientation(VERTICAL);
        docTextCol.setPadding(dp(10), 0, 0, 0);

        documentNameView = new TextView(context);
        documentNameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        documentNameView.setTypeface(AndroidUtilities.bold());
        documentNameView.setTextColor(accentColor);
        documentNameView.setMaxLines(1);
        documentNameView.setEllipsize(TextUtils.TruncateAt.END);
        docTextCol.addView(documentNameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        documentSizeView = new TextView(context);
        documentSizeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        documentSizeView.setTextColor(grayColor);
        docTextCol.addView(documentSizeView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        documentContainer.addView(docTextCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        addView(documentContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

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

        textFormatter = new FeedTextFormatter(resourceProvider, expandedQuoteOffsets);
        textFormatter.setRebuildCallback(this::rebuildMessageText);

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
        cancelPendingTruncate();
        currentItem = item;
        textExpanded = false;
        fullText = null;
        collapsedEndOffset = -1;
        expandedQuoteOffsets.clear();
        fwdChannelId = 0;
        fwdMessageId = 0;
        replyChannelId = 0;
        replyMessageId = 0;
        pollView.setVisibility(GONE);
        readMoreView.setVisibility(GONE);
        replyContainer.setVisibility(GONE);
        forwardContainer.setVisibility(GONE);
        documentContainer.setVisibility(GONE);

        if (item == null) return;

        MessageObject primary = item.getPrimaryMessage();
        TLRPC.Message raw = primary.messageOwner;
        MessagesController controller = MessagesController.getInstance(currentAccount);

        TLRPC.Chat chat = controller.getChat(-item.channelId);
        if (chat != null) {
            channelNameView.setText(chat.title);
            avatarDrawable.setInfo(chat);
            if (chat.photo != null && chat.photo.photo_small != null)
                avatarView.setImage(ImageLocation.getForChat(chat, ImageLocation.TYPE_SMALL), "44_44", avatarDrawable, chat);
            else avatarView.setImageDrawable(avatarDrawable);
        }

        String timeStr = LocaleController.formatDateAudio(raw.date, true);
        if (isReallyEdited(raw)) {
            timeStr += " · edited";
        }
        timeView.setText(timeStr);
        unreadDot.setVisibility(item.isRead ? GONE : VISIBLE);

        setupReply(raw, controller);

        setupForward(raw, controller);

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
                    item,
                    getContext(),
                    mediaContainer,
                    mediaImageView1,
                    mediaOverlayLabel,
                    mediaRow,
                    albumLabel,
                    (feedItem, index) -> {
                        if (callback != null) callback.onMediaClick(feedItem, index);
                    },
                    resourceProvider
            );
        }

        setupDocuments(item);

        CharSequence text = textFormatter.format(item,
                messageTextView.getPaint().getFontMetricsInt());
        if (text != null && text.length() > 0) {
            fullText = text;

            boolean hasQuotes = false;
            if (text instanceof Spanned) {
                FeedQuoteSpan[] qs = ((Spanned) text).getSpans(0, text.length(), FeedQuoteSpan.class);
                hasQuotes = qs != null && qs.length > 0;
            }
            messageTextView.setPadding(0, 0, hasQuotes ? FeedQuoteSpan.ICON_ZONE : 0, 0);

            messageTextView.setMaxLines(Integer.MAX_VALUE);
            messageTextView.setText(fullText);
            messageTextView.setVisibility(VISIBLE);
            scheduleMeasureAndTruncate();
        } else {
            fullText = null;
            messageTextView.setPadding(0, 0, 0, 0);
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

    @SuppressLint("SetTextI18n")
    private void setupReply(TLRPC.Message raw, MessagesController controller) {
        replyImageView.setVisibility(GONE);

        if (raw.reply_to == null || raw.reply_to.reply_to_msg_id == 0) {
            replyContainer.setVisibility(GONE);
            return;
        }

        replyMessageId = raw.reply_to.reply_to_msg_id;
        if (raw.reply_to.reply_to_peer_id != null && raw.reply_to.reply_to_peer_id.channel_id != 0) {
            replyChannelId = raw.reply_to.reply_to_peer_id.channel_id;
        } else if (raw.peer_id != null && raw.peer_id.channel_id != 0) {
            replyChannelId = raw.peer_id.channel_id;
        }

        String replyName = null;

        if (raw.reply_to.reply_to_peer_id != null) {
            replyName = getPeerName(raw.reply_to.reply_to_peer_id, controller);
        }
        try {
            TLRPC.MessageFwdHeader replyFrom = raw.reply_to.reply_from;
            if (replyFrom != null) {
                if (replyName == null && replyFrom.from_id != null)
                    replyName = getPeerName(replyFrom.from_id, controller);
                if (replyName == null && replyFrom.from_name != null)
                    replyName = replyFrom.from_name;
            }
        } catch (Exception e) { /* field might not exist */ }
        if (replyName == null && replyChannelId != 0) {
            TLRPC.Chat c = controller.getChat(replyChannelId);
            if (c != null) replyName = c.title;
        }
        if (replyName == null) replyName = "Message";
        replyNameView.setText(replyName);

        String replyText = null;

        try {
            replyText = raw.reply_to.quote_text;
        } catch (Exception e) { /* field might not exist */ }

        if (replyText == null || replyText.isEmpty()) {
            TLRPC.Message replyMsg = raw.replyMessage;
            if (replyMsg != null) {
                if (replyMsg.message != null && !replyMsg.message.isEmpty()) {
                    replyText = replyMsg.message;
                }
                if (replyMsg.media != null && !(replyMsg.media instanceof TLRPC.TL_messageMediaEmpty)) {
                    setupReplyImage(replyMsg);

                    if (replyText == null || replyText.isEmpty()) {
                        replyText = getMediaTypeLabel(replyMsg.media);
                    }
                }
            }
        }

        if (replyText == null || replyText.isEmpty()) {
            replyText = "...";
            loadReplyMessage(raw);
        }

        boolean isQuote = false;
        try {
            isQuote = raw.reply_to.quote;
        } catch (Exception e) { /* field might not exist */ }

        if (isQuote && raw.reply_to.quote_text != null && !raw.reply_to.quote_text.isEmpty()) {
            replyTextView.setText("💬 " + replyText);
        } else {
            replyTextView.setText(replyText);
        }

        replyContainer.setVisibility(VISIBLE);
    }

    private void setupReplyImage(TLRPC.Message replyMsg) {
        if (replyMsg.media instanceof TLRPC.TL_messageMediaPhoto && replyMsg.media.photo != null) {
            TLRPC.PhotoSize thumb = smallestThumb(replyMsg.media.photo.sizes);
            if (thumb != null) {
                replyImageView.setImage(
                        ImageLocation.getForPhoto(thumb, replyMsg.media.photo),
                        "36_36", (ImageLocation) null, null, 0, replyMsg.media.photo);
                replyImageView.setVisibility(VISIBLE);
            }
        } else if (replyMsg.media instanceof TLRPC.TL_messageMediaDocument && replyMsg.media.document != null) {
            TLRPC.Document doc = replyMsg.media.document;
            if (doc.thumbs != null && !doc.thumbs.isEmpty()) {
                TLRPC.PhotoSize thumb = smallestThumb(doc.thumbs);
                if (thumb != null) {
                    replyImageView.setImage(
                            ImageLocation.getForDocument(thumb, doc),
                            "36_36", null, null, 0, doc);
                    replyImageView.setVisibility(VISIBLE);
                }
            }
        }
    }

    private void loadReplyMessage(TLRPC.Message raw) {
        if (raw.peer_id == null || raw.peer_id.channel_id == 0) return;

        TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(raw.peer_id.channel_id);
        if (chat == null) return;
        req.channel = MessagesController.getInputChannel(chat);
        req.id = new ArrayList<>();
        req.id.add(raw.reply_to.reply_to_msg_id);

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) response;
                if (!msgs.messages.isEmpty()) {
                    TLRPC.Message replyMsg = msgs.messages.get(0);
                    AndroidUtilities.runOnUIThread(() -> {
                        String text = replyMsg.message;
                        if (text == null || text.isEmpty()) {
                            if (replyMsg.media != null) {
                                text = getMediaTypeLabel(replyMsg.media);
                            }
                        }
                        if (text != null && !text.isEmpty()) {
                            replyTextView.setText(text);
                        }
                        if (replyMsg.media != null && !(replyMsg.media instanceof TLRPC.TL_messageMediaEmpty)) {
                            setupReplyImage(replyMsg);
                        }
                    });
                }
            }
        });
    }

    private String getMediaTypeLabel(TLRPC.MessageMedia media) {
        if (media instanceof TLRPC.TL_messageMediaPhoto) return "📷 Photo";
        if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            for (TLRPC.DocumentAttribute attr : media.document.attributes) {
                if (attr instanceof TLRPC.TL_documentAttributeVideo) return "📹 Video";
                if (attr instanceof TLRPC.TL_documentAttributeAnimated) return "GIF";
                if (attr instanceof TLRPC.TL_documentAttributeAudio) {
                    if (attr.voice) return "🎤 Voice message";
                    return "🎵 Audio";
                }
                if (attr instanceof TLRPC.TL_documentAttributeSticker) return "Sticker";
            }
            return "📎 Document";
        }
        if (media instanceof TLRPC.TL_messageMediaPoll) return "📊 Poll";
        if (media instanceof TLRPC.TL_messageMediaGeo) return "📍 Location";
        if (media instanceof TLRPC.TL_messageMediaGeoLive) return "📍 Live location";
        if (media instanceof TLRPC.TL_messageMediaContact) return "👤 Contact";
        return "Attachment";
    }

    private void setupForward(TLRPC.Message raw, MessagesController controller) {
        if (raw.fwd_from == null) {
            forwardContainer.setVisibility(GONE);
            return;
        }

        TLRPC.MessageFwdHeader fwd = raw.fwd_from;
        String fwdName = null;
        fwdChannelId = 0;
        fwdMessageId = 0;

        if (fwd.from_id != null) {
            if (fwd.from_id.channel_id != 0) {
                fwdChannelId = fwd.from_id.channel_id;
                TLRPC.Chat fwdChat = controller.getChat(fwdChannelId);
                if (fwdChat != null) fwdName = fwdChat.title;
            } else if (fwd.from_id.user_id != 0) {
                TLRPC.User user = controller.getUser(fwd.from_id.user_id);
                if (user != null) {
                    fwdName = user.first_name;
                    if (user.last_name != null && !user.last_name.isEmpty())
                        fwdName += " " + user.last_name;
                }
            } else if (fwd.from_id.chat_id != 0) {
                TLRPC.Chat fwdChat = controller.getChat(fwd.from_id.chat_id);
                if (fwdChat != null) fwdName = fwdChat.title;
            }
        }

        if (fwdName == null && fwd.from_name != null && !fwd.from_name.isEmpty()) {
            fwdName = fwd.from_name;
        }

        if (fwd.channel_post != 0) {
            fwdMessageId = fwd.channel_post;
        }

        if (fwdName != null) {
            forwardNameView.setText(fwdName);
            forwardContainer.setVisibility(VISIBLE);
        } else {
            forwardContainer.setVisibility(GONE);
        }
    }

    @SuppressLint("SetTextI18n")
    private void setupDocuments(FeedController.FeedItem item) {
        List<TLRPC.Document> docs = getDocuments(item);

        if (docs.isEmpty()) {
            documentContainer.setVisibility(GONE);
            return;
        }

        TLRPC.Document doc = docs.get(0);
        String fileName = null;
        for (TLRPC.DocumentAttribute attr : doc.attributes) {
            if (attr instanceof TLRPC.TL_documentAttributeFilename) {
                fileName = attr.file_name;
                break;
            }
        }
        if (fileName == null || fileName.isEmpty()) {
            fileName = "Document";
        }

        documentNameView.setText(fileName);
        documentSizeView.setText(formatFileSize(doc.size));

        if (docs.size() > 1) {
            documentSizeView.setText(formatFileSize(doc.size) + " · +" + (docs.size() - 1) + " more");
        }

        documentContainer.setVisibility(VISIBLE);
    }

    @NonNull
    private static List<TLRPC.Document> getDocuments(FeedController.FeedItem item) {
        List<TLRPC.Document> docs = new ArrayList<>();
        for (MessageObject msg : item.messages) {
            TLRPC.MessageMedia media = msg.messageOwner.media;
            if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
                TLRPC.Document doc = media.document;
                boolean isVisualMedia = false;
                for (TLRPC.DocumentAttribute attr : doc.attributes) {
                    if (attr instanceof TLRPC.TL_documentAttributeVideo) isVisualMedia = true;
                    if (attr instanceof TLRPC.TL_documentAttributeAnimated) isVisualMedia = true;
                    if (attr instanceof TLRPC.TL_documentAttributeSticker) isVisualMedia = true;
                }
                if (!isVisualMedia) {
                    docs.add(doc);
                }
            }
        }
        return docs;
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1f KB", size / 1024f);
        if (size < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", size / (1024f * 1024f));
        return String.format(Locale.US, "%.1f GB", size / (1024f * 1024f * 1024f));
    }

    private String getPeerName(TLRPC.Peer peer, MessagesController controller) {
        if (peer == null) return null;
        if (peer.channel_id != 0) {
            TLRPC.Chat chat = controller.getChat(peer.channel_id);
            return chat != null ? chat.title : null;
        } else if (peer.chat_id != 0) {
            TLRPC.Chat chat = controller.getChat(peer.chat_id);
            return chat != null ? chat.title : null;
        } else if (peer.user_id != 0) {
            TLRPC.User user = controller.getUser(peer.user_id);
            if (user == null) return null;
            String name = user.first_name;
            if (user.last_name != null && !user.last_name.isEmpty())
                name += " " + user.last_name;
            return name;
        }
        return null;
    }

    private boolean isReallyEdited(TLRPC.Message msg) {
        if (msg.edit_date == 0) return false;
        if (msg.edit_hide) return false;
        if (msg.fwd_from != null) return false;
        if (msg.media instanceof TLRPC.TL_messageMediaGeoLive) return false;
        return !(msg.media instanceof TLRPC.TL_messageMediaPoll);
    }

    private void scheduleMeasureAndTruncate() {
        cancelPendingTruncate();

        pendingTruncateListener = () -> {
            if (messageTextView.getWidth() <= 0) {
                return true;
            }
            cancelPendingTruncate();
            performMeasureAndTruncate();
            return true;
        };
        messageTextView.getViewTreeObserver().addOnPreDrawListener(pendingTruncateListener);
    }

    private void cancelPendingTruncate() {
        if (pendingTruncateListener != null) {
            try {
                messageTextView.getViewTreeObserver().removeOnPreDrawListener(pendingTruncateListener);
            } catch (Exception ignored) {}
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
            }
            readMoreView.setVisibility(VISIBLE);
            readMoreView.setText(LocaleController.getString("FeedReadMore", R.string.FeedReadMore));
        } else {
            collapsedEndOffset = -1;
            readMoreView.setVisibility(GONE);
        }
    }

    private void setCollapsedText() {
        if (fullText == null || collapsedEndOffset <= 0) return;
        int end = Math.min(collapsedEndOffset, fullText.length());
        SpannableStringBuilder truncated = new SpannableStringBuilder(fullText, 0, end);
        while (truncated.length() > 0 && Character.isWhitespace(truncated.charAt(truncated.length() - 1)))
            truncated.delete(truncated.length() - 1, truncated.length());
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
        scheduleQuoteWidthUpdate();
        requestLayout();
    }

    public FeedController.FeedItem getCurrentItem() {
        return currentItem;
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
            int end   = spanned.getSpanEnd(q);
            if (start < 0 || end <= start) continue;

            int startLine = layout.getLineForOffset(start);
            int endLine   = layout.getLineForOffset(Math.max(start, end - 1));

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



    private void rebuildMessageText() {
        if (currentItem == null) return;

        CharSequence text = textFormatter.format(currentItem,
                messageTextView.getPaint().getFontMetricsInt());
        if (text == null || text.length() == 0) return;

        fullText = text;

        boolean hasQuotes = false;
        if (text instanceof Spanned) {
            FeedQuoteSpan[] qs = ((Spanned) text).getSpans(0, text.length(), FeedQuoteSpan.class);
            hasQuotes = qs != null && qs.length > 0;
        }
        messageTextView.setPadding(0, 0, hasQuotes ? FeedQuoteSpan.ICON_ZONE : 0, 0);

        messageTextView.setMaxLines(Integer.MAX_VALUE);
        messageTextView.setText(fullText);
        messageTextView.setVisibility(VISIBLE);

        collapsedEndOffset = -1;
        readMoreView.setVisibility(GONE);
        scheduleMeasureAndTruncate();
        requestLayout();
    }
}