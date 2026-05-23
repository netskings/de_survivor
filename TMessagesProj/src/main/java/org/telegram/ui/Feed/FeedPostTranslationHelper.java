package org.telegram.ui.Feed;

import android.annotation.SuppressLint;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LanguageDetector;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.TranslateController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.TranslateAlert2;

import java.util.ArrayList;

class FeedPostTranslationHelper {

    private final FeedPostCell cell;
    private boolean loading;

    FeedPostTranslationHelper(FeedPostCell cell) {
        this.cell = cell;
    }

    void reset() {
        loading = false;
        cell.translateBtn.setVisibility(View.GONE);
        cell.translationCard.setVisibility(View.GONE);
    }

    void bind(FeedController.FeedItem item) {
        loading = false;
        MessageObject primary = item.getPrimaryMessage();

        if (primary == null || primary.messageOwner == null) { reset(); return; }

        String text = primary.messageOwner.message;
        if (TextUtils.isEmpty(text) || text.length() < 30) { reset(); return; }

        TLRPC.Message raw = primary.messageOwner;

        if (raw.translatedText != null && !TextUtils.isEmpty(raw.translatedToLanguage)) {
            if (item.translationShown) showTranslatedUI(raw);
            else showTranslateButton();
            return;
        }

        if (raw.originalLanguage != null
                && !TranslateController.UNKNOWN_LANGUAGE.equals(raw.originalLanguage)) {
            if (isUserLanguage(raw.originalLanguage)) reset();
            else showTranslateButton();
            return;
        }

        if (LanguageDetector.hasSupport()) {
            cell.translateBtn.setVisibility(View.GONE);
            cell.translationCard.setVisibility(View.GONE);
            detectLanguage(primary, item);
        } else {
            showTranslateButton();
        }
    }

    void onTranslateClick() {
        if (cell.currentItem == null) return;
        MessageObject primary = cell.currentItem.getPrimaryMessage();
        if (primary == null || primary.messageOwner == null) return;

        TLRPC.Message raw = primary.messageOwner;

        if (raw.translatedText != null && cell.currentItem.translationShown) {
            cell.currentItem.translationShown = false;
            showTranslateButton();
            return;
        }
        if (raw.translatedText != null && !TextUtils.isEmpty(raw.translatedToLanguage)) {
            cell.currentItem.translationShown = true;
            showTranslatedUI(raw);
            return;
        }
        if (loading) return;
        requestTranslation(primary);
    }

    private void detectLanguage(MessageObject message, FeedController.FeedItem item) {
        if (message == null || message.messageOwner == null
                || TextUtils.isEmpty(message.messageOwner.message)) return;

        final long dialogId = message.getDialogId();

        LanguageDetector.detectLanguage(message.messageOwner.message,
                lang -> AndroidUtilities.runOnUIThread(() -> {
                    message.messageOwner.originalLanguage =
                            lang != null ? lang : TranslateController.UNKNOWN_LANGUAGE;
                    MessagesStorage.getInstance(cell.currentAccount)
                            .updateMessageCustomParams(dialogId, message.messageOwner);
                    if (cell.currentItem == item && !isUserLanguage(lang))
                        showTranslateButton();
                }),
                err -> AndroidUtilities.runOnUIThread(() -> {
                    message.messageOwner.originalLanguage = TranslateController.UNKNOWN_LANGUAGE;
                    if (cell.currentItem == item) showTranslateButton();
                }));
    }

    @SuppressLint("SetTextI18n")
    private void showTranslateButton() {
        cell.translateBtn.setText(LocaleController.getString(R.string.FeedTranslatePost));
        cell.translateBtn.setAlpha(1f);
        cell.translateBtn.setEnabled(true);
        cell.translateBtn.setVisibility(View.VISIBLE);
        cell.translationCard.setVisibility(View.GONE);
    }

    @SuppressLint("SetTextI18n")
    private void showTranslatedUI(TLRPC.Message raw) {
        String fromName = null;
        if (raw.originalLanguage != null
                && !TranslateController.UNKNOWN_LANGUAGE.equals(raw.originalLanguage)) {
            fromName = TranslateAlert2.capitalFirst(
                    TranslateAlert2.languageName(raw.originalLanguage));
        }
        cell.translationHeaderView.setText(
                fromName != null
                        ? LocaleController.formatString(R.string.FeedTranslatedFrom, fromName)
                        : LocaleController.getString(R.string.FeedTranslated));

        CharSequence display;
        if (raw.translatedText.entities != null && !raw.translatedText.entities.isEmpty()) {
            SpannableStringBuilder ssb =
                    SpannableStringBuilder.valueOf(raw.translatedText.text);
            MessageObject.addEntitiesToText(ssb, raw.translatedText.entities,
                    false, true, false, false);
            display = Emoji.replaceEmoji(ssb,
                    cell.translationTextView.getPaint().getFontMetricsInt(), false);
        } else {
            display = Emoji.replaceEmoji(raw.translatedText.text,
                    cell.translationTextView.getPaint().getFontMetricsInt(), false);
        }
        cell.translationTextView.setText(display);
        cell.translationCard.setVisibility(View.VISIBLE);

        cell.translateBtn.setText(LocaleController.getString(R.string.FeedShowOriginal));
        cell.translateBtn.setAlpha(1f);
        cell.translateBtn.setEnabled(true);
        cell.translateBtn.setVisibility(View.VISIBLE);
    }

    @SuppressLint("SetTextI18n")
    private void requestTranslation(MessageObject message) {
        loading = true;
        cell.translateBtn.setText(LocaleController.getString(R.string.FeedTranslating));
        cell.translateBtn.setAlpha(0.5f);
        cell.translateBtn.setEnabled(false);

        String toLang = TranslateAlert2.getToLanguage();
        if (toLang != null) toLang = toLang.split("_")[0];
        if ("nb".equals(toLang)) toLang = "no";

        final String targetLang = toLang;
        final long dialogId = message.getDialogId();
        final int msgId = message.getId();
        final FeedController.FeedItem item = cell.currentItem;

        TLRPC.TL_messages_translateText req = new TLRPC.TL_messages_translateText();
        req.flags |= 1;
        req.peer = MessagesController.getInstance(cell.currentAccount)
                .getInputPeer(dialogId);
        req.id.add(msgId);
        req.to_lang = targetLang;

        ConnectionsManager.getInstance(cell.currentAccount).sendRequest(req, (res, err) ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (err != null && "TRANSLATIONS_DISABLED_ALT"
                            .equalsIgnoreCase(err.text)) {
                        requestFallback(message, targetLang, item);
                        return;
                    }
                    loading = false;

                    if (res instanceof TLRPC.TL_messages_translateResult) {
                        TLRPC.TL_messages_translateResult result =
                                (TLRPC.TL_messages_translateResult) res;
                        if (!result.result.isEmpty() && result.result.get(0) != null) {
                            TLRPC.TL_textWithEntities source = new TLRPC.TL_textWithEntities();
                            source.text = message.messageOwner.message;
                            source.entities = message.messageOwner.entities != null
                                    ? message.messageOwner.entities : new ArrayList<>();
                            TLRPC.TL_textWithEntities translated =
                                    TranslateAlert2.preprocess(source, result.result.get(0));
                            applyTranslation(message, translated, targetLang, item);
                            return;
                        }
                    }
                    handleError(err, item);
                }));
    }

    private void requestFallback(MessageObject message, String targetLang,
                                 FeedController.FeedItem item) {
        TranslateAlert2.alternativeTranslate(
                message.messageOwner.message,
                message.messageOwner.originalLanguage,
                targetLang,
                (result, rateLimit) -> AndroidUtilities.runOnUIThread(() -> {
                    loading = false;
                    if (result != null) {
                        TLRPC.TL_textWithEntities translated = new TLRPC.TL_textWithEntities();
                        translated.text = result;
                        translated.entities = new ArrayList<>();
                        applyTranslation(message, translated, targetLang, item);
                    } else {
                        handleError(null, item);
                    }
                }));
    }

    private void applyTranslation(MessageObject message,
                                  TLRPC.TL_textWithEntities translated,
                                  String targetLang, FeedController.FeedItem item) {
        message.messageOwner.translatedText = translated;
        message.messageOwner.translatedToLanguage = targetLang;
        MessagesStorage.getInstance(cell.currentAccount)
                .updateMessageCustomParams(message.getDialogId(), message.messageOwner);
        item.translationShown = true;
        if (cell.currentItem == item) showTranslatedUI(message.messageOwner);
    }

    @SuppressLint("SetTextI18n")
    private void handleError(TLRPC.TL_error err, FeedController.FeedItem item) {
        if (cell.currentItem == item) showTranslateButton();
        String msg = LocaleController.getString(R.string.FeedTranslationFailed);
        if (err != null && "TO_LANG_INVALID".equals(err.text)) {
            msg = LocaleController.getString(R.string.FeedInvalidTargetLanguage);
        }
        else if (err != null && "QUOTA_EXCEEDED".equals(err.text))
            msg = LocaleController.getString(R.string.FeedTranslationQuotaExceeded);
        Toast.makeText(cell.getContext(), msg, Toast.LENGTH_SHORT).show();
    }

    private boolean isUserLanguage(String lang) {
        if (lang == null || TranslateController.UNKNOWN_LANGUAGE.equals(lang)) return false;
        String appLang = LocaleController.getInstance().getCurrentLocaleInfo().pluralLangCode;
        if (appLang != null) appLang = appLang.split("_")[0];
        String sysLang = java.util.Locale.getDefault().getLanguage();
        return TextUtils.equals(lang, appLang) || TextUtils.equals(lang, sysLang);
    }
}
