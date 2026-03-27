package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.MetricAffectingSpan;

import androidx.annotation.NonNull;

public class FeedCodeSpan {

    public static final int BLOCK_TOP_PAD    = dp(24);
    public static final int BLOCK_BOTTOM_PAD = dp(8);
    public static final int BLOCK_PAD_H      = dp(10);
    public static final int BLOCK_CORNER     = dp(6);
    public static final int COPY_ZONE        = dp(30);

    public static final float INLINE_PAD_H   = dp(4);
    public static final float INLINE_CORNER  = dp(4);

    public static class Inline extends MetricAffectingSpan {

        @Override
        public void updateDrawState(@NonNull TextPaint tp) {
            tp.setTypeface(android.graphics.Typeface.MONOSPACE);
            tp.setTextSize(tp.getTextSize() * 0.9f);
        }

        @Override
        public void updateMeasureState(@NonNull TextPaint tp) {
            tp.setTypeface(android.graphics.Typeface.MONOSPACE);
            tp.setTextSize(tp.getTextSize() * 0.9f);
        }
    }

    public static class Block implements LineBackgroundSpan, LeadingMarginSpan {

        final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tmpRect = new RectF();
        private final Path  tmpPath = new Path();

        public final String language;
        public final String codeText;

        public Block(int bgColor, String language, String codeText) {
            bgPaint.setColor(bgColor);
            this.language = (language != null && !language.trim().isEmpty())
                    ? language.trim() : null;
            this.codeText = codeText;
        }

        @Override
        public int getLeadingMargin(boolean first) {
            return BLOCK_PAD_H;
        }

        @Override
        public void drawLeadingMargin(Canvas c, Paint p, int x, int dir,
                                      int top, int baseline, int bottom,
                                      CharSequence text, int start, int end,
                                      boolean first, android.text.Layout layout) {
        }

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
            boolean isLast  = (end   >= spanEnd);

            tmpRect.set(left, top, right, bottom);

            if (!isFirst && !isLast) {
                canvas.drawRect(tmpRect, bgPaint);
                return;
            }

            float tl = isFirst ? BLOCK_CORNER : 0;
            float tr = isFirst ? BLOCK_CORNER : 0;
            float br = isLast  ? BLOCK_CORNER : 0;
            float bl = isLast  ? BLOCK_CORNER : 0;

            tmpPath.reset();
            tmpPath.addRoundRect(tmpRect,
                    new float[]{ tl, tl, tr, tr, br, br, bl, bl },
                    Path.Direction.CW);
            canvas.drawPath(tmpPath, bgPaint);
        }
    }

    public static class BlockLineHeight implements android.text.style.LineHeightSpan {

        private final int topPad;
        private final int bottomPad;

        public BlockLineHeight(int topPad, int bottomPad) {
            this.topPad    = topPad;
            this.bottomPad = bottomPad;
        }

        @Override
        public void chooseHeight(CharSequence text, int start, int end,
                                 int spanstartv, int lineHeight,
                                 Paint.FontMetricsInt fm) {
            Spanned sp    = (Spanned) text;
            int spanStart = sp.getSpanStart(this);
            int spanEnd   = sp.getSpanEnd(this);

            if (start <= spanStart) {
                fm.ascent -= topPad;
                fm.top    -= topPad;
            }
            if (end >= spanEnd) {
                fm.descent += bottomPad;
                fm.bottom  += bottomPad;
            }
        }
    }
}