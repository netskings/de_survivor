package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class FeedSettingsActivity extends BaseFragment {

    private ListAdapter adapter;
    private TextView emptyView;

    private final List<Long> hiddenChannelIds = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        reloadHiddenChannels();
        return super.onFragmentCreate();
    }

    private void reloadHiddenChannels() {
        hiddenChannelIds.clear();
        Set<Long> set = FeedController.getInstance(currentAccount).getHiddenChannelIds();
        hiddenChannelIds.addAll(set);
    }

    @Override
    public View createView(Context context) {

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Feed Settings");
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        adapter = new ListAdapter(context);

        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter);
        listView.setVerticalScrollBarEnabled(false);
        root.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText("No hidden channels");
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setTextSize(16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(40), dp(80), dp(40), dp(40));
        emptyView.setVisibility(hiddenChannelIds.isEmpty() ? View.VISIBLE : View.GONE);
        root.addView(emptyView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        fragmentView = root;
        return fragmentView;
    }

    private void unhideChannel(int position) {
        if (position < 0 || position >= hiddenChannelIds.size()) return;
        long channelId = hiddenChannelIds.get(position);

        FeedController.getInstance(currentAccount).unhideChannel(channelId);

        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(channelId);
        String name = chat != null ? chat.title : "Channel";

        hiddenChannelIds.remove(position);
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(hiddenChannelIds.isEmpty() ? View.VISIBLE : View.GONE);

        BulletinFactory.of(this)
                .createSimpleBulletin(R.drawable.msg_check_s,
                        name + " will appear in feed again")
                .show();
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHANNEL = 1;
    private static final int TYPE_INFO = 2;

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context ctx;

        ListAdapter(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public int getItemCount() {
            if (hiddenChannelIds.isEmpty()) return 0;
            return 1 + hiddenChannelIds.size() + 1; // header + channels + info
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_CHANNEL;
        }

        @Override
        public int getItemViewType(int pos) {
            if (pos == 0) return TYPE_HEADER;
            if (pos <= hiddenChannelIds.size()) return TYPE_CHANNEL;
            return TYPE_INFO;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case TYPE_HEADER:
                    view = new HeaderCell(ctx);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_CHANNEL:
                    view = new HiddenChannelCell(ctx);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_INFO:
                default:
                    view = new TextInfoPrivacyCell(ctx);
                    view.setBackground(Theme.getThemedDrawableByKey(ctx,
                            R.drawable.greydivider_bottom,
                            Theme.key_windowBackgroundGrayShadow));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    ((HeaderCell) holder.itemView).setText("Hidden Channels");
                    break;
                }
                case TYPE_CHANNEL: {
                    int index = pos - 1;
                    if (index >= 0 && index < hiddenChannelIds.size()) {
                        long chatId = hiddenChannelIds.get(index);
                        ((HiddenChannelCell) holder.itemView).setChannel(chatId, index);
                    }
                    break;
                }
                case TYPE_INFO: {
                    ((TextInfoPrivacyCell) holder.itemView).setText(
                            "Tap the ✕ button to show the channel in your feed again.");
                    break;
                }
            }
        }
    }

    private class HiddenChannelCell extends FrameLayout {

        private final BackupImageView avatarView;
        private final AvatarDrawable avatarDrawable;
        private final TextView nameView;
        private int channelIndex = -1;

        public HiddenChannelCell(Context context) {
            super(context);

            avatarDrawable = new AvatarDrawable();

            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(dp(20));
            addView(avatarView, LayoutHelper.createFrame(
                    40, 40, Gravity.CENTER_VERTICAL | Gravity.START, 16, 8, 0, 8));

            nameView = new TextView(context);
            nameView.setTextSize(16);
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setSingleLine(true);
            nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            addView(nameView, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL | Gravity.START, 72, 0, 56, 0));

            ImageView removeButton = new ImageView(context);
            removeButton.setScaleType(ImageView.ScaleType.CENTER);
            removeButton.setImageResource(R.drawable.msg_close);
            removeButton.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
            removeButton.setBackground(Theme.createSelectorDrawable(
                    Theme.getColor(Theme.key_listSelector), 1));
            removeButton.setOnClickListener(v -> {
                if (channelIndex >= 0) unhideChannel(channelIndex);
            });
            addView(removeButton, LayoutHelper.createFrame(
                    48, 48, Gravity.CENTER_VERTICAL | Gravity.END, 0, 0, 4, 0));

            View divider = new View(context) {
                private final Paint paint = new Paint();

                {
                    paint.setColor(Theme.getColor(Theme.key_divider));
                }

                @Override
                protected void onDraw(Canvas canvas) {
                    canvas.drawRect(dp(72), 0, getWidth(), getHeight(), paint);
                }
            };
            addView(divider, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, 1, Gravity.BOTTOM));
        }

        @SuppressLint("SetTextI18n")
        public void setChannel(long chatId, int index) {
            this.channelIndex = index;
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(chatId);
            if (chat != null) {
                nameView.setText(chat.title);
                avatarDrawable.setInfo(chat);
                if (chat.photo != null && chat.photo.photo_small != null) {
                    avatarView.setImage(
                            ImageLocation.getForChat(chat, ImageLocation.TYPE_SMALL),
                            "40_40", avatarDrawable, chat);
                } else {
                    avatarView.setImageDrawable(avatarDrawable);
                }
            } else {
                nameView.setText("Channel " + chatId);
                avatarDrawable.setInfo(chatId, "", "");
                avatarView.setImageDrawable(avatarDrawable);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY));
        }
    }
}