package org.telegram.ui.Feed;

import android.annotation.SuppressLint;
import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

class FeedPostSummaryHelper {

    private final FeedPostCell cell;
    private boolean loading;

    FeedPostSummaryHelper(FeedPostCell cell) {
        this.cell = cell;
    }

    void reset() {
        loading = false;
        cell.summarizeBtn.setVisibility(android.view.View.GONE);
        cell.summaryCard.setVisibility(android.view.View.GONE);
    }

    boolean canSummarize(MessageObject msg) {
        if (msg == null || msg.messageOwner == null) return false;
        String text = msg.messageOwner.message;
        if (TextUtils.isEmpty(text) || text.length() <= 100) return false;
        return msg.messageOwner.summary_from_language != null;
    }

    @SuppressLint("SetTextI18n")
    void bind(FeedController.FeedItem item) {
        loading = false;
        MessageObject primary = item.getPrimaryMessage();

        if (!canSummarize(primary)) {
            cell.summarizeBtn.setVisibility(android.view.View.GONE);
            cell.summaryCard.setVisibility(android.view.View.GONE);
            return;
        }

        TLRPC.Message raw = primary.messageOwner;

        if (raw.summarizedOpen && raw.summaryText != null) {
            showSummaryCard(raw);
            cell.summarizeBtn.setText(LocaleController.getString(R.string.FeedHideSummary));
            setButtonEnabled(true);
        } else if (raw.summaryText != null) {
            cell.summaryCard.setVisibility(android.view.View.GONE);
            cell.summarizeBtn.setText(LocaleController.getString(R.string.FeedShowSummary));
            setButtonEnabled(true);
        } else {
            cell.summaryCard.setVisibility(android.view.View.GONE);
            cell.summarizeBtn.setText(LocaleController.getString(R.string.FeedSummarize));
            setButtonEnabled(true);
        }
    }

    @SuppressLint("SetTextI18n")
    void onSummarizeClick() {
        if (cell.currentItem == null) return;
        MessageObject primary = cell.currentItem.getPrimaryMessage();
        if (primary == null || primary.messageOwner == null) return;

        TLRPC.Message raw = primary.messageOwner;

        if (raw.summarizedOpen && raw.summaryText != null) {
            raw.summarizedOpen = false;
            saveState(primary);
            updateUI();
            return;
        }
        if (raw.summaryText != null) {
            raw.summarizedOpen = true;
            saveState(primary);
            updateUI();
            return;
        }
        if (loading) return;
        requestSummary(primary);
    }

    private void requestSummary(MessageObject message) {
        loading = true;
        updateUI();

        TLRPC.TL_messages_summarizeText req = new TLRPC.TL_messages_summarizeText();
        req.peer = MessagesController.getInstance(cell.currentAccount)
                .getInputPeer(message.getDialogId());
        req.id = message.getId();

        final long dialogId = message.getDialogId();
        final int msgId = message.getId();

        ConnectionsManager.getInstance(cell.currentAccount).sendRequest(req, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    loading = false;
                    if (response instanceof TLRPC.TL_textWithEntities) {
                        message.messageOwner.summaryText = (TLRPC.TL_textWithEntities) response;
                        message.messageOwner.summarizedOpen = true;
                        MessagesStorage.getInstance(cell.currentAccount)
                                .updateMessageCustomParams(dialogId, message.messageOwner);
                    } else {
                        message.messageOwner.summarizedOpen = false;
                        if (error != null && "SUMMARY_FLOOD_PREMIUM"
                                .equalsIgnoreCase(error.text)) {
                            Toast.makeText(cell.getContext(),
                                    LocaleController.getString(R.string.FeedSummaryLimitPremium),
                                    Toast.LENGTH_LONG).show();
                        } else if (error != null) {
                            Toast.makeText(cell.getContext(),
                                    LocaleController.getString(R.string.FeedSummarizeFailed),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }

                    if (cell.currentItem != null) {
                        MessageObject cur = cell.currentItem.getPrimaryMessage();
                        if (cur != null && cur.getId() == msgId
                                && cur.getDialogId() == dialogId)
                            updateUI();
                    }
                }));
    }

    @SuppressLint("SetTextI18n")
    private void updateUI() {
        if (cell.currentItem == null) return;
        MessageObject primary = cell.currentItem.getPrimaryMessage();
        if (primary == null || primary.messageOwner == null) return;

        TLRPC.Message raw = primary.messageOwner;

        if (loading) {
            cell.summarizeBtn.setText(LocaleController.getString(R.string.FeedSummarizing));
            setButtonEnabled(false);
            cell.summaryCard.setVisibility(android.view.View.GONE);
        } else if (raw.summarizedOpen && raw.summaryText != null) {
            showSummaryCard(raw);
            cell.summarizeBtn.setText(LocaleController.getString(R.string.FeedHideSummary));
            setButtonEnabled(true);
        } else if (raw.summaryText != null) {
            cell.summaryCard.setVisibility(android.view.View.GONE);
            cell.summarizeBtn.setText(LocaleController.getString(R.string.FeedShowSummary));
            setButtonEnabled(true);
        } else {
            cell.summaryCard.setVisibility(android.view.View.GONE);
            cell.summarizeBtn.setText(LocaleController.getString(R.string.FeedSummarize));
            setButtonEnabled(true);
        }
        cell.requestLayout();
    }

    private void showSummaryCard(TLRPC.Message raw) {
        CharSequence display = Emoji.replaceEmoji(raw.summaryText.text,
                cell.summaryTextView.getPaint().getFontMetricsInt(), false);
        cell.summaryTextView.setText(display);
        cell.summaryCard.setVisibility(android.view.View.VISIBLE);
    }

    private void setButtonEnabled(boolean enabled) {
        cell.summarizeBtn.setAlpha(enabled ? 1f : 0.5f);
        cell.summarizeBtn.setEnabled(enabled);
        cell.summarizeBtn.setVisibility(android.view.View.VISIBLE);
    }

    private void saveState(MessageObject message) {
        if (message != null)
            MessagesStorage.getInstance(cell.currentAccount)
                    .updateMessageCustomParams(message.getDialogId(), message.messageOwner);
    }
}
