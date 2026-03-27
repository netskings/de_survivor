package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

import org.telegram.ui.ActionBar.Theme;

public class FeedMediaShimmer extends View {

    private final Paint shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient shimmerGradient;
    private final Matrix shimmerMatrix = new Matrix();
    private final RectF rect = new RectF();
    private float translateX;
    private ValueAnimator animator;
    private final float cornerRadius;
    private int lastWidth;
    private boolean started;

    public FeedMediaShimmer(Context context) {
        super(context);
        cornerRadius = dp(12);
        basePaint.setColor(Theme.isCurrentThemeDark() ? 0xFF2A2A2A : 0xFFE8E8E8);
        setVisibility(GONE);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && w != lastWidth) {
            lastWidth = w;
            rebuildGradient(w);
        }
    }

    private void rebuildGradient(int w) {
        boolean isDark = Theme.isCurrentThemeDark();
        int base = isDark ? 0xFF2A2A2A : 0xFFE8E8E8;
        int highlight = isDark ? 0xFF3A3A3A : 0xFFF5F5F5;

        shimmerGradient = new LinearGradient(
                0, 0, w * 0.6f, 0,
                new int[]{base, highlight, base},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP
        );
        shimmerPaint.setShader(shimmerGradient);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        rect.set(0, 0, getWidth(), getHeight());

        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, basePaint);

        if (shimmerGradient != null && getWidth() > 0) {
            float w = getWidth();
            shimmerMatrix.setTranslate(translateX * w * 2.5f - w * 0.75f, 0);
            shimmerGradient.setLocalMatrix(shimmerMatrix);
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, shimmerPaint);
        }
    }

    public void start() {
        if (started) return;
        started = true;
        setAlpha(1f);
        setVisibility(VISIBLE);
        clearAnimation();

        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1200);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            translateX = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void hide(boolean animated) {
        started = false;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (animated && getVisibility() == VISIBLE && getAlpha() > 0f) {
            animate().alpha(0f).setDuration(250).withEndAction(() -> {
                setVisibility(GONE);
                setAlpha(1f);
            }).start();
        } else {
            clearAnimation();
            setVisibility(GONE);
            setAlpha(1f);
        }
    }

    public boolean isStarted() {
        return started;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        started = false;
    }
}