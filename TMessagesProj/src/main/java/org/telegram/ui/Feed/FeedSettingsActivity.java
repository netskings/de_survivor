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
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Custom.CustomSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class FeedSettingsActivity extends BaseFragment {

    private ListAdapter adapter;

    private final List<Long> hiddenChannelIds = new ArrayList<>();

    private int rowCount;
    private int recommendationsHeaderRow;
    private int recommendationsToggleRow;
    private int recommendationsInfoRow;
    private int hiddenHeaderRow;
    private int hiddenChannelsStartRow;
    private int hiddenChannelsEndRow;
    private int hiddenInfoRow;
    private int recommendationFrequencyRow;
    private int recommendationsDetailRow;

    public FeedSettingsActivity() {
    }

    @Override
    public boolean onFragmentCreate() {
        reloadHiddenChannels();
        buildRows();
        return super.onFragmentCreate();
    }

    private void reloadHiddenChannels() {
        hiddenChannelIds.clear();
        Set<Long> set = FeedController.getInstance(currentAccount).getHiddenChannelIds();
        hiddenChannelIds.addAll(set);
    }

    private void buildRows() {
        rowCount = 0;
        recommendationsHeaderRow = rowCount++;
        recommendationsToggleRow = rowCount++;
        recommendationFrequencyRow = rowCount++;
        recommendationsDetailRow = rowCount++;
        recommendationsInfoRow = rowCount++;

        if (!hiddenChannelIds.isEmpty()) {
            hiddenHeaderRow = rowCount++;
            hiddenChannelsStartRow = rowCount;
            rowCount += hiddenChannelIds.size();
            hiddenChannelsEndRow = rowCount;
            hiddenInfoRow = rowCount++;
        } else {
            hiddenHeaderRow = -1;
            hiddenChannelsStartRow = -1;
            hiddenChannelsEndRow = -1;
            hiddenInfoRow = -1;
        }
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

        RecyclerListView listView = getRecyclerListView(context);

        root.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = root;
        return fragmentView;
    }

    @NonNull
    private RecyclerListView getRecyclerListView(Context context) {
        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter);
        listView.setVerticalScrollBarEnabled(false);

        listView.setOnItemClickListener((view, position) -> {
            if (position == recommendationsToggleRow) {
                boolean val = !CustomSettings.feedRecommendations();
                CustomSettings.setFeedRecommendations(val);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(val);
                }
                if (val) {
                    FeedRecommendationEngine.getInstance(currentAccount).requestScan();
                }
            } else if (position == recommendationFrequencyRow) {
                showFrequencyPicker();
            } else if (position == recommendationsDetailRow) {
                presentFragment(new FeedRecommendationsDetailActivity());
            }
        });
        return listView;
    }

    @SuppressLint("NotifyDataSetChanged")
    private void unhideChannel(int channelIndex) {
        if (channelIndex < 0 || channelIndex >= hiddenChannelIds.size()) return;
        long channelId = hiddenChannelIds.get(channelIndex);

        FeedController.getInstance(currentAccount).unhideChannel(channelId);

        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(channelId);
        String name = chat != null ? chat.title : "Channel";

        hiddenChannelIds.remove(channelIndex);
        buildRows();
        adapter.notifyDataSetChanged();

        BulletinFactory.of(this)
                .createSimpleBulletin(R.drawable.msg_check_s,
                        name + " will appear in feed again")
                .show();
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHANNEL = 1;
    private static final int TYPE_INFO = 2;
    private static final int TYPE_CHECK = 3;
    private static final int TYPE_TEXT_VALUE = 4;


    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context ctx;

        ListAdapter(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == TYPE_CHANNEL || type == TYPE_CHECK || type == TYPE_TEXT_VALUE;
        }

        @Override
        public int getItemViewType(int pos) {
            if (pos == recommendationsHeaderRow || pos == hiddenHeaderRow) return TYPE_HEADER;
            if (pos == recommendationsToggleRow) return TYPE_CHECK;
            if (pos == recommendationFrequencyRow || pos == recommendationsDetailRow) return TYPE_TEXT_VALUE;
            if (pos == recommendationsInfoRow || pos == hiddenInfoRow) return TYPE_INFO;
            if (pos >= hiddenChannelsStartRow && pos < hiddenChannelsEndRow) return TYPE_CHANNEL;
            return TYPE_HEADER;
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
                case TYPE_CHECK:
                    view = new TextCheckCell(ctx);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_CHANNEL:
                    view = new HiddenChannelCell(ctx);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_TEXT_VALUE:
                    view = new TextCell(ctx);
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
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (pos == recommendationsHeaderRow) {
                        cell.setText("Recommendations");
                    } else if (pos == hiddenHeaderRow) {
                        cell.setText("Hidden Channels");
                    }
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (pos == recommendationsToggleRow) {
                        cell.setTextAndCheck("Channel Recommendations",
                                CustomSettings.feedRecommendations(), true);
                    }
                    break;
                }
                case TYPE_TEXT_VALUE: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (pos == recommendationFrequencyRow) {
                        cell.setTextAndValue("Show every N posts",
                                String.valueOf(CustomSettings.feedRecommendationFrequency()),
                                true);
                    } else if (pos == recommendationsDetailRow) {
                        int count = FeedRecommendationEngine.getInstance(currentAccount)
                                .getRecommendations().size();
                        cell.setTextAndValue("Recommended Channels",
                                count > 0 ? String.valueOf(count) : "—",
                                true);
                    }
                    break;
                }
                case TYPE_CHANNEL: {
                    int index = pos - hiddenChannelsStartRow;
                    if (index >= 0 && index < hiddenChannelIds.size()) {
                        long chatId = hiddenChannelIds.get(index);
                        ((HiddenChannelCell) holder.itemView).setChannel(chatId, index);
                    }
                    break;
                }
                case TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (pos == recommendationsInfoRow) {
                        cell.setText("Discover new channels based on your subscriptions. " +
                                "The system analyzes similar channels, forwarded posts, " +
                                "and mentions to suggest relevant content.");
                    } else if (pos == hiddenInfoRow) {
                        cell.setText("Tap ✕ to show the channel in your feed again.");
                    }
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
            addView(avatarView, LayoutHelper.createFrame(40, 40,
                    Gravity.CENTER_VERTICAL | Gravity.START, 16, 8, 0, 8));

            nameView = new TextView(context);
            nameView.setTextSize(16);
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setSingleLine(true);
            nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            addView(nameView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT,
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
            addView(removeButton, LayoutHelper.createFrame(48, 48,
                    Gravity.CENTER_VERTICAL | Gravity.END, 0, 0, 4, 0));

            View divider = new View(context) {
                private final Paint paint = new Paint();
                { paint.setColor(Theme.getColor(Theme.key_divider)); }
                @Override
                protected void onDraw(Canvas canvas) {
                    canvas.drawRect(dp(72), 0, getWidth(), getHeight(), paint);
                }
            };
            addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1, Gravity.BOTTOM));
        }

        @SuppressLint("SetTextI18n")
        public void setChannel(long chatId, int index) {
            this.channelIndex = index;
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(chatId);
            if (chat != null) {
                nameView.setText(chat.title);
                avatarDrawable.setInfo(chat);
                if (chat.photo != null && chat.photo.photo_small != null) {
                    avatarView.setImage(ImageLocation.getForChat(chat, ImageLocation.TYPE_SMALL),
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

    @SuppressLint("NotifyDataSetChanged")
    private void showFrequencyPicker() {
        if (getParentActivity() == null) return;

        NumberPicker picker = new NumberPicker(getParentActivity());
        picker.setMinValue(3);
        picker.setMaxValue(30);
        picker.setValue(CustomSettings.feedRecommendationFrequency());
        picker.setWrapSelectorWheel(false);

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Show recommendation every N posts");
        builder.setView(picker);
        builder.setPositiveButton("OK", (dialog, which) -> {
            CustomSettings.setFeedRecommendationFrequency(picker.getValue());
            if (adapter != null) adapter.notifyDataSetChanged();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }
}