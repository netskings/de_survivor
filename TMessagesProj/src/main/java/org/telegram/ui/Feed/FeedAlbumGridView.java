package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.view.ViewGroup;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.Components.BackupImageView;

import java.util.List;

@SuppressLint("ViewConstructor")
public class FeedAlbumGridView extends ViewGroup {

    public interface OnItemClickListener {
        void onItemClick(int index);
    }

    private static final int GAP       = 2;
    private static final int MAX_SHOW  = 6;

    private List<MessageObject> messages;
    private BackupImageView[]   cells;
    private final android.widget.TextView moreOverlay;

    private OnItemClickListener clickListener;

    private int[] cellL, cellT, cellR, cellB;

    public FeedAlbumGridView(Context context) {
        super(context);
        setClipChildren(true);

        moreOverlay = new android.widget.TextView(context);
        moreOverlay.setTextColor(0xFFFFFFFF);
        moreOverlay.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20);
        moreOverlay.setTypeface(AndroidUtilities.bold());
        moreOverlay.setGravity(android.view.Gravity.CENTER);
        moreOverlay.setBackgroundColor(0x99000000);
        moreOverlay.setVisibility(GONE);
        addView(moreOverlay);
    }

    public void setMessages(List<MessageObject> msgs) {
        this.messages = msgs;
        rebuildCells();
        requestLayout();
    }

    public void setOnItemClickListener(OnItemClickListener l) {
        this.clickListener = l;
    }

    @SuppressLint("SetTextI18n")
    private void rebuildCells() {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            android.view.View child = getChildAt(i);
            if (child != moreOverlay) removeViewAt(i);
        }

        if (messages == null || messages.isEmpty()) {
            cells = new BackupImageView[0];
            return;
        }

        int n      = messages.size();
        int show   = Math.min(n, MAX_SHOW);
        cells      = new BackupImageView[show];

        for (int i = 0; i < show; i++) {
            final int idx = i;
            BackupImageView iv = new BackupImageView(getContext());
            iv.setRoundRadius(0);
            iv.setOnClickListener(v -> {
                if (clickListener != null) clickListener.onItemClick(idx);
            });
            FeedMediaHelper.setupMediaThumb(messages.get(i), iv);
            cells[i] = iv;
            addView(iv, getChildCount() - 1);
        }

        if (n > MAX_SHOW) {
            moreOverlay.setText("+" + (n - MAX_SHOW + 1));
            moreOverlay.setVisibility(VISIBLE);
            moreOverlay.setOnClickListener(v -> {
                if (clickListener != null) clickListener.onItemClick(MAX_SHOW - 1);
            });
        } else {
            moreOverlay.setVisibility(GONE);
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int totalWidth = MeasureSpec.getSize(widthSpec);
        int h = computeLayout(totalWidth);
        setMeasuredDimension(totalWidth, h);

        int n = cells == null ? 0 : cells.length;
        for (int i = 0; i < n; i++) {
            int cw = cellR[i] - cellL[i];
            int ch = cellB[i] - cellT[i];
            cells[i].measure(
                    MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
        }

        if (moreOverlay.getVisibility() == VISIBLE && n > 0) {
            int last = n - 1;
            int cw = cellR[last] - cellL[last];
            int ch = cellB[last] - cellT[last];
            moreOverlay.measure(
                    MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int n = cells == null ? 0 : cells.length;
        for (int i = 0; i < n; i++) {
            cells[i].layout(cellL[i], cellT[i], cellR[i], cellB[i]);
        }
        if (moreOverlay.getVisibility() == VISIBLE && n > 0) {
            int last = n - 1;
            moreOverlay.layout(cellL[last], cellT[last], cellR[last], cellB[last]);
        }
    }

    private int computeLayout(int W) {
        int n   = cells == null ? 0 : cells.length;
        int g   = dp(GAP);

        cellL = new int[n];
        cellT = new int[n];
        cellR = new int[n];
        cellB = new int[n];

        if (n == 0) return 0;

        int rowH  = (int)(W * 9f / 16f);
        int halfH = rowH / 2 - g / 2;
        int half  = W / 2 - g / 2;

        switch (n) {
            case 1: {
                set(0, 0, 0, W, rowH);
                return rowH;
            }
            case 2: {
                set(0, 0,        0, half,     rowH);
                set(1, half + g, 0, W,        rowH);
                return rowH;
            }
            case 3: {
                int bigW = W * 2 / 3 - g / 2;
                set(0, 0,         0,       bigW,          rowH);
                set(1, bigW + g,  0,       W,             halfH);
                set(2, bigW + g,  halfH+g, W,             rowH);
                return rowH;
            }
            case 4: {
                set(0, 0,        0,        half,  halfH);
                set(1, half + g, 0,        W,     halfH);
                set(2, 0,        halfH + g, half, rowH);
                set(3, half + g, halfH + g, W,    rowH);
                return rowH;
            }
            case 5: {
                int bigW  = W * 2 / 3 - g / 2;
                int topH  = rowH * 3 / 5;
                int botH  = rowH - topH - g;
                int smTop = topH / 2;

                set(0, 0,         0,            bigW,  topH);
                set(1, bigW + g,  0,            W,     smTop - g/2);
                set(2, bigW + g,  smTop + g/2,  W,     topH);
                set(3, 0,         topH + g,      half,  topH + g + botH);
                set(4, half + g,  topH + g,      W,     topH + g + botH);
                return topH + g + botH;
            }
            default: {
                int col  = (W - 2 * g) / 3;
                int row2 = rowH / 2 - g / 2;

                set(0, 0,             0,       col,       row2);
                set(1, col + g,       0,       2*col+g,   row2);
                set(2, 2*(col+g),     0,       W,         row2);
                set(3, 0,             row2+g,  col,       rowH);
                set(4, col + g,       row2+g,  2*col+g,   rowH);
                set(5, 2*(col+g),     row2+g,  W,         rowH);
                return rowH;
            }
        }
    }

    private void set(int i, int l, int t, int r, int b) {
        cellL[i] = l; cellT[i] = t; cellR[i] = r; cellB[i] = b;
    }

    private final android.graphics.Path clipPath = new android.graphics.Path();
    private final android.graphics.RectF clipRect = new android.graphics.RectF();

    @Override
    public void draw(Canvas canvas) {
        clipRect.set(0, 0, getWidth(), getHeight());
        clipPath.reset();
        clipPath.addRoundRect(clipRect, dp(12), dp(12), Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clipPath);
        super.draw(canvas);
        canvas.restore();
    }
}