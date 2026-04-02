package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;

class FeedRoundProgressView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private float displayProgress;

    private long syncRealtime;
    private float syncProgress;
    private float playbackSpeed = 1f;
    private int durationMs;
    private boolean running;
    private boolean seeking;

    private static final float STROKE = dp(3);
    private static final float THUMB_R = dp(5f);
    private static final float THUMB_R_BIG = dp(8f);
    private static final float INSET = STROKE / 2f + dp(2);
    private static final float DRIFT_SNAP_THRESHOLD = 0.03f;

    FeedRoundProgressView(Context context) {
        super(context);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(STROKE);
        trackPaint.setColor(0x40FFFFFF);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(0xFFFFFFFF);

        thumbPaint.setColor(0xFFFFFFFF);
    }

    float computeCurrentProgress() {
        if (seeking) return displayProgress;
        if (!running || durationMs <= 0) return displayProgress;
        long now = SystemClock.elapsedRealtime();
        float elapsedMs = (now - syncRealtime) * playbackSpeed;
        return Math.min(1f, syncProgress + elapsedMs / durationMs);
    }

    void sync(int positionMs, int dur, float speed) {
        if (seeking) return;

        this.durationMs = dur;
        this.playbackSpeed = speed;

        float realProgress = dur > 0 ? (float) positionMs / dur : 0;
        float current = computeCurrentProgress();
        float drift = Math.abs(realProgress - current);

        this.syncRealtime = SystemClock.elapsedRealtime();

        if (drift > DRIFT_SNAP_THRESHOLD || !running) {
            syncProgress = realProgress;
            displayProgress = realProgress;
        } else {
            syncProgress = current;
            displayProgress = current;
        }
    }

    void startSmooth() {
        if (running) return;
        running = true;
        invalidate();
    }

    void stopSmooth() {
        running = false;
    }

    void setSeekProgress(float p) {
        seeking = true;
        displayProgress = clamp(p);
        invalidate();
    }

    void endSeek() {
        seeking = false;
        syncRealtime = SystemClock.elapsedRealtime();
        syncProgress = displayProgress;
    }

    void setDirect(float p) {
        displayProgress = clamp(p);
        syncProgress = displayProgress;
        syncRealtime = SystemClock.elapsedRealtime();
        invalidate();
    }

    float getProgress() {
        return computeCurrentProgress();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (running && !seeking && durationMs > 0) {
            displayProgress = computeCurrentProgress();
        }

        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        arcRect.set(INSET, INSET, w - INSET, h - INSET);

        canvas.drawArc(arcRect, 0, 360, false, trackPaint);

        float sweep = displayProgress * 360f;
        if (sweep > 0.1f) {
            canvas.drawArc(arcRect, -90, sweep, false, arcPaint);
        }

        float rad = (float) Math.toRadians(-90 + sweep);
        float tr = seeking ? THUMB_R_BIG : THUMB_R;
        float cx = arcRect.centerX() + arcRect.width() / 2f * (float) Math.cos(rad);
        float cy = arcRect.centerY() + arcRect.height() / 2f * (float) Math.sin(rad);
        canvas.drawCircle(cx, cy, tr, thumbPaint);

        if (running && !seeking) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopSmooth();
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }
}