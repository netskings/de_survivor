package org.telegram.ui.Cells;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocalMessageFilterEngine;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/** A compact local-only replacement row. Hidden rows deliberately measure to zero. */
public class LocalMessageFilterCell extends FrameLayout {

    private final TextView textView;
    private boolean hidden;
    private MessageObject messageObject;

    public LocalMessageFilterCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        setBackgroundColor(Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider));
        setFocusable(true);

        textView = new TextView(context);
        textView.setGravity(Gravity.CENTER);
        textView.setTextSize(14);
        textView.setTextColor(Theme.getColor(Theme.key_chat_messagePanelText, resourcesProvider));
        textView.setSingleLine(true);
        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.CENTER, 16, 0, 16, 0));
    }

    public void setMessage(MessageObject messageObject, LocalMessageFilterEngine.Decision decision,
                           boolean albumRepresentative, Runnable reveal) {
        this.messageObject = messageObject;
        hidden = decision == null || decision.action == LocalMessageFilterEngine.Action.HIDE || !albumRepresentative;
        if (hidden) {
            textView.setText(null);
            setContentDescription(null);
            setOnClickListener(null);
            setClickable(false);
            setFocusable(false);
        } else {
            String title = LocaleController.getString(R.string.LocalMessageFilterCollapsed);
            if (decision.ruleName != null && !decision.ruleName.isEmpty()) {
                title += " · " + decision.ruleName;
            }
            String show = LocaleController.getString(R.string.LocalMessageFilterShow);
            textView.setText(title + "   " + show);
            setContentDescription(title + ". " + show);
            setClickable(true);
            setFocusable(true);
            setOnClickListener(view -> reveal.run());
        }
        requestLayout();
    }

    public MessageObject getMessageObject() {
        return messageObject;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (hidden) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0);
        } else {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(44), MeasureSpec.EXACTLY));
        }
    }
}
