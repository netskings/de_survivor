package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;

import java.util.ArrayList;
import java.util.List;

@SuppressLint({"ClickableViewAccessibility", "ViewConstructor"})
public class FeedAlbumCarouselView extends FrameLayout {

    public interface OnPageClickListener {
        void onPageClick(int index);
    }

    private static final int DOT_R_SMALL = 3;
    private static final int DOT_R_BIG   = 4;
    private static final int DOT_GAP     = 9;
    private static final int MAX_DOTS    = 9;

    private List<MessageObject> messages = new ArrayList<>();
    private int currentIndex = 0;
    private OnPageClickListener clickListener;
    private int fixedHeight = dp(220);

    private static final int ROLE_PREV = 0;
    private static final int ROLE_CUR  = 1;
    private static final int ROLE_NEXT = 2;

    private final BackupImageView[] iv = new BackupImageView[3];
    private final int[] ivRole = {ROLE_PREV, ROLE_CUR, ROLE_NEXT};
    private final int[] ivMsg  = {-1, -1, -1};

    private float touchStartX, touchStartY;
    private float touchLastX;
    private boolean touchDecided  = false;
    private boolean touchIsHoriz  = false;
    private boolean isDragging    = false;
    private int     dragDir       = 0;
    private float   dragOffsetPx  = 0f;

    private static final float DECIDE_SLOP = 8f;

    private ValueAnimator animator;

    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txtPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect  = new RectF();

    public FeedAlbumCarouselView(Context context, Theme.ResourcesProvider rp) {
        super(context);
        setWillNotDraw(false);
        setClipChildren(true);

        bgPaint.setColor(0xBB000000);
        dotPaint.setColor(0xFFFFFFFF);
        txtPaint.setColor(0xFFFFFFFF);
        txtPaint.setTextSize(dp(12));
        txtPaint.setTextAlign(Paint.Align.CENTER);
        txtPaint.setTypeface(AndroidUtilities.bold());

        for (int i = 0; i < 3; i++) {
            iv[i] = new BackupImageView(context);
            iv[i].setRoundRadius(dp(12));
            iv[i].setClickable(false);
            addView(iv[i], new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
    }

    public void setMessages(List<MessageObject> msgs, int heightPx) {
        if (animator != null) { animator.cancel(); animator = null; }

        messages    = msgs != null ? msgs : new ArrayList<>();
        fixedHeight = heightPx;
        resetState();

        if (size() > 0) {
            ivRole[0] = ROLE_PREV;
            ivRole[1] = ROLE_CUR;
            ivRole[2] = ROLE_NEXT;

            loadIntoPhysical(1, 0);
            if (size() > 1) loadIntoPhysical(2, 1);
            clearPhysical(0);
        }

        requestLayout();
        invalidate();
    }

    public void setOnPageClickListener(OnPageClickListener l) {
        clickListener = l;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        super.onMeasure(wSpec,
                MeasureSpec.makeMeasureSpec(fixedHeight, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (!isDragging) placeViews(0f);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchStartX   = ev.getX();
            touchStartY   = ev.getY();
            touchLastX    = ev.getX();
            touchDecided  = false;
            touchIsHoriz  = false;
            return false;
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_MOVE && !touchDecided) {
            float dx = Math.abs(ev.getX() - touchStartX);
            float dy = Math.abs(ev.getY() - touchStartY);
            if (dx + dy > dp(DECIDE_SLOP)) {
                touchDecided = true;
                touchIsHoriz = dx > dy * 1.2f;
                if (touchIsHoriz) {
                    disallowParent(true);
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                touchStartX  = ev.getX();
                touchStartY  = ev.getY();
                touchLastX   = ev.getX();
                touchDecided = false;
                touchIsHoriz = false;
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - touchLastX;
                float totalDx = Math.abs(ev.getX() - touchStartX);
                float totalDy = Math.abs(ev.getY() - touchStartY);

                if (!touchDecided) {
                    if (totalDx + totalDy > dp(DECIDE_SLOP)) {
                        touchDecided = true;
                        touchIsHoriz = totalDx > totalDy * 1.2f;
                        if (touchIsHoriz) disallowParent(true);
                    }
                }

                if (!touchIsHoriz) return false;

                if (!isDragging) {
                    int dir = dx < 0 ? -1 : 1;
                    int targetMsg = currentIndex - dir;

                    if (targetMsg < 0 || targetMsg >= size()) {
                        return true;
                    }

                    isDragging = true;
                    dragDir    = dir;
                    ensureNeighbourLoaded(dragDir);
                }

                dragOffsetPx += dx;
                dragOffsetPx = Math.max(-getWidth(),
                        Math.min(getWidth(), dragOffsetPx));
                touchLastX   = ev.getX();

                placeViews(dragOffsetPx);
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (!isDragging) {
                    float totalDx = Math.abs(ev.getX() - touchStartX);
                    float totalDy = Math.abs(ev.getY() - touchStartY);
                    if (totalDx < dp(8) && totalDy < dp(8)) {
                        if (clickListener != null) {
                            clickListener.onPageClick(currentIndex);
                        }
                    }
                    disallowParent(false);
                    return true;
                }

                float threshold = getWidth() * 0.28f;
                boolean commit =
                        (dragDir == -1 && dragOffsetPx < -threshold) ||
                                (dragDir ==  1 && dragOffsetPx >  threshold);

                if (commit) animateCommit();
                else        animateCancel();

                disallowParent(false);
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                if (isDragging) animateCancel();
                disallowParent(false);
                return true;
            }
        }
        return false;
    }

    private void placeViews(float offset) {
        int w = getWidth();
        if (w == 0) return;
        for (int i = 0; i < 3; i++) {
            float tx;
            switch (ivRole[i]) {
                case ROLE_PREV: tx = -w + offset; break;
                case ROLE_NEXT: tx =  w + offset; break;
                default:        tx =      offset; break;
            }
            iv[i].setTranslationX(tx);
        }
    }

    private void loadIntoPhysical(int physIdx, int msgIndex) {
        if (msgIndex < 0 || msgIndex >= size()) {
            clearPhysical(physIdx);
            return;
        }
        if (ivMsg[physIdx] == msgIndex) return;
        ivMsg[physIdx] = msgIndex;
        loadImage(iv[physIdx], messages.get(msgIndex));
    }

    private void clearPhysical(int physIdx) {
        ivMsg[physIdx] = -1;
        iv[physIdx].getImageReceiver().clearImage();
    }

    private int physOf(int role) {
        for (int i = 0; i < 3; i++) if (ivRole[i] == role) return i;
        return -1;
    }

    private void ensureNeighbourLoaded(int dir) {
        if (dir == -1) {
            int physNext = physOf(ROLE_NEXT);
            if (physNext >= 0) loadIntoPhysical(physNext, currentIndex + 1);
        } else {
            int physPrev = physOf(ROLE_PREV);
            if (physPrev >= 0) loadIntoPhysical(physPrev, currentIndex - 1);
        }
    }

    private void animateCommit() {
        int w        = getWidth();
        float endOff = dragDir == -1 ? -w : w;

        runAnim(endOff, () -> {
            currentIndex -= dragDir;

            if (dragDir == -1) {
                for (int i = 0; i < 3; i++) {
                    switch (ivRole[i]) {
                        case ROLE_NEXT: ivRole[i] = ROLE_CUR;  break;
                        case ROLE_CUR:  ivRole[i] = ROLE_PREV; break;
                        case ROLE_PREV: ivRole[i] = ROLE_NEXT; break;
                    }
                }
                int physNext = physOf(ROLE_NEXT);
                if (physNext >= 0) loadIntoPhysical(physNext, currentIndex + 1);

            } else {
                for (int i = 0; i < 3; i++) {
                    switch (ivRole[i]) {
                        case ROLE_PREV: ivRole[i] = ROLE_CUR;  break;
                        case ROLE_CUR:  ivRole[i] = ROLE_NEXT; break;
                        case ROLE_NEXT: ivRole[i] = ROLE_PREV; break;
                    }
                }
                int physPrev = physOf(ROLE_PREV);
                if (physPrev >= 0) loadIntoPhysical(physPrev, currentIndex - 1);
            }

            dragOffsetPx = 0f;
            isDragging   = false;
            dragDir      = 0;

            placeViews(0f);
            invalidate();
        });
    }

    private void animateCancel() {
        runAnim(0f, () -> {
            dragOffsetPx = 0f;
            isDragging   = false;
            dragDir      = 0;
            placeViews(0f);
        });
    }

    private void runAnim(float endValue, Runnable onEnd) {
        if (animator != null) animator.cancel();
        float startValue = dragOffsetPx;
        animator = ValueAnimator.ofFloat(startValue, endValue);
        animator.setDuration(220);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            dragOffsetPx = (float) a.getAnimatedValue();
            placeViews(dragOffsetPx);
            invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean done = false;
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (!done) { done = true; onEnd.run(); }
            }
            @Override public void onAnimationCancel(android.animation.Animator a) {
                if (!done) { done = true; onEnd.run(); }
            }
        });
        animator.start();
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        super.dispatchDraw(canvas);
        if (size() <= 1) return;
        drawCounter(canvas);
        if (size() <= MAX_DOTS) drawDots(canvas);
    }

    private void drawCounter(Canvas canvas) {
        String txt  = (currentIndex + 1) + " / " + size();
        float  padW = dp(10), padH = dp(5);
        float  boxW = txtPaint.measureText(txt) + padW * 2;
        float  boxH = dp(20);
        float  right = getWidth() - dp(10);
        float  top   = dp(10);
        tmpRect.set(right - boxW, top, right, top + boxH);
        canvas.drawRoundRect(tmpRect, boxH / 2f, boxH / 2f, bgPaint);
        canvas.drawText(txt,
                tmpRect.centerX(),
                tmpRect.centerY() + dp(4.5f),
                txtPaint);
    }

    private void drawDots(Canvas canvas) {
        int   n      = size();
        float gap    = dp(DOT_GAP);
        float rSmall = dp(DOT_R_SMALL);
        float rBig   = dp(DOT_R_BIG);
        float totalW = (n - 1) * gap;
        float startX = (getWidth() - totalW) / 2f;
        float dotY   = getHeight() - dp(14);

        float activePos;
        if (isDragging && getWidth() > 0) {
            activePos = currentIndex - (dragOffsetPx / getWidth());
        } else {
            activePos = currentIndex;
        }

        for (int i = 0; i < n; i++) {
            float dist  = Math.abs(i - activePos);
            float frac  = Math.max(0f, 1f - dist);
            float r     = rSmall + (rBig - rSmall) * frac;
            int   alpha = (int)(0x66 + 0x99 * frac);
            dotPaint.setAlpha(alpha);
            canvas.drawCircle(startX + i * gap, dotY, r, dotPaint);
        }
        dotPaint.setAlpha(255);
    }

    private void loadImage(BackupImageView view, MessageObject msg) {
        view.getImageReceiver().clearImage();
        TLRPC.Message raw = msg.messageOwner;
        int dispW = AndroidUtilities.displaySize.x - dp(32);
        int h     = fixedHeight;

        if (raw.media instanceof TLRPC.TL_messageMediaPhoto
                && raw.media.photo != null) {

            TLRPC.Photo    photo = raw.media.photo;
            TLRPC.PhotoSize best = FeedMediaHelper.bestSize(photo.sizes);
            if (best == null) return;

            if (best.w > 0 && best.h > 0) {
                h = Math.max(dp(150), Math.min(dp(400),
                        (int)(dispW * (float) best.h / best.w)));
            }

            TLRPC.PhotoSize small = FeedMediaHelper.smallestThumb(photo.sizes);
            ImageLocation thumbLoc = small != null
                    ? ImageLocation.getForPhoto(small, photo) : null;

            view.setImage(
                    ImageLocation.getForPhoto(best, photo), dispW + "_" + h,
                    thumbLoc, "80_80_b",
                    0, msg);

        } else if (raw.media instanceof TLRPC.TL_messageMediaDocument
                && raw.media.document != null) {

            TLRPC.Document doc   = raw.media.document;
            boolean isGif        = false;
            int videoW = 0, videoH = 0;

            for (TLRPC.DocumentAttribute attr : doc.attributes) {
                if (attr instanceof TLRPC.TL_documentAttributeAnimated) {
                    isGif = true;
                } else if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                    videoW = attr.w;
                    videoH = attr.h;
                }
            }

            if (videoW > 0 && videoH > 0) {
                h = Math.max(dp(150), Math.min(dp(400),
                        (int)(dispW * (float) videoH / videoW)));
            }

            TLRPC.PhotoSize thumb = FeedMediaHelper.bestSize(doc.thumbs);
            ImageLocation   thumbLoc = thumb != null
                    ? ImageLocation.getForDocument(thumb, doc) : null;

            if (isGif) {
                view.setImage(
                        ImageLocation.getForDocument(doc), dispW + "_" + h,
                        thumbLoc, "80_80_b",
                        (int) doc.size, msg);
                view.getImageReceiver().setAutoRepeat(1);
                view.getImageReceiver().setAllowStartAnimation(true);
            } else if (thumb != null) {
                view.setImage(
                        ImageLocation.getForDocument(thumb, doc), dispW + "_" + h,
                        thumbLoc, "80_80_b",
                        0, doc);
            }
        }
    }

    private void resetState() {
        currentIndex = 0;
        isDragging   = false;
        dragOffsetPx = 0f;
        dragDir      = 0;
        touchDecided = false;
        touchIsHoriz = false;
        ivRole[0]    = ROLE_PREV;
        ivRole[1]    = ROLE_CUR;
        ivRole[2]    = ROLE_NEXT;
        for (int i = 0; i < 3; i++) ivMsg[i] = -1;
    }

    private int size() { return messages.size(); }

    private void disallowParent(boolean disallow) {
        ViewParent p = getParent();
        if (p != null) p.requestDisallowInterceptTouchEvent(disallow);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) { animator.cancel(); animator = null; }
        for (BackupImageView v : iv) v.getImageReceiver().clearImage();
    }
}