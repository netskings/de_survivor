package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.WebFile;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    private final TextView voteButton;
    private final TextView retractButton;
    private final LinearLayout explanationContainer;
    private final TextView explanationText;

    private TLRPC.TL_messageMediaPoll pollMedia;
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
        BackupImageView answerImage;
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

        explanationText = new TextView(context);
        explanationText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        explanationText.setTextColor(textColor);
        explanationContainer.addView(explanationText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addView(explanationContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
    }

    @SuppressLint("SetTextI18n")
    public void setPoll(TLRPC.TL_messageMediaPoll pollMedia, TLRPC.Message message) {
        this.pollMedia = pollMedia;
        this.message = message;
        this.selectedOptions.clear();
        this.answerRows.clear();
        this.voteSending = false;
        answersContainer.removeAllViews();

        TLRPC.Poll poll = pollMedia.poll;
        quiz = poll.quiz;
        multipleChoice = poll.multiple_choice;
        closed = poll.closed;
        hideResults = poll.hide_results_until_close;
        revotingDisabled = poll.revoting_disabled;

        voted = false;
        if (pollMedia.results != null && pollMedia.results.results != null) {
            for (TLRPC.PollAnswerVoters answerResult : pollMedia.results.results) {
                if (answerResult.chosen) {
                    voted = true;
                    break;
                }
            }
        }

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

        questionView.setText(poll.question.text);

        if (pollMedia.attached_media != null) {
            setupCoverMedia(pollMedia.attached_media);
        } else {
            coverContainer.setVisibility(GONE);
        }

        List<TLRPC.PollAnswer> answersList = new ArrayList<>(poll.answers);
        if (poll.shuffle_answers && !voted && !closed) {
            Collections.shuffle(answersList, Utilities.random);
        }

        for (TLRPC.PollAnswer answer : answersList) {
            AnswerRow row = createAnswerRow(answer);
            answerRows.add(row);
            answersContainer.addView(row.container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));
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

    private void setupCoverMedia(TLRPC.MessageMedia media) {
        int displayWidth = AndroidUtilities.displaySize.x - dp(32);
        int height = dp(200);

        if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null) {
            TLRPC.PhotoSize best = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, displayWidth);
            if (best != null && best.w > 0) {
                height = Math.max(dp(150), Math.min(dp(400), (int) (displayWidth * ((float) best.h / best.w))));
            }
            coverImageView.setImage(ImageLocation.getForPhoto(best, media.photo), displayWidth + "_" + height, null, null, 0);
            coverContainer.setVisibility(VISIBLE);
            coverVideoOverlay.setVisibility(GONE);
        } else if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            TLRPC.Document doc = media.document;
            TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, displayWidth);
            int videoW = 0, videoH = 0;
            double duration = 0;

            for (TLRPC.DocumentAttribute attr : doc.attributes) {
                if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                    videoW = attr.w;
                    videoH = attr.h;
                    duration = attr.duration;
                }
            }

            if (videoW > 0 && videoH > 0) {
                height = Math.max(dp(150), Math.min(dp(400), (int) (displayWidth * ((float) videoH / videoW))));
            }

            if (thumb != null) {
                coverImageView.setImage(ImageLocation.getForDocument(thumb, doc), displayWidth + "_" + height, null, null, 0);
            }

            if (duration > 0) {
                int d = (int) duration;
                coverVideoOverlay.setText(String.format(Locale.US, "▶ %d:%02d", d / 60, d % 60));
                coverVideoOverlay.setVisibility(VISIBLE);
            } else {
                coverVideoOverlay.setVisibility(GONE);
            }

            coverContainer.setVisibility(VISIBLE);
        } else {
            coverContainer.setVisibility(GONE);
        }

        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) coverContainer.getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            coverContainer.setLayoutParams(lp);
        }
    }

    private void setupAnswerMedia(BackupImageView imageView, TLRPC.MessageMedia media) {
        if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null) {
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, 80);
            if (size != null) {
                imageView.setImage(ImageLocation.getForPhoto(size, media.photo), "36_36", null, null, 0);
                imageView.setVisibility(VISIBLE);
            }
        } else if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(media.document.thumbs, 80);
            if (size != null) {
                imageView.setImage(ImageLocation.getForDocument(size, media.document), "36_36", null, null, 0);
                imageView.setVisibility(VISIBLE);
            }
        } else if (media instanceof TLRPC.TL_messageMediaGeo || media instanceof TLRPC.TL_messageMediaVenue) {
            if (media.geo != null) {
                WebFile webFile = WebFile.createWithGeoPoint(media.geo, 80, 80, 15, Math.min(2, (int) Math.ceil(AndroidUtilities.density)));
                imageView.setImage(ImageLocation.getForWebFile(webFile), "36_36", null, null, 0);
                imageView.setVisibility(VISIBLE);
            }
        }
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
        row.textView.setText(answer.text.text);
        content.addView(row.textView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        row.answerImage = new BackupImageView(context);
        row.answerImage.setRoundRadius(dp(6));
        row.answerImage.setVisibility(GONE);
        content.addView(row.answerImage, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

        if (answer.media != null) {
            setupAnswerMedia(row.answerImage, answer.media);
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
        if (voted || closed || voteSending) return;

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
                boolean selected = isOptionSelected(r.option);
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
        if (message == null || message.peer_id == null) return;

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
                            pollMedia.results = pollUpdate.results;
                        }
                        if (pollUpdate.poll != null) {
                            pollMedia.poll = pollUpdate.poll;
                            closed = pollMedia.poll.closed;
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
        boolean showResults = (voted || closed) && !(hideResults && !closed);

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

                if (pollMedia.results != null && pollMedia.results.results != null) {
                    for (TLRPC.PollAnswerVoters answerResult : pollMedia.results.results) {
                        if (Arrays.equals(answerResult.option, row.option)) {
                            voters = (answerResult.flags & 4) != 0 ? answerResult.voters : 0;
                            chosen = answerResult.chosen;
                            correct = answerResult.correct;
                            break;
                        }
                    }
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

                boolean selected = isOptionSelected(row.option);
                row.indicator.setState(selected ? IndicatorView.STATE_SELECTED : IndicatorView.STATE_EMPTY);
            }
        }

        if (totalVoters > 0) {
            totalVotersView.setText(LocaleController.formatPluralString(
                    "Vote", totalVoters));
        } else {
            totalVotersView.setText(LocaleController.getString(R.string.FeedPollNoVotesYet));
        }

        if (voted && !quiz && !closed && !revotingDisabled) {
            retractButton.setVisibility(VISIBLE);
        } else {
            retractButton.setVisibility(GONE);
        }
    }

    private void updateExplanation() {
        if (!quiz || !voted) {
            explanationContainer.setVisibility(GONE);
            return;
        }
        try {
            String solution = pollMedia.results != null ? pollMedia.results.solution : null;
            if (solution != null && !solution.isEmpty()) {
                explanationText.setText(solution);
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
