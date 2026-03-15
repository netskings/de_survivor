package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TranscribeButton;

@SuppressLint("ViewConstructor")
public class FeedVoiceView extends LinearLayout implements NotificationCenter.NotificationCenterDelegate {

    private final int currentAccount;
    private final FeedPlayPauseButton playButton;
    private final FeedWaveformView waveformView;
    private final TextView labelView;
    private final TextView durationView;

    private final TextView transcribeBtn;
    private final TextView transcriptionTextView;

    private MessageObject currentMessage;
    private int totalDuration;
    private boolean isVoiceMessage = false;
    private boolean transcriptionLoading = false;

    private final int accentColor;

    public FeedVoiceView(Context context, int account, Theme.ResourcesProvider resourceProvider) {
        super(context);
        this.currentAccount = account;

        accentColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2, resourceProvider);
        int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider);
        int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourceProvider);

        setOrientation(VERTICAL);
        setPadding(0, dp(8), 0, dp(4));

        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        playButton = new FeedPlayPauseButton(context, accentColor);
        playButton.setOnClickListener(v -> togglePlayback());
        topRow.addView(playButton, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL));

        LinearLayout middle = new LinearLayout(context);
        middle.setOrientation(VERTICAL);
        middle.setPadding(dp(10), 0, 0, 0);

        labelView = new TextView(context);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        labelView.setTextColor(textColor);
        labelView.setTypeface(AndroidUtilities.bold());
        labelView.setMaxLines(1);
        labelView.setEllipsize(TextUtils.TruncateAt.END);
        middle.addView(labelView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        waveformView = new FeedWaveformView(context, accentColor);
        middle.addView(waveformView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 24, 0, 2, 0, 0));

        topRow.addView(middle,
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        durationView = new TextView(context);
        durationView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        durationView.setTextColor(grayColor);
        durationView.setPadding(dp(8), 0, 0, 0);
        topRow.addView(durationView,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL));

        transcribeBtn = new androidx.appcompat.widget.AppCompatTextView(context) {
            private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF r = new RectF();
            {
                bgPaint.setColor(ColorUtils.setAlphaComponent(accentColor, 0x28));
            }
            @Override
            protected void onDraw(Canvas canvas) {
                r.set(0, 0, getWidth(), getHeight());
                canvas.drawRoundRect(r, dp(8), dp(8), bgPaint);
                super.onDraw(canvas);
            }
        };
        transcribeBtn.setText("Aa");
        transcribeBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        transcribeBtn.setTextColor(accentColor);
        transcribeBtn.setTypeface(AndroidUtilities.bold());
        transcribeBtn.setGravity(Gravity.CENTER);
        transcribeBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        transcribeBtn.setOnClickListener(v -> onTranscribeClick());
        transcribeBtn.setVisibility(GONE);
        topRow.addView(transcribeBtn,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 28,
                        Gravity.CENTER_VERTICAL, 6, 0, 0, 0));

        addView(topRow,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        transcriptionTextView = new TextView(context);
        transcriptionTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        transcriptionTextView.setTextColor(textColor);
        transcriptionTextView.setLineSpacing(dp(2), 1f);
        transcriptionTextView.setPadding(dp(46), dp(4), 0, dp(4));
        transcriptionTextView.setVisibility(GONE);
        addView(transcriptionTextView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        waveformView.setSeekListener(new FeedWaveformView.SeekListener() {
            @Override public void onSeekStart() {}

            @Override
            public void onSeek(float progress) {
                updateDurationText(progress);
            }

            @Override
            public void onSeekEnd(float progress) {
                if (currentMessage == null) return;
                MediaController mc = MediaController.getInstance();
                if (mc.isPlayingMessage(currentMessage)) {
                    mc.seekToProgress(currentMessage, progress);
                } else {
                    mc.playMessage(currentMessage);
                    AndroidUtilities.runOnUIThread(
                            () -> mc.seekToProgress(currentMessage, progress), 300);
                }
                updatePlayButton();
            }
        });
    }

    @SuppressLint("SetTextI18n")
    public void setData(FeedController.FeedItem item) {
        currentMessage = null;
        totalDuration = 0;
        isVoiceMessage = false;
        transcriptionLoading = false;

        if (item == null) {
            setVisibility(GONE);
            return;
        }

        MessageObject voiceMsg = null;
        boolean isVoice = false;
        String title = null;
        String performer = null;
        int duration = 0;
        byte[] waveform = null;

        for (MessageObject msg : item.messages) {
            TLRPC.MessageMedia media = msg.messageOwner.media;
            if (!(media instanceof TLRPC.TL_messageMediaDocument) || media.document == null)
                continue;
            if (FeedUtils.isRoundVideo(media.document)) continue;
            if (FeedUtils.isVideo(media.document) && !FeedUtils.isVoiceOrAudio(media.document))
                continue;

            for (TLRPC.DocumentAttribute attr : media.document.attributes) {
                if (attr instanceof TLRPC.TL_documentAttributeAudio) {
                    TLRPC.TL_documentAttributeAudio audio =
                            (TLRPC.TL_documentAttributeAudio) attr;
                    voiceMsg  = msg;
                    isVoice   = audio.voice;
                    duration  = (int) audio.duration;
                    title     = audio.title;
                    performer = audio.performer;
                    waveform  = audio.waveform;
                    break;
                }
            }
            if (voiceMsg != null) break;
        }

        if (voiceMsg == null) {
            setVisibility(GONE);
            return;
        }

        currentMessage  = voiceMsg;
        totalDuration   = duration;
        isVoiceMessage  = isVoice;

        if (isVoice) {
            labelView.setText("Voice message");
            waveformView.setWaveform(waveform);
        } else {
            String label = (title != null && !title.isEmpty()) ? title : "Audio";
            if (performer != null && !performer.isEmpty()) label += " — " + performer;
            labelView.setText("🎵 " + label);
            waveformView.setWaveform(null);
        }
        waveformView.setVisibility(VISIBLE);
        durationView.setText(FeedUtils.formatVoiceDuration(duration));

        MediaController mc = MediaController.getInstance();
        if (mc.isPlayingMessage(currentMessage)) {
            MessageObject playing = mc.getPlayingMessageObject();
            if (playing != null) {
                waveformView.setProgress(playing.audioProgress);
                updateDurationText(playing.audioProgress);
            }
        }
        updatePlayButton();

        transcribeBtn.setVisibility(isVoice ? VISIBLE : GONE);
        updateTranscriptionUI();

        setVisibility(VISIBLE);
    }

    public void clear() {
        currentMessage = null;
        totalDuration = 0;
        isVoiceMessage = false;
        transcriptionLoading = false;
        transcribeBtn.setVisibility(GONE);
        transcriptionTextView.setVisibility(GONE);
        setVisibility(GONE);
    }

    public MessageObject getCurrentMessage() {
        return currentMessage;
    }

    @SuppressLint("SetTextI18n")
    private void updateTranscriptionUI() {
        if (currentMessage == null || !isVoiceMessage) {
            transcriptionTextView.setVisibility(GONE);
            transcribeBtn.setAlpha(1f);
            return;
        }

        TLRPC.Message raw = currentMessage.messageOwner;
        boolean isOpen  = raw.voiceTranscriptionOpen;
        boolean isFinal = raw.voiceTranscriptionFinal;
        String text     = raw.voiceTranscription;

        if (transcriptionLoading || (isOpen && !isFinal)) {
            transcriptionTextView.setText("Transcribing…");
            transcriptionTextView.setVisibility(VISIBLE);
            transcribeBtn.setAlpha(0.5f);

        } else if (isOpen && text != null && isFinal) {
            CharSequence display = currentMessage.getVoiceTranscription();
            transcriptionTextView.setText(display != null ? display : text);
            transcriptionTextView.setVisibility(VISIBLE);
            transcribeBtn.setAlpha(1f);

        } else {
            transcriptionTextView.setVisibility(GONE);
            transcribeBtn.setAlpha(1f);
        }
    }

    private void onTranscribeClick() {
        if (currentMessage == null || currentMessage.messageOwner == null) return;
        TLRPC.Message raw = currentMessage.messageOwner;

        if (raw.voiceTranscriptionOpen && raw.voiceTranscription != null && raw.voiceTranscriptionFinal) {
            raw.voiceTranscriptionOpen = false;
            saveTranscriptionOpenState();
            updateTranscriptionUI();
            return;
        }

        if (!raw.voiceTranscriptionOpen && raw.voiceTranscription != null && raw.voiceTranscriptionFinal) {
            raw.voiceTranscriptionOpen = true;
            saveTranscriptionOpenState();
            updateTranscriptionUI();
            return;
        }

        if (transcriptionLoading) return;

        boolean premium  = UserConfig.getInstance(currentAccount).isPremium();
        boolean canTrial = TranscribeButton.canTranscribeTrial(currentMessage);
        boolean hasCached = !TextUtils.isEmpty(raw.voiceTranscription);

        if (premium || canTrial || hasCached) {
            requestTranscription();
        } else {
            android.widget.Toast.makeText(getContext(),
                    "Telegram Premium is required for voice transcription",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void requestTranscription() {
        if (currentMessage == null || currentMessage.messageOwner == null
                || !currentMessage.isSent()) return;

        final int account        = currentMessage.currentAccount;
        final long startTime     = SystemClock.elapsedRealtime();
        final long minDuration   = 350;
        final MessageObject target = currentMessage;

        TLRPC.InputPeer peer =
                MessagesController.getInstance(account).getInputPeer(target.messageOwner.peer_id);
        final long dialogId  = target.getDialogId();
        final int  messageId = target.getId();

        TLRPC.TL_messages_transcribeAudio req = new TLRPC.TL_messages_transcribeAudio();
        req.peer   = peer;
        req.msg_id = messageId;

        transcriptionLoading = true;
        updateTranscriptionUI();

        int flags = 0;
        if (!UserConfig.getInstance(account).isPremium()) {
            flags |= ConnectionsManager.RequestFlagDoNotWaitFloodWait;
        }

        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            String text;
            long   id      = 0;
            boolean isFinal = false;

            if (res instanceof TLRPC.TL_messages_transcribedAudio) {
                TLRPC.TL_messages_transcribedAudio r = (TLRPC.TL_messages_transcribedAudio) res;
                text    = r.text;
                id      = r.transcription_id;
                isFinal = !r.pending;

                if (TextUtils.isEmpty(text)) {
                    text = isFinal ? "" : null;
                }

                if ((r.flags & 2) != 0) {
                    MessagesController.getInstance(account)
                            .updateTranscribeAudioTrialCurrentNumber(r.trial_remains_num);
                    MessagesController.getInstance(account)
                            .updateTranscribeAudioTrialCooldownUntil(r.trial_remains_until_date);
                }

                target.messageOwner.voiceTranscriptionId = id;

                if (!isFinal) {
                    TranscribeButton.registerPendingTranscription(id, target);
                }

            } else {
                text    = "";
                isFinal = true;

                if (err != null && err.text != null && err.text.startsWith("FLOOD_WAIT_")) {
                    MessagesController.getInstance(account)
                            .updateTranscribeAudioTrialCurrentNumber(0);
                    MessagesController.getInstance(account)
                            .updateTranscribeAudioTrialCooldownUntil(
                                    ConnectionsManager.getInstance(account).getCurrentTime()
                                            + Utilities.parseInt(err.text));
                    AndroidUtilities.runOnUIThread(() -> {
                        transcriptionLoading = false;
                        updateTranscriptionUI();
                        android.widget.Toast.makeText(getContext(),
                                "Please wait before trying again",
                                android.widget.Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
            }

            final String  finalText  = text;
            final long    finalId    = id;
            final boolean finalFinal = isFinal;
            final long    elapsed    = SystemClock.elapsedRealtime() - startTime;

            target.messageOwner.voiceTranscriptionOpen  = true;
            target.messageOwner.voiceTranscriptionFinal = isFinal;
            if (finalText != null) {
                target.messageOwner.voiceTranscription = finalText;
            }

            MessagesStorage.getInstance(account).updateMessageVoiceTranscription(
                    dialogId, messageId, finalText, target.messageOwner);

            if (finalFinal) {
                AndroidUtilities.runOnUIThread(() -> {
                    transcriptionLoading = false;
                    if (currentMessage == target) {
                        updateTranscriptionUI();
                    }
                }, Math.max(0, minDuration - elapsed));
            } else {
                AndroidUtilities.runOnUIThread(() -> {
                    if (currentMessage == target) {
                        updateTranscriptionUI();
                    }
                });
            }
        }, flags);
    }

    private void saveTranscriptionOpenState() {
        if (currentMessage == null) return;
        MessagesStorage.getInstance(currentAccount).updateMessageVoiceTranscriptionOpen(
                currentMessage.getDialogId(),
                currentMessage.getId(),
                currentMessage.messageOwner);
    }

    private void togglePlayback() {
        if (currentMessage == null) return;
        MediaController mc = MediaController.getInstance();
        if (mc.isPlayingMessage(currentMessage)) {
            if (mc.isMessagePaused()) mc.playMessage(currentMessage);
            else mc.pauseMessage(currentMessage);
        } else {
            mc.playMessage(currentMessage);
        }
        updatePlayButton();
    }

    private void updatePlayButton() {
        if (currentMessage == null) return;
        MediaController mc = MediaController.getInstance();
        boolean playing = mc.isPlayingMessage(currentMessage) && !mc.isMessagePaused();
        playButton.setPlaying(playing);
    }

    @SuppressLint("SetTextI18n")
    private void updateDurationText(float progress) {
        if (totalDuration <= 0) return;
        int current = (int) (progress * totalDuration);
        durationView.setText(FeedUtils.formatVoiceDuration(current)
                + " / " + FeedUtils.formatVoiceDuration(totalDuration));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter nc = NotificationCenter.getInstance(currentAccount);
        nc.addObserver(this, NotificationCenter.messagePlayingDidReset);
        nc.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        nc.addObserver(this, NotificationCenter.voiceTranscriptionUpdate);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter nc = NotificationCenter.getInstance(currentAccount);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidReset);
        nc.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        nc.removeObserver(this, NotificationCenter.voiceTranscriptionUpdate);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount || currentMessage == null) return;

        if (id == NotificationCenter.messagePlayingDidReset) {
            updatePlayButton();
            waveformView.setProgress(0);
            updateDurationText(0);

        } else if (id == NotificationCenter.messagePlayingPlayStateChanged) {
            updatePlayButton();

        } else if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            MediaController mc = MediaController.getInstance();
            if (mc.isPlayingMessage(currentMessage)) {
                MessageObject playing = mc.getPlayingMessageObject();
                if (playing != null) {
                    waveformView.setProgress(playing.audioProgress);
                    updateDurationText(playing.audioProgress);
                }
            }

        } else if (id == NotificationCenter.voiceTranscriptionUpdate) {
            handleTranscriptionUpdate(args);
        }
    }

    private void handleTranscriptionUpdate(Object... args) {
        if (args.length == 0 || !(args[0] instanceof MessageObject)) return;
        MessageObject updated = (MessageObject) args[0];

        if (!isSameMessage(updated, currentMessage)) return;

        if (updated != currentMessage && updated.messageOwner != null) {
            TLRPC.Message src = updated.messageOwner;
            TLRPC.Message dst = currentMessage.messageOwner;
            dst.voiceTranscription      = src.voiceTranscription;
            dst.voiceTranscriptionOpen  = src.voiceTranscriptionOpen;
            dst.voiceTranscriptionFinal = src.voiceTranscriptionFinal;
            dst.voiceTranscriptionId    = src.voiceTranscriptionId;
        }

        transcriptionLoading = false;
        updateTranscriptionUI();
    }

    private static boolean isSameMessage(MessageObject a, MessageObject b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.getId() == b.getId() && a.getDialogId() == b.getDialogId();
    }
}