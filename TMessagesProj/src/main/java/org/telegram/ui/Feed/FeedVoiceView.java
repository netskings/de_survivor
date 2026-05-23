package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TranscribeButton;

import java.io.File;

@SuppressLint("ViewConstructor")
public class FeedVoiceView extends LinearLayout implements NotificationCenter.NotificationCenterDelegate {

    private final int currentAccount;
    private final int accentColor;

    private final LinearLayout voiceLayout;
    private final FeedPlayPauseButton voicePlayButton;
    private final FeedWaveformView waveformView;
    private final TextView voiceLabelView;
    private final TextView voiceDurationView;
    private final TextView transcribeBtn;
    private final TextView transcriptionTextView;

    private final LinearLayout musicLayout;
    private final BackupImageView albumArtView;
    private final TextView musicNotePlaceholder;
    private final TextView musicTitleView;
    private final TextView musicArtistView;
    private final FeedPlayPauseButton musicPlayButton;
    private final SeekBar musicSeekBar;
    private final TextView musicTimeView;

    private MessageObject currentMessage;
    private int totalDuration;
    private boolean isVoiceMessage = false;
    private boolean isSeeking = false;
    private boolean transcriptionLoading = false;

    private TLRPC.Document currentDocument;
    private String currentFileName;
    private boolean fileLoaded;
    private boolean fileLoading;
    private float fileProgress;
    private boolean pendingPlayAfterDownload;
    private Float pendingSeekProgress;

    @SuppressLint("SetTextI18n")
    public FeedVoiceView(Context context, int account, Theme.ResourcesProvider resourceProvider) {
        super(context);
        this.currentAccount = account;
        setOrientation(VERTICAL);
        setPadding(0, dp(8), 0, dp(4));

        accentColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2, resourceProvider);
        int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider);
        int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourceProvider);

        voiceLayout = new LinearLayout(context);
        voiceLayout.setOrientation(VERTICAL);
        voiceLayout.setVisibility(GONE);

        LinearLayout voiceTopRow = new LinearLayout(context);
        voiceTopRow.setOrientation(HORIZONTAL);
        voiceTopRow.setGravity(Gravity.CENTER_VERTICAL);

        voicePlayButton = new FeedPlayPauseButton(context, accentColor);
        voicePlayButton.setOnClickListener(v -> togglePlayback());
        voiceTopRow.addView(voicePlayButton, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL));

        LinearLayout voiceMiddle = new LinearLayout(context);
        voiceMiddle.setOrientation(VERTICAL);
        voiceMiddle.setPadding(dp(10), 0, 0, 0);

        voiceLabelView = new TextView(context);
        voiceLabelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        voiceLabelView.setTextColor(textColor);
        voiceLabelView.setTypeface(AndroidUtilities.bold());
        voiceLabelView.setMaxLines(1);
        voiceLabelView.setEllipsize(TextUtils.TruncateAt.END);
        voiceMiddle.addView(voiceLabelView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        waveformView = new FeedWaveformView(context, accentColor);
        voiceMiddle.addView(waveformView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 24, 0, 2, 0, 0));

        voiceTopRow.addView(voiceMiddle,
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        voiceDurationView = new TextView(context);
        voiceDurationView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        voiceDurationView.setTextColor(grayColor);
        voiceDurationView.setPadding(dp(8), 0, 0, 0);
        voiceTopRow.addView(voiceDurationView,
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
        voiceTopRow.addView(transcribeBtn,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 28,
                        Gravity.CENTER_VERTICAL, 6, 0, 0, 0));

        voiceLayout.addView(voiceTopRow,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        transcriptionTextView = new TextView(context);
        transcriptionTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        transcriptionTextView.setTextColor(textColor);
        transcriptionTextView.setLineSpacing(dp(2), 1f);
        transcriptionTextView.setPadding(dp(46), dp(4), 0, dp(4));
        transcriptionTextView.setVisibility(GONE);
        voiceLayout.addView(transcriptionTextView,
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
                if (!ensureFileReadyForPlayback(progress)) return;
                startPlaybackFromProgress(progress);
            }
        });

        addView(voiceLayout,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        musicLayout = new LinearLayout(context);
        musicLayout.setOrientation(VERTICAL);
        musicLayout.setVisibility(GONE);
        musicLayout.setClipChildren(false);
        musicLayout.setClipToPadding(false);

        LinearLayout musicTopRow = new LinearLayout(context);
        musicTopRow.setOrientation(HORIZONTAL);
        musicTopRow.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout artContainer = new FrameLayout(context);
        GradientDrawable artBg = new GradientDrawable();
        artBg.setColor(ColorUtils.setAlphaComponent(accentColor, 0x18));
        artBg.setCornerRadius(dp(10));
        artContainer.setBackground(artBg);

        musicNotePlaceholder = new TextView(context);
        musicNotePlaceholder.setText("🎵");
        musicNotePlaceholder.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        musicNotePlaceholder.setGravity(Gravity.CENTER);
        artContainer.addView(musicNotePlaceholder,
                LayoutHelper.createFrame(56, 56, Gravity.CENTER));

        albumArtView = new BackupImageView(context);
        albumArtView.setRoundRadius(dp(10));
        artContainer.addView(albumArtView,
                LayoutHelper.createFrame(56, 56, Gravity.CENTER));

        musicTopRow.addView(artContainer,
                LayoutHelper.createLinear(56, 56, Gravity.CENTER_VERTICAL));

        // Info column
        LinearLayout infoCol = new LinearLayout(context);
        infoCol.setOrientation(VERTICAL);
        infoCol.setPadding(dp(12), 0, dp(8), 0);
        infoCol.setGravity(Gravity.CENTER_VERTICAL);

        musicTitleView = new TextView(context);
        musicTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        musicTitleView.setTextColor(textColor);
        musicTitleView.setTypeface(AndroidUtilities.bold());
        musicTitleView.setMaxLines(1);
        musicTitleView.setEllipsize(TextUtils.TruncateAt.END);
        infoCol.addView(musicTitleView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        musicArtistView = new TextView(context);
        musicArtistView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        musicArtistView.setTextColor(grayColor);
        musicArtistView.setMaxLines(1);
        musicArtistView.setEllipsize(TextUtils.TruncateAt.END);
        musicArtistView.setPadding(0, dp(2), 0, 0);
        infoCol.addView(musicArtistView,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        musicTopRow.addView(infoCol,
                LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f,
                        Gravity.CENTER_VERTICAL));

        musicPlayButton = new FeedPlayPauseButton(context, accentColor);
        musicPlayButton.setOnClickListener(v -> togglePlayback());
        musicTopRow.addView(musicPlayButton,
                LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));

        musicLayout.addView(musicTopRow,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

// --- Seek row ---
        LinearLayout seekRow = new LinearLayout(context);
        seekRow.setOrientation(HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        seekRow.setPadding(dp(2), dp(4), dp(2), 0);
        seekRow.setClipChildren(false);
        seekRow.setClipToPadding(false);

        musicSeekBar = new SeekBar(context);
        musicSeekBar.setMax(1000);
        musicSeekBar.setPadding(dp(8), 0, dp(8), 0);
        musicSeekBar.setProgressTintList(ColorStateList.valueOf(accentColor));
        musicSeekBar.setThumbTintList(ColorStateList.valueOf(accentColor));
        musicSeekBar.setProgressBackgroundTintList(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(accentColor, 0x40)));
        musicSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    updateMusicTimeText((float) progress / 1000f);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                isSeeking = true;
            }
            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                isSeeking = false;
                float progress = (float) sb.getProgress() / 1000f;
                if (currentMessage == null) return;
                if (!ensureFileReadyForPlayback(progress)) return;
                startPlaybackFromProgress(progress);
            }
        });
        seekRow.addView(musicSeekBar,
                LayoutHelper.createLinear(0, 32, 1f, Gravity.CENTER_VERTICAL));

        musicTimeView = new TextView(context);
        musicTimeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        musicTimeView.setTextColor(grayColor);
        musicTimeView.setPadding(dp(8), 0, 0, 0);
        seekRow.addView(musicTimeView,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL));

        musicLayout.addView(seekRow,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addView(musicLayout,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    @SuppressLint("SetTextI18n")
    public void setData(FeedController.FeedItem item) {
        currentMessage = null;
        totalDuration = 0;
        isVoiceMessage = false;
        isSeeking = false;
        transcriptionLoading = false;

        currentDocument = null;
        currentFileName = null;
        fileLoaded = false;
        fileLoading = false;
        fileProgress = 0f;
        pendingPlayAfterDownload = false;
        pendingSeekProgress = null;

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
        TLRPC.Document audioDoc = null;

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
                    audioDoc  = media.document;
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

        currentDocument = audioDoc;
        currentFileName = audioDoc != null ? FileLoader.getAttachFileName(audioDoc) : null;
        refreshFileState();

        if (isVoice) {
            setupVoiceLayout(waveform, duration);
        } else {
            setupMusicLayout(title, performer, audioDoc);
        }

        MediaController mc = MediaController.getInstance();
        if (mc.isPlayingMessage(currentMessage)) {
            MessageObject playing = mc.getPlayingMessageObject();
            if (playing != null) {
                float progress = playing.audioProgress;
                if (isVoice) {
                    waveformView.setProgress(progress);
                } else {
                    if (!isSeeking) musicSeekBar.setProgress((int) (progress * 1000));
                }
                updateDurationText(progress);
            }
        }
        updatePlayButton();
        setVisibility(VISIBLE);
    }

    @SuppressLint("SetTextI18n")
    private void setupVoiceLayout(byte[] waveform, int duration) {
        voiceLayout.setVisibility(VISIBLE);
        musicLayout.setVisibility(GONE);

        voiceLabelView.setText(LocaleController.getString(R.string.FeedVoiceMessage));
        waveformView.setWaveform(waveform);
        waveformView.setProgress(0);
        waveformView.setVisibility(VISIBLE);
        voiceDurationView.setText(FeedUtils.formatVoiceDuration(duration));

        transcribeBtn.setVisibility(VISIBLE);
        updateTranscriptionUI();
    }

    @SuppressLint("SetTextI18n")
    private void setupMusicLayout(String title, String performer,
                                  TLRPC.Document doc) {
        voiceLayout.setVisibility(GONE);
        musicLayout.setVisibility(VISIBLE);

        musicTitleView.setText(title != null && !title.isEmpty()
                ? title : LocaleController.getString(R.string.FeedAudio));

        if (performer != null && !performer.isEmpty()) {
            musicArtistView.setText(performer);
            musicArtistView.setVisibility(VISIBLE);
        } else {
            musicArtistView.setVisibility(GONE);
        }

        loadAlbumArt(doc);

        musicSeekBar.setProgress(0);

        updateMusicTimeText(0);
    }

    private void loadAlbumArt(TLRPC.Document doc) {
        if (doc != null && doc.thumbs != null && !doc.thumbs.isEmpty()) {
            TLRPC.PhotoSize thumb = FeedMediaHelper.bestSize(doc.thumbs);
            if (thumb != null) {
                albumArtView.setImage(
                        ImageLocation.getForDocument(thumb, doc),
                        "56_56", null, null, 0, doc);
                musicNotePlaceholder.setVisibility(GONE);
                albumArtView.setVisibility(VISIBLE);
                return;
            }
        }
        albumArtView.setImageDrawable(null);
        albumArtView.setVisibility(GONE);
        musicNotePlaceholder.setVisibility(VISIBLE);
    }

    public void clear() {
        currentMessage = null;
        totalDuration = 0;
        isVoiceMessage = false;
        isSeeking = false;
        transcriptionLoading = false;
        currentDocument = null;
        currentFileName = null;
        fileLoaded = false;
        fileLoading = false;
        fileProgress = 0f;
        pendingPlayAfterDownload = false;
        pendingSeekProgress = null;

        voicePlayButton.setState(FeedPlayPauseButton.STATE_PLAY);
        voicePlayButton.setLoadingProgress(0f);
        musicPlayButton.setState(FeedPlayPauseButton.STATE_PLAY);
        musicPlayButton.setLoadingProgress(0f);
        voiceLayout.setVisibility(GONE);
        musicLayout.setVisibility(GONE);
        transcribeBtn.setVisibility(GONE);
        transcriptionTextView.setVisibility(GONE);
        setVisibility(GONE);
    }

    public MessageObject getCurrentMessage() {
        return currentMessage;
    }

    private void togglePlayback() {
        if (currentMessage == null) return;

        if (!ensureFileReadyForPlayback(null)) {
            return;
        }

        MediaController mc = MediaController.getInstance();
        if (mc.isPlayingMessage(currentMessage)) {
            if (mc.isMessagePaused()) {
                mc.playMessage(currentMessage);
            } else {
                mc.pauseMessage(currentMessage);
            }
        } else {
            mc.playMessage(currentMessage);
        }
        updatePlayButton();
    }

    private void updatePlayButton() {
        if (currentMessage == null) return;

        if (!fileLoaded) {
            int state = fileLoading
                    ? FeedPlayPauseButton.STATE_LOADING
                    : FeedPlayPauseButton.STATE_DOWNLOAD;

            if (isVoiceMessage) {
                voicePlayButton.setState(state);
                voicePlayButton.setLoadingProgress(fileProgress);
            } else {
                musicPlayButton.setState(state);
                musicPlayButton.setLoadingProgress(fileProgress);
            }
            return;
        }

        MediaController mc = MediaController.getInstance();
        boolean playing = mc.isPlayingMessage(currentMessage) && !mc.isMessagePaused();

        if (isVoiceMessage) {
            voicePlayButton.setPlaying(playing);
        } else {
            musicPlayButton.setPlaying(playing);
        }
    }

    @SuppressLint("SetTextI18n")
    private void updateDurationText(float progress) {
        if (totalDuration <= 0) return;
        int current = (int) (progress * totalDuration);
        if (isVoiceMessage) {
            voiceDurationView.setText(FeedUtils.formatVoiceDuration(current)
                    + " / " + FeedUtils.formatVoiceDuration(totalDuration));
        } else {
            updateMusicTimeText(progress);
        }
    }

    @SuppressLint("SetTextI18n")
    private void updateMusicTimeText(float progress) {
        if (totalDuration <= 0) return;
        int current = (int) (progress * totalDuration);
        musicTimeView.setText(FeedUtils.formatVoiceDuration(current)
                + " / " + FeedUtils.formatVoiceDuration(totalDuration));
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
            transcriptionTextView.setText(LocaleController.getString(R.string.FeedTranscribing));
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

        if (raw.voiceTranscriptionOpen && raw.voiceTranscription != null
                && raw.voiceTranscriptionFinal) {
            raw.voiceTranscriptionOpen = false;
            saveTranscriptionOpenState();
            updateTranscriptionUI();
            return;
        }

        if (!raw.voiceTranscriptionOpen && raw.voiceTranscription != null
                && raw.voiceTranscriptionFinal) {
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
                    LocaleController.getString(R.string.FeedVoiceTranscriptionPremiumRequired),
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
            long   id;
            boolean isFinal;

            if (res instanceof TLRPC.TL_messages_transcribedAudio) {
                TLRPC.TL_messages_transcribedAudio r = (TLRPC.TL_messages_transcribedAudio) res;
                text    = r.text;
                id      = r.transcription_id;
                isFinal = !r.pending;

                if (TextUtils.isEmpty(text)) text = isFinal ? "" : null;

                if ((r.flags & 2) != 0) {
                    MessagesController.getInstance(account)
                            .updateTranscribeAudioTrialCurrentNumber(r.trial_remains_num);
                    MessagesController.getInstance(account)
                            .updateTranscribeAudioTrialCooldownUntil(r.trial_remains_until_date);
                }

                target.messageOwner.voiceTranscriptionId = id;
                if (!isFinal) TranscribeButton.registerPendingTranscription(id, target);
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
                                LocaleController.getString(R.string.FeedPleaseWaitTryAgain),
                                android.widget.Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
            }

            final String  finalText  = text;
            final boolean finalFinal = isFinal;
            final long    elapsed    = SystemClock.elapsedRealtime() - startTime;

            target.messageOwner.voiceTranscriptionOpen  = true;
            target.messageOwner.voiceTranscriptionFinal = isFinal;
            if (finalText != null) target.messageOwner.voiceTranscription = finalText;

            MessagesStorage.getInstance(account).updateMessageVoiceTranscription(
                    dialogId, messageId, finalText, target.messageOwner);

            if (finalFinal) {
                AndroidUtilities.runOnUIThread(() -> {
                    transcriptionLoading = false;
                    if (currentMessage == target) updateTranscriptionUI();
                }, Math.max(0, minDuration - elapsed));
            } else {
                AndroidUtilities.runOnUIThread(() -> {
                    if (currentMessage == target) updateTranscriptionUI();
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

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter nc = NotificationCenter.getInstance(currentAccount);
        nc.addObserver(this, NotificationCenter.messagePlayingDidReset);
        nc.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        nc.addObserver(this, NotificationCenter.voiceTranscriptionUpdate);
        nc.addObserver(this, NotificationCenter.fileLoaded);
        nc.addObserver(this, NotificationCenter.fileLoadProgressChanged);
        nc.addObserver(this, NotificationCenter.fileLoadFailed);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter nc = NotificationCenter.getInstance(currentAccount);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidReset);
        nc.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        nc.removeObserver(this, NotificationCenter.voiceTranscriptionUpdate);
        nc.removeObserver(this, NotificationCenter.fileLoaded);
        nc.removeObserver(this, NotificationCenter.fileLoadProgressChanged);
        nc.removeObserver(this, NotificationCenter.fileLoadFailed);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount || currentMessage == null) return;

        if (id == NotificationCenter.fileLoaded) {
            if (isCurrentFileEvent(args)) {
                fileLoaded = true;
                fileLoading = false;
                fileProgress = 1f;
                updatePlayButton();
                maybeStartPendingPlayback();
            }
            return;
        } else if (id == NotificationCenter.fileLoadProgressChanged) {
            if (isCurrentFileEvent(args)) {
                fileLoaded = false;
                fileLoading = true;
                fileProgress = extractFileProgress(args);
                updatePlayButton();
            }
            return;
        } else if (id == NotificationCenter.fileLoadFailed) {
            if (isCurrentFileEvent(args)) {
                fileLoaded = false;
                fileLoading = false;
                fileProgress = 0f;
                pendingPlayAfterDownload = false;
                pendingSeekProgress = null;
                updatePlayButton();
            }
            return;
        }

        if (id == NotificationCenter.messagePlayingDidReset) {
            updatePlayButton();
            if (isVoiceMessage) {
                waveformView.setProgress(0);
            } else {
                if (!isSeeking) musicSeekBar.setProgress(0);
            }
            updateDurationText(0);

        } else if (id == NotificationCenter.messagePlayingPlayStateChanged) {
            updatePlayButton();

        } else if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            MediaController mc = MediaController.getInstance();
            if (mc.isPlayingMessage(currentMessage)) {
                MessageObject playing = mc.getPlayingMessageObject();
                if (playing != null) {
                    float progress = playing.audioProgress;
                    if (isVoiceMessage) {
                        waveformView.setProgress(progress);
                    } else {
                        if (!isSeeking) {
                            musicSeekBar.setProgress((int) (progress * 1000));
                        }
                    }
                    updateDurationText(progress);
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

    private void refreshFileState() {
        if (currentDocument == null) {
            fileLoaded = true;
            fileLoading = false;
            fileProgress = 0f;
            return;
        }

        File file = getDocumentFile(currentDocument);
        if (file != null && file.exists()) {
            fileLoaded = true;
            fileLoading = false;
            fileProgress = 1f;
        } else {
            fileLoaded = false;
            fileLoading = currentFileName != null
                    && FileLoader.getInstance(currentAccount).isLoadingFile(currentFileName);
            if (!fileLoading) {
                fileProgress = 0f;
            }
        }
    }

    private File getDocumentFile(TLRPC.Document doc) {
        if (doc == null) return null;
        FileLoader fl = FileLoader.getInstance(currentAccount);
        File f = fl.getPathToAttach(doc, false);
        if (f != null && f.exists()) return f;
        f = fl.getPathToAttach(doc, true);
        if (f != null && f.exists()) return f;
        return f;
    }

    private void requestCurrentFileDownload(int priority) {
        if (currentDocument == null || currentMessage == null) return;
        FileLoader.getInstance(currentAccount)
                .loadFile(currentDocument, currentMessage, priority, 0);
        fileLoading = true;
        updatePlayButton();
    }

    private boolean ensureFileReadyForPlayback(Float seekProgress) {
        refreshFileState();
        if (fileLoaded) {
            return true;
        }

        pendingPlayAfterDownload = true;
        pendingSeekProgress = seekProgress;
        requestCurrentFileDownload(FileLoader.PRIORITY_HIGH);
        updatePlayButton();
        return false;
    }

    private void startPlaybackFromProgress(Float progress) {
        if (currentMessage == null) return;

        MediaController mc = MediaController.getInstance();
        if (progress == null) {
            mc.playMessage(currentMessage);
        } else if (mc.isPlayingMessage(currentMessage)) {
            mc.seekToProgress(currentMessage, progress);
        } else {
            mc.playMessage(currentMessage);
            AndroidUtilities.runOnUIThread(
                    () -> mc.seekToProgress(currentMessage, progress), 300);
        }
        updatePlayButton();
    }

    private void maybeStartPendingPlayback() {
        if (!pendingPlayAfterDownload || currentMessage == null || !fileLoaded) return;

        Float progress = pendingSeekProgress;
        pendingPlayAfterDownload = false;
        pendingSeekProgress = null;

        startPlaybackFromProgress(progress);
    }

    private boolean isCurrentFileEvent(Object... args) {
        return currentFileName != null
                && args != null
                && args.length > 0
                && currentFileName.equals(args[0]);
    }

    private float extractFileProgress(Object... args) {
        if (args == null || args.length < 2) return 0f;

        if (args[1] instanceof Float) {
            return Math.max(0f, Math.min(1f, (Float) args[1]));
        }

        if (args.length >= 3 && args[1] instanceof Long && args[2] instanceof Long) {
            long loaded = (Long) args[1];
            long total = (Long) args[2];
            if (total > 0) {
                return Math.max(0f, Math.min(1f, (float) loaded / total));
            }
        }

        return 0f;
    }
}
