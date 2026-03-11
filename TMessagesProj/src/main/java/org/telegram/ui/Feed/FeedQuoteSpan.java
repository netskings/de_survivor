package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;

import androidx.annotation.NonNull;

public class FeedQuoteSpan implements LineBackgroundSpan, LeadingMarginSpan {


    final Paint bgPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint stripePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();
    private final android.graphics.Path tmpPath = new android.graphics.Path();

    private final int stripeWidth = dp(3);
    private final int gapWidth    = dp(7);
    final int cornerRadius        = dp(6);

    static final int ICON_ZONE = dp(24);

    boolean collapsible = false;
    boolean expanded    = false;
    float boxWidth      = -1;

    int topPad    = 0;
    int bottomPad = 0;

    FeedQuoteSpan(int stripeColor, int bgColor) {
        stripePaint.setColor(stripeColor);
        bgPaint.setColor(bgColor);
    }

    void setCollapsible(boolean collapsible, boolean expanded) {
        this.collapsible = collapsible;
        this.expanded = expanded;
    }

    void setPadding(int top, int bottom) {
        this.topPad = top;
        this.bottomPad = bottom;
    }

    @Override
    public int getLeadingMargin(boolean first) {
        return stripeWidth + gapWidth;
    }

    @Override
    public void drawLeadingMargin(Canvas c, Paint p, int x, int dir,
                                  int top, int baseline, int bottom,
                                  CharSequence text, int start, int end,
                                  boolean first, Layout layout) { }

    @Override
    public void drawBackground(@NonNull Canvas canvas, @NonNull Paint paint,
                               int left, int right,
                               int top, int baseline, int bottom,
                               @NonNull CharSequence text,
                               int start, int end, int lineNumber) {

        Spanned sp    = (Spanned) text;
        int spanStart = sp.getSpanStart(this);
        int spanEnd   = sp.getSpanEnd(this);

        boolean isFirst = (start <= spanStart);
        boolean isLast  = (end >= spanEnd);

        float t = isFirst ? top + topPad : top;
        float b = isLast  ? bottom - bottomPad : bottom;

        float effectiveRight;
        boolean roundRight;

        if (boxWidth > 0 && left + boxWidth <= right) {
            effectiveRight = left + boxWidth;
            roundRight = true;
        } else {
            effectiveRight = right;
            roundRight = (boxWidth <= 0);
        }

        drawBlock(canvas, left, effectiveRight, t, b,
                isFirst, isLast, roundRight, bgPaint, cornerRadius);

        drawBlock(canvas, left, left + stripeWidth, t, b,
                isFirst, isLast, false, stripePaint, stripeWidth / 2f);
    }

    private void drawBlock(Canvas canvas,
                           float left, float right, float top, float bottom,
                           boolean roundTop, boolean roundBottom, boolean roundRight,
                           Paint paint, float radius) {

        tmpRect.set(left, top, right, bottom);

        boolean anyRound = roundTop || roundBottom;
        if (!anyRound) {
            canvas.drawRect(tmpRect, paint);
            return;
        }

        float tl = roundTop    ? radius : 0;
        float tr = (roundTop && roundRight)    ? radius : 0;
        float br = (roundBottom && roundRight)  ? radius : 0;
        float bl = roundBottom ? radius : 0;

        tmpPath.reset();
        tmpPath.addRoundRect(tmpRect, new float[]{
                tl, tl, tr, tr, br, br, bl, bl
        }, android.graphics.Path.Direction.CW);
        canvas.drawPath(tmpPath, paint);
    }

    public static class LineHeight implements android.text.style.LineHeightSpan {
        final int topPad;
        final int bottomPad;

        public LineHeight(int topPad, int bottomPad) {
            this.topPad = topPad;
            this.bottomPad = bottomPad;
        }

        @Override
        public void chooseHeight(CharSequence text, int start, int end,
                                 int spanstartv, int lineHeight,
                                 Paint.FontMetricsInt fm) {
            Spanned sp = (Spanned) text;
            int spanStart = sp.getSpanStart(this);
            int spanEnd = sp.getSpanEnd(this);

            if (start <= spanStart) {
                fm.ascent -= topPad;
                fm.top    -= topPad;
            }
            if (end >= spanEnd) {
                fm.descent += bottomPad;
                fm.bottom  += bottomPad;
            }
            if (end < spanEnd) {
                int compensation = dp(2);
                fm.descent -= compensation;
                fm.bottom  -= compensation;
            }
        }
    }

    public static abstract class Clickable extends ClickableSpan {
        @Override
        public void updateDrawState(@NonNull TextPaint ds) {
            ds.setUnderlineText(false);
        }
    }
}
