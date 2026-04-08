package org.telegram.ui.Feed;

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

    public static class FeedURLSpan extends android.text.style.URLSpan {
        public FeedURLSpan(String url) {
            super(url);
        }

        @Override
        public void onClick(@NonNull View widget) {
        }
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

        SpannableStringBuilder ssb;
        if (result.text instanceof SpannableStringBuilder) {
            ssb = new SpannableStringBuilder(result.text);
        } else {
            ssb = new SpannableStringBuilder(result.text);
        }

        List<int[]> dateReplacements = applyFormattedDateSpans(ssb, result.sourceMsg);

        applyAnimatedEmojiSpans(ssb, result.sourceMsg, fontMetrics, dateReplacements);
        applyInlineCodeSpans(ssb, result.sourceMsg, dateReplacements);
        applyBlockSpans(ssb, result.sourceMsg, dateReplacements);

        return Emoji.replaceEmoji(ssb, fontMetrics, false);
    }

    private List<int[]> applyFormattedDateSpans(SpannableStringBuilder ssb,
                                                @Nullable MessageObject sourceMsg) {
        List<int[]> replacements = new ArrayList<>();
        if (sourceMsg == null || sourceMsg.messageOwner == null
                || sourceMsg.messageOwner.entities == null) return replacements;

        int accentColor = Theme.getColor(
                Theme.key_windowBackgroundWhiteBlueText2, resourceProvider);

        List<TLRPC.TL_messageEntityFormattedDate> dateEntities = new ArrayList<>();
        for (TLRPC.MessageEntity entity : sourceMsg.messageOwner.entities) {
            if (entity instanceof TLRPC.TL_messageEntityFormattedDate) {
                dateEntities.add((TLRPC.TL_messageEntityFormattedDate) entity);
            }
        }
        if (dateEntities.isEmpty()) return replacements;

        Collections.sort(dateEntities, (a, b) -> b.offset - a.offset);

        for (TLRPC.TL_messageEntityFormattedDate dateEntity : dateEntities) {
            int start = dateEntity.offset;
            int end = dateEntity.offset + dateEntity.length;
            if (start < 0 || start >= ssb.length()) continue;
            end = Math.min(end, ssb.length());
            if (start >= end) continue;

            String formatted = LocaleController.formatEntityFormattedDate(dateEntity);
            if (formatted == null || formatted.isEmpty()) continue;

            replacements.add(new int[]{start, end, formatted.length()});

            ssb.replace(start, end, formatted);
            int newEnd = start + formatted.length();

            ssb.setSpan(
                    new FeedDateSpan(dateEntity, accentColor),
                    start, newEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return replacements;
    }

    private static int mapOffset(int original, List<int[]> replacements) {
        if (replacements == null || replacements.isEmpty()) return original;
        int delta = 0;
        for (int[] r : replacements) {
            int origLen = r[1] - r[0];
            if (original >= r[1]) {
                delta += r[2] - origLen;
            }
        }
        return original + delta;
    }

    private void applyAnimatedEmojiSpans(SpannableStringBuilder ssb,
                                         @Nullable MessageObject sourceMsg,
                                         Paint.FontMetricsInt fontMetrics,
                                         List<int[]> dateReplacements) {
        if (sourceMsg == null || sourceMsg.messageOwner == null
                || sourceMsg.messageOwner.entities == null) return;

        AnimatedEmojiSpan[] existing =
                ssb.getSpans(0, ssb.length(), AnimatedEmojiSpan.class);
        if (existing != null && existing.length > 0) return;

        for (TLRPC.MessageEntity entity : sourceMsg.messageOwner.entities) {
            if (entity instanceof TLRPC.TL_messageEntityCustomEmoji) {
                long docId = ((TLRPC.TL_messageEntityCustomEmoji) entity).document_id;
                int start = mapOffset(entity.offset, dateReplacements);           // ← CHANGED
                int end = mapOffset(entity.offset + entity.length, dateReplacements); // ← CHANGED
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
                                      @Nullable MessageObject sourceMsg,
                                      List<int[]> dateReplacements) {
        if (sourceMsg == null || sourceMsg.messageOwner == null
                || sourceMsg.messageOwner.entities == null) return;

        URLSpanMono[] existing =
                ssb.getSpans(0, ssb.length(), URLSpanMono.class);
        if (existing != null && existing.length > 0) return;

        String fullText = ssb.toString();

        for (TLRPC.MessageEntity entity : sourceMsg.messageOwner.entities) {
            if (entity instanceof TLRPC.TL_messageEntityCode) {
                int start = mapOffset(entity.offset, dateReplacements);           // ← CHANGED
                int end = Math.min(
                        mapOffset(entity.offset + entity.length, dateReplacements), // ← CHANGED
                        ssb.length());
                if (start >= 0 && start < end) {
                    ssb.setSpan(
                            new URLSpanMono(fullText, start, end, (byte) 0),
                            start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
    }

    private void applyBlockSpans(SpannableStringBuilder ssb,
                                 @Nullable MessageObject sourceMsg,
                                 List<int[]> dateReplacements) {
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
            int start = mapOffset(entity.offset, dateReplacements);               // ← CHANGED
            int end = Math.min(
                    mapOffset(entity.offset + entity.length, dateReplacements),   // ← CHANGED
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
        ssb.setSpan(blockSpan, start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
        if (primary == null) return null;

        boolean isGrouped = false;
        for (MessageObject msg : item.messages) {
            if (msg.messageOwner.grouped_id != 0) {
                isGrouped = true;
                break;
            }
        }

        if (isGrouped) {
            for (MessageObject msg : item.messages) {
                CharSequence text = pickBestText(msg);
                if (text != null && text.length() > 0
                        && !isPlaceholderText(text.toString().trim())) {
                    return new ExtractResult(text, msg);
                }
            }
            for (MessageObject msg : item.messages) {
                CharSequence mt = msg.messageText;
                if (mt != null && mt.length() > 0
                        && !isPlaceholderText(mt.toString().trim())) {
                    return new ExtractResult(mt, msg);
                }
            }
            return null;
        } else {
            TLRPC.Document primaryDoc = primary.messageOwner != null
                    && primary.messageOwner.media != null
                    ? primary.messageOwner.media.document : null;
            if (primaryDoc != null && FeedUtils.isSticker(primaryDoc)) {
                return null;
            }

            CharSequence text = pickBestText(primary);
            if (text == null || text.length() == 0
                    || isPlaceholderText(text.toString().trim())) {
                return null;
            }
            return new ExtractResult(text, primary);
        }
    }

    @Nullable
    private CharSequence pickBestText(MessageObject msg) {
        if (msg == null || msg.messageOwner == null) return null;

        CharSequence mt = msg.messageText;
        boolean hasMt = mt != null
                && mt.length() > 0
                && !isPlaceholderText(mt.toString().trim());

        CharSequence cap = msg.caption;
        boolean hasCap = cap != null
                && cap.length() > 0
                && !isPlaceholderText(cap.toString().trim());

        if (hasCap && (!hasMt || cap.length() >= mt.length())) {
            return buildSpannableFromCaption(msg);
        }

        if (hasMt) {
            return mt;
        }

        if (hasCap) {
            return buildSpannableFromCaption(msg);
        }

        return null;
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
            case "Album":
            case "Photo album":
            case "Video album":
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
                    || text.equals(LocaleController.getString(R.string.Poll))
                    || text.equals(LocaleController.getString(R.string.AttachSticker)))
                return true;
        } catch (Exception e) { /* ignore */ }
        return false;
    }

    private CharSequence buildSpannableFromCaption(MessageObject msg) {
        if (msg == null || msg.messageOwner == null) return null;

        CharSequence cap = msg.caption;
        if (cap == null || cap.length() == 0) return null;

        if (cap instanceof android.text.Spannable) {
            android.text.Spannable sp = (android.text.Spannable) cap;
            Object[] spans = sp.getSpans(0, sp.length(), Object.class);
            if (spans != null && spans.length > 0) {
                return cap;
            }
        }

        if (msg.messageOwner.entities == null || msg.messageOwner.entities.isEmpty()) {
            return cap;
        }

        SpannableStringBuilder ssb = new SpannableStringBuilder(cap);
        int accentColor = Theme.getColor(
                Theme.key_windowBackgroundWhiteBlueText2, resourceProvider);

        for (TLRPC.MessageEntity entity : msg.messageOwner.entities) {
            int start = entity.offset;
            int end = entity.offset + entity.length;

            if (start < 0 || start >= ssb.length()) continue;
            end = Math.min(end, ssb.length());
            if (start >= end) continue;

            try {
                applyEntitySpan(ssb, entity, start, end);
            } catch (Exception ignored) {}
        }

        return ssb;
    }

    private void applyEntitySpan(SpannableStringBuilder ssb,
                                 TLRPC.MessageEntity entity,
                                 int start, int end) {
        if (entity instanceof TLRPC.TL_messageEntityBold) {
            ssb.setSpan(
                    new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        } else if (entity instanceof TLRPC.TL_messageEntityItalic) {
            ssb.setSpan(
                    new android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        } else if (entity instanceof TLRPC.TL_messageEntityUnderline) {
            ssb.setSpan(
                    new android.text.style.UnderlineSpan(),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        } else if (entity instanceof TLRPC.TL_messageEntityStrike) {
            ssb.setSpan(
                    new android.text.style.StrikethroughSpan(),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        } else if (entity instanceof TLRPC.TL_messageEntityUrl) {
            String url = ssb.subSequence(start, end).toString();
            if (!url.contains("://")) {
                url = "https://" + url;
            }
            ssb.setSpan(new FeedURLSpan(url),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        } else if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
            String url = entity.url;
            if (url != null && !url.isEmpty()) {
                ssb.setSpan(new FeedURLSpan(url),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

        } else if (entity instanceof TLRPC.TL_messageEntityMention) {
            String username = ssb.subSequence(start, end).toString();
            ssb.setSpan(new FeedURLSpan("tg://resolve?domain=" + username.replace("@", "")),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        } else if (entity instanceof TLRPC.TL_messageEntityMentionName) {
            long userId = ((TLRPC.TL_messageEntityMentionName) entity).user_id;
            ssb.setSpan(new FeedURLSpan("tg://user?id=" + userId),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        } else if (entity instanceof TLRPC.TL_messageEntityHashtag) {
            String tag = ssb.subSequence(start, end).toString();
            ssb.setSpan(new FeedURLSpan("tg://search?query=" + tag),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        } else if (entity instanceof TLRPC.TL_messageEntityCashtag) {
            String tag = ssb.subSequence(start, end).toString();
            ssb.setSpan(new FeedURLSpan("tg://search?query=" + tag),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        } else if (entity instanceof TLRPC.TL_messageEntityBotCommand) {
            String cmd = ssb.subSequence(start, end).toString();
            ssb.setSpan(new FeedURLSpan("tg://bot_command?command=" + cmd),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        } else if (entity instanceof TLRPC.TL_messageEntityEmail) {
            String email = ssb.subSequence(start, end).toString();
            ssb.setSpan(new FeedURLSpan("mailto:" + email),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        } else if (entity instanceof TLRPC.TL_messageEntityPhone) {
            String phone = ssb.subSequence(start, end).toString();
            ssb.setSpan(new FeedURLSpan("tel:" + phone),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (entity instanceof TLRPC.TL_messageEntitySpoiler) {
            ssb.setSpan(
                    new org.telegram.ui.Components.TextStyleSpan(
                            new org.telegram.ui.Components.TextStyleSpan.TextStyleRun()),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}