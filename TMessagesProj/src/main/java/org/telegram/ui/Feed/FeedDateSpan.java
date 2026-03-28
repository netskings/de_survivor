package org.telegram.ui.Feed;

import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.view.View;

import androidx.annotation.NonNull;

import org.telegram.messenger.LocaleController;
import org.telegram.tgnet.TLRPC;

public class FeedDateSpan extends ClickableSpan {

    public final TLRPC.TL_messageEntityFormattedDate entity;
    private final int color;

    public FeedDateSpan(TLRPC.TL_messageEntityFormattedDate entity, int color) {
        this.entity = entity;
        this.color = color;
    }

    public int getTimestamp() {
        return entity.date;
    }

    public String getFormattedFull() {
        return LocaleController.formatEntityFormattedDate(entity, true);
    }

    public String getFormattedShort() {
        return LocaleController.formatEntityFormattedDate(entity);
    }

    @Override
    public void onClick(@NonNull View widget) {
        // обрабатывается снаружи через callback
    }

    @Override
    public void updateDrawState(@NonNull TextPaint ds) {
        ds.setColor(color);
        ds.setUnderlineText(false);
    }
}