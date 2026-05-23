package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.utils.tlutils.TlUtils;
import org.telegram.messenger.WebFile;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.PhotoViewer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@SuppressLint("ViewConstructor")
public class FeedPollView extends LinearLayout {

    private final int currentAccount;
    private final Theme.ResourcesProvider resourceProvider;

    private final FrameLayout coverContainer;
    private final BackupImageView coverImageView;
    private final TextView coverVideoOverlay;

    private final TextView questionView;
    private final TextView typeLabel;
    private final LinearLayout answersContainer;
    private final TextView totalVotersView;
    private final TextView timerView;

    private final TextView addOptionButton;
    private final TextView voteButton;
    private final TextView retractButton;
    private final LinearLayout explanationContainer;
    private final FrameLayout explanationMediaContainer;
    private final BackupImageView explanationMediaView;
    private final TextView explanationMediaOverlay;
    private final TextView explanationText;

    private TLRPC.TL_messageMediaPoll pollMedia;
    private MessageObject messageObject;
    private TLRPC.Message message;
    private boolean voted;
    private boolean closed;
    private boolean quiz;
    private boolean multipleChoice;
    private boolean hideResults;
    private boolean revotingDisabled;
    private final List<byte[]> selectedOptions = new ArrayList<>();
    private boolean voteSending = false;

    private final List<AnswerRow> answerRows = new ArrayList<>();

    private static class AnswerRow {
        LinearLayout container;
        PollProgressBar progressBar;
        IndicatorView indicator;
        TextView textView;
        TextView percentView;
        FrameLayout mediaContainer;
        BackupImageView answerImage;
        TextView answerMediaOverlay;
        byte[] option;
    }

    @SuppressLint("SetTextI18n")
    public FeedPollView(Context context, int account, Theme.ResourcesProvider resourceProvider) {
        super(context);
        this.currentAccount = account;
        this.resourceProvider = resourceProvider;

        setOrientation(VERTICAL);
        setPadding(0, dp(8), 0, 0);

        int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourceProvider);
        int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider);
        int accentColor = Theme.getColor(Theme.key_featuredStickers_addButton, resourceProvider);

        coverContainer = new FrameLayout(context);
        coverContainer.setVisibility(GONE);
        coverContainer.setClipChildren(true);

        coverImageView = new BackupImageView(context);
        coverImageView.setRoundRadius(dp(12));
        coverContainer.addView(coverImageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        coverVideoOverlay = new TextView(context);
        coverVideoOverlay.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        coverVideoOverlay.setTextColor(0xFFFFFFFF);
        coverVideoOverlay.setBackgroundColor(0x99000000);
        coverVideoOverlay.setPadding(dp(8), dp(3), dp(8), dp(3));
        coverVideoOverlay.setGravity(Gravity.CENTER);
        coverVideoOverlay.setVisibility(GONE);
        coverContainer.addView(coverVideoOverlay, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 8, 0, 0, 8));

        addView(coverContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 200, 0, 0, 0, 8));

        questionView = new TextView(context);
        questionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        questionView.setTypeface(AndroidUtilities.bold());
        questionView.setTextColor(textColor);
        addView(questionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        typeLabel = new TextView(context);
        typeLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        typeLabel.setTextColor(grayColor);
        addView(typeLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        answersContainer = new LinearLayout(context);
        answersContainer.setOrientation(VERTICAL);
        addView(answersContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addOptionButton = new TextView(context);
        addOptionButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        addOptionButton.setTypeface(AndroidUtilities.bold());
        addOptionButton.setTextColor(accentColor);
        addOptionButton.setGravity(Gravity.CENTER);
        addOptionButton.setPadding(0, dp(10), 0, dp(10));
        addOptionButton.setText(LocaleController.getString(R.string.PollAddAnOption));
        addOptionButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourceProvider), 2));
        addOptionButton.setVisibility(GONE);
        addOptionButton.setOnClickListener(v -> showAddOptionDialog());
        addView(addOptionButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        voteButton = new TextView(context);
        voteButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        voteButton.setTypeface(AndroidUtilities.bold());
        voteButton.setTextColor(accentColor);
        voteButton.setGravity(Gravity.CENTER);
        voteButton.setPadding(0, dp(12), 0, dp(12));
        voteButton.setText(LocaleController.getString(R.string.FeedPollVote));
        voteButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourceProvider), 2));
        voteButton.setVisibility(GONE);
        voteButton.setOnClickListener(v -> submitVote());
        addView(voteButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        retractButton = new TextView(context);
        retractButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        retractButton.setTextColor(accentColor);
        retractButton.setGravity(Gravity.CENTER);
        retractButton.setPadding(0, dp(8), 0, dp(4));
        retractButton.setText(LocaleController.getString(R.string.FeedPollRetractVote));
        retractButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourceProvider), 2));
        retractButton.setVisibility(GONE);
        retractButton.setOnClickListener(v -> retractVote());
        addView(retractButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout bottomLayout = new LinearLayout(context);
        bottomLayout.setOrientation(HORIZONTAL);
        bottomLayout.setGravity(Gravity.CENTER_VERTICAL);

        totalVotersView = new TextView(context);
        totalVotersView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        totalVotersView.setTextColor(grayColor);
        bottomLayout.addView(totalVotersView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        timerView = new TextView(context);
        timerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        timerView.setTextColor(grayColor);
        timerView.setVisibility(GONE);
        bottomLayout.addView(timerView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        addView(bottomLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        explanationContainer = new LinearLayout(context);
        explanationContainer.setOrientation(VERTICAL);
        explanationContainer.setVisibility(GONE);
        explanationContainer.setBackgroundColor(0x0D000000);
        explanationContainer.setPadding(dp(12), dp(8), dp(12), dp(8));

        TextView explanationLabel = new TextView(context);
        explanationLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        explanationLabel.setTypeface(AndroidUtilities.bold());
        explanationLabel.setTextColor(grayColor);
        explanationLabel.setText(LocaleController.getString(R.string.FeedPollExplanation));
        explanationContainer.addView(explanationLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        explanationMediaContainer = new FrameLayout(context);
        explanationMediaContainer.setVisibility(GONE);
        explanationMediaContainer.setClipChildren(true);
        explanationMediaView = new BackupImageView(context);
        explanationMediaView.setRoundRadius(dp(10));
        explanationMediaContainer.addView(explanationMediaView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        explanationMediaOverlay = new TextView(context);
        explanationMediaOverlay.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        explanationMediaOverlay.setTextColor(0xFFFFFFFF);
        explanationMediaOverlay.setBackgroundColor(0x99000000);
        explanationMediaOverlay.setPadding(dp(8), dp(3), dp(8), dp(3));
        explanationMediaOverlay.setGravity(Gravity.CENTER);
        explanationMediaOverlay.setVisibility(GONE);
        explanationMediaContainer.addView(explanationMediaOverlay, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.LEFT, 8, 0, 0, 8));
        explanationContainer.addView(explanationMediaContainer,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 160, 0, 2, 0, 6));

        explanationText = new TextView(context);
        explanationText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        explanationText.setTextColor(textColor);
        explanationContainer.addView(explanationText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addView(explanationContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
    }

    @SuppressLint("SetTextI18n")
    public void setPoll(TLRPC.TL_messageMediaPoll pollMedia, MessageObject messageObject) {
        this.pollMedia = pollMedia;
        this.messageObject = messageObject;
        this.message = messageObject != null ? messageObject.messageOwner : null;
        this.selectedOptions.clear();
        this.answerRows.clear();
        this.voteSending = false;
        answersContainer.removeAllViews();
        coverImageView.setImageDrawable(null);
        coverVideoOverlay.setVisibility(GONE);
        explanationMediaView.setImageDrawable(null);
        explanationMediaOverlay.setVisibility(GONE);
        explanationMediaContainer.setVisibility(GONE);

        if (pollMedia == null || pollMedia.poll == null) {
            setVisibility(GONE);
            return;
        }

        TLRPC.Poll poll = pollMedia.poll;
        quiz = poll.quiz;
        multipleChoice = poll.multiple_choice;
        closed = poll.closed;
        hideResults = poll.hide_results_until_close;
        revotingDisabled = poll.revoting_disabled;

        voted = MessageObject.isVoted(pollMedia);

        StringBuilder typeText = new StringBuilder();
        if (quiz) {
            typeText.append(LocaleController.getString(poll.public_voters
                    ? R.string.FeedPollQuiz : R.string.FeedPollAnonymousQuiz));
        } else {
            typeText.append(LocaleController.getString(poll.public_voters
                    ? R.string.FeedPollPoll : R.string.FeedPollAnonymousPoll));
        }
        if (poll.open_answers) {
            typeText.append(" · ")
                    .append(LocaleController.getString(R.string.FeedPollOpenAnswers));
        }
        if (closed) {
            typeText.append(" · ")
                    .append(LocaleController.getString(R.string.FeedPollFinalResults));
        }
        typeLabel.setText(typeText);

        questionView.setText(poll.question != null ? poll.question.text : "");

        if (pollMedia.attached_media != null) {
            setupCoverMedia(pollMedia.attached_media);
            coverContainer.setOnClickListener(v -> openPollMedia(pollMedia.attached_media));
        } else {
            coverContainer.setOnClickListener(null);
            coverContainer.setVisibility(GONE);
        }

        TlUtils.calculateAnswerShuffleHash(poll,
                UserConfig.getInstance(currentAccount).getClientUserId());
        List<TLRPC.PollAnswer> answersList = poll.shuffled_answers != null
                ? poll.shuffled_answers : poll.answers;

        for (TLRPC.PollAnswer answer : answersList) {
            AnswerRow row = createAnswerRow(answer);
            answerRows.add(row);
            answersContainer.addView(row.container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));
        }

        if (canAddPollAnswer()) {
            addOptionButton.setVisibility(VISIBLE);
            addOptionButton.setEnabled(true);
            addOptionButton.setText(LocaleController.getString(R.string.PollAddAnOption));
        } else {
            addOptionButton.setVisibility(GONE);
        }

        if (multipleChoice && !voted && !closed) {
            voteButton.setVisibility(VISIBLE);
            voteButton.setText(LocaleController.getString(R.string.FeedPollVote));
            voteButton.setEnabled(false);
        } else {
            voteButton.setVisibility(GONE);
        }

        updateResultsUI();
        updateExplanation();
        updateTimer();
    }

    private boolean setupMedia(FrameLayout container, BackupImageView imageView,
                               TextView overlay, TLRPC.MessageMedia media,
                               int targetWidth, int defaultHeight) {
        int height = defaultHeight;
        imageView.setImageDrawable(null);
        overlay.setVisibility(GONE);

        if (media == null) {
            container.setVisibility(GONE);
            return false;
        }

        int maxHeight = targetWidth <= dp(120) ? dp(96) : dp(400);

        if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null) {
            TLRPC.PhotoSize best = FileLoader.getClosestPhotoSizeWithSize(
                    media.photo.sizes, targetWidth);
            if (best == null || best.w <= 0) {
                container.setVisibility(GONE);
                return false;
            }
            height = Math.max(dp(56), Math.min(maxHeight,
                    (int) (targetWidth * ((float) best.h / best.w))));
            imageView.setImage(ImageLocation.getForPhoto(best, media.photo),
                    targetWidth + "_" + height, null, null, 0);
            container.setVisibility(VISIBLE);
        } else if (media instanceof TLRPC.TL_messageMediaDocument
                && media.document != null) {
            TLRPC.Document doc = media.document;
            TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(
                    doc.thumbs, targetWidth);
            int videoW = 0;
            int videoH = 0;
            double duration = 0;

            for (TLRPC.DocumentAttribute attr : doc.attributes) {
                if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                    videoW = attr.w;
                    videoH = attr.h;
                    duration = attr.duration;
                }
            }

            if (videoW > 0 && videoH > 0) {
                height = Math.max(dp(56), Math.min(maxHeight,
                        (int) (targetWidth * ((float) videoH / videoW))));
            }

            if (thumb != null) {
                imageView.setImage(ImageLocation.getForDocument(thumb, doc),
                        targetWidth + "_" + height, null, null, 0);
            } else if (FeedUtils.isSticker(doc) || FeedUtils.isGif(doc)) {
                imageView.setImage(ImageLocation.getForDocument(doc),
                        targetWidth + "_" + height, null, null, 0);
            } else {
                container.setVisibility(GONE);
                return false;
            }

            if (duration > 0) {
                int d = (int) duration;
                overlay.setText(String.format(Locale.US, "▶ %d:%02d", d / 60, d % 60));
                overlay.setVisibility(VISIBLE);
            }
            container.setVisibility(VISIBLE);
        } else if (media instanceof TLRPC.TL_messageMediaGeo
                || media instanceof TLRPC.TL_messageMediaVenue) {
            if (media.geo == null) {
                container.setVisibility(GONE);
                return false;
            }
            WebFile webFile = WebFile.createWithGeoPoint(media.geo, targetWidth, height,
                    15, Math.min(2, (int) Math.ceil(AndroidUtilities.density)));
            imageView.setImage(ImageLocation.getForWebFile(webFile),
                    targetWidth + "_" + height, null, null, 0);
            container.setVisibility(VISIBLE);
        } else {
            container.setVisibility(GONE);
            return false;
        }

        ViewGroup.LayoutParams lp = container.getLayoutParams();
        if (lp != null && lp.height != height) {
            lp.height = height;
            container.setLayoutParams(lp);
        }
        return true;
    }

    private void setupCoverMedia(TLRPC.MessageMedia media) {
        setupMedia(coverContainer, coverImageView, coverVideoOverlay, media,
                AndroidUtilities.displaySize.x - dp(32), dp(200));
    }

    private boolean setupAnswerMedia(AnswerRow row, TLRPC.MessageMedia media) {
        return setupMedia(row.mediaContainer, row.answerImage, row.answerMediaOverlay,
                media, dp(96), dp(56));
    }

    private AnswerRow createAnswerRow(TLRPC.PollAnswer answer) {
        Context context = getContext();
        AnswerRow row = new AnswerRow();
        row.option = answer.option;

        row.container = new LinearLayout(context);
        row.container.setOrientation(HORIZONTAL);
        row.container.setGravity(Gravity.CENTER_VERTICAL);
        row.container.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.container.setMinimumHeight(dp(44));
        row.container.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourceProvider), 2));

        FrameLayout wrapper = new FrameLayout(context);
        row.progressBar = new PollProgressBar(context);
        row.progressBar.setVisibility(INVISIBLE);
        wrapper.addView(row.progressBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);

        row.percentView = new TextView(context);
        row.percentView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        row.percentView.setTypeface(AndroidUtilities.bold());
        row.percentView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        row.percentView.setVisibility(GONE);
        row.percentView.setMinWidth(dp(40));
        content.addView(row.percentView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        row.indicator = new IndicatorView(context, resourceProvider);
        row.indicator.setType(multipleChoice ? IndicatorView.TYPE_CHECKBOX : IndicatorView.TYPE_RADIO);
        row.indicator.setState(IndicatorView.STATE_EMPTY);
        content.addView(row.indicator, LayoutHelper.createLinear(22, 22, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

        row.textView = new TextView(context);
        row.textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        row.textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        row.textView.setText(answer.text != null ? answer.text.text : "");
        content.addView(row.textView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        row.mediaContainer = new FrameLayout(context);
        row.mediaContainer.setVisibility(GONE);
        row.mediaContainer.setClipChildren(true);
        row.answerImage = new BackupImageView(context);
        row.answerImage.setRoundRadius(dp(6));
        row.mediaContainer.addView(row.answerImage,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        row.answerMediaOverlay = new TextView(context);
        row.answerMediaOverlay.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
        row.answerMediaOverlay.setTextColor(0xFFFFFFFF);
        row.answerMediaOverlay.setBackgroundColor(0x99000000);
        row.answerMediaOverlay.setPadding(dp(5), dp(1), dp(5), dp(1));
        row.answerMediaOverlay.setGravity(Gravity.CENTER);
        row.answerMediaOverlay.setVisibility(GONE);
        row.mediaContainer.addView(row.answerMediaOverlay, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.LEFT, 4, 0, 0, 4));
        content.addView(row.mediaContainer,
                LayoutHelper.createLinear(96, 56, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

        if (answer.media != null && setupAnswerMedia(row, answer.media)) {
            row.mediaContainer.setOnClickListener(v -> openPollMedia(answer.media));
        }

        wrapper.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        row.container.addView(wrapper, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        row.container.setOnClickListener(v -> onAnswerClicked(row));

        return row;
    }

    @SuppressLint("SetTextI18n")
    private void updateTimer() {
        if (pollMedia == null || closed) {
            timerView.setVisibility(GONE);
            return;
        }
        TLRPC.Poll poll = pollMedia.poll;
        if (poll.close_date > 0) {
            int timeDiff = poll.close_date - ConnectionsManager.getInstance(currentAccount).getCurrentTime();
            if (timeDiff > 0) {
                timerView.setVisibility(VISIBLE);
                timerView.setText("⏱ " + formatShortDuration(timeDiff));
            } else {
                timerView.setVisibility(GONE);
            }
        } else {
            timerView.setVisibility(GONE);
        }
    }

    private String formatShortDuration(int seconds) {
        if (seconds < 60) return LocaleController.formatString(
                R.string.FeedSecondsShort, seconds);
        int minutes = seconds / 60;
        if (minutes < 60) return LocaleController.formatString(
                R.string.FeedMinutesShort, minutes);
        int hours = minutes / 60;
        if (hours < 24) return LocaleController.formatString(
                R.string.FeedHoursShort, hours);
        int days = hours / 24;
        return LocaleController.formatString(R.string.FeedDaysShort, days);
    }

    private void onAnswerClicked(AnswerRow row) {
        if (closed || voteSending) return;
        if (voted && !MessageObject.isVotedButResultsHiddenUntilClose(pollMedia)) return;

        if (multipleChoice) {
            boolean found = false;
            for (int i = selectedOptions.size() - 1; i >= 0; i--) {
                if (Arrays.equals(selectedOptions.get(i), row.option)) {
                    selectedOptions.remove(i);
                    found = true;
                    break;
                }
            }
            if (!found) selectedOptions.add(row.option);

            for (AnswerRow r : answerRows) {
                boolean selected = isOptionSelected(r.option) || isOptionChosen(r.option);
                r.indicator.setState(selected ? IndicatorView.STATE_SELECTED : IndicatorView.STATE_EMPTY);
            }
            voteButton.setEnabled(!selectedOptions.isEmpty());
        } else {
            selectedOptions.clear();
            selectedOptions.add(row.option);
            row.indicator.setState(IndicatorView.STATE_SELECTED);
            submitVote();
        }
    }

    private boolean canAddPollAnswer() {
        if (pollMedia == null || pollMedia.poll == null || messageObject == null) {
            return false;
        }
        return pollMedia.poll.open_answers
                && !pollMedia.poll.closed
                && !messageObject.scheduled
                && !messageObject.isForwarded()
                && pollMedia.poll.answers.size() < MessagesController
                        .getInstance(currentAccount).config.pollAnswersMax.get();
    }

    private void showAddOptionDialog() {
        if (!canAddPollAnswer()) {
            return;
        }

        Context context = getContext();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourceProvider);
        builder.setTitle(LocaleController.getString(R.string.PollAddAnOption));

        FrameLayout container = new FrameLayout(context);
        container.setPadding(dp(24), 0, dp(24), 0);

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, resourceProvider));
        editText.setHint(LocaleController.getString(R.string.PollAddAnOptionHint));
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setBackground(null);
        editText.setPadding(0, dp(8), 0, dp(8));
        editText.setFilters(new InputFilter[] {
                new InputFilter.LengthFilter(MessagesController
                        .getInstance(currentAccount).config.pollAnswerLengthMax.get())
        });
        container.addView(editText, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, dp(48), Gravity.CENTER_VERTICAL));

        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString(R.string.Add), null);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);

        AlertDialog dialog = builder.show();

        View positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positiveButton != null) {
            positiveButton.setOnClickListener(v -> {
                CharSequence text = editText.getText();
                if (TextUtils.isEmpty(text) || TextUtils.isEmpty(text.toString().trim())) {
                    AndroidUtilities.shakeView(editText);
                    AndroidUtilities.showKeyboard(editText);
                    return;
                }

                AndroidUtilities.hideKeyboard(editText);
                SendMessagesHelper.getInstance(currentAccount)
                        .addPollOption(messageObject, text.toString().trim(), null);
                dialog.dismiss();
                setPoll(pollMedia, messageObject);
            });
        }

        editText.requestFocus();
        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (positiveButton != null) {
                    positiveButton.callOnClick();
                }
                return true;
            }
            return false;
        });
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(editText), 200);
    }

    private void openPollMedia(TLRPC.MessageMedia targetMedia) {
        if (messageObject == null || message == null || pollMedia == null
                || targetMedia == null || !isPollPreviewMedia(targetMedia)) {
            return;
        }

        ArrayList<MessageObject> messages = new ArrayList<>();
        int index = -1;

        index = addPollMediaMessage(messages, index, pollMedia.attached_media,
                pollMedia.poll != null && pollMedia.poll.question != null
                        ? pollMedia.poll.question.text : "", targetMedia);

        if (pollMedia.results != null) {
            index = addPollMediaMessage(messages, index, pollMedia.results.solution_media,
                    pollMedia.results.solution, targetMedia);
        }

        if (pollMedia.poll != null) {
            TlUtils.calculateAnswerShuffleHash(pollMedia.poll,
                    UserConfig.getInstance(currentAccount).getClientUserId());
            List<TLRPC.PollAnswer> answers = pollMedia.poll.shuffled_answers != null
                    ? pollMedia.poll.shuffled_answers : pollMedia.poll.answers;
            for (TLRPC.PollAnswer answer : answers) {
                index = addPollMediaMessage(messages, index, answer.media,
                        answer.text != null ? answer.text.text : "", targetMedia);
            }
        }

        if (index < 0 || messages.isEmpty()) {
            return;
        }

        if (FeedUtils.getActivity(getContext()) == null) {
            return;
        }

        PhotoViewer.getInstance().setParentActivity(
                FeedUtils.getActivity(getContext()), resourceProvider);
        PhotoViewer.getInstance().openPhoto(messages, index, messageObject.getDialogId(),
                0, 0, new PhotoViewer.EmptyPhotoViewerProvider());
    }

    private int addPollMediaMessage(ArrayList<MessageObject> messages, int index,
                                    TLRPC.MessageMedia media, String caption,
                                    TLRPC.MessageMedia targetMedia) {
        if (!isPollPreviewMedia(media)) {
            return index;
        }
        if (media == targetMedia) {
            index = messages.size();
        }

        TLRPC.TL_message previewMessage = copyMessage(message);
        previewMessage.media = media;
        previewMessage.message = caption != null ? caption : "";
        previewMessage.entities = new ArrayList<>();
        previewMessage.noforwards = true;
        messages.add(new MessageObject(currentAccount, previewMessage, false, true));
        return index;
    }

    private boolean isPollPreviewMedia(TLRPC.MessageMedia media) {
        if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null) {
            return true;
        }
        if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            return FeedUtils.isGif(media.document)
                    || FeedUtils.isVideo(media.document);
        }
        return false;
    }

    private TLRPC.TL_message copyMessage(TLRPC.Message source) {
        TLRPC.TL_message copy = new TLRPC.TL_message();
        copy.id = source.id;
        copy.from_id = source.from_id;
        copy.peer_id = source.peer_id;
        copy.saved_peer_id = source.saved_peer_id;
        copy.date = source.date;
        copy.flags = source.flags;
        copy.flags2 = source.flags2;
        copy.out = source.out;
        copy.unread = source.unread;
        copy.post = source.post;
        copy.silent = source.silent;
        copy.dialog_id = source.dialog_id;
        copy.grouped_id = source.grouped_id;
        copy.fwd_from = source.fwd_from;
        copy.reply_to = source.reply_to;
        copy.reply_markup = source.reply_markup;
        copy.views = source.views;
        copy.forwards = source.forwards;
        copy.replies = source.replies;
        return copy;
    }

    private boolean isOptionSelected(byte[] option) {
        for (byte[] opt : selectedOptions) {
            if (Arrays.equals(opt, option)) return true;
        }
        return false;
    }

    @SuppressLint("SetTextI18n")
    private void submitVote() {
        if (selectedOptions.isEmpty() || voteSending) return;
        voteSending = true;

        for (AnswerRow r : answerRows) r.container.setClickable(false);
        voteButton.setEnabled(false);
        voteButton.setText(LocaleController.getString(R.string.FeedPollSending));

        sendVoteRequest(new ArrayList<>(selectedOptions));
    }

    @SuppressLint("SetTextI18n")
    private void retractVote() {
        if (voteSending || quiz || revotingDisabled) return;
        voteSending = true;
        retractButton.setEnabled(false);
        retractButton.setText(LocaleController.getString(R.string.FeedPollRetracting));

        sendVoteRequest(new ArrayList<>());
    }

    @SuppressLint("SetTextI18n")
    private void sendVoteRequest(ArrayList<byte[]> options) {
        if (message == null || message.peer_id == null) {
            voteSending = false;
            return;
        }

        TLRPC.TL_messages_sendVote req = new TLRPC.TL_messages_sendVote();

        long dialogId;
        if (message.peer_id.channel_id != 0) {
            dialogId = -message.peer_id.channel_id;
        } else if (message.peer_id.chat_id != 0) {
            dialogId = -message.peer_id.chat_id;
        } else {
            dialogId = message.peer_id.user_id;
        }

        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        req.msg_id = message.id;
        req.options = options;

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            voteSending = false;

            if (error != null) {
                for (AnswerRow r : answerRows) r.container.setClickable(true);
                voteButton.setEnabled(true);
                voteButton.setText(LocaleController.getString(R.string.FeedPollVote));
                retractButton.setEnabled(true);
                retractButton.setText(LocaleController.getString(R.string.FeedPollRetractVote));
                try {
                    Toast.makeText(getContext(),
                            LocaleController.formatString(
                                    R.string.FeedPollVoteFailed, error.text),
                            Toast.LENGTH_SHORT).show();
                } catch (Exception e) { /* ignore */ }
                return;
            }

            if (response instanceof TLRPC.Updates) {
                MessagesController.getInstance(currentAccount)
                        .processUpdates((TLRPC.Updates) response, false);
                extractPollResults((TLRPC.Updates) response);
            }

            if (options.isEmpty()) {
                voted = false;
                selectedOptions.clear();
                if (pollMedia.results != null && pollMedia.results.results != null) {
                    for (TLRPC.PollAnswerVoters answerResult : pollMedia.results.results) {
                        answerResult.chosen = false;
                    }
                }
            } else {
                voted = true;
                ensureResults();
                for (byte[] chosen : options) {
                    boolean found = false;
                    for (TLRPC.PollAnswerVoters answerResult : pollMedia.results.results) {
                        if (Arrays.equals(answerResult.option, chosen)) {
                            answerResult.chosen = true;
                            answerResult.flags |= 1;
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        TLRPC.TL_pollAnswerVoters newResult = new TLRPC.TL_pollAnswerVoters();
                        newResult.option = chosen;
                        newResult.chosen = true;
                        newResult.voters = 1;
                        newResult.flags |= 1;
                        newResult.flags |= 4;
                        pollMedia.results.results.add(newResult);
                    }
                }
            }

            voted = MessageObject.isVoted(pollMedia);
            voteButton.setVisibility(multipleChoice && !voted && !closed ? VISIBLE : GONE);
            voteButton.setText(LocaleController.getString(R.string.FeedPollVote));
            retractButton.setText(LocaleController.getString(R.string.FeedPollRetractVote));
            retractButton.setEnabled(true);

            updateResultsUI();
            updateExplanation();
            updateTimer();
        }));
    }

    private void extractPollResults(TLRPC.Updates updates) {
        try {
            if (updates.updates != null) {
                for (TLRPC.Update update : updates.updates) {
                    if (update instanceof TLRPC.TL_updateMessagePoll) {
                        TLRPC.TL_updateMessagePoll pollUpdate = (TLRPC.TL_updateMessagePoll) update;
                        if (pollUpdate.results != null) {
                            ensureResults();
                            MessageObject.updatePollResults(pollMedia, pollUpdate.results);
                        }
                        if (pollUpdate.poll != null) {
                            pollMedia.poll = pollUpdate.poll;
                            closed = pollMedia.poll.closed;
                            multipleChoice = pollMedia.poll.multiple_choice;
                            hideResults = pollMedia.poll.hide_results_until_close;
                            revotingDisabled = pollMedia.poll.revoting_disabled;
                            quiz = pollMedia.poll.quiz;
                        }
                        return;
                    }
                }
            }
        } catch (Exception e) { /* ignore */ }
    }

    private void ensureResults() {
        if (pollMedia.results == null) {
            pollMedia.results = new TLRPC.TL_pollResults();
        }
        if (pollMedia.results.results == null) {
            pollMedia.results.results = new ArrayList<>();
        }
    }

    @SuppressLint("SetTextI18n")
    private void updateResultsUI() {
        boolean hasResults = MessageObject.isVoteResultsIsNotEmpty(pollMedia);
        boolean showResults = closed || pollMedia.poll.creator
                || (voted && (!hideResults || hasResults));

        int totalVoters = 0;
        if (pollMedia.results != null) {
            totalVoters = pollMedia.results.total_voters;
        }

        int accentColor = Theme.getColor(Theme.key_featuredStickers_addButton, resourceProvider);
        int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider);

        for (AnswerRow row : answerRows) {
            if (showResults) {
                int voters = 0;
                boolean chosen = false;
                boolean correct = false;

                TLRPC.PollAnswerVoters answerResult = MessageObject.getPollResult(
                        pollMedia, row.option);
                if (answerResult != null) {
                    voters = (answerResult.flags & 4) != 0 ? answerResult.voters : 0;
                    chosen = answerResult.chosen;
                    correct = answerResult.correct;
                }

                float percent = totalVoters > 0 ? (float) voters / totalVoters : 0;
                int percentInt = Math.round(percent * 100);

                row.percentView.setText(percentInt + "%");
                row.percentView.setVisibility(VISIBLE);

                row.progressBar.setProgress(percent);
                row.progressBar.setVisibility(VISIBLE);

                if (quiz) {
                    if (correct) {
                        row.indicator.setState(IndicatorView.STATE_CORRECT);
                        row.progressBar.setBarColor(0x3300C853);
                        row.percentView.setTextColor(0xFF00C853);
                        row.textView.setTypeface(AndroidUtilities.bold());
                    } else if (chosen) {
                        row.indicator.setState(IndicatorView.STATE_WRONG);
                        row.progressBar.setBarColor(0x33FF1744);
                        row.percentView.setTextColor(0xFFFF1744);
                        row.textView.setTypeface(null);
                    } else {
                        row.indicator.setState(IndicatorView.STATE_EMPTY);
                        row.progressBar.setBarColor(0x15000000);
                        row.percentView.setTextColor(textColor);
                        row.textView.setTypeface(null);
                    }
                } else {
                    if (chosen) {
                        row.indicator.setState(IndicatorView.STATE_SELECTED);
                        row.progressBar.setBarColor((accentColor & 0x00FFFFFF) | 0x33000000);
                        row.percentView.setTextColor(accentColor);
                        row.textView.setTypeface(AndroidUtilities.bold());
                    } else {
                        row.indicator.setState(IndicatorView.STATE_EMPTY);
                        row.progressBar.setBarColor(0x15000000);
                        row.percentView.setTextColor(textColor);
                        row.textView.setTypeface(null);
                    }
                }

                row.container.setClickable(false);
            } else {
                row.percentView.setVisibility(GONE);
                row.progressBar.setVisibility(INVISIBLE);
                row.container.setClickable(true);
                row.textView.setTypeface(null);

                boolean selected = isOptionSelected(row.option) || isOptionChosen(row.option);
                row.indicator.setState(selected ? IndicatorView.STATE_SELECTED : IndicatorView.STATE_EMPTY);
            }
        }

        if (MessageObject.isVotedButResultsHiddenUntilClose(pollMedia)) {
            totalVotersView.setText(LocaleController.getString(
                    R.string.FeedPollResultsHidden));
        } else if (totalVoters > 0) {
            totalVotersView.setText(LocaleController.formatPluralString(
                    "Vote", totalVoters));
        } else {
            totalVotersView.setText(LocaleController.getString(R.string.FeedPollNoVotesYet));
        }

        if (MessageObject.canUnvote(pollMedia) && !quiz && !closed && !revotingDisabled) {
            retractButton.setVisibility(VISIBLE);
        } else {
            retractButton.setVisibility(GONE);
        }
    }

    private boolean isOptionChosen(byte[] option) {
        TLRPC.PollAnswerVoters result = MessageObject.getPollResult(pollMedia, option);
        return result != null && result.chosen;
    }

    private void updateExplanation() {
        if (!quiz || !voted || pollMedia == null || pollMedia.results == null) {
            explanationContainer.setVisibility(GONE);
            return;
        }
        try {
            boolean hasMedia = setupMedia(explanationMediaContainer, explanationMediaView,
                    explanationMediaOverlay, pollMedia.results.solution_media,
                    AndroidUtilities.displaySize.x - dp(56), dp(160));
            String solution = pollMedia.results.solution;
            boolean hasText = solution != null && !solution.isEmpty();
            if (hasText) {
                explanationText.setText(solution);
                explanationText.setVisibility(VISIBLE);
            } else {
                explanationText.setVisibility(GONE);
            }
            if (hasText || hasMedia) {
                explanationContainer.setVisibility(VISIBLE);
            } else {
                explanationContainer.setVisibility(GONE);
            }
        } catch (Exception e) {
            explanationContainer.setVisibility(GONE);
        }
    }

    private static class PollProgressBar extends View {
        private float progress = 0;
        private int barColor = 0x22000000;
        private final Paint paint = new Paint();
        private final RectF rect = new RectF();

        PollProgressBar(Context context) { super(context); }

        void setProgress(float p) { this.progress = p; invalidate(); }
        void setBarColor(int c) { this.barColor = c; invalidate(); }

        @Override
        protected void onDraw(Canvas canvas) {
            paint.setColor(barColor);
            float w = getWidth() * progress;
            rect.set(0, 0, w, getHeight());
            canvas.drawRoundRect(rect, dp(6), dp(6), paint);
        }
    }

    private static class IndicatorView extends View {
        static final int TYPE_RADIO = 0;
        static final int TYPE_CHECKBOX = 1;

        static final int STATE_EMPTY = 0;
        static final int STATE_SELECTED = 1;
        static final int STATE_CORRECT = 2;
        static final int STATE_WRONG = 3;

        private int type = TYPE_RADIO;
        private int state = STATE_EMPTY;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Theme.ResourcesProvider rp;

        IndicatorView(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.rp = rp;
        }

        void setType(int type) { this.type = type; invalidate(); }
        void setState(int state) { this.state = state; invalidate(); }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - dp(2);

            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);

            switch (state) {
                case STATE_EMPTY:
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, rp));
                    if (type == TYPE_RADIO) {
                        canvas.drawCircle(cx, cy, r, paint);
                    } else {
                        RectF rect = new RectF(cx - r, cy - r, cx + r, cy + r);
                        canvas.drawRoundRect(rect, dp(3), dp(3), paint);
                    }
                    break;

                case STATE_SELECTED:
                    int accent = Theme.getColor(Theme.key_featuredStickers_addButton, rp);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(accent);
                    if (type == TYPE_RADIO) {
                        canvas.drawCircle(cx, cy, r, paint);
                        paint.setColor(0xFFFFFFFF);
                        canvas.drawCircle(cx, cy, r * 0.4f, paint);
                    } else {
                        RectF rect = new RectF(cx - r, cy - r, cx + r, cy + r);
                        canvas.drawRoundRect(rect, dp(3), dp(3), paint);
                        drawCheckmark(canvas, cx, cy, r, 0xFFFFFFFF);
                    }
                    break;

                case STATE_CORRECT:
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0xFF00C853);
                    canvas.drawCircle(cx, cy, r, paint);
                    drawCheckmark(canvas, cx, cy, r, 0xFFFFFFFF);
                    break;

                case STATE_WRONG:
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0xFFFF1744);
                    canvas.drawCircle(cx, cy, r, paint);
                    drawCross(canvas, cx, cy, r, 0xFFFFFFFF);
                    break;
            }
        }

        private void drawCheckmark(Canvas canvas, float cx, float cy, float r, int color) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(color);
            canvas.drawLine(cx - r * 0.4f, cy, cx - r * 0.1f, cy + r * 0.35f, paint);
            canvas.drawLine(cx - r * 0.1f, cy + r * 0.35f, cx + r * 0.5f, cy - r * 0.3f, paint);
        }

        private void drawCross(Canvas canvas, float cx, float cy, float r, int color) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(color);
            float d = r * 0.35f;
            canvas.drawLine(cx - d, cy - d, cx + d, cy + d, paint);
            canvas.drawLine(cx + d, cy - d, cx - d, cy + d, paint);
        }
    }
}
