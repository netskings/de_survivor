package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

@SuppressLint("ViewConstructor")
public class FeedReactionsView extends FrameLayout {

    private static final int MAX_COLLAPSED = 3;

    private final int currentAccount;
    private final Theme.ResourcesProvider resourcesProvider;

    private final LinearLayout container;

    private FeedController.FeedItem currentItem;
    private boolean expanded = false;
    private final List<ReactionData> allReactions = new ArrayList<>();
    private PopupWindow pickerPopup;

    private int animCounter = 0;

    private static class ReactionData {
        TLRPC.Reaction reaction;
        int count;
        boolean chosen;
        boolean isPaid;
    }

    public interface ReactionCallback {
        void onReactionToggle(FeedController.FeedItem item, TLRPC.Reaction reaction);
        void onPaidReactionTap(FeedController.FeedItem item);
        void onPaidReactionLongPress(FeedController.FeedItem item);
    }

    private ReactionCallback callback;

    public void setCallback(ReactionCallback cb) { this.callback = cb; }

    public FeedReactionsView(Context context, int account, Theme.ResourcesProvider rp) {
        super(context);
        this.currentAccount = account;
        this.resourcesProvider = rp;

        setClipChildren(false);
        setClipToPadding(false);

        HorizontalScrollView scrollView = new HorizontalScrollView(context);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setOverScrollMode(OVER_SCROLL_NEVER);
        scrollView.setClipChildren(false);
        scrollView.setClipToPadding(false);

        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setPadding(0, dp(4), 0, dp(4));
        container.setClipChildren(false);
        container.setClipToPadding(false);

        scrollView.addView(container, new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        addView(scrollView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    public void setData(FeedController.FeedItem item) {
        currentItem = item;
        expanded = false;

        if (item != null) {
            long chatId = -item.channelId;
            if (chatId > 0) {
                TLRPC.ChatFull full = MessagesController.getInstance(currentAccount).getChatFull(chatId);
                if (full == null) {
                    MessagesController.getInstance(currentAccount).loadFullChat(chatId, 0, true);
                }
            }
        }

        collectReactions();
        for (ReactionData rd : allReactions) {
            if (rd.reaction instanceof TLRPC.TL_reactionEmoji) {
                TLRPC.TL_availableReaction ar = findAvailableReaction(rd.reaction);
                if (ar != null) {
                    if (ar.select_animation != null) {
                        org.telegram.messenger.FileLoader.getInstance(currentAccount)
                                .loadFile(ar.select_animation, null,
                                        org.telegram.messenger.FileLoader.PRIORITY_LOW, 0);
                    }
                    if (ar.around_animation != null) {
                        org.telegram.messenger.FileLoader.getInstance(currentAccount)
                                .loadFile(ar.around_animation, null,
                                        org.telegram.messenger.FileLoader.PRIORITY_LOW, 0);
                    }
                }
            }
            if (rd.reaction instanceof TLRPC.TL_reactionCustomEmoji) {
                long docId = ((TLRPC.TL_reactionCustomEmoji) rd.reaction).document_id;
                AnimatedEmojiDrawable drawable = AnimatedEmojiDrawable.make(
                        currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES, docId);
                drawable.preload();
            }
        }

        rebuild();
    }

    public void clear() {
        currentItem = null;
        container.removeAllViews();
        setVisibility(GONE);
    }

    public void optimisticallyAddPaid(int amount) {
        if (currentItem == null) return;
        MessageObject msg = currentItem.getPrimaryMessage();
        if (msg.messageOwner.reactions == null) {
            msg.messageOwner.reactions = new TLRPC.TL_messageReactions();
            msg.messageOwner.reactions.results = new ArrayList<>();
        }
        TLRPC.ReactionCount target = null;
        for (TLRPC.ReactionCount rc : msg.messageOwner.reactions.results) {
            if (rc.reaction instanceof TLRPC.TL_reactionPaid) { target = rc; break; }
        }
        if (target != null) { target.count += amount; target.flags |= 1; }
        else {
            TLRPC.TL_reactionCount newRc = new TLRPC.TL_reactionCount();
            newRc.reaction = new TLRPC.TL_reactionPaid();
            newRc.count = amount; newRc.flags |= 1;
            msg.messageOwner.reactions.results.add(newRc);
        }
        collectReactions(); rebuild();
    }

    public void optimisticallyRemovePaid(int amount) {
        if (currentItem == null) return;
        MessageObject msg = currentItem.getPrimaryMessage();
        if (msg.messageOwner.reactions == null) return;
        for (int i = msg.messageOwner.reactions.results.size() - 1; i >= 0; i--) {
            TLRPC.ReactionCount rc = msg.messageOwner.reactions.results.get(i);
            if (rc.reaction instanceof TLRPC.TL_reactionPaid) {
                rc.count -= amount;
                if (rc.count <= 0) msg.messageOwner.reactions.results.remove(i);
                break;
            }
        }
        collectReactions(); rebuild();
    }

    private void collectReactions() {
        allReactions.clear();
        if (currentItem == null) return;
        MessageObject msg = currentItem.getPrimaryMessage();
        TLRPC.MessageReactions reactions = msg.messageOwner.reactions;
        if (reactions == null || reactions.results == null) return;
        for (TLRPC.ReactionCount rc : reactions.results) {
            if (rc.count <= 0) continue;
            ReactionData rd = new ReactionData();
            rd.reaction = rc.reaction;
            rd.count = rc.count;
            rd.chosen = (rc.flags & 1) != 0;
            rd.isPaid = rc.reaction instanceof TLRPC.TL_reactionPaid;
            allReactions.add(rd);
        }
        allReactions.sort((a, b) -> {
            if (a.isPaid != b.isPaid) return a.isPaid ? -1 : 1;
            if (a.chosen != b.chosen) return a.chosen ? -1 : 1;
            return Integer.compare(b.count, a.count);
        });
    }

    private boolean reactionsAllowed() {
        if (currentItem == null) return true;
        long chatId = -currentItem.channelId;
        TLRPC.ChatFull chatFull = MessagesController.getInstance(currentAccount).getChatFull(chatId);
        if (chatFull == null) return false;
        return chatFull.available_reactions instanceof TLRPC.TL_chatReactionsNone;
    }

    private boolean paidReactionsAllowed() {
        if (currentItem == null) return false;
        long chatId = -currentItem.channelId;
        TLRPC.ChatFull chatFull = MessagesController.getInstance(currentAccount).getChatFull(chatId);
        if (chatFull == null) return false;
        return chatFull.paid_reactions_available;
    }

    private void rebuild() {
        container.removeAllViews();
        if (currentItem == null) { setVisibility(GONE); return; }
        if (reactionsAllowed() && allReactions.isEmpty()) { setVisibility(GONE); return; }

        boolean hasMany = allReactions.size() > MAX_COLLAPSED;
        List<ReactionData> toShow = (hasMany && !expanded)
                ? allReactions.subList(0, MAX_COLLAPSED) : allReactions;

        for (ReactionData rd : toShow) {
            View chip = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                chip = createReactionChip(rd);
            }
            if (rd.isPaid) {
                assert chip != null;
                chip.setOnClickListener(v -> {
                    animateBounce(v);
                    if (callback != null) callback.onPaidReactionTap(currentItem);
                });
                chip.setOnLongClickListener(v -> {
                    if (callback != null) callback.onPaidReactionLongPress(currentItem);
                    return true;
                });
            } else {
                chip.setOnClickListener(v -> {
                    animateReaction(v, rd.reaction, !rd.chosen);
                    toggleReaction(rd.reaction);
                });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(6), 0);
            container.addView(chip, lp);
        }

        if (hasMany) {
            if (!expanded) {
                addExpandCollapseChip("+" + (allReactions.size() - MAX_COLLAPSED), true);
            } else {
                addExpandCollapseChip("<", false);
            }
        }
        addPickerButton();
        setVisibility(VISIBLE);

        postDelayed(() -> {
            for (int i = 0; i < container.getChildCount(); i++) {
                container.getChildAt(i).invalidate();
            }
        }, 100);
        postDelayed(() -> {
            for (int i = 0; i < container.getChildCount(); i++) {
                container.getChildAt(i).invalidate();
            }
        }, 500);
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private View createReactionChip(ReactionData rd) {
        AnimatedEmojiSpan.TextViewEmojis chip = new AnimatedEmojiSpan.TextViewEmojis(getContext());
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(8), dp(5), dp(10), dp(5));
        chip.setSingleLine(true);

        int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourcesProvider);
        int starGold = 0xFFFFAC1C;

        if (rd.isPaid) {
            chip.setBackground(createChipBg(rd.chosen, starGold));
            chip.setTextColor(rd.chosen ? starGold : gray);
        } else {
            chip.setBackground(createChipBg(rd.chosen, accent));
            chip.setTextColor(rd.chosen ? accent : gray);
        }

        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (rd.isPaid) {
            try {
                @SuppressLint("UseCompatLoadingForDrawables") Drawable star = getContext().getResources().getDrawable(R.drawable.star_small_inner).mutate();
                star.setBounds(0, 0, dp(16), dp(16));
                sb.append(" ");
                sb.setSpan(new ImageSpan(star, ImageSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (Exception e) { sb.append("⭐"); }
        } else if (rd.reaction instanceof TLRPC.TL_reactionEmoji) {
            sb.append(((TLRPC.TL_reactionEmoji) rd.reaction).emoticon);
        } else if (rd.reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            long docId = ((TLRPC.TL_reactionCustomEmoji) rd.reaction).document_id;
            sb.append("⭐");
            sb.setSpan(new AnimatedEmojiSpan(docId, chip.getPaint().getFontMetricsInt()),
                    0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sb.append(" ").append(formatCount(rd.count));
        chip.setText(Emoji.replaceEmoji(sb, chip.getPaint().getFontMetricsInt(), false, null));
        chip.setTag(rd.reaction);
        return chip;
    }

    private void animateReaction(View chipView, TLRPC.Reaction reaction, boolean adding) {
        animateBounce(chipView);
        if (adding) {
            TLRPC.TL_availableReaction available = findAvailableReaction(reaction);
            animateSelectOnChip(chipView, reaction, available);
            animateAroundEffect(chipView, available);
        }
    }

    private void animateAroundEffect(View chipView, TLRPC.TL_availableReaction available) {
        if (available == null) return;
        TLRPC.Document effectDoc = available.around_animation;
        if (effectDoc == null) effectDoc = available.effect_animation;
        if (effectDoc == null) return;

        org.telegram.messenger.FileLoader.getInstance(currentAccount)
                .loadFile(effectDoc, null,
                        org.telegram.messenger.FileLoader.PRIORITY_HIGH, 0);

        int size = dp(56);
        BackupImageView effectView = new BackupImageView(getContext());
        animCounter++;
        effectView.setImage(ImageLocation.getForDocument(effectDoc),
                size + "_" + size + "_nr_" + animCounter,
                null, null, null, 0);
        effectView.getImageReceiver().setAutoRepeat(0);
        effectView.getImageReceiver().setAllowStartAnimation(true);
        effectView.setPivotX(size / 2f);
        effectView.setPivotY(size / 2f);

        addAnimationView(chipView, effectView, size);

        ValueAnimator fade = ValueAnimator.ofFloat(1f, 0f);
        fade.setStartDelay(800);
        fade.setDuration(500);
        fade.addUpdateListener(a -> effectView.setAlpha((float) a.getAnimatedValue()));
        fade.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                removeAnimationView(effectView);
            }
        });
        fade.start();
    }

    private void addAnimationView(View chipView, View animView, int size) {
        android.view.ViewGroup decorView = (android.view.ViewGroup)
                ((android.app.Activity) getContext()).getWindow().getDecorView();

        int[] chipLoc = new int[2];
        chipView.getLocationOnScreen(chipLoc);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.leftMargin = chipLoc[0] + chipView.getWidth() / 2 - size / 2;
        lp.topMargin = chipLoc[1] + chipView.getHeight() / 2 - size / 2;
        lp.gravity = Gravity.TOP | Gravity.LEFT;

        decorView.addView(animView, lp);
    }

    private void removeAnimationView(View animView) {
        android.view.ViewGroup decorView = (android.view.ViewGroup)
                ((android.app.Activity) getContext()).getWindow().getDecorView();
        decorView.removeView(animView);
    }

    private void animateSelectOnChip(View chipView, TLRPC.Reaction reaction,
                                     TLRPC.TL_availableReaction available) {
        TLRPC.Document selectDoc = null;
        if (available != null) {
            if (available.select_animation != null) selectDoc = available.select_animation;
            else if (available.activate_animation != null) selectDoc = available.activate_animation;
        }
        if (selectDoc == null && reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            long docId = ((TLRPC.TL_reactionCustomEmoji) reaction).document_id;
            animateCustomEmojiOnChip(chipView, docId);
            return;
        }
        if (selectDoc == null) return;

        int size = dp(36);
        BackupImageView selectView = new BackupImageView(getContext());
        animCounter++;
        selectView.setImage(ImageLocation.getForDocument(selectDoc),
                size + "_" + size + "_nr_" + animCounter,
                null, null, null, 0);
        selectView.getImageReceiver().setAutoRepeat(0);
        selectView.getImageReceiver().setAllowStartAnimation(true);
        selectView.setPivotX(size / 2f);
        selectView.setPivotY(size / 2f);
        selectView.setScaleX(0.5f);
        selectView.setScaleY(0.5f);

        addAnimationView(chipView, selectView, size);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(selectView, View.SCALE_X, 0.5f, 1f),
                ObjectAnimator.ofFloat(selectView, View.SCALE_Y, 0.5f, 1f),
                ObjectAnimator.ofFloat(selectView, View.ALPHA, 1f, 1f, 1f, 1f, 0f)
        );
        set.setDuration(1200);
        set.setInterpolator(new DecelerateInterpolator(2f));
        set.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                removeAnimationView(selectView);
            }
        });
        set.start();
    }

    private void animateCustomEmojiOnChip(View chipView, long docId) {
        int size = dp(36);
        BackupImageView flyView = new BackupImageView(getContext());
        AnimatedEmojiDrawable drawable = AnimatedEmojiDrawable.make(
                currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES, docId);
        flyView.setImageDrawable(drawable);
        flyView.setPivotX(size / 2f);
        flyView.setPivotY(size / 2f);
        flyView.setScaleX(0.5f);
        flyView.setScaleY(0.5f);

        addAnimationView(chipView, flyView, size);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(flyView, View.SCALE_X, 0.5f, 1f),
                ObjectAnimator.ofFloat(flyView, View.SCALE_Y, 0.5f, 1f),
                ObjectAnimator.ofFloat(flyView, View.ALPHA, 1f, 1f, 1f, 1f, 0f)
        );
        set.setDuration(1200);
        set.setInterpolator(new DecelerateInterpolator(2f));
        set.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                removeAnimationView(flyView);
            }
        });
        set.start();
    }


    private void animateBounce(View view) {
        view.animate().cancel();
        view.animate()
                .scaleX(0.75f).scaleY(0.75f)
                .setDuration(100)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() ->
                        view.animate()
                                .scaleX(1f).scaleY(1f)
                                .setDuration(250)
                                .setInterpolator(new OvershootInterpolator(3f))
                                .start()
                ).start();
    }

    private TLRPC.TL_availableReaction findAvailableReaction(TLRPC.Reaction reaction) {
        if (!(reaction instanceof TLRPC.TL_reactionEmoji)) return null;
        String emoticon = ((TLRPC.TL_reactionEmoji) reaction).emoticon;
        List<TLRPC.TL_availableReaction> list =
                MediaDataController.getInstance(currentAccount).getReactionsList();
        if (list == null) return null;
        for (TLRPC.TL_availableReaction ar : list) {
            if (ar.reaction.equals(emoticon)) return ar;
        }
        return null;
    }

    private void addExpandCollapseChip(String text, boolean isExpand) {
        TextView chip = makeSimpleChip(text);
        chip.setOnClickListener(v -> { expanded = isExpand; rebuild(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(6), 0);
        container.addView(chip, lp);
    }

    @SuppressLint("SetTextI18n")
    private void addPickerButton() {
        if (reactionsAllowed()) return;
        TextView addBtn = makeSimpleChip("+");
        addBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        addBtn.setOnClickListener(this::showPicker);
        container.addView(addBtn, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    private TextView makeSimpleChip(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text); tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tv.setTypeface(AndroidUtilities.bold()); tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(5), dp(10), dp(5));
        tv.setBackground(createChipBg(false, 0));
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourcesProvider));
        return tv;
    }

    @SuppressLint({"SetTextI18n", "UseCompatLoadingForDrawables"})
    private void showPicker(View anchor) {
        if (pickerPopup != null && pickerPopup.isShowing()) { pickerPopup.dismiss(); return; }

        List<TLRPC.Reaction> available = getAvailableReactions();
        boolean hasPaid = paidReactionsAllowed();
        if (available.isEmpty() && !hasPaid) return;

        LinearLayout pickerRoot = new LinearLayout(getContext());
        pickerRoot.setOrientation(LinearLayout.VERTICAL);
        pickerRoot.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6333333); bg.setCornerRadius(dp(20));
        pickerRoot.setBackground(bg);

        if (hasPaid) {
            LinearLayout starRow = new LinearLayout(getContext());
            starRow.setOrientation(LinearLayout.HORIZONTAL);
            starRow.setGravity(Gravity.CENTER);
            starRow.setPadding(dp(8), dp(6), dp(8), dp(6));
            starRow.setBackground(Theme.createSelectorDrawable(0x33FFFFFF, 2));
            try {
                android.widget.ImageView si = new android.widget.ImageView(getContext());
                si.setImageDrawable(getContext().getResources().getDrawable(R.drawable.star_small_inner).mutate());
                starRow.addView(si, new LinearLayout.LayoutParams(dp(20), dp(20)));
            } catch (Exception ignored) {}
            TextView label = new TextView(getContext());
            label.setText("  Send Star"); label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            label.setTextColor(0xFFFFAC1C); label.setTypeface(AndroidUtilities.bold());
            starRow.addView(label);
            starRow.setOnClickListener(v -> {
                if (pickerPopup != null) pickerPopup.dismiss();
                if (callback != null) callback.onPaidReactionTap(currentItem);
            });
            starRow.setOnLongClickListener(v -> {
                if (pickerPopup != null) pickerPopup.dismiss();
                if (callback != null) callback.onPaidReactionLongPress(currentItem);
                return true;
            });
            pickerRoot.addView(starRow);
            View sep = new View(getContext()); sep.setBackgroundColor(0x33FFFFFF);
            pickerRoot.addView(sep, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1)));
        }

        int perRow = 8;
        LinearLayout currentRow = null;
        for (int i = 0; i < available.size(); i++) {
            if (i % perRow == 0) {
                currentRow = new LinearLayout(getContext());
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                pickerRoot.addView(currentRow);
            }
            TLRPC.Reaction reaction = available.get(i);
            View cell = createPickerCell(reaction);
            cell.setOnClickListener(v -> {
                if (pickerPopup != null) pickerPopup.dismiss();
                toggleReaction(reaction);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
            lp.setMargins(dp(1), dp(1), dp(1), dp(1));
            if (currentRow != null) currentRow.addView(cell, lp);
        }

        View popupContent;
        if (available.size() > perRow * 3) {
            ScrollView sv = new ScrollView(getContext()); sv.addView(pickerRoot); popupContent = sv;
        } else { popupContent = pickerRoot; }

        popupContent.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(dp(300), MeasureSpec.AT_MOST));

        pickerPopup = new PopupWindow(popupContent, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, true);
        pickerPopup.setOutsideTouchable(true);
        pickerPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pickerPopup.setAnimationStyle(android.R.style.Animation_Toast);

        int[] loc = new int[2]; anchor.getLocationInWindow(loc);
        int popupW = popupContent.getMeasuredWidth(), popupH = popupContent.getMeasuredHeight();
        int x = Math.max(dp(8), loc[0] - popupW / 2 + anchor.getWidth() / 2);
        int screenW = AndroidUtilities.displaySize.x;
        if (x + popupW > screenW - dp(8)) x = screenW - popupW - dp(8);
        int y = loc[1] - popupH - dp(6);
        if (y < dp(48)) y = loc[1] + anchor.getHeight() + dp(6);
        pickerPopup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
    }

    private View createPickerCell(TLRPC.Reaction reaction) {
        AnimatedEmojiSpan.TextViewEmojis tv = new AnimatedEmojiSpan.TextViewEmojis(getContext());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22); tv.setGravity(Gravity.CENTER);
        tv.setBackground(Theme.createSelectorDrawable(0x33FFFFFF, 1));
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (reaction instanceof TLRPC.TL_reactionEmoji) {
            sb.append(((TLRPC.TL_reactionEmoji) reaction).emoticon);
        } else if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
            long docId = ((TLRPC.TL_reactionCustomEmoji) reaction).document_id;
            sb.append("⭐");
            sb.setSpan(new AnimatedEmojiSpan(docId, tv.getPaint().getFontMetricsInt()), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tv.setText(Emoji.replaceEmoji(sb, tv.getPaint().getFontMetricsInt(), false, null));
        return tv;
    }

    private List<TLRPC.Reaction> getAvailableReactions() {
        if (currentItem == null) return getAllGlobalReactions();
        long chatId = -currentItem.channelId;
        TLRPC.ChatFull chatFull = MessagesController.getInstance(currentAccount).getChatFull(chatId);
        if (chatFull == null) {
            MessagesController.getInstance(currentAccount).loadFullChat(chatId, 0, true);
            return getAllGlobalReactions();
        }
        if (chatFull.available_reactions instanceof TLRPC.TL_chatReactionsNone) return new ArrayList<>();
        if (chatFull.available_reactions instanceof TLRPC.TL_chatReactionsSome) {
            TLRPC.TL_chatReactionsSome some = (TLRPC.TL_chatReactionsSome) chatFull.available_reactions;
            List<TLRPC.Reaction> list = new ArrayList<>();
            for (TLRPC.Reaction r : some.reactions) {
                if (!(r instanceof TLRPC.TL_reactionPaid)) list.add(r);
            }
            return list;
        }
        return getAllGlobalReactions();
    }

    private List<TLRPC.Reaction> getAllGlobalReactions() {
        List<TLRPC.Reaction> list = new ArrayList<>();
        List<TLRPC.TL_availableReaction> global = MediaDataController.getInstance(currentAccount).getReactionsList();
        if (global != null) {
            for (TLRPC.TL_availableReaction ar : global) {
                if (!ar.inactive) {
                    TLRPC.TL_reactionEmoji r = new TLRPC.TL_reactionEmoji();
                    r.emoticon = ar.reaction; list.add(r);
                }
            }
        }
        return list;
    }

    private void toggleReaction(TLRPC.Reaction reaction) {
        if (currentItem == null) return;
        optimisticallyToggleRegular(reaction);
        collectReactions();
        rebuild();
        if (callback != null) callback.onReactionToggle(currentItem, reaction);
    }

    private void optimisticallyToggleRegular(TLRPC.Reaction reaction) {
        MessageObject msg = currentItem.getPrimaryMessage();

        if (msg.messageOwner.reactions == null) {
            msg.messageOwner.reactions = new TLRPC.TL_messageReactions();
            msg.messageOwner.reactions.results = new ArrayList<>();
        }

        TLRPC.MessageReactions reactions = msg.messageOwner.reactions;
        boolean isPremium = UserConfig.getInstance(currentAccount).isPremium();
        int maxReactions = isPremium ? 3 : 1;

        TLRPC.ReactionCount target = null;
        List<TLRPC.ReactionCount> chosenList = new ArrayList<>();

        for (TLRPC.ReactionCount rc : reactions.results) {
            if (rc.reaction instanceof TLRPC.TL_reactionPaid) continue;
            if (reactionsEqual(rc.reaction, reaction)) target = rc;
            if ((rc.flags & 1) != 0 && !(rc.reaction instanceof TLRPC.TL_reactionPaid))
                chosenList.add(rc);
        }

        if (target != null && (target.flags & 1) != 0) {
            target.flags &= ~1;
            target.count--;
            if (target.count <= 0) reactions.results.remove(target);
        } else {
            if (chosenList.size() >= maxReactions && !chosenList.isEmpty()) {
                TLRPC.ReactionCount oldest = chosenList.get(chosenList.size() - 1);
                oldest.flags &= ~1;
                oldest.count--;
                if (oldest.count <= 0) reactions.results.remove(oldest);
            }

            if (target != null) {
                target.flags |= 1;
                target.count++;
            } else {
                TLRPC.TL_reactionCount newRc = new TLRPC.TL_reactionCount();
                if (reaction instanceof TLRPC.TL_reactionEmoji) {
                    TLRPC.TL_reactionEmoji copy = new TLRPC.TL_reactionEmoji();
                    copy.emoticon = ((TLRPC.TL_reactionEmoji) reaction).emoticon;
                    newRc.reaction = copy;
                } else if (reaction instanceof TLRPC.TL_reactionCustomEmoji) {
                    TLRPC.TL_reactionCustomEmoji copy = new TLRPC.TL_reactionCustomEmoji();
                    copy.document_id = ((TLRPC.TL_reactionCustomEmoji) reaction).document_id;
                    newRc.reaction = copy;
                } else {
                    newRc.reaction = reaction;
                }
                newRc.count = 1; newRc.flags |= 1;
                reactions.results.add(newRc);
            }
        }
    }

    static boolean reactionsEqual(TLRPC.Reaction a, TLRPC.Reaction b) {
        if (a instanceof TLRPC.TL_reactionPaid && b instanceof TLRPC.TL_reactionPaid) return true;
        if (a instanceof TLRPC.TL_reactionEmoji && b instanceof TLRPC.TL_reactionEmoji)
            return TextUtils.equals(((TLRPC.TL_reactionEmoji) a).emoticon, ((TLRPC.TL_reactionEmoji) b).emoticon);
        if (a instanceof TLRPC.TL_reactionCustomEmoji && b instanceof TLRPC.TL_reactionCustomEmoji)
            return ((TLRPC.TL_reactionCustomEmoji) a).document_id == ((TLRPC.TL_reactionCustomEmoji) b).document_id;
        return false;
    }

    @SuppressLint("DefaultLocale")
    private static String formatCount(int count) {
        if (count >= 1_000_000) return String.format("%.1fM", count / 1_000_000f);
        if (count >= 1_000) return String.format("%.1fK", count / 1_000f);
        return String.valueOf(count);
    }

    private GradientDrawable createChipBg(boolean chosen, int accentOverride) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(14));
        if (chosen) {
            int accent = accentOverride != 0 ? accentOverride
                    : Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
            gd.setColor((accent & 0x00FFFFFF) | 0x1A000000);
            gd.setStroke(dp(1), accent);
        } else {
            int c = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            gd.setColor((c & 0x00FFFFFF) | 0x0F000000);
        }
        return gd;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pickerPopup != null && pickerPopup.isShowing()) pickerPopup.dismiss();
    }

    public TLRPC.Reaction getDefaultReaction() {
        if (currentItem == null) return null;

        TLRPC.Reaction defaultReaction = getUserDefaultReaction();
        if (defaultReaction != null && isReactionAllowed(defaultReaction)) {
            return defaultReaction;
        }

        if (!allReactions.isEmpty()) {
            for (ReactionData rd : allReactions) {
                if (!rd.isPaid && isReactionAllowed(rd.reaction)) {
                    return rd.reaction;
                }
            }
        }

        List<TLRPC.Reaction> available = getAvailableReactions();
        if (!available.isEmpty()) {
            return available.get(0);
        }

        return null;
    }

    public boolean triggerDefaultReaction() {
        TLRPC.Reaction reaction = getDefaultReaction();
        if (reaction == null) return false;

        if (isAlreadyChosen(reaction)) return false;

        findChipForReaction(reaction);

        optimisticallyToggleRegular(reaction);
        collectReactions();
        rebuild();

        View newChip = findChipForReaction(reaction);
        if (newChip != null) {
            findAvailableReaction(reaction);
            animateReaction(newChip, reaction, true);
        }

        if (callback != null) {
            callback.onReactionToggle(currentItem, reaction);
        }

        return true;
    }

    private TLRPC.Reaction getUserDefaultReaction() {
        try {
            String emoticon = MediaDataController.getInstance(currentAccount)
                    .getDoubleTapReaction();
            if (emoticon != null && !emoticon.isEmpty()) {
                TLRPC.TL_reactionEmoji r = new TLRPC.TL_reactionEmoji();
                r.emoticon = emoticon;
                return r;
            }
        } catch (Exception ignored) {}

        TLRPC.TL_reactionEmoji fallback = new TLRPC.TL_reactionEmoji();
        fallback.emoticon = "\uD83D\uDC4D";
        return fallback;
    }

    private boolean isReactionAllowed(TLRPC.Reaction reaction) {
        if (currentItem == null) return false;
        long chatId = -currentItem.channelId;
        TLRPC.ChatFull chatFull = MessagesController.getInstance(currentAccount).getChatFull(chatId);
        if (chatFull == null) return true;

        if (chatFull.available_reactions instanceof TLRPC.TL_chatReactionsNone) {
            return false;
        }

        if (chatFull.available_reactions instanceof TLRPC.TL_chatReactionsSome) {
            TLRPC.TL_chatReactionsSome some =
                    (TLRPC.TL_chatReactionsSome) chatFull.available_reactions;
            for (TLRPC.Reaction r : some.reactions) {
                if (reactionsEqual(r, reaction)) return true;
            }
            return false;
        }

        return reaction instanceof TLRPC.TL_reactionEmoji;
    }

    private boolean isAlreadyChosen(TLRPC.Reaction reaction) {
        for (ReactionData rd : allReactions) {
            if (rd.chosen && reactionsEqual(rd.reaction, reaction)) {
                return true;
            }
        }
        return false;
    }

    private View findChipForReaction(TLRPC.Reaction reaction) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof TLRPC.Reaction && reactionsEqual((TLRPC.Reaction) tag, reaction)) {
                return child;
            }
        }
        if (container.getChildCount() > 0) {
            return container.getChildAt(0);
        }
        return null;
    }
}