package org.telegram.ui.Feed;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FeedRecommendationsDetailActivity extends BaseFragment {

    private ListAdapter adapter;
    private final List<FeedRecommendationEngine.RecommendedChannel> channels = new ArrayList<>();
    private final Set<Long> expandedIds = new HashSet<>();

    private int headerRow = -1;
    private int channelsStartRow = -1;
    private int channelsEndRow = -1;
    private int infoRow = -1;
    private int emptyRow = -1;
    private int rowCount;

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHANNEL = 1;
    private static final int TYPE_INFO = 2;
    private static final int TYPE_EMPTY = 3;

    @Override
    public boolean onFragmentCreate() {
        loadChannels();
        buildRows();
        return super.onFragmentCreate();
    }

    private void loadChannels() {
        channels.clear();
        FeedRecommendationEngine engine = FeedRecommendationEngine.getInstance(currentAccount);
        List<FeedRecommendationEngine.RecommendedChannel> recs = engine.getRecommendations();
        if (recs != null) {
            channels.addAll(recs);
        }
    }

    private void buildRows() {
        rowCount = 0;
        if (channels.isEmpty()) {
            headerRow = -1;
            channelsStartRow = -1;
            channelsEndRow = -1;
            infoRow = -1;
            emptyRow = rowCount++;
        } else {
            emptyRow = -1;
            headerRow = rowCount++;
            channelsStartRow = rowCount;
            rowCount += channels.size();
            channelsEndRow = rowCount;
            infoRow = rowCount++;
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.FeedRecommendedChannels));
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
        listView.setVerticalScrollBarEnabled(true);

        listView.setOnItemClickListener((view, position) -> {
            if (position >= channelsStartRow && position < channelsEndRow) {
                int index = position - channelsStartRow;
                if (index >= 0 && index < channels.size()) {
                    long channelId = channels.get(index).channelId;
                    if (expandedIds.contains(channelId)) {
                        expandedIds.remove(channelId);
                    } else {
                        expandedIds.add(channelId);
                    }
                    adapter.notifyItemChanged(position);
                }
            }
        });
        return listView;
    }

    @SuppressLint("NotifyDataSetChanged")
    private void dismissChannel(int index) {
        if (index < 0 || index >= channels.size()) return;
        FeedRecommendationEngine.RecommendedChannel rec = channels.get(index);
        String name = rec.chat != null ? rec.chat.title
                : LocaleController.getString(R.string.FeedChannel);

        FeedRecommendationEngine.getInstance(currentAccount).dismiss(rec.channelId);
        expandedIds.remove(rec.channelId);
        channels.remove(index);
        buildRows();
        adapter.notifyDataSetChanged();

        BulletinFactory.of(this)
                .createSimpleBulletin(R.drawable.msg_close,
                        LocaleController.formatString(
                                R.string.FeedRecommendationDismissed, name))
                .show();
    }

    @SuppressLint("DefaultLocale")
    private String formatSubscribers(int count) {
        if (count >= 1_000_000) {
            return LocaleController.formatString(
                    R.string.FeedSubscribersShortMillions, count / 1_000_000f);
        }
        if (count >= 1_000) {
            return LocaleController.formatString(
                    R.string.FeedSubscribersShortThousands, count / 1_000f);
        }
        return LocaleController.formatPluralString("FeedSubscribers", count);
    }

    private String getChannelNames(Set<Long> ids) {
        MessagesController controller = MessagesController.getInstance(currentAccount);
        List<String> names = new ArrayList<>();
        for (Long id : ids) {
            TLRPC.Chat chat = controller.getChat(id);
            names.add(chat != null ? chat.title : "ID:" + id);
        }
        return TextUtils.join(", ", names);
    }

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
            return holder.getItemViewType() == TYPE_CHANNEL;
        }

        @Override
        public int getItemViewType(int pos) {
            if (pos == headerRow) return TYPE_HEADER;
            if (pos == infoRow) return TYPE_INFO;
            if (pos == emptyRow) return TYPE_EMPTY;
            if (pos >= channelsStartRow && pos < channelsEndRow) return TYPE_CHANNEL;
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
                case TYPE_CHANNEL:
                    view = new RecChannelCell(ctx);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_INFO:
                    view = new TextInfoPrivacyCell(ctx);
                    view.setBackground(Theme.getThemedDrawableByKey(ctx,
                            R.drawable.greydivider_bottom,
                            Theme.key_windowBackgroundGrayShadow));
                    break;
                case TYPE_EMPTY:
                default:
                    TextView tv = new TextView(ctx);
                    tv.setText(LocaleController.getString(R.string.FeedNoRecommendations));
                    tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                    tv.setGravity(Gravity.CENTER);
                    tv.setPadding(dp(40), dp(60), dp(40), dp(60));
                    view = tv;
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
                    cell.setText(LocaleController.formatString(
                            R.string.FeedDiscoveredChannels, channels.size()));
                    break;
                }
                case TYPE_CHANNEL: {
                    int index = pos - channelsStartRow;
                    if (index >= 0 && index < channels.size()) {
                        FeedRecommendationEngine.RecommendedChannel rec = channels.get(index);
                        boolean expanded = expandedIds.contains(rec.channelId);
                        ((RecChannelCell) holder.itemView).bind(rec, index, expanded);
                    }
                    break;
                }
                case TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(LocaleController.getString(
                            R.string.FeedRecommendationsDetailInfo));
                    break;
                }
            }
        }
    }

    private class RecChannelCell extends LinearLayout {

        private final BackupImageView avatarView;
        private final AvatarDrawable avatarDrawable;
        private final TextView nameView;
        private final TextView subscribersView;
        private final TextView scoreView;
        private final TextView reasonView;
        private final TextView expandHint;
        private final LinearLayout sourcesContainer;
        private int channelIndex = -1;

        @SuppressLint("SetTextI18n")
        RecChannelCell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(0, dp(8), 0, 0);

            FrameLayout topRow = new FrameLayout(context);

            avatarDrawable = new AvatarDrawable();
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(dp(22));
            topRow.addView(avatarView, LayoutHelper.createFrame(44, 44,
                    Gravity.TOP | Gravity.START, 16, 0, 0, 0));

            nameView = new TextView(context);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setTypeface(Typeface.DEFAULT_BOLD);
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            topRow.addView(nameView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START, 76, 0, 96, 0));

            subscribersView = new TextView(context);
            subscribersView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subscribersView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            subscribersView.setSingleLine(true);
            topRow.addView(subscribersView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START, 76, 22, 96, 0));

            scoreView = new TextView(context);
            scoreView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            scoreView.setTextColor(0xFFFFFFFF);
            scoreView.setGravity(Gravity.CENTER);
            scoreView.setPadding(dp(6), dp(2), dp(6), dp(2));
            GradientDrawable scoreBg = new GradientDrawable();
            scoreBg.setCornerRadius(dp(4));
            scoreBg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            scoreView.setBackground(scoreBg);
            topRow.addView(scoreView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT,
                    LayoutHelper.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END, 0, 4, 44, 0));

            ImageView dismissButton = new ImageView(context);
            dismissButton.setScaleType(ImageView.ScaleType.CENTER);
            dismissButton.setImageResource(R.drawable.msg_close);
            dismissButton.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
            dismissButton.setBackground(Theme.createSelectorDrawable(
                    Theme.getColor(Theme.key_listSelector), 1));
            dismissButton.setOnClickListener(v -> {
                if (channelIndex >= 0) dismissChannel(channelIndex);
            });
            topRow.addView(dismissButton, LayoutHelper.createFrame(32, 32,
                    Gravity.TOP | Gravity.END, 0, 0, 6, 0));

            addView(topRow, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            reasonView = new TextView(context);
            reasonView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            reasonView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            reasonView.setPadding(dp(76), dp(4), dp(16), 0);
            addView(reasonView, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            expandHint = new TextView(context);
            expandHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            expandHint.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            expandHint.setPadding(dp(76), dp(4), dp(16), 0);
            addView(expandHint, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            sourcesContainer = new LinearLayout(context);
            sourcesContainer.setOrientation(VERTICAL);
            sourcesContainer.setPadding(dp(76), dp(4), dp(16), dp(4));
            sourcesContainer.setVisibility(GONE);

            GradientDrawable sourcesBg = new GradientDrawable();
            sourcesBg.setCornerRadius(dp(8));
            sourcesBg.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
            sourcesContainer.setBackground(sourcesBg);

            FrameLayout sourcesWrapper = new FrameLayout(context);
            sourcesWrapper.setPadding(dp(76), dp(4), dp(16), dp(8));
            sourcesWrapper.addView(sourcesContainer, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            addView(sourcesWrapper, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            View divider = new View(context) {
                private final Paint paint = new Paint();
                {
                    paint.setColor(Theme.getColor(Theme.key_divider));
                }

                @Override
                protected void onDraw(Canvas canvas) {
                    canvas.drawRect(dp(76), 0, getWidth(), getHeight(), paint);
                }
            };
            addView(divider, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, 1, 0, 4, 0, 0));
        }

        @SuppressLint({"SetTextI18n", "DefaultLocale"})
        void bind(FeedRecommendationEngine.RecommendedChannel rec, int index, boolean expanded) {
            channelIndex = index;

            TLRPC.Chat chat = rec.chat;
            if (chat != null) {
                nameView.setText(chat.title);
                avatarDrawable.setInfo(chat);
                if (chat.photo != null && chat.photo.photo_small != null) {
                    avatarView.setImage(ImageLocation.getForChat(chat, ImageLocation.TYPE_SMALL),
                            "44_44", avatarDrawable, chat);
                } else {
                    avatarView.setImageDrawable(avatarDrawable);
                }
                if (chat.participants_count > 0) {
                    subscribersView.setText(formatSubscribers(chat.participants_count));
                    subscribersView.setVisibility(VISIBLE);
                } else {
                    subscribersView.setVisibility(GONE);
                }
            } else {
                nameView.setText(LocaleController.formatString(
                        R.string.FeedChannelId, rec.channelId));
                avatarDrawable.setInfo(rec.channelId, "", "");
                avatarView.setImageDrawable(avatarDrawable);
                subscribersView.setVisibility(GONE);
            }

            scoreView.setText(String.format("%.1f", rec.score));

            reasonView.setText(rec.reason != null ? rec.reason
                    : LocaleController.getString(R.string.FeedRecommendedForYou));

            if (expanded) {
                expandHint.setText(LocaleController.getString(R.string.FeedHideSources));
                sourcesContainer.setVisibility(VISIBLE);
                buildSources(rec);
            } else {
                expandHint.setText(LocaleController.getString(R.string.FeedShowSources));
                sourcesContainer.setVisibility(GONE);
            }
        }

        @SuppressLint("SetTextI18n")
        private void buildSources(FeedRecommendationEngine.RecommendedChannel rec) {
            sourcesContainer.removeAllViews();

            if (!rec.similarSourceIds.isEmpty()) {
                addSourceSection(
                        LocaleController.formatString(R.string.FeedSimilarSources,
                                rec.similarSourceIds.size()),
                        getChannelNames(rec.similarSourceIds));
            }
            if (!rec.forwardSourceIds.isEmpty()) {
                addSourceSection(
                        LocaleController.formatString(R.string.FeedForwardedBySources,
                                rec.forwardSourceIds.size()),
                        getChannelNames(rec.forwardSourceIds));
            }
            if (!rec.mentionSourceIds.isEmpty()) {
                addSourceSection(
                        LocaleController.formatString(R.string.FeedMentionedBySources,
                                rec.mentionSourceIds.size()),
                        getChannelNames(rec.mentionSourceIds));
            }

            int total = rec.similarSourceIds.size()
                    + rec.forwardSourceIds.size()
                    + rec.mentionSourceIds.size();
            addSummaryLine(LocaleController.formatString(
                    R.string.FeedRecommendationSourcesSummary, total,
                    rec.similarSources, rec.forwardSources, rec.mentionSources));
        }

        private void addSourceSection(String label, String names) {
            Context ctx = getContext();

            TextView labelView = new TextView(ctx);
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            labelView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            labelView.setTypeface(Typeface.DEFAULT_BOLD);
            labelView.setText(label);
            labelView.setPadding(dp(8), dp(6), dp(8), 0);
            sourcesContainer.addView(labelView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView namesView = new TextView(ctx);
            namesView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            namesView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            namesView.setText(names);
            namesView.setPadding(dp(16), dp(2), dp(8), dp(2));
            sourcesContainer.addView(namesView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        private void addSummaryLine(String text) {
            Context ctx = getContext();

            View line = new View(ctx);
            line.setBackgroundColor(Theme.getColor(Theme.key_divider));
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            lineLp.topMargin = dp(6);
            lineLp.bottomMargin = dp(4);
            lineLp.leftMargin = dp(8);
            lineLp.rightMargin = dp(8);
            sourcesContainer.addView(line, lineLp);

            TextView summaryView = new TextView(ctx);
            summaryView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            summaryView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            summaryView.setText(text);
            summaryView.setPadding(dp(8), 0, dp(8), dp(4));
            sourcesContainer.addView(summaryView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }
}
