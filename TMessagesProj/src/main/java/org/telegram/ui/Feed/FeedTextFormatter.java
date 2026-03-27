package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.URLSpanMono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class FeedTextFormatter {

    public interface RebuildCallback {
        void onRebuildNeeded();
    }

    private final HashSet<Integer> expandedQuoteOffsets;
    private final Theme.ResourcesProvider resourceProvider;
    private RebuildCallback rebuildCallback;

    public FeedTextFormatter(Theme.ResourcesProvider rp,
                             HashSet<Integer> expandedOffsets) {
        this.resourceProvider = rp;
        this.expandedQuoteOffsets = expandedOffsets;
    }

    public void setRebuildCallback(@Nullable RebuildCallback callback) {
        this.rebuildCallback = callback;
    }

    @Nullable
    public CharSequence format(@Nullable FeedController.FeedItem item,
                               Paint.FontMetricsInt fontMetrics) {
        if (item == null) return null;

        ExtractResult result = extractRawText(item);
        if (result == null) return null;

        SpannableStringBuilder ssb = new SpannableStringBuilder(result.text);

        applyAnimatedEmojiSpans(ssb, result.sourceMsg, fontMetrics);
        applyInlineCodeSpans(ssb, result.sourceMsg);
        applyBlockSpans(ssb, result.sourceMsg);

        return Emoji.replaceEmoji(ssb, fontMetrics, false);
    }


    private void applyAnimatedEmojiSpans(SpannableStringBuilder ssb,
                                         @Nullable MessageObject sourceMsg,
                                         Paint.FontMetricsInt fontMetrics) {
        if (sourceMsg == null || sourceMsg.messageOwner == null
                || sourceMsg.messageOwner.entities == null) return;

        AnimatedEmojiSpan[] existing =
                ssb.getSpans(0, ssb.length(), AnimatedEmojiSpan.class);
        if (existing != null && existing.length > 0) return;

        for (TLRPC.MessageEntity entity : sourceMsg.messageOwner.entities) {
            if (entity instanceof TLRPC.TL_messageEntityCustomEmoji) {
                long docId = ((TLRPC.TL_messageEntityCustomEmoji) entity).document_id;
                int start = entity.offset;
                int end = entity.offset + entity.length;
                if (start >= 0 && start < ssb.length()
                        && end > start && end <= ssb.length()) {
                    try {
                        ssb.setSpan(new AnimatedEmojiSpan(docId, fontMetrics),
                                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private void applyInlineCodeSpans(SpannableStringBuilder ssb,
                                      @Nullable MessageObject sourceMsg) {
        if (sourceMsg == null || sourceMsg.messageOwner == null
                || sourceMsg.messageOwner.entities == null) return;

        URLSpanMono[] existing =
                ssb.getSpans(0, ssb.length(), URLSpanMono.class);
        if (existing != null && existing.length > 0) return;

        String fullText = ssb.toString();

        for (TLRPC.MessageEntity entity : sourceMsg.messageOwner.entities) {
            if (entity instanceof TLRPC.TL_messageEntityCode) {
                int start = entity.offset;
                int end = Math.min(entity.offset + entity.length,
                        ssb.length());
                if (start >= 0 && start < end) {
                    ssb.setSpan(
                            new URLSpanMono(fullText, start, end,
                                    (byte) 0),
                            start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
    }


    private void applyBlockSpans(SpannableStringBuilder ssb,
                                 @Nullable MessageObject sourceMsg) {
        if (sourceMsg == null || sourceMsg.messageOwner == null
                || sourceMsg.messageOwner.entities == null) return;

        int accentColor = Theme.getColor(
                Theme.key_windowBackgroundWhiteBlueText2, resourceProvider);
        int quoteBgColor = (accentColor & 0x00FFFFFF) | 0x1A000000;

        int textColor = Theme.getColor(
                Theme.key_windowBackgroundWhiteBlackText, resourceProvider);
        int codeBgColor = ColorUtils.setAlphaComponent(textColor, 0x12);

        List<TLRPC.MessageEntity> blockEntities = new ArrayList<>();
        for (TLRPC.MessageEntity entity : sourceMsg.messageOwner.entities) {
            if (entity instanceof TLRPC.TL_messageEntityBlockquote
                    || entity instanceof TLRPC.TL_messageEntityPre) {
                blockEntities.add(entity);
            }
        }
        if (blockEntities.isEmpty()) return;

        Collections.sort(blockEntities, (a, b) -> b.offset - a.offset);

        for (TLRPC.MessageEntity entity : blockEntities) {
            int start = entity.offset;
            int end = Math.min(entity.offset + entity.length,
                    ssb.length());
            if (start < 0 || start >= end) continue;

            if (entity instanceof TLRPC.TL_messageEntityBlockquote) {
                processQuoteEntity(ssb, entity, start, end,
                        accentColor, quoteBgColor);
            } else if (entity instanceof TLRPC.TL_messageEntityPre) {
                processCodeBlockEntity(ssb, start, end,
                        entity.language, codeBgColor);
            }
        }
    }

    private void processQuoteEntity(SpannableStringBuilder ssb,
                                    TLRPC.MessageEntity entity,
                                    int start, int end,
                                    int accentColor, int quoteBgColor) {
        int quotePadTop    = dp(6);
        int quotePadBottom = dp(6);

        boolean isCollapsible = false;
        try { isCollapsible = entity.collapsed; }
        catch (Throwable ignored) {}

        boolean isExpanded = expandedQuoteOffsets.contains(entity.offset);
        final int key = entity.offset;

        if (isCollapsible) {
            if (!isExpanded) {
                CharSequence quoteContent = ssb.subSequence(start, end);
                int cutoff = findQuoteCutoff(quoteContent, 3, 150);
                if (cutoff < quoteContent.length()) {
                    while (cutoff > 0
                            && Character.isWhitespace(
                            ssb.charAt(start + cutoff - 1)))
                        cutoff--;
                    ssb.delete(start + cutoff, end);
                    ssb.insert(start + cutoff, "…");
                    end = start + cutoff + 1;
                }
                ssb.setSpan(new FeedQuoteSpan.Clickable() {
                    @Override public void onClick(@NonNull View w) {
                        expandedQuoteOffsets.add(key);
                        if (rebuildCallback != null)
                            rebuildCallback.onRebuildNeeded();
                    }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                ssb.setSpan(new FeedQuoteSpan.Clickable() {
                    @Override public void onClick(@NonNull View w) {
                        expandedQuoteOffsets.remove(key);
                        if (rebuildCallback != null)
                            rebuildCallback.onRebuildNeeded();
                    }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        if (end < ssb.length() && ssb.charAt(end) != '\n') {
            ssb.insert(end, "\n");
        }
        if (start > 0 && ssb.charAt(start - 1) != '\n') {
            ssb.insert(start, "\n");
            start++;
            end++;
        }

        LeadingMarginSpan[] existing =
                ssb.getSpans(start, end, LeadingMarginSpan.class);
        for (LeadingMarginSpan span : existing) {
            int ss = ssb.getSpanStart(span);
            int se = ssb.getSpanEnd(span);
            if (ss >= start && se <= end) ssb.removeSpan(span);
        }

        FeedQuoteSpan blockSpan =
                new FeedQuoteSpan(accentColor, quoteBgColor);
        if (isCollapsible) blockSpan.setCollapsible(true, isExpanded);
        blockSpan.setPadding(quotePadTop, quotePadBottom);
        ssb.setSpan(blockSpan, start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new FeedQuoteSpan.LineHeight(quotePadTop, quotePadBottom),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void processCodeBlockEntity(SpannableStringBuilder ssb,
                                        int start, int end,
                                        String language,
                                        int codeBgColor) {
        String codeText = ssb.subSequence(start, end).toString();

        if (end < ssb.length() && ssb.charAt(end) != '\n') {
            ssb.insert(end, "\n");
        }
        if (start > 0 && ssb.charAt(start - 1) != '\n') {
            ssb.insert(start, "\n");
            start++;
            end++;
        }

        LeadingMarginSpan[] existing =
                ssb.getSpans(start, end, LeadingMarginSpan.class);
        for (LeadingMarginSpan span : existing) {
            int ss = ssb.getSpanStart(span);
            int se = ssb.getSpanEnd(span);
            if (ss >= start && se <= end) ssb.removeSpan(span);
        }

        FeedCodeSpan.Block blockSpan =
                new FeedCodeSpan.Block(codeBgColor, language, codeText);
        ssb.setSpan(blockSpan, start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new FeedCodeSpan.BlockLineHeight(
                        FeedCodeSpan.BLOCK_TOP_PAD,
                        FeedCodeSpan.BLOCK_BOTTOM_PAD),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static class ExtractResult {
        final CharSequence text;
        final MessageObject sourceMsg;
        ExtractResult(CharSequence text, MessageObject sourceMsg) {
            this.text = text;
            this.sourceMsg = sourceMsg;
        }
    }

    @Nullable
    private ExtractResult extractRawText(FeedController.FeedItem item) {
        MessageObject primary = item.getPrimaryMessage();
        CharSequence text = null;
        MessageObject sourceMsg = null;

        if (item.isAlbum()) {
            for (MessageObject msg : item.messages) {
                if (msg.caption != null && msg.caption.length() > 0) {
                    text = msg.caption;
                    sourceMsg = msg;
                    break;
                }
            }
            if (text == null || text.length() == 0) {
                for (MessageObject msg : item.messages) {
                    CharSequence mt = msg.messageText;
                    if (mt != null && mt.length() > 0
                            && !isPlaceholderText(mt.toString().trim())) {
                        text = mt;
                        sourceMsg = msg;
                        break;
                    }
                }
            }
        } else {
            if (primary.caption != null && primary.caption.length() > 0) {
                text = primary.caption;
                sourceMsg = primary;
            } else {
                CharSequence mt = primary.messageText;
                if (mt != null && mt.length() > 0
                        && !isPlaceholderText(mt.toString().trim())) {
                    text = mt;
                    sourceMsg = primary;
                }
            }
        }

        if (text == null || text.length() == 0) return null;
        if (isPlaceholderText(text.toString().trim())) return null;
        return new ExtractResult(text, sourceMsg);
    }

    private int findQuoteCutoff(CharSequence text, int maxLines,
                                int maxChars) {
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
                if (lines >= maxLines) return i;
            }
        }
        if (text.length() > maxChars) {
            for (int i = Math.min(maxChars, text.length() - 1);
                 i >= Math.max(0, maxChars - 50); i--) {
                if (text.charAt(i) == ' ' || text.charAt(i) == '\n')
                    return i;
            }
            return Math.min(maxChars, text.length());
        }
        return text.length();
    }

    boolean isPlaceholderText(String text) {
        if (text == null || text.isEmpty()) return true;
        switch (text) {
            case "Photo": case "Video": case "GIF":
            case "Document": case "Sticker": case "Audio":
            case "Voice message": case "Video message":
            case "Contact": case "Location":
            case "Live location": case "Poll": case "Quiz":
                return true;
        }
        try {
            if (text.equals(LocaleController.getString(R.string.AttachPhoto))
                    || text.equals(LocaleController.getString(R.string.AttachVideo))
                    || text.equals(LocaleController.getString(R.string.AttachGif))
                    || text.equals(LocaleController.getString(R.string.AttachDocument))
                    || text.equals(LocaleController.getString(R.string.AttachSticker))
                    || text.equals(LocaleController.getString(R.string.AttachAudio))
                    || text.equals(LocaleController.getString(R.string.AttachRound))
                    || text.equals(LocaleController.getString(R.string.AttachContact))
                    || text.equals(LocaleController.getString(R.string.AttachLocation))
                    || text.equals(LocaleController.getString(R.string.AttachLiveLocation))
                    || text.equals(LocaleController.getString(R.string.Poll)))
                return true;
        } catch (Exception e) { /* ignore */ }
        return false;
    }
}