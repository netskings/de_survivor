package org.telegram.ui.Feed;

import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.VideoPlayer;

import java.io.File;

public class FeedInlineVideoPlayer implements VideoPlayer.VideoPlayerDelegate {

    private TextureView textureView;
    private FeedVideoTimelineView timelineView;
    private VideoPlayer videoPlayer;
    private MessageObject currentMessage;
    private boolean isPlaying = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (videoPlayer != null && isPlaying && timelineView != null) {
                long position = videoPlayer.getCurrentPosition();
                long duration = videoPlayer.getDuration();
                if (duration > 0) {
                    float progress = (float) position / duration;
                    long positionSec = position / 1000;
                    timelineView.setProgress(progress, positionSec);
                }
            }
            if (isPlaying) {
                handler.postDelayed(this, 16);
            }
        }
    };

    private Runnable onFirstFrameListener;
    private Runnable onErrorListener;

    public void setOnFirstFrameListener(Runnable listener) {
        this.onFirstFrameListener = listener;
    }

    public void setOnErrorListener(Runnable listener) {
        this.onErrorListener = listener;
    }

    public FeedInlineVideoPlayer(TextureView textureView, FeedVideoTimelineView timelineView) {
        this.textureView = textureView;
        this.timelineView = timelineView;
    }

    public void play(MessageObject msg, android.view.TextureView textureView, FeedVideoTimelineView timelineView) {
        if (currentMessage != null && currentMessage.getId() == msg.getId()) {
            this.textureView = textureView;
            this.timelineView = timelineView;
            if (videoPlayer != null) {
                videoPlayer.setTextureView(textureView);
            }
            if (!isPlaying) {
                resume();
            }
            return;
        }

        release();
        this.textureView = textureView;
        this.timelineView = timelineView;
        currentMessage = msg;

        TLRPC.Document document = msg.messageOwner.media.document;
        if (document == null) return;

        FileLoader.getInstance(msg.currentAccount).loadFile(document, msg, FileLoader.PRIORITY_NORMAL, 0);

        File file = FileLoader.getInstance(msg.currentAccount).getPathToAttach(document, true);
        Uri uri;
        if (file != null && file.exists()) {
            uri = Uri.fromFile(file);
        } else {
            try {
                uri = VideoPlayer.VideoUri.getUri(msg.currentAccount, document, 0);
            } catch (Exception e) {
                e.printStackTrace();
                return;
            }
        }

        textureView.setVisibility(android.view.View.VISIBLE);
        textureView.setAlpha(0f);
        timelineView.setVisibility(android.view.View.VISIBLE);

        videoPlayer = new VideoPlayer(true, true);
        videoPlayer.setDelegate(this);
        videoPlayer.setTextureView(textureView);
        videoPlayer.setLooping(true);
        videoPlayer.setVolume(0f);

        videoPlayer.preparePlayer(uri, "other", FileLoader.PRIORITY_NORMAL, 0L);
        videoPlayer.play();

        isPlaying = true;
        handler.post(progressRunnable);
    }

    public void release() {
        isPlaying = false;
        handler.removeCallbacks(progressRunnable);

        if (videoPlayer != null) {
            videoPlayer.setTextureView(null);
            videoPlayer.releasePlayer(true);
            videoPlayer = null;
        }

        currentMessage = null;
        if (textureView != null) textureView.setVisibility(android.view.View.GONE);
        if (timelineView != null) timelineView.setVisibility(android.view.View.GONE);

        textureView = null;
        timelineView = null;
    }

    public void pause() {
        if (videoPlayer != null && isPlaying) {
            videoPlayer.pause();
            isPlaying = false;
            handler.removeCallbacks(progressRunnable);
        }
    }

    @Override
    public void onStateChanged(boolean playWhenReady, int playbackState) {
    }

    @Override
    public void onError(VideoPlayer player, Exception e) {
        if (textureView != null) {
            textureView.setVisibility(android.view.View.GONE);
            textureView.setAlpha(0f);
        }
        if (timelineView != null) {
            timelineView.setVisibility(android.view.View.GONE);
        }
        if (onErrorListener != null) {
            onErrorListener.run();
        }
    }

    @Override
    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
        if (width <= 0 || height <= 0) return;
        applyTextureViewScale(width, height, unappliedRotationDegrees, pixelWidthHeightRatio);
    }

    @Override
    public void onRenderedFirstFrame() {
        if (onFirstFrameListener != null) {
            onFirstFrameListener.run();
        }
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
    }

    @Override
    public boolean onSurfaceDestroyed(SurfaceTexture surfaceTexture) {
        return false;
    }

    private void applyTextureViewScale(int videoWidth, int videoHeight, int rotation, float pixelRatio) {
        if (textureView == null) return;

        if (!(textureView.getParent() instanceof android.view.View)) return;
        android.view.View parent = (android.view.View) textureView.getParent();

        int containerWidth = parent.getWidth();
        int containerHeight = parent.getHeight();

        if (containerWidth <= 0 || containerHeight <= 0) {
            textureView.post(() -> applyTextureViewScale(videoWidth, videoHeight, rotation, pixelRatio));
            return;
        }

        float actualVideoWidth = videoWidth * (pixelRatio > 0 ? pixelRatio : 1f);

        if (actualVideoWidth <= 0 || (float) videoHeight <= 0) return;

        float visualWidth = actualVideoWidth;
        float visualHeight = (float) videoHeight;
        if (rotation == 90 || rotation == 270) {
            visualWidth = (float) videoHeight;
            visualHeight = actualVideoWidth;
        }

        float scaleX = containerWidth / visualWidth;
        float scaleY = containerHeight / visualHeight;
        float scale = Math.max(scaleX, scaleY);

        int scaledWidth = Math.round(actualVideoWidth * scale);
        int scaledHeight = Math.round((float) videoHeight * scale);

        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) textureView.getLayoutParams();
        if (lp.width != scaledWidth || lp.height != scaledHeight) {
            lp.width = scaledWidth;
            lp.height = scaledHeight;
            lp.gravity = android.view.Gravity.CENTER;
            textureView.setLayoutParams(lp);
        }

        textureView.setRotation(rotation);
        textureView.setTransform(null);
    }

    public boolean isPlayingMessage(MessageObject msg) {
        return currentMessage != null && currentMessage.getId() == msg.getId();
    }

    public void resume() {
        if (videoPlayer != null && currentMessage != null && !isPlaying) {
            videoPlayer.setVolume(0f);
            videoPlayer.play();
            isPlaying = true;
            handler.post(progressRunnable);
        }
    }
}