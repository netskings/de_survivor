package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

@SuppressLint("ViewConstructor")
public class FeedSeparatorCell extends LinearLayout {

    private final TextView textView;
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public FeedSeparatorCell(Context context, Theme.ResourcesProvider resourceProvider) {
        super(context);
        setOrientation(VERTICAL);
        setWillNotDraw(false);
        setPadding(dp(32), dp(24), dp(32), dp(16));
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));

        linePaint.setColor(Theme.getColor(Theme.key_divider, resourceProvider));
        linePaint.setStrokeWidth(1);

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        textView.setTextColor(
                Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourceProvider));
        textView.setGravity(Gravity.CENTER);
        textView.setTypeface(AndroidUtilities.bold());
        addView(textView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    public void setText(String text) {
        textView.setText(text);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cy = dp(12);
        int textLeft = textView.getLeft() + dp(4);
        int textRight = textView.getRight() - dp(4);

        if (textLeft > dp(32)) {
            canvas.drawLine(dp(16), cy, textLeft - dp(8), cy, linePaint);
        }
        if (textRight < getWidth() - dp(32)) {
            canvas.drawLine(textRight + dp(8), cy, getWidth() - dp(16), cy, linePaint);
        }
    }
}