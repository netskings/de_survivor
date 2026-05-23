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
import org.telegram.messenger.LocaleController;
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
    private int albumModeHeaderRow;
    private int albumModeRow;
    private int albumModeInfoRow;

    private int filterHeaderRow;
    private int banListRow;
    private int hiddenLogRow;
    private int filterInfoRow;

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

        albumModeHeaderRow = rowCount++;
        albumModeRow       = rowCount++;
        albumModeInfoRow   = rowCount++;

        filterHeaderRow = rowCount++;
        banListRow = rowCount++;
        hiddenLogRow = rowCount++;
        filterInfoRow = rowCount++;

        recommendationsHeaderRow    = rowCount++;
        recommendationsToggleRow    = rowCount++;
        recommendationFrequencyRow  = rowCount++;
        recommendationsDetailRow    = rowCount++;
        recommendationsInfoRow      = rowCount++;

        if (!hiddenChannelIds.isEmpty()) {
            hiddenHeaderRow      = rowCount++;
            hiddenChannelsStartRow = rowCount;
            rowCount += hiddenChannelIds.size();
            hiddenChannelsEndRow = rowCount;
            hiddenInfoRow        = rowCount++;
        } else {
            hiddenHeaderRow = hiddenChannelsStartRow =
                    hiddenChannelsEndRow = hiddenInfoRow = -1;
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.CustomSettingsFeedSettings));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        adapter = new ListAdapter(context);

        RecyclerListView listView = getRecyclerListView(context);

        root.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = root;
        return root;
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
            } else if (position == albumModeRow) {
                showAlbumModePicker();
            } else if (position == banListRow) {
                presentFragment(new FeedBanListActivity());
            } else if (position == hiddenLogRow) {
                presentFragment(new FeedHiddenLogActivity());
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
            if (pos == recommendationsHeaderRow || pos == hiddenHeaderRow || pos == filterHeaderRow) return TYPE_HEADER;
            if (pos == recommendationsToggleRow) return TYPE_CHECK;
            if (pos == recommendationFrequencyRow || pos == recommendationsDetailRow || pos == albumModeRow || pos == banListRow || pos == hiddenLogRow) return TYPE_TEXT_VALUE;
            if (pos == recommendationsInfoRow || pos == hiddenInfoRow || pos == albumModeInfoRow || pos == filterInfoRow) return TYPE_INFO;
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
                    if (pos == albumModeHeaderRow) cell.setText(LocaleController.getString(R.string.FeedAlbumLayout));
                    else if (pos == recommendationsHeaderRow) cell.setText(LocaleController.getString(R.string.FeedRecommendations));
                    else if (pos == hiddenHeaderRow) cell.setText(LocaleController.getString(R.string.FeedHiddenChannels));
                    else if (pos == filterHeaderRow) cell.setText(LocaleController.getString(R.string.FeedContentFilter));
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (pos == recommendationsToggleRow) {
                        cell.setTextAndCheck(LocaleController.getString(R.string.FeedChannelRecommendations),
                                CustomSettings.feedRecommendations(), true);
                    }
                    break;
                }
                case TYPE_TEXT_VALUE: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (pos == albumModeRow) {
                        FeedAlbumMode mode = CustomSettings.feedAlbumMode();
                        String modeStr = mode == FeedAlbumMode.CAROUSEL
                                ? LocaleController.getString(R.string.FeedAlbumCarousel)
                                : LocaleController.getString(R.string.FeedAlbumGrid);
                        cell.setTextAndValue(LocaleController.getString(R.string.FeedAlbumDisplayMode), modeStr, true);
                    } else if (pos == recommendationFrequencyRow) {
                        cell.setTextAndValue(LocaleController.getString(R.string.FeedRecommendationEveryNPosts),
                                String.valueOf(CustomSettings.feedRecommendationFrequency()),
                                true);
                    } else if (pos == recommendationsDetailRow) {
                        int count = FeedRecommendationEngine.getInstance(currentAccount)
                                .getRecommendations().size();
                        cell.setTextAndValue(LocaleController.getString(R.string.FeedRecommendedChannels),
                                count > 0 ? String.valueOf(count) : "—",
                                true);
                    } else if (pos == banListRow) {
                        int count = 0;
                        for (CustomSettings.BanGroup g : CustomSettings.getBanGroups()) {
                            count += g.phrases.size();
                        }
                        cell.setTextAndValue(LocaleController.getString(R.string.FeedBannedWordsPhrases),
                                count > 0 ? LocaleController.formatPluralString("FeedWords", count)
                                        : LocaleController.getString(R.string.FeedOff), true);
                    }
                    else if (pos == hiddenLogRow) {
                        int logCount = CustomSettings.getHiddenLog().length();
                        cell.setTextAndValue(LocaleController.getString(R.string.FeedHiddenPostsLog),
                                logCount > 0 ? String.valueOf(logCount)
                                        : LocaleController.getString(R.string.FeedEmptyValue), true);
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
                    if (pos == albumModeInfoRow) {
                        cell.setText(LocaleController.getString(R.string.FeedAlbumModeInfo));
                    } else if (pos == recommendationsInfoRow) {
                        cell.setText(LocaleController.getString(R.string.FeedRecommendationsInfo));
                    } else if (pos == hiddenInfoRow) {
                        cell.setText(LocaleController.getString(R.string.FeedHiddenChannelsInfo));
                    } else if (pos == filterInfoRow) {
                        cell.setText(LocaleController.getString(R.string.FeedContentFilterInfo));
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
                nameView.setText(LocaleController.formatString(R.string.FeedChannelId, chatId));
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
        builder.setTitle(LocaleController.getString(R.string.FeedRecommendationEveryNPostsTitle));
        builder.setView(picker);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
            CustomSettings.setFeedRecommendationFrequency(picker.getValue());
            if (adapter != null) adapter.notifyDataSetChanged();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    @SuppressLint({"NotifyDataSetChanged", "ResourceType"})
    private void showAlbumModePicker() {
        if (getParentActivity() == null) return;

        FeedAlbumMode current = CustomSettings.feedAlbumMode();

        android.widget.LinearLayout layout = new android.widget.LinearLayout(getParentActivity());
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(8), dp(20), dp(8));

        android.widget.RadioGroup radioGroup = new android.widget.RadioGroup(getParentActivity());
        radioGroup.setOrientation(android.widget.RadioGroup.VERTICAL);

        android.widget.RadioButton rbCarousel = new android.widget.RadioButton(getParentActivity());
        rbCarousel.setText(LocaleController.getString(R.string.FeedAlbumCarousel));
        rbCarousel.setId(0);
        rbCarousel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
        rbCarousel.setPadding(dp(8), dp(12), dp(8), dp(12));
        rbCarousel.setTextColor(
                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));

        android.widget.RadioButton rbGrid = new android.widget.RadioButton(getParentActivity());
        rbGrid.setText(LocaleController.getString(R.string.FeedAlbumGrid));
        rbGrid.setId(1);
        rbGrid.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
        rbGrid.setPadding(dp(8), dp(12), dp(8), dp(12));
        rbGrid.setTextColor(
                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));

        radioGroup.addView(rbCarousel);
        radioGroup.addView(rbGrid);

        radioGroup.check(current == FeedAlbumMode.CAROUSEL ? 0 : 1);

        layout.addView(radioGroup);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.FeedAlbumDisplayMode));
        builder.setView(layout);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
            int checkedId = radioGroup.getCheckedRadioButtonId();
            FeedAlbumMode newMode = checkedId == 0
                    ? FeedAlbumMode.CAROUSEL
                    : FeedAlbumMode.GRID;
            CustomSettings.setFeedAlbumMode(newMode);
            if (adapter != null) adapter.notifyDataSetChanged();
        });
        builder.setNegativeButton(
                LocaleController.getString(
                        R.string.Cancel),
                null);
        showDialog(builder.create());
    }
}
