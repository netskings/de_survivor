package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.LinkPath;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.URLSpanMono;
import org.telegram.ui.Components.spoilers.SpoilerEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

@SuppressLint("ViewConstructor")
class FeedMessageTextView extends AnimatedEmojiSpan.TextViewEmojis {

    private final FeedPostCell cell;

    private final Paint extBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint extIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint extArrowBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint extArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF extRect = new RectF();
    private final android.graphics.Path extPath = new android.graphics.Path();

    private final android.text.TextPaint codeBlockLangPaint =
            new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint codeBlockCopyBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint codeBlockCopyIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint codeBlockCopyFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF codeRect = new RectF();
    private FeedCodeSpan.Block touchedCopyBlock;

    private final List<SpoilerEffect> spoilerEffects = new ArrayList<>();
    private final Stack<SpoilerEffect> spoilerPool = new Stack<>();
    private boolean spoilersRevealed;

    private ClickableSpan touchedSpan;
    private URLSpanMono touchedMonoSpan;
    private Runnable spanLongPressRunnable;
    private boolean spanLongPressed;
    private float spanDownX, spanDownY;

    FeedMessageTextView(Context context, FeedPostCell cell) {
        super(context);
        this.cell = cell;

        extArrowPaint.setStyle(Paint.Style.STROKE);
        extArrowPaint.setStrokeWidth(dp(1.6f));
        extArrowPaint.setStrokeCap(Paint.Cap.ROUND);
        extArrowPaint.setStrokeJoin(Paint.Join.ROUND);
        extIconPaint.setStyle(Paint.Style.FILL);

        codeBlockLangPaint.setTextSize(dp(11));
        codeBlockLangPaint.setTypeface(AndroidUtilities.bold());
        codeBlockCopyIconPaint.setStyle(Paint.Style.STROKE);
        codeBlockCopyIconPaint.setStrokeWidth(dp(1.2f));
        codeBlockCopyIconPaint.setStrokeCap(Paint.Cap.ROUND);
        codeBlockCopyIconPaint.setStrokeJoin(Paint.Join.ROUND);
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
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
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
        cell.messageHasSpoilers = !spoilerEffects.isEmpty();
        cell.spoilerRevealer = this::revealSpoilers;
    }

    public void revealSpoilers() {
        spoilersRevealed = true;
        spoilerEffects.clear();
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        boolean needsClip = getLayoutParams() != null
                && getLayoutParams().height > 0
                && getLayoutParams().height != ViewGroup.LayoutParams.WRAP_CONTENT
                && getLayoutParams().height != ViewGroup.LayoutParams.MATCH_PARENT;

        if (needsClip) {
            canvas.save();
            canvas.clipRect(0, 0, getWidth(), getHeight());
        }

        boolean hasSpoilers = !spoilerEffects.isEmpty() && !spoilersRevealed;

        if (cell.getPressedLink() != null || !cell.linkCollector.isEmpty()) {
            canvas.save();
            canvas.translate(getCompoundPaddingLeft(), getExtendedPaddingTop());
            cell.linkCollector.draw(canvas);
            canvas.restore();
        }

        if (hasSpoilers) {
            canvas.save();
            cell.spoilerClipPath.rewind();
            int pl = getCompoundPaddingLeft();
            int pt = getExtendedPaddingTop();
            for (SpoilerEffect eff : spoilerEffects) {
                android.graphics.Rect b = eff.getBounds();
                cell.spoilerClipPath.addRect(pl + b.left, pt + b.top,
                        pl + b.right, pt + b.bottom,
                        android.graphics.Path.Direction.CW);
            }
            canvas.clipPath(cell.spoilerClipPath,
                    android.graphics.Region.Op.DIFFERENCE);
        }

        super.onDraw(canvas);

        if (hasSpoilers) canvas.restore();

        drawQuoteDecorations(canvas);
        drawCodeBlockDecorations(canvas);
        drawSpoilerEffects(canvas);

        if (needsClip) {
            canvas.restore();
        }
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

        Spanned sp = (Spanned) text;
        FeedQuoteSpan[] quotes = sp.getSpans(0, text.length(), FeedQuoteSpan.class);
        if (quotes == null || quotes.length == 0) return;

        int pr = getPaddingRight();
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

            float bgTop = pt + layout.getLineTop(firstLine) - q.topPad;
            float bgBottom = pt + layout.getLineBottom(lastLine) + q.bottomPad;
            int cr = q.cornerRadius;

            float bgRight = (q.boxWidth > 0 && q.boxWidth <= layoutW)
                    ? pl + q.boxWidth
                    : Math.min(pl + (q.boxWidth > 0 ? q.boxWidth : layoutW), viewW);

            if (pr > 0 && q.boxWidth > layoutW) {
                extBgPaint.setColor(q.bgPaint.getColor());
                extPath.reset();
                extRect.set(pl + layoutW, bgTop, bgRight, bgBottom);
                extPath.addRoundRect(extRect,
                        new float[]{0, 0, cr, cr, cr, cr, 0, 0},
                        android.graphics.Path.Direction.CW);
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
                float btnR = dp(9);
                float btnCX = bgRight - dp(6) - btnR;
                float btnCY = (lastTop + lastBottom) / 2f;

                extArrowBg.setColor(iconColor);
                extArrowBg.setAlpha(38);
                canvas.drawCircle(btnCX, btnCY, btnR, extArrowBg);

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

    private void drawCodeBlockDecorations(Canvas canvas) {
        Layout layout = getLayout();
        if (layout == null) return;
        CharSequence text = getText();
        if (!(text instanceof Spanned)) return;

        Spanned sp = (Spanned) text;
        FeedCodeSpan.Block[] blocks = sp.getSpans(0, text.length(), FeedCodeSpan.Block.class);
        if (blocks == null || blocks.length == 0) return;

        int pl = getCompoundPaddingLeft();
        int pt = getExtendedPaddingTop();
        int viewW = getWidth();
        int pr = getPaddingRight();

        int textColor = getCurrentTextColor();
        int mutedColor = ColorUtils.setAlphaComponent(textColor, 0x80);
        codeBlockLangPaint.setColor(mutedColor);
        codeBlockCopyIconPaint.setColor(mutedColor);
        codeBlockCopyBgPaint.setColor(ColorUtils.setAlphaComponent(textColor, 0x12));

        for (FeedCodeSpan.Block block : blocks) {
            int start = sp.getSpanStart(block);
            int end = sp.getSpanEnd(block);
            if (start < 0 || end <= start) continue;

            int firstLine = layout.getLineForOffset(start);
            int lastLine = layout.getLineForOffset(Math.max(start, end - 1));
            float blockTop = pt + layout.getLineTop(firstLine);
            float blockBottom = pt + layout.getLineBottom(lastLine);

            if (pr > 0) {
                codeBlockCopyFillPaint.setColor(block.bgPaint.getColor());
                codeBlockCopyFillPaint.setStyle(Paint.Style.FILL);
                float cr = FeedCodeSpan.BLOCK_CORNER;
                extPath.reset();
                extRect.set(pl + layout.getWidth(), blockTop, viewW, blockBottom);
                extPath.addRoundRect(extRect,
                        new float[]{0, 0, cr, cr, cr, cr, 0, 0},
                        android.graphics.Path.Direction.CW);
                canvas.drawPath(extPath, codeBlockCopyFillPaint);
            }

            float bgRight = Math.min(pl + layout.getWidth() + pr, viewW);

            float btnR = dp(12);
            float btnCX = bgRight - dp(6) - btnR;
            float btnCY = blockTop + dp(13);
            canvas.drawCircle(btnCX, btnCY, btnR, codeBlockCopyBgPaint);

            float iconS = dp(4.5f), off = dp(1.5f);
            codeRect.set(btnCX - iconS, btnCY - iconS,
                    btnCX + iconS - off, btnCY + iconS - off);
            canvas.drawRoundRect(codeRect, dp(1.5f), dp(1.5f), codeBlockCopyIconPaint);

            codeBlockCopyFillPaint.setColor(block.bgPaint.getColor());
            codeBlockCopyFillPaint.setStyle(Paint.Style.FILL);
            codeRect.set(btnCX - iconS + off, btnCY - iconS + off,
                    btnCX + iconS, btnCY + iconS);
            canvas.drawRoundRect(codeRect, dp(1.5f), dp(1.5f), codeBlockCopyFillPaint);
            canvas.drawRoundRect(codeRect, dp(1.5f), dp(1.5f), codeBlockCopyIconPaint);

            if (block.language != null) {
                canvas.drawText(block.language,
                        pl + FeedCodeSpan.BLOCK_PAD_H, blockTop + dp(16),
                        codeBlockLangPaint);
            }
        }
    }

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

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    return handleActionDown(event, spanned, off, inText);
                case MotionEvent.ACTION_MOVE:
                    return handleActionMove(event);
                case MotionEvent.ACTION_UP:
                    return handleActionUp(event);
                case MotionEvent.ACTION_CANCEL:
                    cancelSpanTouch();
                    return super.onTouchEvent(event);
            }
        }
        return super.onTouchEvent(event);
    }

    private boolean handleActionDown(MotionEvent event, Spanned spanned,
                                     int off, boolean inText) {
        spanLongPressed = false;
        spanDownX = event.getX();
        spanDownY = event.getY();

        if (cell.messageHasSpoilers && cell.spoilerRevealer != null) {
            touchedSpan = null;
            return true;
        }

        FeedCodeSpan.Block copyBlock = findCopyBlock(event.getX(), event.getY());
        if (copyBlock != null) {
            touchedCopyBlock = copyBlock;
            return true;
        }

        if (inText) {
            URLSpanMono[] monoSpans = spanned.getSpans(off, off, URLSpanMono.class);
            if (monoSpans != null && monoSpans.length > 0) {
                touchedMonoSpan = monoSpans[0];
                touchedSpan = null;
                highlightSpan(touchedMonoSpan, spanned, event);
                return true;
            }

            ClickableSpan[] spans = spanned.getSpans(off, off, ClickableSpan.class);
            for (ClickableSpan span : spans) {
                if (!(span instanceof FeedQuoteSpan.Clickable)) {
                    touchedSpan = span;
                    highlightClickableSpan(touchedSpan, spanned, event);
                    cell.setDimmed(true);
                    scheduleSpanLongPress();
                    return true;
                }
            }
        }

        FeedQuoteSpan.Clickable quoteClickable =
                findQuoteClickable(event.getX(), event.getY(), spanned);
        if (quoteClickable != null) {
            touchedSpan = quoteClickable;
            return true;
        }

        touchedSpan = null;
        return super.onTouchEvent(event);
    }

    private boolean handleActionMove(MotionEvent event) {
        if (touchedCopyBlock != null) {
            if (movedBeyondSlop(event)) touchedCopyBlock = null;
            return true;
        }
        if (touchedMonoSpan != null) {
            if (movedBeyondSlop(event)) cancelMonoTouch();
            return true;
        }
        if (touchedSpan != null) {
            if (movedBeyondSlop(event)) cancelSpanTouch();
            return true;
        }
        return super.onTouchEvent(event);
    }

    private boolean handleActionUp(MotionEvent event) {
        if (spanLongPressRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(spanLongPressRunnable);
            spanLongPressRunnable = null;
        }
        cell.setDimmed(false);

        if (cell.messageHasSpoilers && cell.spoilerRevealer != null) {
            cell.linkCollector.clear();
            cell.setPressedLink(null);
            cell.spoilerRevealer.run();
            cell.messageHasSpoilers = false;
            touchedSpan = null;
            return true;
        }

        if (touchedCopyBlock != null) {
            copyCodeToClipboard(touchedCopyBlock);
            touchedCopyBlock = null;
            return true;
        }

        if (touchedMonoSpan != null) {
            touchedMonoSpan.copyToClipboard();
            cell.linkCollector.clear();
            cell.setPressedLink(null);
            touchedMonoSpan = null;
            Toast.makeText(getContext(), "Copied", Toast.LENGTH_SHORT).show();
            return true;
        }

        cell.linkCollector.clear();
        cell.setPressedLink(null);

        if (spanLongPressed) {
            spanLongPressed = false;
            touchedSpan = null;
            return true;
        }

        if (touchedSpan != null) {
            ClickableSpan span = touchedSpan;
            touchedSpan = null;

            if (span instanceof FeedQuoteSpan.Clickable) { span.onClick(this); return true; }
            if (span instanceof FeedDateSpan) {
                if (cell.callback != null)
                    cell.callback.onDateEntityClick(((FeedDateSpan) span).entity, cell);
                return true;
            }
            if (span instanceof URLSpan) {
                String url = ((URLSpan) span).getURL();
                if (cell.callback != null) {
                    cell.callback.onLinkClick(url);
                } else {
                    Browser.openUrl(getContext(), url);
                }
                return true;
            }

            if (span instanceof FeedQuoteSpan.Clickable) {
                span.onClick(this);
                return true;
            }

            if (span instanceof FeedDateSpan) {
                if (cell.callback != null) {
                    cell.callback.onDateEntityClick(((FeedDateSpan) span).entity, cell);
                }
                return true;
            }

            span.onClick(this);
            return true;
        }

        return super.onTouchEvent(event);
    }

    private void highlightSpan(URLSpanMono mono, Spanned spanned, MotionEvent event) {
        Layout layout = getLayout();
        LinkSpanDrawable<URLSpanMono> link = new LinkSpanDrawable<>(
                mono, cell.resourceProvider, event.getX(), event.getY(), false);
        cell.setPressedLink((LinkSpanDrawable) link);
        cell.linkCollector.addLink(link);

        int start = spanned.getSpanStart(mono);
        int end = spanned.getSpanEnd(mono);
        LinkPath path = link.obtainNewPath();
        path.setCurrentLayout(layout, start, getPaddingTop());
        layout.getSelectionPath(start, end, path);
        invalidate();
    }

    private void highlightClickableSpan(ClickableSpan span, Spanned spanned,
                                        MotionEvent event) {
        Layout layout = getLayout();
        LinkSpanDrawable<ClickableSpan> link = new LinkSpanDrawable<>(
                span, cell.resourceProvider, event.getX(), event.getY());
        cell.setPressedLink(link);
        cell.linkCollector.addLink(link);

        int start = spanned.getSpanStart(span);
        int end = spanned.getSpanEnd(span);
        LinkPath path = link.obtainNewPath();
        path.setCurrentLayout(layout, start, getPaddingTop());
        layout.getSelectionPath(start, end, path);
    }

    private void scheduleSpanLongPress() {
        if (spanLongPressRunnable != null)
            AndroidUtilities.cancelRunOnUIThread(spanLongPressRunnable);

        spanLongPressRunnable = () -> {
            spanLongPressed = true;
            cell.linkCollector.clear();
            cell.setPressedLink(null);
            cell.setDimmed(false);

            if (touchedSpan instanceof FeedDateSpan) {
                FeedDateSpan ds = (FeedDateSpan) touchedSpan;
                ClipboardManager cm = (ClipboardManager) getContext()
                        .getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(
                        ClipData.newPlainText("date", ds.getFormattedFull()));
                Toast.makeText(getContext(),
                        "Copied: " + ds.getFormattedFull(), Toast.LENGTH_SHORT).show();
            } else if (touchedSpan instanceof URLSpan) {
                String url = ((URLSpan) touchedSpan).getURL();
                if (cell.callback != null)
                    cell.callback.onLinkLongPress(url, cell, touchedSpan);
            }
            touchedSpan = null;
        };
        AndroidUtilities.runOnUIThread(spanLongPressRunnable,
                ViewConfiguration.getLongPressTimeout());
    }

    private void cancelSpanTouch() {
        if (spanLongPressRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(spanLongPressRunnable);
            spanLongPressRunnable = null;
        }
        cell.setDimmed(false);
        cell.linkCollector.clear();
        cell.setPressedLink(null);
        touchedSpan = null;
        spanLongPressed = false;
        touchedCopyBlock = null;
        touchedMonoSpan = null;
    }

    private void cancelMonoTouch() {
        cell.linkCollector.clear();
        cell.setPressedLink(null);
        touchedMonoSpan = null;
    }

    private boolean movedBeyondSlop(MotionEvent event) {
        return Math.abs(event.getX() - spanDownX) > dp(8)
                || Math.abs(event.getY() - spanDownY) > dp(8);
    }

    private FeedCodeSpan.Block findCopyBlock(float viewX, float viewY) {
        Layout layout = getLayout();
        if (layout == null) return null;
        CharSequence text = getText();
        if (!(text instanceof Spanned)) return null;

        Spanned sp = (Spanned) text;
        FeedCodeSpan.Block[] blocks = sp.getSpans(0, text.length(), FeedCodeSpan.Block.class);
        if (blocks == null) return null;

        int pl = getCompoundPaddingLeft();
        int pt = getExtendedPaddingTop();
        int pr = getPaddingRight();
        int viewW = getWidth();

        for (FeedCodeSpan.Block block : blocks) {
            int start = sp.getSpanStart(block);
            int end = sp.getSpanEnd(block);
            if (start < 0 || end <= start) continue;

            int firstLine = layout.getLineForOffset(start);
            float blockTop = pt + layout.getLineTop(firstLine);
            float bgRight = Math.min(pl + layout.getWidth() + pr, viewW);
            float btnR = dp(12);
            float btnCX = bgRight - dp(6) - btnR;
            float btnCY = blockTop + dp(13);
            float dist = (float) Math.sqrt(
                    (viewX - btnCX) * (viewX - btnCX) + (viewY - btnCY) * (viewY - btnCY));
            if (dist <= btnR + dp(4)) return block;
        }
        return null;
    }

    private void copyCodeToClipboard(FeedCodeSpan.Block block) {
        if (block.codeText == null || block.codeText.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("code", block.codeText));
        Toast.makeText(getContext(), "Copied", Toast.LENGTH_SHORT).show();
    }

    private FeedQuoteSpan.Clickable findQuoteClickable(float viewX, float viewY,
                                                       Spanned spanned) {
        Layout layout = getLayout();
        if (layout == null) return null;

        int pt = getExtendedPaddingTop();

        FeedQuoteSpan[] quotes = spanned.getSpans(0, spanned.length(), FeedQuoteSpan.class);
        if (quotes == null) return null;

        for (FeedQuoteSpan q : quotes) {
            if (!q.collapsible) continue;

            int start = spanned.getSpanStart(q);
            int end = spanned.getSpanEnd(q);
            if (start < 0 || end <= start) continue;

            int firstLine = layout.getLineForOffset(start);
            int lastLine = layout.getLineForOffset(Math.max(start, end - 1));

            float quoteTop = pt + layout.getLineTop(firstLine) - q.topPad;
            float quoteBottom = pt + layout.getLineBottom(lastLine) + q.bottomPad;

            if (viewY >= quoteTop && viewY <= quoteBottom) {
                FeedQuoteSpan.Clickable[] clickables = spanned.getSpans(
                        start, end, FeedQuoteSpan.Clickable.class);
                if (clickables != null && clickables.length > 0) {
                    return clickables[0];
                }
            }
        }
        return null;
    }

    boolean hasInteractiveElementAt(float viewX, float viewY) {
        if (getVisibility() != VISIBLE) return false;

        Layout layout = getLayout();
        if (layout == null || !(getText() instanceof Spanned)) return false;

        Spanned spanned = (Spanned) getText();

        if (isTouchOnSpoiler(viewX, viewY)) {
            return true;
        }

        if (findCopyBlock(viewX, viewY) != null) {
            return true;
        }

        if (findQuoteClickable(viewX, viewY, spanned) != null) {
            return true;
        }

        int x = (int) viewX - getTotalPaddingLeft() + getScrollX();
        int y = (int) viewY - getTotalPaddingTop() + getScrollY();

        if (y < 0 || y > layout.getHeight()) return false;

        int line = layout.getLineForVertical(y);
        float lineLeft = layout.getLineLeft(line);
        float lineRight = layout.getLineRight(line);
        if (x < lineLeft || x > lineRight) return false;

        int off = layout.getOffsetForHorizontal(line, x);

        URLSpanMono[] monoSpans = spanned.getSpans(off, off, URLSpanMono.class);
        if (monoSpans != null && monoSpans.length > 0) {
            return true;
        }

        ClickableSpan[] spans = spanned.getSpans(off, off, ClickableSpan.class);
        if (spans != null) {
            for (ClickableSpan span : spans) {
                if (!(span instanceof FeedQuoteSpan.Clickable)) {
                    return true;
                }
            }
        }

        return false;
    }

    boolean hasPlainTextAt(float viewX, float viewY) {
        if (getVisibility() != VISIBLE) return false;
        if (hasInteractiveElementAt(viewX, viewY)) return false;

        Layout layout = getLayout();
        if (layout == null) return false;

        int x = (int) viewX - getTotalPaddingLeft() + getScrollX();
        int y = (int) viewY - getTotalPaddingTop() + getScrollY();

        if (y < 0 || y > layout.getHeight()) return false;

        int line = layout.getLineForVertical(y);
        float lineLeft = layout.getLineLeft(line);
        float lineRight = layout.getLineRight(line);

        return x >= lineLeft && x <= lineRight;
    }

    private boolean isTouchOnSpoiler(float viewX, float viewY) {
        if (spoilerEffects.isEmpty() || spoilersRevealed) return false;

        float x = viewX - getCompoundPaddingLeft();
        float y = viewY - getExtendedPaddingTop();

        for (SpoilerEffect eff : spoilerEffects) {
            android.graphics.Rect b = eff.getBounds();
            if (x >= b.left && x <= b.right && y >= b.top && y <= b.bottom) {
                return true;
            }
        }
        return false;
    }
}